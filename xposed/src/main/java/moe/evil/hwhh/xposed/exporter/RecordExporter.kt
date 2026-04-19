package moe.evil.hwhh.xposed.exporter

import kotlinx.serialization.json.Json
import moe.evil.hwhh.xposed.fit.FitEncoder
import moe.evil.hwhh.xposed.model.ExportOutcome
import moe.evil.hwhh.xposed.model.SportRecord
import moe.evil.hwhh.xposed.utils.HLog
import java.io.File

object RecordExporter {
    private val json = Json { prettyPrint = true }
    private val log = HLog.of<RecordExporter>()

    @Volatile
    var verboseExport: Boolean = false

    fun basename(record: SportRecord): String =
        "record_${record.sportType}_${record.startTimeMs}"

    fun export(record: SportRecord, dir: File, basename: String = basename(record)): ExportOutcome {
        if (verboseExport) {
            File(dir, "$basename.json").writeText(json.encodeToString(record))
            log.debug { "Exported JSON: $basename.json (sportType=${record.sportType})" }
        }
        val sport = record.huaweiSport ?: run {
            log.debug { "FIT skipped — sportType=${record.sportType} is not in HuaweiSportType registry" }
            return ExportOutcome.Unsupported(record)
        }
        val fitFile = File(dir, "$basename.fit")
        return runCatching {
            FitEncoder.encode(record, fitFile)
            log.debug { "Exported FIT: $basename.fit (${sport.displayName})" }
            ExportOutcome.Success(record, sport)
        }.getOrElse { e ->
            log.error(e) { "FIT encoding failed for $basename" }
            fitFile.delete()
            ExportOutcome.FitFailed(record, sport, e)
        }
    }
}
