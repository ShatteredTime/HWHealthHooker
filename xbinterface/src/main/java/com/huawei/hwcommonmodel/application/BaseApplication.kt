package com.huawei.hwcommonmodel.application

import android.content.Context

class BaseApplication {
    companion object {
        @JvmStatic
        fun getContext(): Context? = stub()
    }
}

private fun stub(): Nothing = throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
