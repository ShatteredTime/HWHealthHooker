@file:OptIn(HostInternalApi::class)

package moe.evil.hwhh.xposed.utils.wrapper

import com.highcapable.kavaref.KavaRef
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.ConstructorCondition
import com.highcapable.kavaref.condition.FieldCondition
import com.highcapable.kavaref.condition.MethodCondition
import com.highcapable.kavaref.runtime.KavaRefRuntime
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.AccessibleObject

private const val CANDIDATE_LIMIT = 5

@PublishedApi
internal val resolveLog = HLog("Resolve")

private val kavaRefLog = HLog("KavaRef")

private val kavaRefLogger = lazy {
    KavaRef.setLogger(object : KavaRefRuntime.Logger {
        override val tag get() = "KavaRef"
        override fun debug(msg: Any?, throwable: Throwable?) = kavaRefLog.debug { msg.toString() }
        override fun info(msg: Any?, throwable: Throwable?) = kavaRefLog.info { msg.toString() }
        override fun warn(msg: Any?, throwable: Throwable?) =
            kavaRefLog.warn(throwable) { msg.toString() }

        override fun error(msg: Any?, throwable: Throwable?) =
            kavaRefLog.error(throwable) { msg.toString() }
    })
}

class HostResolutionException(label: String) : IllegalStateException("Unresolved: $label")

inline fun <T : Any> T?.orFail(label: () -> String): T =
    this ?: throw HostResolutionException(label())

inline fun <T> optionally(label: String, block: () -> T): T? = try {
    block()
} catch (e: HostResolutionException) {
    resolveLog.warn { "$label unavailable <- ${e.message}" }
    null
}

@PublishedApi
internal fun List<*>.candidates() = take(CANDIDATE_LIMIT).joinToString("") { "\n  - $it" } +
        if (size > CANDIDATE_LIMIT) "\n  - (${size - CANDIDATE_LIMIT} more)" else ""

fun <C : Collection<*>> C.orWarnEmpty(label: String) = also {
    if (it.isEmpty()) resolveLog.warn { "No match for $label" }
}

fun <C : Collection<*>> C.requireNotEmpty(label: String) =
    takeIf { it.isNotEmpty() }.orFail { label }

private fun <M : AccessibleObject> M.accessible() = apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
private fun Class<*>.scope() = (this as Class<Any>).resolve().optional()
    .also { kavaRefLogger.value }

fun Class<*>.method(condition: MethodCondition<Any>.() -> Unit = {}): HostMethod<Any>? =
    scope().firstMethodOrNull(condition)?.let { HostMethod(it.self.accessible()) }

fun <R : Any> Class<*>.method(
    returning: Class<R>,
    condition: MethodCondition<Any>.() -> Unit = {},
): HostMethod<R>? = scope().firstMethodOrNull {
    condition()
    returnType(returning)
}?.let { HostMethod(it.self.accessible()) }

fun Class<*>.requireMethod(condition: MethodCondition<Any>.() -> Unit = {}) =
    method(condition).orFail { methodLabel(condition) }

fun <R : Any> Class<*>.requireMethod(
    returning: Class<R>,
    condition: MethodCondition<Any>.() -> Unit = {},
) = method(returning, condition).orFail { methodLabel(condition) }

fun Class<*>.field(condition: FieldCondition<Any>.() -> Unit = {}): HostField<Any>? =
    scope().firstFieldOrNull(condition)?.let { HostField(it.self.accessible()) }

fun <T : Any> Class<*>.field(
    type: Class<T>,
    condition: FieldCondition<Any>.() -> Unit = {},
): HostField<T>? = scope().firstFieldOrNull {
    condition()
    this.type = type
}?.let { HostField(it.self.accessible()) }

fun Class<*>.requireField(condition: FieldCondition<Any>.() -> Unit = {}) =
    field(condition).orFail { fieldLabel(condition) }

fun <T : Any> Class<*>.requireField(
    type: Class<T>,
    condition: FieldCondition<Any>.() -> Unit = {},
) = field(type, condition).orFail { fieldLabel(condition) }

fun Class<*>.fields(condition: FieldCondition<Any>.() -> Unit = {}): List<HostField<Any>> =
    scope().field(condition).map { HostField(it.self.accessible()) }

fun <T : Any> Class<T>.constructor(
    condition: ConstructorCondition<Any>.() -> Unit = {},
): HostConstructor<T>? =
    scope().firstConstructorOrNull(condition)?.let { HostConstructor(it.self.accessible()) }

fun <T : Any> Class<T>.requireConstructor(condition: ConstructorCondition<Any>.() -> Unit = {}) =
    constructor(condition).orFail { "$name#<init>" }

private fun Class<*>.methodLabel(condition: MethodCondition<Any>.() -> Unit) =
    "$name#${MethodCondition<Any>().apply(condition).name ?: "<method>"}"

private fun Class<*>.fieldLabel(condition: FieldCondition<Any>.() -> Unit) =
    "$name#${FieldCondition<Any>().apply(condition).name ?: "<field>"}"

inline fun <reified T : Any> hostClass(): Class<T>? = runCatching { classOf<T>() }
    .onFailure { resolveLog.warn { "Class not found <- ${it.describe()}" } }
    .getOrNull()

@PublishedApi
internal inline fun <reified R : Any> returnTypeOf(): Class<*>? = when (R::class) {
    Any::class -> null
    Unit::class -> Void.TYPE
    else -> classOf<R>()
}

@PublishedApi
internal inline fun <reified T : Any> fieldTypeOf(): Class<*>? =
    if (T::class == Any::class) null else classOf<T>()

class HostBridge internal constructor(
    private val bridge: DexKitBridge,
    internal val loader: ClassLoader,
) {
    fun findMethod(init: FindMethod.() -> Unit) = bridge.findMethod(init).map(::HostMethodData)

    fun findClass(init: FindClass.() -> Unit) = bridge.findClass(init).map(::HostClassData)

    fun findField(init: FindField.() -> Unit) = bridge.findField(init).map(::HostFieldData)

    companion object {
        private val nativeLibrary = lazy { System.loadLibrary("dexkit") }

        fun <R> open(loader: ClassLoader, block: (HostBridge) -> R): R {
            nativeLibrary.value
            return DexKitBridge.create(loader, true).use { block(HostBridge(it, loader)) }
        }
    }
}

fun HostBridge.hostClass(name: String): Class<*>? = runCatching { loader.loadClass(name) }
    .onFailure { resolveLog.warn { "Class not found: $name" } }
    .getOrNull()

fun HostBridge.requireClass(name: String) = hostClass(name).orFail { name }

inline fun <reified R : Any> HostBridge.method(label: String, data: HostMethodData) =
    method<R>(label, data, returnTypeOf<R>())

@PublishedApi
internal fun <R : Any> HostBridge.method(
    label: String,
    data: HostMethodData,
    expected: Class<*>?,
): HostMethod<R>? {
    if (expected != null && !(data returns expected)) {
        resolveLog.error { "Return type mismatch for $label: expected ${expected.name} in $data" }
        return null
    }
    return runCatching { data.data.getMethodInstance(loader) }
        .onFailure { resolveLog.error { "Cannot load $label: $data <- ${it.describe()}" } }
        .getOrNull()
        ?.let { HostMethod<R>(it.accessible()) }
        ?.also { resolveLog.debug { "Resolved $label -> ${it.member}" } }
}

inline fun <reified T : Any> HostBridge.field(label: String, data: HostFieldData) =
    field<T>(label, data, fieldTypeOf<T>())

@PublishedApi
internal fun <T : Any> HostBridge.field(
    label: String,
    data: HostFieldData,
    expected: Class<*>?,
): HostField<T>? {
    if (expected != null && !(data holds expected)) {
        resolveLog.error { "Field type mismatch for $label: expected ${expected.name} in $data" }
        return null
    }
    return runCatching { data.data.getFieldInstance(loader) }
        .onFailure { resolveLog.error { "Cannot load $label: $data <- ${it.describe()}" } }
        .getOrNull()
        ?.let { HostField<T>(it.accessible()) }
        ?.also { resolveLog.debug { "Resolved $label -> ${it.member}" } }
}

inline fun <reified R : Any> HostBridge.method(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostMethodData>.() -> HostMethodData? = { singleOrNull() },
    crossinline match: MethodMatcher.() -> Unit,
): HostMethod<R>? {
    val matches = findMethod {
        inPackage?.let { searchPackages(it) }
        matcher {
            returnTypeOf<R>()?.let { returnType(it) }
            match()
        }
    }
    val data = matches.pick() ?: run {
        val loose = matches.ifEmpty {
            findMethod {
                inPackage?.let { searchPackages(it) }
                matcher { match() }
            }
        }
        resolveLog.warn {
            "No unique match for $label among ${matches.size}" +
                    (if (matches.isEmpty() && loose.isNotEmpty()) {
                        " (${loose.size} ignoring return type)"
                    } else "") + loose.candidates()
        }
        return null
    }
    return method<R>(label, data)
}

inline fun <reified R : Any> HostBridge.requireMethod(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostMethodData>.() -> HostMethodData? = { singleOrNull() },
    crossinline match: MethodMatcher.() -> Unit,
) = method<R>(label, inPackage, pick, match).orFail { label }

inline fun <reified T : Any> HostBridge.field(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostFieldData>.() -> HostFieldData? = { singleOrNull() },
    crossinline match: FieldMatcher.() -> Unit,
): HostField<T>? {
    val matches = findField {
        inPackage?.let { searchPackages(it) }
        matcher {
            fieldTypeOf<T>()?.let { type(it) }
            match()
        }
    }
    val data = matches.pick() ?: run {
        val loose = matches.ifEmpty {
            findField {
                inPackage?.let { searchPackages(it) }
                matcher { match() }
            }
        }
        resolveLog.warn {
            "No unique match for $label among ${matches.size}" +
                    (if (matches.isEmpty() && loose.isNotEmpty()) {
                        " (${loose.size} ignoring field type)"
                    } else "") + loose.candidates()
        }
        return null
    }
    return field<T>(label, data)
}

inline fun <reified T : Any> HostBridge.requireField(
    label: String,
    inPackage: String? = null,
    noinline pick: List<HostFieldData>.() -> HostFieldData? = { singleOrNull() },
    crossinline match: FieldMatcher.() -> Unit,
) = field<T>(label, inPackage, pick, match).orFail { label }
