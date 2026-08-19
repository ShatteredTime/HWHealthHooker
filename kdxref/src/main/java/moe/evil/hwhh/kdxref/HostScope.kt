package moe.evil.hwhh.kdxref

import com.highcapable.kavaref.KavaRef
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.ConstructorCondition
import com.highcapable.kavaref.condition.FieldCondition
import com.highcapable.kavaref.condition.MethodCondition
import kotlin.reflect.KClass

@JvmInline
value class HostScope internal constructor(internal val scope: KavaRef.MemberScope<Any>) {
    fun firstMethodOrNullLogged(
        owner: Class<*>,
        condition: MethodCondition<Any>.() -> Unit = {},
    ): HostMethod<Any>? = scope.firstMethodOrNull(condition)?.let { HostMethod(it) }
        ?: null.also {
            hostLog.error(tag = "KavaRef") {
                "Method not found in ${owner.name}: " +
                        scope.method().apply(condition).toReadableDesc()
            }
        }

    fun firstFieldOrNullLogged(
        owner: Class<*>,
        condition: FieldCondition<Any>.() -> Unit = {},
    ): HostField<Any>? = scope.firstFieldOrNull(condition)?.let { HostField(it) }
        ?: null.also {
            hostLog.error(tag = "KavaRef") {
                "Field not found in ${owner.name}: " +
                        scope.field().apply(condition).toReadableDesc()
            }
        }

    fun <R : Any> firstMethodOrNullLogged(
        owner: Class<*>,
        returning: Class<R>,
        condition: MethodCondition<Any>.() -> Unit = {},
    ): HostMethod<R>? = firstMethodOrNullLogged(owner) {
        condition()
        returnType(returning)
    }?.let { HostMethod(it.resolver) }

    fun <T : Any> firstFieldOrNullLogged(
        owner: Class<*>,
        fieldType: Class<T>,
        condition: FieldCondition<Any>.() -> Unit = {},
    ): HostField<T>? = firstFieldOrNullLogged(owner) {
        condition()
        type = fieldType
    }?.let { HostField(it.resolver) }

    fun <T : Any> firstConstructorOrNullLogged(
        owner: Class<*>,
        condition: ConstructorCondition<Any>.() -> Unit = {},
    ): HostConstructor<T>? = scope.firstConstructorOrNull(condition)
        ?.let { HostConstructor(it) }
        ?: null.also {
            hostLog.error(tag = "KavaRef") {
                "Constructor not found in ${owner.name}: " +
                        scope.constructor().apply(condition).toReadableDesc()
            }
        }

    fun fields(condition: FieldCondition<Any>.() -> Unit = {}): List<HostField<Any>> =
        scope.field(condition).map { HostField(it) }
}

@Suppress("UNCHECKED_CAST")
fun Class<*>.resolveAny() = HostScope((this as Class<Any>).resolve().optional(silent = true))

fun Any.resolveInstance() = HostScope(asResolver().optional(silent = true))

fun Class<*>.firstMethodOrNullLogged(
    condition: MethodCondition<Any>.() -> Unit = {},
) = resolveAny().firstMethodOrNullLogged(this, condition)

fun Class<*>.firstFieldOrNullLogged(
    condition: FieldCondition<Any>.() -> Unit = {},
) = resolveAny().firstFieldOrNullLogged(this, condition)

fun <R : Any> Class<*>.firstMethodOrNullLogged(
    returning: Class<R>,
    condition: MethodCondition<Any>.() -> Unit = {},
) = resolveAny().firstMethodOrNullLogged(this, returning, condition)

fun <T : Any> Class<*>.firstFieldOrNullLogged(
    fieldType: Class<T>,
    condition: FieldCondition<Any>.() -> Unit = {},
) = resolveAny().firstFieldOrNullLogged(this, fieldType, condition)

fun <T : Any> Class<T>.firstConstructorOrNullLogged(
    condition: ConstructorCondition<Any>.() -> Unit = {},
): HostConstructor<T>? = resolveAny().firstConstructorOrNullLogged(this, condition)

private fun MethodCondition<*>.toReadableDesc() = describeCondition()

private fun ConstructorCondition<*>.toReadableDesc() = describeCondition()

private fun FieldCondition<*>.toReadableDesc() =
    "${(readGetter("name") as? String)?.takeIf(String::isNotBlank) ?: "<any>"}: " +
            formatType(readGetter("type"))

private fun Any.describeCondition(): String {
    val n = (readGetter("name") as? String)?.takeIf { it.isNotBlank() } ?: "<any>"
    val paramsObj =
        readGetter("parameters") ?: readGetter("parameterTypes") ?: readGetter("paramTypes")
    val paramCount = (readGetter("parameterCount") as? Int)
    val params = formatParams(paramsObj, paramCount)
    val ret = formatType(readGetter("returnType"))
    return "$n($params)->$ret"
}

private fun formatParams(obj: Any?, paramCount: Int?): String {
    val list = when (obj) {
        is Array<*> -> obj.toList()
        is Iterable<*> -> obj.toList()
        else -> null
    }
    return when {
        !list.isNullOrEmpty() -> list.joinToString(", ") { formatType(it) }
        paramCount != null -> if (paramCount == 0) "" else "<$paramCount params>"
        else -> "<any>"
    }
}

private fun formatType(t: Any?) = when (t) {
    null -> "<any>"
    is Class<*> -> if (t == Void.TYPE) "void" else t.name
    is KClass<*> -> t.qualifiedName ?: t.toString()
    else -> t.toString()
}

private fun Any.readGetter(prop: String): Any? {
    val suffix = prop.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    return arrayOf("get$suffix", prop).firstNotNullOfOrNull { name ->
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.let { runCatching { it.invoke(this) }.getOrNull() }
    }
}
