package com.huawei.hwfoundationmodel.trackmodel

class MotionPathSimplify {
    fun requestSportType(): Int = stub()
    fun requestStartTime(): Long = stub()
    fun requestEndTime(): Long = stub()
    fun requestTotalTime(): Long = stub()
    fun requestTotalDistance(): Int = stub()
    fun requestTotalSteps(): Int = stub()
    fun requestTotalCalories(): Int = stub()
    fun requestMapCoordinate(): String? = stub()

    fun requestAvgHeartRate(): Int = stub()
    fun requestMaxHeartRate(): Int = stub()
    fun requestMinHeartRate(): Int = stub()
    fun requestAvgStepRate(): Int = stub()
    fun requestBestStepRate(): Int = stub()
    fun requestAvgPace(): Float = stub()
    fun requestMaxAltitude(): Float = stub()
    fun requestMinAltitude(): Float = stub()
    fun requestTotalDescent(): Float = stub()

    fun requestSportData(): Map<String, Int>? = stub()
}

private fun stub(): Nothing = throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
