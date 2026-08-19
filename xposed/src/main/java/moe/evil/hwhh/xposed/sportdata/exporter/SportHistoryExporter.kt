package moe.evil.hwhh.xposed.sportdata.exporter

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import moe.evil.hwhh.kdxref.describe
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.fit.FitInspector
import moe.evil.hwhh.xposed.hooks.SportHistoryApi
import moe.evil.hwhh.xposed.hooks.TrackSource
import moe.evil.hwhh.xposed.model.HuaweiSportType
import moe.evil.hwhh.xposed.model.SportRecordExportOutcome
import java.io.File

data class HistoryExportStats(
    val candidates: Int,
    val exported: Int,
    val missingSequence: Int,
    val failed: Int,
)

private sealed interface OneOutcome {
    data class Exported(val export: SportRecordExportOutcome) : OneOutcome
    data object NoDetail : OneOutcome
    data class MissingSequence(val fileUrl: String?) : OneOutcome
    data class Error(val cause: Throwable) : OneOutcome
}

internal object SportHistoryExporter {
    private val json = Json
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
        if (supported.isEmpty()) return HistoryExportStats(0, 0, 0, 0)
        var exported = 0
        var missingSequence = 0
        var failed = 0
        for ((idx, summary) in supported.withIndex()) {
            if (isCancelled()) {
                log.debug { "Cancelled after $idx/${supported.size}" }
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
            when (outcome) {
                OneOutcome.NoDetail -> {
                    failed++
                    log.warn { "Detail fetch failed @$start" }
                }

                is OneOutcome.MissingSequence -> {
                    missingSequence++
                    log.warn { "Sequence file missing @$start (fileUrl=${outcome.fileUrl})" }
                }

                is OneOutcome.Exported -> when (val export = outcome.export) {
                    is SportRecordExportOutcome.Success -> {
                        exported++
                        if (RecordExporter.verboseExport) runCatching {
                            val fit = export.file
                            File(fit.parentFile, "${fit.nameWithoutExtension}.fit.json").writeText(
                                json.encodeToString(
                                    JsonObject.serializer(),
                                    FitInspector.inspect(fit),
                                ),
                            )
                        }.onFailure {
                            log.warn { "Inspect failed @${export.file.name}: ${it.describe()}" }
                        }
                    }

                    is SportRecordExportOutcome.Unsupported -> {
                        failed++
                        log.warn { "Unsupported sportType @$start" }
                    }

                    is SportRecordExportOutcome.FitFailed -> {
                        failed++
                        log.error(export.error) { "FIT encode failed @$start" }
                    }
                }

                is OneOutcome.Error -> {
                    failed++
                    log.error(outcome.cause) { "Unexpected error @$start" }
                }
            }
            onProgress(idx + 1, supported.size)
        }
        return HistoryExportStats(supported.size, exported, missingSequence, failed)
    }
}
