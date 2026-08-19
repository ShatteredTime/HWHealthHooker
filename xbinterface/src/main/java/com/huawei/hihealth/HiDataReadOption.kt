package com.huawei.hihealth

class HiDataReadOption {
    fun setType(type: IntArray?) {
        stub()
    }

    fun setType(type: IntArray?, constantsKey: Array<String?>?, alignType: Int) {
        stub()
    }

    fun setConstantsKey(constantsKey: Array<String?>?) {
        stub()
    }

    fun getType(): IntArray? = stub()
    fun getConstantsKey(): Array<String?>? = stub()
    fun setTimeInterval(startTime: Long, endTime: Long) {
        stub()
    }

    fun setCount(count: Int) {
        stub()
    }

    fun setSortOrder(order: Int) {
        stub()
    }
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
