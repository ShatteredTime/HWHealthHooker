package moe.evil.hwhh.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class SportRecord(
    val sportType: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val totalTimeMs: Long,
    val totalDistanceMm: Int,
    val totalSteps: Int,
    val totalCaloriesRaw: Int,
    val coordinate: String,
    val summary: Summary,
    val gpsTrack: List<GpsPoint>,
    val heartRateTrack: List<TimedShort>,
    val altitudeTrack: List<TimedFloat>,
    val cadenceTrack: List<TimedShort>,
    val powerTrack: List<TimedShort> = emptyList(),
    val speedTrack: List<TimedFloat> = emptyList(),
) {
    @Serializable
    data class Summary(
        val avgHeartRate: Int,
        val maxHeartRate: Int,
        val minHeartRate: Int,
        val avgStepRate: Int,
        val maxStepRate: Int,
        val avgPace: Float,
        val maxAltitude: Float,
        val minAltitude: Float,
        val totalDescent: Float,
        val trainingLoadPeak: Int,
        val trainingEffect: Int,
    )

    @Serializable
    data class GpsPoint(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
    )

    @Serializable
    data class TimedShort(val timeMs: Long, val value: Short)

    @Serializable
    data class TimedFloat(val timeMs: Long, val value: Float)

    val isGcj02: Boolean get() = coordinate == "GCJ02"

    val huaweiSport: HuaweiSportType? get() = HuaweiSportType.of(sportType)

    val totalDistanceMeters: Float
        get() = totalDistanceMm.toFloat()

    val totalCaloriesKcal: Int
        get() = if (totalCaloriesRaw > 10000) totalCaloriesRaw / 1000 else totalCaloriesRaw
}
