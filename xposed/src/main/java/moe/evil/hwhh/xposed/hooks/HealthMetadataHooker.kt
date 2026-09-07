package moe.evil.hwhh.xposed.hooks

import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.shared.model.HealthCategory
import moe.evil.hwhh.xposed.hooks.metadata.HealthAliasHooker
import moe.evil.hwhh.xposed.hooks.metadata.HealthMetaDataDbHooker
import moe.evil.hwhh.xposed.hooks.metadata.HealthMetadataAnalysisHooker
import moe.evil.hwhh.xposed.hooks.metadata.HealthMetadataHostHooker
import moe.evil.hwhh.xposed.hooks.metadata.HealthTypeClassifierHooker
import moe.evil.hwhh.xposed.hooks.metadata.HostDictionaryEntry
import moe.evil.hwhh.xposed.hooks.metadata.MetadataSourceApi
import moe.evil.hwhh.xposed.hooks.metadata.TrackSlot
import moe.evil.hwhh.xposed.model.Aggregation
import moe.evil.hwhh.xposed.model.HealthMetadata
import moe.evil.hwhh.xposed.model.NameSource
import moe.evil.hwhh.xposed.model.TypeOrigin
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge

internal interface HealthMetadataApi : HookApi {
    fun resolveAll(types: Collection<Int>): Result<Map<Int, HealthMetadata>>
}

internal object HealthMetadataHooker : DexKitHooker<HealthMetadataApi>() {
    // com.huawei.hihealthservice.store.stat.HiTrackStat.m39373a(int, int, int, double, int) boolean
    // with numarr
    // TODO: Automatic solving
    private val trackMetrics = mapOf(
        0 to "totalSteps", 1 to "totalDistance", 2 to "totalCalories", 3 to "totalTime",
        4 to "count", 5 to "abnormalCount", 7 to "maxDistance", 8 to "maxMet", 9 to "creepingWave",
    )
    private val trackSpecials = mapOf(
        42004 to TrackSlot("track_avgPace", 14),
        42007 to TrackSlot("track_lastMaxMet", 0),
        43800 to TrackSlot("track_sportTypeFlags", 0),
    )
    private val log = HLog.of<HealthMetadataHooker>()
    private val dbMetadata by require { HealthMetaDataDbHooker }
    private val alias by require { HealthAliasHooker }
    private val analysis by require { HealthMetadataAnalysisHooker }
    private val classifier by require { HealthTypeClassifierHooker }
    private val host by require { HealthMetadataHostHooker }

    private val unavailableMajors
        get() = dependencyApis.filterIsInstance<MetadataSourceApi>()
            .filter { it.isMajor && !it.isAvailable }

    private data class ResolvedName(
        val value: String,
        val source: NameSource,
    )

    private fun resolveName(
        enumName: String?,
        entry: HostDictionaryEntry?,
        keyName: String?,
        analyzedName: String?,
        trackName: String?,
        aliasName: String?,
    ): ResolvedName? =
        enumName.from(NameSource.DATATYPE_ENUM)
            ?: entry?.let { it.name.from(it.source) }
            ?: keyName.from(NameSource.KEY_ARRAY)
            ?: analyzedName.from(NameSource.ANALYZED)
            ?: trackName.from(NameSource.TRACK_SLOT)
            ?: aliasName.from(NameSource.ALIAS_HOOK)

    private fun String?.from(source: NameSource) =
        this?.let { ResolvedName(it, source) }

    private val MetadataSourceApi.name
        get() = javaClass.enclosingClass?.simpleName ?: javaClass.name

    private val trackSlots by lazy {
        val analyzed = analysis.takeIf { it.isAvailable }?.snapshot()
        buildMap {
            putAll(trackSpecials)
            host.trackIdArrays.forEach arrays@{ (source, ids) ->
                val family = analyzed?.trackFamilies?.get(source) ?: "track".takeIf {
                    ids.all { id -> HealthCategory.of(id) == HealthCategory.STAT }
                } ?: return@arrays
                trackMetrics.forEach metrics@{ (slot, metric) ->
                    val unit = analyzed?.trackUnits?.get(slot) ?: return@metrics
                    ids.getOrNull(slot)?.let {
                        put(it, TrackSlot("${family}_$metric", unit))
                    }
                }
            }
        }.also { log.debug { "Track slots=${it.size}" } }
    }

    override val providedApi = object : HealthMetadataApi {
        override fun resolveAll(types: Collection<Int>) = runCatching {
            val unavailable = unavailableMajors
            if (unavailable.isNotEmpty()) {
                val failures = unavailable.map { source ->
                    source to (source.availabilityError
                        ?: IllegalStateException("<Unavailable without a reported error>"))
                }
                val error = IllegalStateException(
                    failures.joinToString(
                        separator = "\n",
                        prefix = "Major metadata sources unavailable:\n",
                    ) { (source, cause) ->
                        "- ${source.name}: ${cause.describe()}"
                    }
                )
                failures.forEach { (_, cause) -> error.addSuppressed(cause) }
                throw error
            }
            val dbSnapshot = dbMetadata.snapshot().getOrThrow()
            val units = dbSnapshot.units
            val bases = dbSnapshot.bases
            val enumNames = host.enumNames
            val keyNames = host.keyNames
            val dictionary = host.dictionary().getOrThrow()
            val analyzedNames = analysis.snapshot()?.names.orEmpty()
            val aliasNames = alias.names
            val typeClassifier = classifier.takeIf { it.isAvailable }
            types.associateWith { type ->
                val entry = dictionary[type]
                val enumName = enumNames[type]
                val category = typeClassifier?.classify(type)
                    ?.let { runCatching { HealthCategory.valueOf(it.name) }.getOrNull() }
                    ?: HealthCategory.of(type)
                val isStat = category == HealthCategory.STAT
                val trackSlot = if (!isStat) null else trackSlots[type]?.takeIf { slot ->
                    (units[type] == null || units[type] == slot.unitId).also { accepted ->
                        if (!accepted) log.warn {
                            "Track slot rejected: $type ${slot.name} " +
                                    "unit=${slot.unitId} db=${units[type]}"
                        }
                    }
                }
                val resolvedName = resolveName(
                    enumName = enumName,
                    entry = entry,
                    keyName = keyNames[type],
                    analyzedName = analyzedNames[type],
                    trackName = trackSlot?.name,
                    aliasName = aliasNames[type],
                )
                HealthMetadata(
                    type = type,
                    category = category,
                    origin = if (entry == null) TypeOrigin.FIXED else TypeOrigin.DICT,
                    name = resolvedName?.value,
                    unit = entry?.unit,
                    unitId = units[type]?.takeIf { isStat },
                    baseType = bases[type]?.takeIf { isStat },
                    aggregation = entry?.statPolicy
                        ?.let { runCatching { Aggregation.valueOf(it.uppercase()) }.getOrNull() },
                    nameSource = resolvedName?.source,
                )
            }
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) = Unit

}
