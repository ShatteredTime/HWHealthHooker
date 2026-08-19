package moe.evil.hwhh.kdxref

import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil
import java.lang.reflect.Modifier

@JvmInline
value class HostMethodData @PublishedApi internal constructor(
    @PublishedApi internal val data: MethodData,
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
value class HostFieldData @PublishedApi internal constructor(
    @PublishedApi internal val data: FieldData,
) {
    val name get() = data.fieldName
    val className get() = data.className

    override fun toString() = data.toString()
}

@JvmInline
value class HostClassData internal constructor(private val data: ClassData) {
    val name get() = data.name

    override fun toString() = data.toString()
}
