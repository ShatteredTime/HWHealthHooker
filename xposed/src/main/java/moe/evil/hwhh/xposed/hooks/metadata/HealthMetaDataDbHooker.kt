package moe.evil.hwhh.xposed.hooks.metadata

import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.xposed.hooks.HiHealthDbHooker

internal interface HealthMetadataDbApi : MetadataSourceApi {
    fun snapshot(): Result<DbMetadataSnapshot>
}

internal object HealthMetaDataDbHooker :
    MetaDataBaseHooker<HealthMetadataDbApi>() {
    private const val STAT_TYPE = "stat_type"
    private const val HIHEALTH_TYPE = "hihealth_type"
    private const val UNIT_ID = "unit_id"
    private val db by require { HiHealthDbHooker }

    @Volatile
    private var cached: DbMetadataSnapshot? = null

    override val providedApi = object : HealthMetadataDbApi {
        override val isMajor get() = this@HealthMetaDataDbHooker.isMajor
        override val isAvailable get() = this@HealthMetaDataDbHooker.isAvailable
        override val availabilityError get() = this@HealthMetaDataDbHooker.availabilityError
        override fun snapshot() =
            cached?.let { Result.success(it) } ?: synchronized(this@HealthMetaDataDbHooker) {
                cached?.let { Result.success(it) } ?: runCatching {
                    val account = db.account()
                    val units = hashMapOf<Int, Int>()
                    val bases = hashMapOf<Int, Int>()
                    db.tables().getOrThrow().forEach { table ->
                        if (STAT_TYPE !in table.columns) return@forEach
                        if (UNIT_ID in table.columns) {
                            db.distinctIntPairs(table, STAT_TYPE, UNIT_ID, account).getOrThrow()
                                .forEach { row ->
                                    if (row[1] != 0) units[row[0]] = row[1]
                                    else units.putIfAbsent(row[0], row[1])
                                }
                        }
                        if (HIHEALTH_TYPE in table.columns) {
                            db.distinctIntPairs(table, STAT_TYPE, HIHEALTH_TYPE, account)
                                .getOrThrow()
                                .forEach { bases.putIfAbsent(it[0], it[1]) }
                        }
                    }
                    DbMetadataSnapshot(units, bases)
                }.onSuccess { cached = it }
            }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        check(db.isAvailable) { "HiHealthDbHooker is unavailable" }
    }
}
