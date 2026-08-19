@file:Suppress("unused")

package net.zetetic.database.sqlcipher

import android.database.Cursor

class SQLiteDatabase {
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor = stub()
    fun isOpen(): Boolean = stub()
    fun isReadOnly(): Boolean = stub()
    fun getPath(): String = stub()
}

private fun stub(): Nothing =
    throw NotImplementedError("xbinterface stub; HostClassLoaderBridge not installed")
