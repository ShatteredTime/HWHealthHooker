package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

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
    val sliceDays: Long = 1L,
) {
    private val sliceMs get() = TimeUnit.DAYS.toMillis(sliceDays)

    val sliceCount get() = slices().count()

    fun slices(): Sequence<LongRange> = when {
        endTimeMs < startTimeMs -> emptySequence()
        count > 0 -> sequenceOf(startTimeMs..endTimeMs)
        else -> generateSequence(startTimeMs) { it + sliceMs }
            .takeWhile { it <= endTimeMs }
            .map { it..minOf(it + sliceMs - 1, endTimeMs) }
    }
}

sealed interface HealthQuerySlice {
    val index: Int
    val startTimeMs: Long
    val endTimeMs: Long

    data class Loaded(
        override val index: Int,
        override val startTimeMs: Long,
        override val endTimeMs: Long,
        val types: Set<Int>,
        val samples: List<HealthSample>,
    ) : HealthQuerySlice

    data class Failed(
        override val index: Int,
        override val startTimeMs: Long,
        override val endTimeMs: Long,
        val cause: Throwable,
    ) : HealthQuerySlice
}
