package com.huawei.hihealth.api

import com.huawei.hihealth.HiDataReadOption
import com.huawei.hihealth.data.listener.HiDataReadResultListener

interface HiHealthApi {
    fun readHiHealthData(option: HiDataReadOption, listener: HiDataReadResultListener)
    fun readHiHealthDataEx(options: List<*>, listener: HiDataReadResultListener)
}
