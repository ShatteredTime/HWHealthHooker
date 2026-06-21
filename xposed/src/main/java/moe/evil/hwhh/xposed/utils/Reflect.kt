package moe.evil.hwhh.xposed.utils

import com.google.gson.annotations.SerializedName
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import java.lang.reflect.Field

inline fun <reified T : Any> Any.rtField(fieldName: String): T? =
    asResolver().optional(silent = true).firstFieldOrNull {
        name = fieldName
        superclass()
    }?.getQuietly() as? T

fun Class<*>.fieldBySerializedName(serializedName: String): Field? =
    generateSequence(this) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.getAnnotation(SerializedName::class.java)?.value == serializedName }
        ?.apply { isAccessible = true }
