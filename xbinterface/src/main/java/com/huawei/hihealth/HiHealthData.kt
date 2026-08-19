package com.huawei.hihealth

import android.content.ContentValues

class HiHealthData {
    fun getValueHolder(): ContentValues? = stub()
    fun getType(): Int = stub()
    fun getStartTime(): Long = stub()
    fun getEndTime(): Long = stub()
    fun getValue(): Double = stub()
    fun getIntValue(): Int = stub()
    fun getPointUnit(): Int = stub()
    fun getSubType(): Int = stub()
    fun getDataSource(): String = stub()
    fun getDeviceUuid(): String? = stub()
    fun getSequenceData(): String? = stub()
    fun getSimpleData(): String? = stub()
    fun getSequenceFileUrl(): String? = stub()
    fun getMetaData(): String? = stub()
    fun getString(key: String): String? = stub()
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
