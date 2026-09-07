package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable

enum class SportExportStatus(val isFailure: Boolean) {
    EXPORTED(false),
    MISSING_SEQUENCE(false),
    NO_DETAIL(true),
    UNSUPPORTED(true),
    FIT_FAILED(true),
    ERROR(true),
}

@Serializable
data class SportExportEntry(
    val status: SportExportStatus,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sportType: Int,
    val sportName: String? = null,
    val file: String? = null,
    val reason: String? = null,
)

@Serializable
data class HistoryExportStats(
    val candidates: Int,
    val exported: Int,
    val missingSequence: Int,
    val failed: Int,
)

@Serializable
data class SportExportReport(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val summaries: Int,
    val cancelled: Boolean,
    val stats: HistoryExportStats,
    val entries: List<SportExportEntry>,
) {
    companion object {
        fun of(
            startTimeMs: Long,
            endTimeMs: Long,
            summaries: Int,
            candidates: Int,
            cancelled: Boolean,
            entries: List<SportExportEntry>,
        ) = SportExportReport(
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            summaries = summaries,
            cancelled = cancelled,
            stats = HistoryExportStats(
                candidates = candidates,
                exported = entries.count { it.status == SportExportStatus.EXPORTED },
                missingSequence = entries.count {
                    it.status == SportExportStatus.MISSING_SEQUENCE
                },
                failed = entries.count { it.status.isFailure },
            ),
            entries = entries,
        )
    }
}
