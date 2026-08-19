@file:Suppress("unused")

package com.huawei.hihealth.dictionary.utils

class DicDataTypeUtil {
    enum class DataType {
        ;

        fun value(): Int = throw NotImplementedError(STUB)
    }
}

private const val STUB = "xbinterface stub; HostClassLoaderBridge not installed"
