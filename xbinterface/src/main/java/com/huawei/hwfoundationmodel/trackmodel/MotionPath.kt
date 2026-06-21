package com.huawei.hwfoundationmodel.trackmodel

class MotionPath {
    fun requestAltitudeList(): ArrayList<TimeSequence>? = stub()
    fun requestHeartRateList(): ArrayList<HeartRateData>? = stub()
    fun requestStepRateList(): ArrayList<StepRateData>? = stub()
    fun requestLbsDataMap(): Map<Long, DoubleArray>? = stub()
    fun requestRidePostureDataList(): List<TimeSequence>? = stub()
    fun requestPowerList(): List<TimeSequence>? = stub()
    fun requestSpeedList(): List<TimeSequence>? = stub()
}

private fun stub(): Nothing = throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
