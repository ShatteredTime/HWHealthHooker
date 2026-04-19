package moe.evil.hwhh.xposed.utils

import com.highcapable.kavaref.KavaRef.Companion.asResolver

inline fun <reified T : Any> Any.rtField(fieldName: String): T? =
    asResolver().optional(silent = true).firstFieldOrNull {
        name = fieldName
        superclass()
    }?.getQuietly() as? T
