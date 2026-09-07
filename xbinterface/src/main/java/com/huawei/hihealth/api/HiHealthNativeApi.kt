package com.huawei.hihealth.api

import com.huawei.hihealth.HiDataReadOption
import com.huawei.hihealth.data.listener.HiDataReadResultListener

class HiHealthNativeApi {
    fun readHiHealthData(option: HiDataReadOption, listener: HiDataReadResultListener) {
        stub()
    }

    fun readHiHealthDataEx(options: List<*>, listener: HiDataReadResultListener) {
        stub()
    }
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
