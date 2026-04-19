@file:Suppress("unused")

package com.huawei.hwbasemgr

fun interface IBaseResponseCallback {
    fun onResponse(errCode: Int, data: Any?)
}
