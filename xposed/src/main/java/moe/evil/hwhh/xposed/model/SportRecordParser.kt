package moe.evil.hwhh.xposed.model

import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import com.huawei.hwfoundationmodel.trackmodel.TimeSequence
import com.huawei.hwfoundationmodel.trackmodel.ValueSequence
import moe.evil.hwhh.xposed.model.SportRecord.GpsPoint
import moe.evil.hwhh.xposed.model.SportRecord.Summary
import moe.evil.hwhh.xposed.model.SportRecord.TimedFloat
import moe.evil.hwhh.xposed.model.SportRecord.TimedShort
import moe.evil.hwhh.xposed.utils.fieldBySerializedName

object SportRecordParser {
    private const val INVALID_SENTINEL_LAT = 90.0
    private const val INVALID_SENTINEL_LON = -80.0
    private const val SPEED_RAW_PER_MS = 10f

    fun parse(mps: MotionPathSimplify, mp: MotionPath): SportRecord {
        val sportType = mps.requestSportType()
        val sport = HuaweiSportType.of(sportType)
        return SportRecord(
            sportType = sportType,
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
            cadenceTrack = if (sport?.cadenceIsStepRate == false) {
                parseRideCadenceTrack(mp)
            } else {
                parseStepRateCadenceTrack(mp)
            },
            powerTrack = if (sport?.hasCyclingMetrics == true) parsePowerTrack(mp) else emptyList(),
            speedTrack = if (sport?.hasCyclingMetrics == true) parseSpeedTrack(mp) else emptyList(),
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

    private fun parseGpsTrack(mp: MotionPath): List<GpsPoint> =
        mp.requestLbsDataMap()?.values.orEmpty()
            .mapNotNull { it.toGpsPoint() }
            .sortedBy { it.timeMs }

    private fun DoubleArray.toGpsPoint(): GpsPoint? {
        if (size < 4) return null
        val lat = this[0]
        val lon = this[1]
        val timeSec = this[3].toLong()
        if (!lat.isFinite() || !lon.isFinite() || timeSec <= 0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == INVALID_SENTINEL_LAT && lon == INVALID_SENTINEL_LON) return null
        return GpsPoint(timeMs = timeSec * 1000, lat = lat, lon = lon)
    }

    private inline fun <E : TimeSequence> List<E>?.toShortTrack(value: (E) -> Int?): List<TimedShort> =
        orEmpty().mapNotNull { e -> value(e)?.let { TimedShort(e.acquireTime(), it.toShort()) } }

    private inline fun <E : TimeSequence> List<E>?.toFloatTrack(value: (E) -> Float?): List<TimedFloat> =
        orEmpty().mapNotNull { e -> value(e)?.let { TimedFloat(e.acquireTime(), it) } }

    private fun <E : Any> List<E>.numberReader(serializedName: String): ((E) -> Number?)? =
        firstOrNull()?.javaClass?.fieldBySerializedName(serializedName)?.let { field ->
            { element -> field.get(element) as? Number }
        }

    private fun parseHeartRateTrack(mp: MotionPath): List<TimedShort> =
        mp.requestHeartRateList().toShortTrack { it.acquireHeartRate().takeIf { hr -> hr > 0 } }

    private fun parseStepRateCadenceTrack(mp: MotionPath): List<TimedShort> =
        mp.requestStepRateList().toShortTrack { it.acquireStepRate().takeIf { spm -> spm > 0 } }

    private fun parsePowerTrack(mp: MotionPath): List<TimedShort> =
        mp.requestPowerList()
            .toShortTrack { (it as? ValueSequence)?.acquireValue()?.takeIf { w -> w >= 0 } }

    private fun parseRideCadenceTrack(mp: MotionPath): List<TimedShort> {
        val list = mp.requestRidePostureDataList().orEmpty()
        val cadence = list.numberReader("cadence") ?: return emptyList()
        return list.toShortTrack { cadence(it)?.toInt()?.takeIf { rpm -> rpm > 0 } }
    }

    private fun parseAltitudeTrack(mp: MotionPath): List<TimedFloat> {
        val list = mp.requestAltitudeList().orEmpty()
        val altitude = list.numberReader("mAltitude") ?: return emptyList()
        return list.toFloatTrack { altitude(it)?.toFloat() }
    }

    private fun parseSpeedTrack(mp: MotionPath): List<TimedFloat> {
        val list = mp.requestSpeedList().orEmpty()
        val speed = list.numberReader("mRealTimeSpeed") ?: return emptyList()
        return list.toFloatTrack { entry ->
            val raw = speed(entry)?.toFloat() ?: return@toFloatTrack null
            if (raw < 0f) null else raw / SPEED_RAW_PER_MS
        }
    }
}
