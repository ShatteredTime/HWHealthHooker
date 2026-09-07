@file:Suppress("unused")

package com.huawei.hwbasemgr

interface IBaseResponseCallback {
    fun onResponse(errCode: Int, data: Any?)
}
