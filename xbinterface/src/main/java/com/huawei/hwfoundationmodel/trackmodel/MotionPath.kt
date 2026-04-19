package com.huawei.hwfoundationmodel.trackmodel

class MotionPath {
    fun requestAltitudeList(): ArrayList<Any>? = stub()
    fun requestHeartRateList(): ArrayList<HeartRateData>? = stub()
    fun requestStepRateList(): ArrayList<StepRateData>? = stub()
    fun requestLbsDataMap(): Map<Long, DoubleArray>? = stub()
}

private fun stub(): Nothing = throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
