package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthExportManifest(
    val kind: String = "manifest",
    val requestedTypes: List<Int>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sliceDays: Long,
    val sliceCount: Int,
    val metadata: Map<Int, HealthMetadata> = emptyMap(),
)

@Serializable
data class HealthSliceFailure(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val error: String,
)

@Serializable
data class HealthExportSummary(
    val kind: String = "summary",
    val count: Int,
    val observedTypes: List<Int>,
    val failedSlices: List<HealthSliceFailure> = emptyList(),
    val cancelled: Boolean = false,
)
