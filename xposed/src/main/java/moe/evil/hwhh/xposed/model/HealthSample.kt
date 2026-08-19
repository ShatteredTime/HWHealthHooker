package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthSample(
    val type: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val value: Double,
    val subType: Int,
    val pointUnit: Int,
    val deviceUuid: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceUniqueCode: String? = null,
    val trackDeviceType: Int? = null,
    val timeZone: String? = null,
    val modifiedTimeMs: Long? = null,
    val clientId: Int? = null,
    val dataId: Long? = null,
    val syncStatus: Int? = null,
    val sequenceData: String? = null,
    val simpleData: String? = null,
    val metadata: String? = null,
)

data class HealthQueryRequest(
    val types: List<Int>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val count: Int = 0,
    val timeoutSec: Long = 10L,
)

@Serializable
data class HealthQueryResponse(
    val requestedTypes: List<Int>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val count: Int,
    val metadata: Map<Int, HealthMetadata> = emptyMap(),
    val metadataError: String? = null,
    val samples: List<HealthSample> = emptyList(),
) {
    companion object {
        fun read(
            request: HealthQueryRequest,
            samples: List<HealthSample>,
            metadata: Result<Map<Int, HealthMetadata>> = Result.success(emptyMap()),
        ) = HealthQueryResponse(
            requestedTypes = request.types,
            startTimeMs = request.startTimeMs,
            endTimeMs = request.endTimeMs,
            count = samples.size,
            metadata = metadata.getOrDefault(emptyMap()),
            metadataError = metadata.exceptionOrNull()?.toString(),
            samples = samples,
        )
    }
}
