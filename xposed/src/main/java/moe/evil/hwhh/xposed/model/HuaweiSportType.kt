package moe.evil.hwhh.xposed.model

import com.garmin.fit.Sport
import com.garmin.fit.SubSport

enum class HuaweiSportType(
    val code: Int,
    val displayName: String,
    val fitSport: Sport,
    val fitSubSport: SubSport,
) {
    OUTDOOR_RUNNING(258, "Outdoor Running", Sport.RUNNING, SubSport.GENERIC);

    companion object {
        private val byCode: Map<Int, HuaweiSportType> =
            entries.associateBy(HuaweiSportType::code)

        fun of(code: Int): HuaweiSportType? = byCode[code]
    }
}

