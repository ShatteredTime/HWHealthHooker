package com.huawei.hwfoundationmodel.trackmodel

class HeartRateData : TimeSequence {
    fun acquireHeartRate(): Int = throw NotImplementedError("stub")
    override fun acquireTime(): Long = throw NotImplementedError("stub")
}
