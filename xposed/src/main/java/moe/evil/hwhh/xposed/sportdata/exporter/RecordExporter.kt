package moe.evil.hwhh.xposed.sportdata.exporter

import kotlinx.serialization.json.Json
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.fit.FitEncoder
import moe.evil.hwhh.xposed.model.SportRecord
import moe.evil.hwhh.xposed.model.SportRecordExportOutcome
import java.io.File

object RecordExporter {
    private val json = Json
    private val log = HLog.of<RecordExporter>()

    @Volatile
    var verboseExport = false

    fun basename(record: SportRecord) =
        "record_${record.sportType}_${record.startTimeMs}"

    fun export(
        record: SportRecord,
        dir: File,
        basename: String = basename(record)
    ): SportRecordExportOutcome {
        if (verboseExport) {
            File(dir, "$basename.json").writeText(json.encodeToString(record))
            log.debug { "Exported JSON: $basename.json (sportType=${record.sportType})" }
        }
        val sport = record.huaweiSport ?: run {
            log.debug { "FIT skipped — sportType=${record.sportType} is not in HuaweiSportType registry" }
            return SportRecordExportOutcome.Unsupported(record)
        }
        val fitFile = File(dir, "$basename.fit")
        return runCatching {
            FitEncoder.encode(record, fitFile)
            log.debug { "Exported FIT: $basename.fit (${sport.displayName})" }
            SportRecordExportOutcome.Success(record, sport, fitFile)
        }.getOrElse { e ->
            log.error(e) { "FIT encoding failed for $basename" }
            fitFile.delete()
            SportRecordExportOutcome.FitFailed(record, sport, e)
        }
    }
}
