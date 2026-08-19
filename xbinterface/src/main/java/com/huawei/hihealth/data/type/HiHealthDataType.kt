@file:Suppress("unused")

package com.huawei.hihealth.data.type

class HiHealthDataType {
    enum class Category {
        POINT,
        SET,
        SESSION,
        SEQUENCE,
        STAT,
        REALTIME,
        CONFIG,
        CONFIGSTAT,
        CHECK_DWONLOAD,
        BUSINESS,
        UNKNOWN,
    }
}
