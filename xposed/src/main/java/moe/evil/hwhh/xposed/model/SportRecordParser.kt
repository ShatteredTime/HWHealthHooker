package moe.evil.hwhh.xposed.model

import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import moe.evil.hwhh.xposed.model.SportRecord.GpsPoint
import moe.evil.hwhh.xposed.model.SportRecord.Summary
import moe.evil.hwhh.xposed.model.SportRecord.TimedFloat
import moe.evil.hwhh.xposed.model.SportRecord.TimedShort
import moe.evil.hwhh.xposed.utils.rtField

object SportRecordParser {
    private const val INVALID_SENTINEL_LAT = 90.0
    private const val INVALID_SENTINEL_LON = -80.0

    private const val MHA_TIME_FIELD = "b"
    private const val MHA_ALT_FIELD = "d"

    fun parse(mps: MotionPathSimplify, mp: MotionPath): SportRecord {
        return SportRecord(
            sportType = mps.requestSportType(),
            startTimeMs = mps.requestStartTime(),
            endTimeMs = mps.requestEndTime(),
            totalTimeMs = mps.requestTotalTime(),
            totalDistanceMm = mps.requestTotalDistance(),
            totalSteps = mps.requestTotalSteps(),
            totalCaloriesRaw = mps.requestTotalCalories(),
            coordinate = mps.requestMapCoordinate().orEmpty(),
            summary = parseSummary(mps),
            gpsTrack = parseGpsTrack(mp),
            heartRateTrack = parseHeartRateTrack(mp),
            altitudeTrack = parseAltitudeTrack(mp),
            cadenceTrack = parseCadenceTrack(mp),
        )
    }

    private fun parseSummary(s: MotionPathSimplify): Summary {
        val wear = s.requestSportData().orEmpty()
        return Summary(
            avgHeartRate = s.requestAvgHeartRate(),
            maxHeartRate = s.requestMaxHeartRate(),
            minHeartRate = s.requestMinHeartRate(),
            avgStepRate = s.requestAvgStepRate(),
            maxStepRate = s.requestBestStepRate(),
            avgPace = s.requestAvgPace(),
            maxAltitude = s.requestMaxAltitude(),
            minAltitude = s.requestMinAltitude(),
            totalDescent = s.requestTotalDescent(),
            trainingLoadPeak = wear["load_peak"] ?: 0,
            trainingEffect = wear["etraining_effect"] ?: 0,
        )
    }

    private fun parseGpsTrack(mp: MotionPath): List<GpsPoint> {
        val map = mp.requestLbsDataMap() ?: return emptyList()
        return map.values.mapNotNull { arr ->
            if (arr.size < 4) return@mapNotNull null
            val lat = arr[0]
            val lon = arr[1]
            val timeSec = arr[3].toLong()
            if (!isValidGpsPoint(lat, lon, timeSec)) return@mapNotNull null
            GpsPoint(timeMs = timeSec * 1000, lat = lat, lon = lon)
        }.sortedBy { it.timeMs }
    }

    private fun isValidGpsPoint(lat: Double, lon: Double, timeSec: Long): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || timeSec <= 0) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        return lat != INVALID_SENTINEL_LAT || lon != INVALID_SENTINEL_LON
    }

    private fun parseHeartRateTrack(mp: MotionPath): List<TimedShort> {
        val list = mp.requestHeartRateList() ?: return emptyList()
        return list.mapNotNull { entry ->
            val hr = entry.acquireHeartRate()
            if (hr <= 0) null else TimedShort(entry.acquireTime(), hr.toShort())
        }
    }

    private fun parseAltitudeTrack(mp: MotionPath): List<TimedFloat> {
        val list = mp.requestAltitudeList() ?: return emptyList()
        return list.mapNotNull { entry ->
            val time = entry.rtField<Long>(MHA_TIME_FIELD) ?: return@mapNotNull null
            val alt = entry.rtField<Number>(MHA_ALT_FIELD) ?: return@mapNotNull null
            TimedFloat(time, alt.toFloat())
        }
    }

    private fun parseCadenceTrack(mp: MotionPath): List<TimedShort> {
        val list = mp.requestStepRateList() ?: return emptyList()
        return list.mapNotNull { entry ->
            val spm = entry.acquireStepRate()
            if (spm <= 0) null else TimedShort(entry.acquireTime(), spm.toShort())
        }
    }
}
