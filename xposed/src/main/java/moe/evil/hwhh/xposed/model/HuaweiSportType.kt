package moe.evil.hwhh.xposed.model

import com.garmin.fit.Sport
import com.garmin.fit.SubSport

enum class HuaweiSportType(
    val code: Int,
    val displayName: String,
    val fitSport: Sport,
    val fitSubSport: SubSport,
    val cadenceIsStepRate: Boolean,
    val hasCyclingMetrics: Boolean,
) {
    OUTDOOR_RUNNING(
        258, "Outdoor Running", Sport.RUNNING, SubSport.GENERIC,
        cadenceIsStepRate = true, hasCyclingMetrics = false,
    ),
    OUTDOOR_CYCLING(
        259, "Outdoor Cycling", Sport.CYCLING, SubSport.GENERIC,
        cadenceIsStepRate = false, hasCyclingMetrics = true,
    ),
    INDOOR_RUNNING(
        264, "Indoor Running", Sport.RUNNING, SubSport.GENERIC,
        cadenceIsStepRate = true, hasCyclingMetrics = false,
    );

    companion object {
        private val byCode =
            entries.associateBy(HuaweiSportType::code)

        fun of(code: Int) = byCode[code]
    }
}

