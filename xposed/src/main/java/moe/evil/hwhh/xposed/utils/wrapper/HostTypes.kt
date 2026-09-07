@file:OptIn(HostInternalApi::class)

package moe.evil.hwhh.xposed.utils.wrapper

import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class HostInternalApi

typealias Modifiers = com.highcapable.kavaref.condition.type.Modifiers

inline fun <reified T : Any> classOf(primitiveType: Boolean = true): Class<T> =
    com.highcapable.kavaref.extension.classOf<T>(primitiveType)

@JvmInline
value class HostMethod<out R : Any> internal constructor(
    @property:HostInternalApi internal val member: Method,
) {
    val owner: Class<*> get() = member.declaringClass
    val name: String get() = member.name
    val label get() = "${owner.simpleName}#$name"
}

@JvmInline
value class HostField<T : Any> internal constructor(
    @property:HostInternalApi internal val member: Field,
) {
    val owner: Class<*> get() = member.declaringClass
    val name: String get() = member.name
    val label get() = "${owner.simpleName}#$name"
}

@JvmInline
value class HostConstructor<T : Any> internal constructor(
    @property:HostInternalApi internal val member: Constructor<*>,
) {
    val owner: Class<*> get() = member.declaringClass
    val label get() = "${owner.simpleName}#<init>"
}

@JvmInline
value class HostMethodData internal constructor(
    @property:HostInternalApi internal val data: MethodData,
) {
    val name get() = data.methodName
    val className get() = data.className
    val descriptor get() = data.descriptor
    val paramCount get() = data.paramCount
    val isPublic get() = Modifier.isPublic(data.modifiers)
    val isStatic get() = Modifier.isStatic(data.modifiers)
    val invokes get() = data.invokes.map(::HostMethodData)
    val usingStrings get() = data.usingStrings
    val usingFieldCount get() = data.usingFields.size
    val usedFields get() = data.usingFields.mapTo(HashSet()) { HostFieldData(it.field) }

    infix fun returns(type: Class<*>) = data.returnTypeName == DexSignUtil.getTypeName(type)

    fun takes(vararg types: Class<*>) =
        data.paramTypeNames.size == types.size &&
                types.withIndex().all { (index, type) ->
                    data.paramTypeNames[index] == DexSignUtil.getTypeName(type)
                }

    override fun toString() = data.toString()
}

@JvmInline
value class HostFieldData internal constructor(
    @property:HostInternalApi internal val data: FieldData,
) {
    val name get() = data.fieldName
    val className get() = data.className

    infix fun holds(type: Class<*>) = data.typeName == DexSignUtil.getTypeName(type)

    override fun toString() = data.toString()
}

@JvmInline
value class HostClassData internal constructor(private val data: ClassData) {
    val name get() = data.name

    override fun toString() = data.toString()
}
