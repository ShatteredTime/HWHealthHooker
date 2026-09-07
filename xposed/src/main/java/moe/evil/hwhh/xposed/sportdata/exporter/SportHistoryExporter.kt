package moe.evil.hwhh.xposed.sportdata.exporter

import android.content.Context
import com.huawei.basefitnessadvice.model.intplan.RecordData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.fit.FitInspector
import moe.evil.hwhh.xposed.hooks.SportHistoryApi
import moe.evil.hwhh.xposed.hooks.TrackSource
import moe.evil.hwhh.xposed.model.HistoryExportStats
import moe.evil.hwhh.xposed.model.HuaweiSportType
import moe.evil.hwhh.xposed.model.SportExportEntry
import moe.evil.hwhh.xposed.model.SportExportReport
import moe.evil.hwhh.xposed.model.SportExportStatus
import moe.evil.hwhh.xposed.model.SportRecordExportOutcome
import java.io.File

private sealed interface OneOutcome {
    data class Exported(val export: SportRecordExportOutcome) : OneOutcome
    data object NoDetail : OneOutcome
    data class MissingSequence(val fileUrl: String?) : OneOutcome
    data class Error(val cause: Throwable) : OneOutcome
}

private fun RecordData.entry(
    status: SportExportStatus,
    file: String? = null,
    reason: String? = null,
) = SportExportEntry(
    status = status,
    startTimeMs = startTime,
    endTimeMs = endTime,
    sportType = sportType,
    sportName = HuaweiSportType.of(sportType)?.displayName,
    file = file,
    reason = reason,
)

internal object SportHistoryExporter {
    private const val REPORT_NAME = "report.json"
    private val json = Json
    private val reportJson = Json { prettyPrint = true }
    private val log = HLog.of<SportHistoryExporter>()

    fun exportSupported(
        history: SportHistoryApi,
        context: Context,
        dir: File,
        startMs: Long,
        endMs: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit,
    ): HistoryExportStats {
        val summaries = history.summaries(startMs, endMs).getOrThrow()
        val supported = summaries.filter { HuaweiSportType.of(it.sportType) != null }
        log.debug { "ExportSupported summaries=${summaries.size} supported=${supported.size}" }
        var cancelled = false
        val entries = buildList {
            for ((idx, summary) in supported.withIndex()) {
                if (isCancelled()) {
                    log.debug { "Cancelled after $idx/${supported.size}" }
                    cancelled = true
                    break
                }
                val start = summary.startTime
                val outcome = runCatching {
                    val detail = history.detail(start, summary.endTime).getOrThrow()
                        ?: return@runCatching OneOutcome.NoDetail
                    when (val track = history.trackOf(context, detail)) {
                        is TrackSource.NoSequence -> OneOutcome.MissingSequence(track.fileUrl)
                        is TrackSource.Track -> OneOutcome.Exported(
                            RecordExporter.export(track.record, dir),
                        )
                    }
                }.getOrElse { OneOutcome.Error(it) }
                this += when (outcome) {
                    OneOutcome.NoDetail -> {
                        log.warn { "Detail fetch failed @$start" }
                        summary.entry(
                            SportExportStatus.NO_DETAIL,
                            reason = "Detail fetch returned no data",
                        )
                    }

                    is OneOutcome.MissingSequence -> {
                        log.warn { "Sequence file missing @$start (fileUrl=${outcome.fileUrl})" }
                        summary.entry(
                            SportExportStatus.MISSING_SEQUENCE,
                            reason = "No track sequence file: ${outcome.fileUrl ?: "unknown"}",
                        )
                    }

                    is OneOutcome.Exported -> when (val export = outcome.export) {
                        is SportRecordExportOutcome.Success -> {
                            if (RecordExporter.verboseExport) runCatching {
                                val fit = export.file
                                File(
                                    fit.parentFile,
                                    "${fit.nameWithoutExtension}.fit.json",
                                ).writeText(
                                    json.encodeToString(
                                        JsonObject.serializer(),
                                        FitInspector.inspect(fit),
                                    ),
                                )
                            }.onFailure {
                                log.warn { "Inspect failed @${export.file.name}: ${it.describe()}" }
                            }
                            summary.entry(SportExportStatus.EXPORTED, file = export.file.name)
                        }

                        is SportRecordExportOutcome.Unsupported -> {
                            log.warn { "Unsupported sportType @$start" }
                            summary.entry(
                                SportExportStatus.UNSUPPORTED,
                                reason = "sportType ${summary.sportType} has no FIT mapping",
                            )
                        }

                        is SportRecordExportOutcome.FitFailed -> {
                            log.error(export.error) { "FIT encode failed @$start" }
                            summary.entry(
                                SportExportStatus.FIT_FAILED,
                                reason = export.error.describe(),
                            )
                        }
                    }

                    is OneOutcome.Error -> {
                        log.error(outcome.cause) { "Unexpected error @$start" }
                        summary.entry(SportExportStatus.ERROR, reason = outcome.cause.describe())
                    }
                }
                onProgress(idx + 1, supported.size)
            }
        }
        val report = SportExportReport.of(
            startTimeMs = startMs,
            endTimeMs = endMs,
            summaries = summaries.size,
            candidates = supported.size,
            cancelled = cancelled,
            entries = entries,
        )
        runCatching { File(dir, REPORT_NAME).writeText(reportJson.encodeToString(report)) }
            .onFailure { log.warn { "Report write failed: ${it.describe()}" } }
        return report.stats
    }
}
