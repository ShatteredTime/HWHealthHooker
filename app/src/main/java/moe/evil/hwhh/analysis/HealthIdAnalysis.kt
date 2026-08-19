package moe.evil.hwhh.analysis

import android.content.Context
import com.highcapable.yukihookapi.hook.factory.prefs
import kotlinx.serialization.json.Json
import moe.evil.hwhh.analyzer.AnalysisOutcome
import moe.evil.hwhh.analyzer.AnalyzerReport
import moe.evil.hwhh.analyzer.HealthIdAnalyzer
import moe.evil.hwhh.analyzer.IdNameSource
import moe.evil.hwhh.analyzer.getOrThrow
import moe.evil.hwhh.analyzer.outcomeOf
import moe.evil.hwhh.shared.AnalysisPrefs
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.PREFS_NAME
import moe.evil.hwhh.shared.hostBuildTag
import moe.evil.hwhh.shared.model.AnalyzedNames
import moe.evil.hwhh.shared.model.HealthCategory
import java.io.File

data class AnalysisOutput(val result: AnalyzerReport, val digest: AnalyzedNames)

sealed interface AnalysisNeed {
    data class Missing(val tag: String) : AnalysisNeed
    data class Stale(val last: String, val tag: String) : AnalysisNeed
}

object HealthIdAnalysis {
    private val json = Json { explicitNulls = false }

    fun analysisNeed(context: Context): AnalysisNeed? {
        val tag = hostBuildTag(
            context.packageManager.getApplicationInfo(
                HOOK_TARGET_PACKAGE,
                0
            ).sourceDir
        )
        val config = context.prefs(PREFS_NAME)
        config.get(AnalysisPrefs.names(tag)).takeIf { it.isNotEmpty() } ?: return when (val last =
            config.get(AnalysisPrefs.lastBuildTag())) {
            "" -> AnalysisNeed.Missing(tag)
            else -> AnalysisNeed.Stale(last, tag)
        }
        return null
    }

    fun clearDigests(context: Context) {
        val bridge = context.prefs(PREFS_NAME)
        val stale = bridge.all().keys.filter { it.startsWith(AnalysisPrefs.NAMES_PREFIX) }
        bridge.edit {
            stale.forEach { key -> remove(key) }
            remove(AnalysisPrefs.lastBuildTag())
        }
    }

    fun run(
        context: Context,
        log: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AnalysisOutcome<AnalysisOutput> = outcomeOf {
        val info = context.packageManager.getApplicationInfo(HOOK_TARGET_PACKAGE, 0)
        val apk = File(info.sourceDir)
        val tag = hostBuildTag(apk.path)
        log("APK ${apk.path} ${apk.length() shr 20}M, buildTag=$tag")

        val result = HealthIdAnalyzer.analyze(apk, context.cacheDir, log, onProgress, isCancelled)
            .getOrThrow()

        val votes = HashMap<Int, HashMap<String, Int>>()
        val strong = HashSet<Pair<Int, String>>()
        result.names.forEach {
            votes.getOrPut(it.id, ::HashMap).merge(it.name, 1, Int::plus)
            if (it.source != IdNameSource.METHOD_LOG) strong += it.id to it.name
        }
        val resolved = votes.mapValues { (id, byName) ->
            byName.entries.reduce { best, next ->
                val betterSource = (id to next.key) in strong && (id to best.key) !in strong
                val worseSource = (id to best.key) in strong && (id to next.key) !in strong
                when {
                    next.value != best.value -> if (next.value > best.value) next else best
                    betterSource -> next
                    worseSource -> best
                    else -> if (next.key.length > best.key.length) next else best
                }
            }.key
        }
        val claimed = resolved.values.groupingBy { it }.eachCount()
        val prefixed = resolved.mapValues { (id, name) ->
            if (claimed.getValue(name) == 1) name
            else "${HealthCategory.of(id).name.lowercase()}_$name"
        }
        val stillClaimed = prefixed.values.groupingBy { it }.eachCount()
        val digest = AnalyzedNames(
            host = context.packageManager.getPackageInfo(
                HOOK_TARGET_PACKAGE,
                0
            ).versionName.orEmpty(),
            generatedAt = System.currentTimeMillis(),
            names = prefixed.mapValues { (id, name) ->
                if (stillClaimed.getValue(name) == 1) name else "${name}_$id"
            },
            trackUnits = result.trackUnits,
            trackFamilies = result.trackFamilies,
        )
        val bridge = context.prefs(PREFS_NAME)
        val stale = bridge.get(AnalysisPrefs.lastBuildTag()).takeIf { it.isNotEmpty() && it != tag }
        bridge.edit {
            stale?.let { remove(AnalysisPrefs.names(it)) }
            put(AnalysisPrefs.names(tag), json.encodeToString(digest))
            put(AnalysisPrefs.lastBuildTag(), tag)
        }
        stale?.let { log("Removed previous prefs[$it]") }
        log("Persisted prefs[$tag] names=${digest.names.size}")

        AnalysisOutput(result, digest)
    }
}
