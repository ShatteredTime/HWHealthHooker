@file:Suppress("unused")

package com.huawei.hihealthservice.db.helper

import net.zetetic.database.sqlcipher.SQLiteDatabase

class HiHealthDBHelper {
    fun getWritableDatabase(): SQLiteDatabase? = stub()
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
