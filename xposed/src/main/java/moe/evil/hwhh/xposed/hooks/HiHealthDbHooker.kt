package moe.evil.hwhh.xposed.hooks

import android.database.Cursor
import com.highcapable.kavaref.extension.classOf
import com.huawei.hihealthservice.db.helper.HiHealthDBHelper
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostMethod
import moe.evil.hwhh.kdxref.HostMethodData
import moe.evil.hwhh.kdxref.describe
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.concurrent.ConcurrentHashMap

internal data class HiHealthAccount(val userId: Int, val clientIds: Set<Int>)

internal data class HiHealthTable(val db: String, val name: String, val createSql: String) {
    val columns = createSql.substringAfter('(', "").substringBeforeLast(')', "")
        .splitToSequence(',')
        .mapNotNullTo(HashSet()) { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }
}

internal interface HiHealthDbApi : HookApi {
    val isAvailable: Boolean
    fun account(): HiHealthAccount?
    fun tables(): Result<List<HiHealthTable>>
    fun localTypes(): Result<List<Int>>
    fun invalidate()
    fun distinctInts(
        table: HiHealthTable,
        column: String,
        account: HiHealthAccount?,
    ): Result<List<Int>>

    fun distinctIntPairs(
        table: HiHealthTable,
        first: String,
        second: String,
        account: HiHealthAccount?,
    ): Result<List<IntArray>>

    fun select(sql: String, db: String = "main"): Result<List<Map<String, String?>>>
}

internal object HiHealthDbHooker : DexKitHooker<HiHealthDbApi>() {
    private const val MAIN_DB = "main"
    private const val SENSITIVE_DB = "hihealth_sensitive.db"
    private const val USER_ID = "user_id"
    private const val CLIENT_ID = "client_id"
    private const val STAT_TYPE = "stat_type"
    private const val MIN_STAT_TYPE = 40000

    private val log = HLog.of<HiHealthDbHooker>()

    private class AccountMembers(
        val huid: () -> String?,
        val userId: (String) -> Int?,
        val clientIds: (Int) -> Set<Int>,
    )

    private class Members(
        val openMain: HostMethod<HiHealthDBHelper>,
        val openNamed: HostMethod<HiHealthDBHelper>,
        val account: AccountMembers?,
    )

    @Volatile
    private var members: Members? = null

    private val isAvailable
        get() = members != null

    @Volatile
    private var tableCache: List<HiHealthTable>? = null

    @Volatile
    private var typeCache: List<Int>? = null

    @Volatile
    private var accountCache: HiHealthAccount? = null

    private val databases = ConcurrentHashMap<String, SQLiteDatabase>()

    override val providedApi = object : HiHealthDbApi {
        override val isAvailable get() = this@HiHealthDbHooker.isAvailable

        override fun account() = accountCache
            ?: synchronized(this@HiHealthDbHooker) {
                accountCache ?: members?.account?.let { resolved ->
                    val huid = resolved.huid()?.takeIf(String::isNotEmpty) ?: return@let null
                    val userId = resolved.userId(huid)?.takeIf { it > 0 } ?: return@let null
                    HiHealthAccount(userId, resolved.clientIds(userId))
                }?.also { accountCache = it }
            }

        override fun tables() = tableCache?.let { Result.success(it) }
            ?: synchronized(this@HiHealthDbHooker) {
                tableCache?.let { Result.success(it) } ?: runCatching {
                    val resolved = checkNotNull(members) { "Database API unavailable" }
                    buildList {
                        val fingerprints = hashSetOf<Set<String>>()
                        listOf(MAIN_DB, SENSITIVE_DB).forEach { db ->
                            val rows = resolved.withCursor(
                                db,
                                "SELECT name, sql FROM sqlite_master WHERE type='table'",
                            ) { cursor ->
                                val nameAt = cursor.getColumnIndex("name")
                                val sqlAt = cursor.getColumnIndex("sql")
                                buildList {
                                    while (cursor.moveToNext()) {
                                        val name = cursor.getString(nameAt) ?: continue
                                        val sql = cursor.getString(sqlAt) ?: continue
                                        if (name != "android_metadata") add(name to sql)
                                    }
                                }
                            } ?: run {
                                check(db != MAIN_DB) { "Main db schema unreadable" }
                                log.warn { "Skip $db, schema unreadable" }
                                return@forEach
                            }
                            if (rows.isEmpty()) return@forEach
                            if (!fingerprints.add(rows.mapTo(hashSetOf()) { "${it.first} ${it.second}" })) {
                                log.info { "Skip $db, schema identical to an already-read db" }
                                return@forEach
                            }
                            rows.mapTo(this) { (name, sql) -> HiHealthTable(db, name, sql) }
                        }
                    }
                }.onSuccess { tableCache = it }
            }

        override fun localTypes() = typeCache?.let { Result.success(it) }
            ?: synchronized(this@HiHealthDbHooker) {
                typeCache?.let { Result.success(it) } ?: runCatching {
                    checkNotNull(members) { "Database API unavailable" }
                    val account = checkNotNull(account()) { "Current account not resolved" }
                    val out = sortedSetOf<Int>()
                    tables().getOrThrow().forEach { table ->
                        val column =
                            table.typeColumn.takeIf { it in table.columns } ?: return@forEach
                        val floor = if (column == STAT_TYPE) MIN_STAT_TYPE else 1
                        distinctInts(table, column, account).getOrThrow()
                            .filterTo(out) { it >= floor }
                    }
                    out.toList().also { log.info { "Local types=${it.size}" } }
                }.onSuccess { typeCache = it }
            }

        override fun invalidate() {
            tableCache = null
            typeCache = null
            accountCache = null
            databases.clear()
        }

        override fun distinctInts(
            table: HiHealthTable,
            column: String,
            account: HiHealthAccount?,
        ): Result<List<Int>> = runCatching {
            val resolved = checkNotNull(members) { "Database API unavailable" }
            if (column !in table.columns) emptyList()
            else checkNotNull(
                resolved.withCursor(
                    table.db,
                    "SELECT DISTINCT $column FROM ${table.name}${table.filterBy(account)} " +
                            "ORDER BY $column",
                ) { cursor ->
                    val at = cursor.getColumnIndex(column)
                    buildList(cursor.count) {
                        while (cursor.moveToNext()) add(cursor.getInt(at))
                    }
                },
            ) { "Query failed: ${table.name}.$column" }
        }

        override fun distinctIntPairs(
            table: HiHealthTable,
            first: String,
            second: String,
            account: HiHealthAccount?,
        ): Result<List<IntArray>> = runCatching {
            val resolved = checkNotNull(members) { "Database API unavailable" }
            if (first !in table.columns || second !in table.columns) emptyList()
            else checkNotNull(
                resolved.withCursor(
                    table.db,
                    "SELECT DISTINCT $first, $second FROM ${table.name}${table.filterBy(account)}",
                ) { cursor ->
                    val firstAt = cursor.getColumnIndex(first)
                    val secondAt = cursor.getColumnIndex(second)
                    buildList(cursor.count) {
                        while (cursor.moveToNext()) {
                            add(intArrayOf(cursor.getInt(firstAt), cursor.getInt(secondAt)))
                        }
                    }
                },
            ) { "Query failed: ${table.name}.$first/$second" }
        }

        override fun select(sql: String, db: String): Result<List<Map<String, String?>>> =
            runCatching {
                val resolved = checkNotNull(members) { "Database API unavailable" }
                val statement = sql.trim().removeSuffix(";").trim()
                check(statement.startsWith("SELECT", ignoreCase = true) && ';' !in statement) {
                    "Rejected, single SELECT only: $sql"
                }
                checkNotNull(
                    resolved.withCursor(db, statement) { cursor ->
                        val names = Array(cursor.columnCount, cursor::getColumnName)
                        buildList(cursor.count) {
                            while (cursor.moveToNext()) {
                                add(buildMap(names.size) {
                                    names.forEachIndexed { index, name ->
                                        put(name, cursor.getString(index))
                                    }
                                })
                            }
                        }
                    },
                ) { "Query failed" }
            }
    }

    private fun singletonOf(role: String, hosted: HostMethod<*>) =
        hostMethod<Any>("$role#instance", pick = { singleOrNull(HostMethodData::isStatic) }) {
            declaredClass(hosted.owner)
            returnType(hosted.owner)
            paramTypes()
        }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val helper = classOf<HiHealthDBHelper>()
        val mainFactory = hostMethod<HiHealthDBHelper>(
            label = "HiHealthDBHelper#main",
            pick = { singleOrNull { it.isStatic && it.isPublic } },
        ) {
            declaredClass(helper)
            paramTypes()
        } ?: return
        val namedFactory = hostMethod<HiHealthDBHelper>(
            label = "HiHealthDBHelper#named",
            pick = { singleOrNull { it.isStatic && it.isPublic } },
        ) {
            declaredClass(helper)
            paramTypes(classOf<String>())
        } ?: return

        val account = run {
            val huidMethod = hostMethod<String>(
                "userStore#huid",
                pick = { singleOrNull(HostMethodData::isPublic) }) {
                usingStrings = listOf("getStringHuid() from DB")
                paramTypes()
            } ?: return@run null
            val userIdMethod =
                hostMethod<Int>(
                    "userInfo#userId",
                    pick = { singleOrNull(HostMethodData::isPublic) }) {
                    usingStrings = listOf("queryUserInfoForUserId")
                    paramTypes(classOf<String>(), classOf<Int>())
                } ?: return@run null
            val clientIdsMethod = hostMethod<List<*>>(
                label = "clientStore#clientIds",
                pick = { singleOrNull(HostMethodData::isPublic) },
            ) {
                usingStrings = listOf("user_id =? and device_id >=? and app_id >=? ")
                paramTypes(classOf<Int>())
            } ?: return@run null

            val userStoreOf = singletonOf("userStore", huidMethod) ?: return@run null
            val userInfoOf = singletonOf("userInfo", userIdMethod) ?: return@run null
            val clientStoreOf = singletonOf("clientStore", clientIdsMethod) ?: return@run null

            AccountMembers(
                huid = { huidMethod.on(userStoreOf.invokeQuietly()).invokeQuietly() },
                userId = { huid ->
                    userIdMethod.on(userInfoOf.invokeQuietly()).invokeQuietly(huid, 0)
                },
                clientIds = { userId ->
                    clientIdsMethod.on(clientStoreOf.invokeQuietly()).invokeQuietly(userId)
                        ?.filterIsInstance<Int>()?.toHashSet().orEmpty()
                },
            )
        }.also { if (it == null) log.warn { "Account resolution unavailable" } }

        members = Members(mainFactory, namedFactory, account)

        log.info { "Ready, account=${account != null}" }
    }

    private val HiHealthTable.typeColumn
        get() = when (name) {
            "hihealth_stat_day", "config_stat_day" -> STAT_TYPE
            "business_data" -> "type"
            else -> "type_id"
        }

    private fun HiHealthTable.filterBy(account: HiHealthAccount?) = when {
        account == null -> ""
        USER_ID in columns -> " WHERE $USER_ID = ${account.userId}"
        CLIENT_ID in columns && account.clientIds.isNotEmpty() ->
            " WHERE $CLIENT_ID IN (${account.clientIds.joinToString(",")})"

        else -> ""
    }

    private fun Members.open(name: String) = databases[name]?.takeIf { it.isOpen() }
        ?: synchronized(this@HiHealthDbHooker) {
            databases[name]?.takeIf { it.isOpen() } ?: runCatching {
                val helper = if (name == SENSITIVE_DB) {
                    openNamed.invokeQuietly(name)
                } else {
                    openMain.invokeQuietly()
                }
                checkNotNull(helper) { "no HiHealthDBHelper" }
                    .getWritableDatabase()
                    ?: error("writable database is null")
            }.onFailure { log.warn(it) { "Open $name failed" } }
                .getOrNull()?.also { databases[name] = it }
        }

    private fun <T> Members.withCursor(db: String, sql: String, block: (Cursor) -> T): T? {
        val database = open(db) ?: return null
        val cursor = runCatching { database.rawQuery(sql, null) }
            .onFailure { log.warn { "Query failed <- ${it.describe()}: $sql" } }
            .getOrNull() ?: return null
        return cursor.use(block)
    }
}
