package com.tencent.mmkv

class MMKV {
    fun allKeys(): Array<String>? = stub()
    fun decodeString(key: String, defaultValue: String?): String? = stub()
    fun decodeLong(key: String, defaultValue: Long): Long = stub()
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
