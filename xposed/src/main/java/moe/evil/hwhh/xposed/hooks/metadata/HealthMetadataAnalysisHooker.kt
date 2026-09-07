package moe.evil.hwhh.xposed.hooks.metadata

import kotlinx.serialization.json.Json
import moe.evil.hwhh.shared.AnalysisPrefs
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.hostBuildTag
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.model.AnalyzedNames
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge

internal interface HealthMetadataAnalysisApi : MetadataSourceApi {
    fun snapshot(): AnalyzedNames?
}

internal object HealthMetadataAnalysisHooker :
    MetaDataBaseHooker<HealthMetadataAnalysisApi>() {
    private val log = HLog.of<HealthMetadataAnalysisHooker>()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var analyzed: AnalyzedNames? = null

    override val providedApi: HealthMetadataAnalysisApi =
        object : HealthMetadataAnalysisApi, MetadataSourceApi by availability {
            override fun snapshot() = analyzed
        }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val tag = hostBuildTag(appInfo.sourceDir)
        val config = prefs(PREFS_NAME)
        val raw = config.get(AnalysisPrefs.names(tag)).takeIf { it.isNotEmpty() } ?: error(
            when (val last = config.get(AnalysisPrefs.lastBuildTag())) {
                "" -> "Analyzed metadata missing for build tag $tag"
                else -> "Analyzed metadata is for build tag $last, host is now $tag"
            }
        )
        analyzed = json.decodeFromString<AnalyzedNames>(raw).also {
            log.debug {
                "Ready, tag=$tag names=${it.names.size} " +
                        "trackUnits=${it.trackUnits.size} families=${it.trackFamilies.size}"
            }
        }
    }
}
