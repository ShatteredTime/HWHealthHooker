package moe.evil.hwhh.xposed.model

import java.io.File

sealed interface SportRecordExportOutcome {
    val record: SportRecord

    data class Success(
        override val record: SportRecord,
        val sport: HuaweiSportType,
        val file: File,
    ) : SportRecordExportOutcome

    data class Unsupported(
        override val record: SportRecord,
    ) : SportRecordExportOutcome

    data class FitFailed(
        override val record: SportRecord,
        val sport: HuaweiSportType,
        val error: Throwable,
    ) : SportRecordExportOutcome
}

