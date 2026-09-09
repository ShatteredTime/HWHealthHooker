package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.util.Base64
import com.tencent.mmkv.MMKV
import kotlinx.serialization.json.Json
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.model.HealthInsightsCard
import moe.evil.hwhh.xposed.model.HealthInsightsManifest
import moe.evil.hwhh.xposed.model.HealthInsightsPayload
import moe.evil.hwhh.xposed.model.HealthTrendReport
import moe.evil.hwhh.xposed.model.HealthTrendsManifest
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.ShareExporter
import moe.evil.hwhh.xposed.utils.ShareOutcome
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

@HookRoot(order = 6)
object HealthInsightsHooker : DexKitBaseHooker() {
    private const val INSIGHTS_STORE = "10100"
    private const val TRENDS_STORE = "HealthKit"
    private const val TRENDS_UPDATED_KEY = "HealthTrendUpdateTime_V3"
    private val log = HLog.of<HealthInsightsHooker>()
    private val commonUi by require { CommonUIHooker }
    private val menu by require { HomeMenuHooker }
    private val mmkv by require { MMKVHooker }
    private val payloadJson = Json { ignoreUnknownKeys = true }
    private val manifestJson = Json { encodeDefaults = true }

    private class Snapshot(val key: String, val source: String, val payload: String)

    override fun onHookWithDexKit(bridge: HostBridge) {
        menu.addEntry(
            title = { it.moduleString(R.string.hwhh_insights_title) },
            onSelect = ::export,
        )
    }

    private fun cardsOf(payload: String) =
        payloadJson.decodeFromString<HealthInsightsPayload>(payload).highlights.domainCards

    private fun MMKV.snapshotFor(userId: String): Snapshot {
        val derived = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest((userId + "healthInsights").toByteArray()),
            Base64.NO_WRAP,
        )
        decodeString(derived, null)?.let { return Snapshot(derived, "derived", it) }
        log.warn { "Derived key is absent from store $INSIGHTS_STORE, scanning by content" }
        return checkNotNull(
            allKeys().orEmpty().asSequence()
                .mapNotNull { key ->
                    runCatching { decodeString(key, null) }.getOrNull()
                        ?.takeIf { "\"domainCards\"" in it }
                        ?.let { Snapshot(key, "scanned", it) }
                }
                .maxByOrNull { snapshot ->
                    cardsOf(snapshot.payload).maxOfOrNull(HealthInsightsCard::generationTime) ?: 0L
                }
        ) { "No health-insights payload in MMKV store $INSIGHTS_STORE" }
    }

    private fun writeInsights(dir: File, userId: String): HealthInsightsManifest {
        val snapshot = mmkv.withStore(INSIGHTS_STORE) { snapshotFor(userId) }.getOrThrow()
        val cards = cardsOf(snapshot.payload)
        val manifest = HealthInsightsManifest(
            mmapId = INSIGHTS_STORE,
            storeKey = snapshot.key,
            keySource = snapshot.source,
            userId = userId,
            exportedAtMs = System.currentTimeMillis(),
            generationTimeMs = cards.maxOfOrNull(HealthInsightsCard::generationTime) ?: 0L,
            cardCount = cards.size,
            cardsWithData = cards.count { it.cardErrorCode == 0 },
        )
        File(dir, "insights.ndjson").bufferedWriter().use { writer ->
            writer.appendLine(manifestJson.encodeToString(manifest))
            writer.appendLine(snapshot.payload)
        }
        return manifest
    }

    private fun writeTrends(dir: File, userId: String) = mmkv.withStore(TRENDS_STORE) {
        val prefix = "${userId}_"
        val items = allKeys().orEmpty()
            .filter { it.startsWith(prefix) && it != prefix + TRENDS_UPDATED_KEY }
            .sorted()
            .mapNotNull { key -> decodeString(key, null)?.let { key.removePrefix(prefix) to it } }
        check(items.isNotEmpty()) { "No health-trend reports in MMKV store $TRENDS_STORE" }
        val reports = items.flatMap { (_, raw) ->
            payloadJson.decodeFromString<List<HealthTrendReport>>(raw)
        }
        val manifest = HealthTrendsManifest(
            mmapId = TRENDS_STORE,
            userId = userId,
            exportedAtMs = System.currentTimeMillis(),
            cacheUpdatedAtMs = decodeLong(prefix + TRENDS_UPDATED_KEY, 0L),
            items = items.map { it.first },
            reportCount = reports.size,
            pointCount = reports.sumOf { it.statValues.size },
        )
        File(dir, "trends.ndjson").bufferedWriter().use { writer ->
            writer.appendLine(manifestJson.encodeToString(manifest))
            items.forEach { (_, raw) -> writer.appendLine(raw) }
        }
        manifest
    }.getOrElse { cause ->
        log.warn(cause) { "Health trends unavailable, exporting insights only" }
        null
    }

    private fun export(activity: Activity) {
        val progress = commonUi.createProgressDialog(
            activity,
            activity.moduleString(R.string.hwhh_insights_exporting),
        ).gracefulShow()
        thread {
            runCatching {
                val userId = checkNotNull(
                    mmkv.withStore("keyvaldb_unencrypt") { decodeString("user_id", null) }
                        .getOrThrow()
                        ?.takeIf { it.isNotEmpty() && it != "empty_value" }
                ) { "No signed-in Huawei account, user_id is unavailable" }
                val root = checkNotNull(activity.applicationContext.ensureExportDir()) {
                    "Export dir unavailable"
                }
                val dir = File(root, "health_insights_${System.currentTimeMillis()}")
                check(dir.mkdirs()) { "Cannot create ${dir.name}" }
                Triple(dir, writeInsights(dir, userId), writeTrends(dir, userId))
            }.also { progress.dismiss() }.fold(
                onSuccess = { (dir, insights, trends) ->
                    log.debug {
                        "Exported ${insights.cardCount} insight cards, " +
                                "${insights.cardsWithData} with data, " +
                                "key=${insights.keySource}; ${trends?.reportCount ?: 0} trend " +
                                "reports over ${trends?.pointCount ?: 0} points -> ${dir.name}"
                    }
                    commonUi.createCustomTextAlertDialog(
                        activity = activity,
                        title = activity.moduleString(R.string.hwhh_insights_done_title),
                        message = activity.moduleString(
                            R.string.hwhh_insights_done_message,
                            dir.name,
                            insights.cardCount,
                            insights.cardsWithData,
                            trends?.reportCount ?: 0,
                            trends?.pointCount ?: 0,
                        ),
                        positive = DialogButton(
                            activity.moduleString(R.string.hwhh_insights_share)
                        ) {
                            val outcome = ShareExporter.share(
                                activity = activity,
                                target = dir,
                                chooserTitle = activity.moduleString(R.string.hwhh_share_title),
                            )
                            if (outcome is ShareOutcome.Failed) {
                                val cause = outcome.error.describe()
                                commonUi.createCustomTextAlertDialog(
                                    activity = activity,
                                    title = activity.moduleString(R.string.hwhh_share_failed_title),
                                    message = activity.moduleString(
                                        R.string.hwhh_error_detail,
                                        cause,
                                    ),
                                    positive = DialogButton(
                                        activity.moduleString(R.string.hwhh_ok)
                                    ),
                                ).gracefulShow {
                                    toast(moduleString(R.string.hwhh_share_failed, cause))
                                }
                            }
                        },
                        negative = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_insights_exported, dir.name))
                    }
                },
                onFailure = { e ->
                    log.error(e) { "Health-insights export failed" }
                    commonUi.createCustomTextAlertDialog(
                        activity = activity,
                        title = activity.moduleString(R.string.hwhh_insights_failed_title),
                        message = activity.moduleString(R.string.hwhh_error_detail, e.describe()),
                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_insights_failed, e.describe()))
                    }
                },
            )
        }
    }
}
