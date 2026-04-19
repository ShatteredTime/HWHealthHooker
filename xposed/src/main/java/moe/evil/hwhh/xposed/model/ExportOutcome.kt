package moe.evil.hwhh.xposed.model

sealed interface ExportOutcome {
    val record: SportRecord

    data class Success(
        override val record: SportRecord,
        val sport: HuaweiSportType,
    ) : ExportOutcome

    data class Unsupported(
        override val record: SportRecord,
    ) : ExportOutcome

    data class FitFailed(
        override val record: SportRecord,
        val sport: HuaweiSportType,
        val error: Throwable,
    ) : ExportOutcome
}

