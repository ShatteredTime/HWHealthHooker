package com.huawei.hihealth.data.listener

interface HiDataReadResultListener {
    fun onResult(data: Any?, errCode: Int, index: Int)
    fun onResultIntent(readIntent: Int, data: Any?, errCode: Int, index: Int)
}
