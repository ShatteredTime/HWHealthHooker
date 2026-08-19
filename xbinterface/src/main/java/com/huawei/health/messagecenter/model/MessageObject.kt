package com.huawei.health.messagecenter.model

class MessageObject {
    val msgId: String? get() = stub()
    val module: String? get() = stub()
    val type: String? get() = stub()
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
