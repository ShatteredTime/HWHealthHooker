package moe.evil.hwhh.kdxref

import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.FieldResolver
import com.highcapable.kavaref.resolver.MethodResolver
import com.highcapable.yukihookapi.hook.param.PackageParam
import moe.evil.hwhh.shared.log.HLog
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

private const val CANDIDATE_LIMIT = 5

@PublishedApi
internal val hostLog = HLog("Host")

@PublishedApi
internal fun List<*>.candidates() = take(CANDIDATE_LIMIT).joinToString("") { "\n  - $it" } +
        if (size > CANDIDATE_LIMIT) "\n  - (${size - CANDIDATE_LIMIT} more)" else ""

fun <C : Collection<*>> C.orWarnEmpty(label: String) = also {
    if (it.isEmpty()) hostLog.warn { "No match for $label" }
}

@JvmInline
value class HostMethod<out R : Any> internal constructor(
    internal val resolver: MethodResolver<Any>,
) {
    val owner: Class<*> get() = resolver.self.declaringClass
    val name: String get() = resolver.self.name
    val label get() = "${owner.simpleName}#$name"
    operator fun invoke(vararg args: Any?) = resolver.invoke<R>(*args)

    fun invokeQuietly(vararg args: Any?) = runCatching { resolver.invoke<R>(*args) }
        .onFailure { hostLog.error { "Invoke failed for $label <- ${it.describe()}" } }
        .getOrNull()

    fun on(instance: Any?) = HostMethod<R>(resolver.copy().of(instance))
}

@JvmInline
value class HostConstructor<T : Any> internal constructor(
    internal val resolver: ConstructorResolver<Any>,
) {
    val owner: Class<in Any> get() = resolver.self.declaringClass

    @Suppress("UNCHECKED_CAST")
    fun createLogged(vararg args: Any?) = runCatching { resolver.create(*args) as T }
        .onFailure {
            hostLog.error(tag = "KavaRef") {
                "Create failed for ${owner.name} <- ${it.describe()}"
            }
        }
        .getOrNull()
}

@JvmInline
value class HostField<out T : Any> internal constructor(
    internal val resolver: FieldResolver<Any>,
) {
    val owner: Class<*> get() = resolver.self.declaringClass
    val name: String get() = resolver.self.name

    val label get() = "${owner.simpleName}#$name"

    @Suppress("UNCHECKED_CAST")
    fun get() = runCatching { resolver.get<Any>() }
        .onFailure { hostLog.error { "Read failed for $label <- ${it.describe()}" } }
        .getOrNull() as? T

    @Suppress("UNCHECKED_CAST")
    fun on(instance: Any?) = runCatching { resolver.copy().of(instance).get<Any>() }
        .onFailure { hostLog.error { "Read failed for $label <- ${it.describe()}" } }
        .getOrNull() as? T

    fun set(instance: Any?, value: @UnsafeVariance T) =
        runCatching { resolver.copy().of(instance).set(value) }
            .onFailure { hostLog.error { "Write failed for $label <- ${it.describe()}" } }
            .isSuccess
}

fun <R : Any> HostBridgeOwner.hostMethodOf(label: String, data: HostMethodData): HostMethod<R>? {
    val loader = hostLoader ?: run {
        hostLog.error { "No appClassLoader for $label: $data" }
        return null
    }
    val member = runCatching { data.data.getMethodInstance(loader) }.getOrElse { cause ->
        hostLog.error { "Cannot load $label: $data <- ${cause.describe()}" }
        return null
    }
    val expected = member.parameterTypes.asList()
    val resolver = member.declaringClass.resolveAny().scope.firstMethodOrNull {
        name = member.name
        parameters { it == expected }
        returnType(member.returnType)
    } ?: run {
        hostLog.error { "Kavaref did not re-resolve $label: $member" }
        return null
    }
    hostLog.debug { "Resolved $label -> $member" }
    return HostMethod(resolver)
}

fun <T : Any> HostBridgeOwner.hostFieldOf(label: String, data: HostFieldData): HostField<T>? {
    val loader = hostLoader ?: run {
        hostLog.error { "No appClassLoader for $label: $data" }
        return null
    }
    val member = runCatching { data.data.getFieldInstance(loader) }.getOrElse { cause ->
        hostLog.error { "Cannot load $label: $data <- ${cause.describe()}" }
        return null
    }
    val resolver = member.declaringClass.resolveAny().scope.firstFieldOrNull {
        name = member.name
        type = member.type
    } ?: run {
        hostLog.error { "Kavaref did not re-resolve $label: $member" }
        return null
    }
    hostLog.debug { "Resolved $label -> $member" }
    return HostField(resolver)
}

inline fun <reified R : Any> HostBridgeOwner.hostMethod(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostMethodData>.() -> HostMethodData? = { singleOrNull() },
    crossinline match: MethodMatcher.() -> Unit,
): HostMethod<R>? {
    val matches = requireHostBridge().findMethod {
        inPackage?.let { searchPackages(it) }
        matcher {
            when (R::class) {
                Any::class -> Unit
                Unit::class -> returnType(Void.TYPE)
                else -> returnType(classOf<R>())
            }
            match()
        }
    }
    val data = matches.pick() ?: run {
        val loose = matches.ifEmpty {
            requireHostBridge().findMethod {
                inPackage?.let { searchPackages(it) }
                matcher { match() }
            }
        }
        hostLog.warn {
            "No unique match for $label among ${matches.size}" +
                    (if (matches.isEmpty() && loose.isNotEmpty()) {
                        " (${loose.size} ignoring return type)"
                    } else "") + loose.candidates()
        }
        return null
    }
    return hostMethodOf(label, data)
}

inline fun <reified T : Any> HostBridgeOwner.hostField(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostFieldData>.() -> HostFieldData? = { singleOrNull() },
    crossinline match: FieldMatcher.() -> Unit,
): HostField<T>? {
    val matches = requireHostBridge().findField {
        inPackage?.let { searchPackages(it) }
        matcher {
            if (T::class != Any::class) type(classOf<T>())
            match()
        }
    }
    val data = matches.pick() ?: run {
        val loose = matches.ifEmpty {
            requireHostBridge().findField {
                inPackage?.let { searchPackages(it) }
                matcher { match() }
            }
        }
        hostLog.warn {
            "No unique match for $label among ${matches.size}" +
                    (if (matches.isEmpty() && loose.isNotEmpty()) {
                        " (${loose.size} ignoring field type)"
                    } else "") + loose.candidates()
        }
        return null
    }
    return hostFieldOf(label, data)
}

context(_: PackageParam)
fun HostMethod<*>.safeHook(initiate: SafeHookCreator.() -> Unit) = resolver.safeHook(initiate)

context(_: PackageParam)
fun HostConstructor<*>.safeHook(initiate: SafeHookCreator.() -> Unit) = resolver.safeHook(initiate)
