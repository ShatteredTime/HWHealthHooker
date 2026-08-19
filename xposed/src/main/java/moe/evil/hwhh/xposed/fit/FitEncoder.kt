package moe.evil.hwhh.xposed.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.DeviceIndex
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.util.SemicirclesConverter
import moe.evil.hwhh.xposed.model.HuaweiSportType
import moe.evil.hwhh.xposed.model.SportRecord
import moe.evil.hwhh.xposed.model.SportRecord.GpsPoint
import moe.evil.hwhh.xposed.model.SportRecord.TimedFloat
import moe.evil.hwhh.xposed.model.SportRecord.TimedShort
import moe.evil.hwhh.xposed.utils.CoordinateConverter
import java.io.File
import java.util.Date
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.garmin.fit.File as FitFile

object FitEncoder {
    private const val MANUFACTURER = Manufacturer.HUAWEI
    private const val PRODUCT_ID = 0x717771 // qwq
    private const val PRODUCT_NAME = "HWHealthExport"
    private const val SERIAL_NUMBER = 0x48574848L // hwhh

    fun encode(record: SportRecord, outputFile: File) {
        val sport = requireNotNull(record.huaweiSport) {
            "Unsupported Huawei sportType=${record.sportType} for FIT export"
        }
        val gps = convertGps(record.gpsTrack, record.isGcj02)
        val hr = record.heartRateTrack
        val alt = record.altitudeTrack
        val cad = if (sport.cadenceIsStepRate) {
            record.cadenceTrack.map { TimedShort(it.timeMs, (it.value / 2).toShort()) }
        } else {
            record.cadenceTrack
        }
        val power = record.powerTrack
        val speed = record.speedTrack

        val records =
            buildRecords(gps, hr, alt, cad, power, speed, record.startTimeMs, record.endTimeMs)
        val fitStart = DateTime(Date(record.startTimeMs))
        val fitEnd = DateTime(Date(record.endTimeMs))

        val encoder = FileEncoder(outputFile, Fit.ProtocolVersion.V2_0)
        encoder.write(buildFileId(fitStart))
        encoder.write(buildDeviceInfo(fitStart))
        encoder.write(buildTimerEvent(fitStart, EventType.START))

        var cumulativeDistance = 0.0f
        var prevGps: GpsPoint? = null
        var maxSpeedMs = 0.0f
        for (r in records) {
            val rec = RecordMesg()
            rec.timestamp = DateTime(Date(r.timeMs))

            var gpsSpeed: Float? = null
            val rLat = r.lat
            val rLon = r.lon
            if (rLat != null && rLon != null) {
                rec.positionLat = SemicirclesConverter.degreesToSemicircles(rLat)
                rec.positionLong = SemicirclesConverter.degreesToSemicircles(rLon)
                if (prevGps != null) {
                    val dist = haversineMeters(prevGps.lat, prevGps.lon, rLat, rLon)
                    cumulativeDistance += dist
                    val dtSec = (r.timeMs - prevGps.timeMs) / 1000.0
                    if (dtSec > 0) gpsSpeed = (dist / dtSec).toFloat()
                }
                rec.distance = cumulativeDistance
                prevGps = GpsPoint(r.timeMs, rLat, rLon)
            }

            // Prefer the device-reported real-time speed (cycling); fall back to GPS-derived.
            (r.speed ?: gpsSpeed)?.let {
                rec.enhancedSpeed = it
                if (it > maxSpeedMs) maxSpeedMs = it
            }
            r.heartRate?.let { rec.heartRate = it }
            r.altitude?.let { rec.enhancedAltitude = it }
            r.cadence?.let { rec.cadence = it }
            r.power?.let { rec.power = it.toInt() }

            encoder.write(rec)
        }

        val totalDistance = record.totalDistanceMeters.takeIf { it > 0f } ?: cumulativeDistance
        val rideStats = if (sport.hasCyclingMetrics) {
            computeRideAggregates(record, cad, power, alt, totalDistance, maxSpeedMs)
        } else {
            null
        }

        encoder.write(buildTimerEvent(fitEnd, EventType.STOP_ALL))
        encoder.write(buildLap(fitStart, fitEnd, record, totalDistance, rideStats))
        encoder.write(buildSession(fitStart, fitEnd, record, sport, totalDistance, rideStats))
        encoder.write(buildActivity(fitEnd, record))
        encoder.close()
    }

    private fun buildFileId(startTime: DateTime) = FileIdMesg().apply {
        type = FitFile.ACTIVITY
        manufacturer = MANUFACTURER
        product = PRODUCT_ID
        serialNumber = SERIAL_NUMBER
        timeCreated = startTime
    }

    private fun buildDeviceInfo(startTime: DateTime) = DeviceInfoMesg().apply {
        deviceIndex = DeviceIndex.CREATOR
        manufacturer = MANUFACTURER
        product = PRODUCT_ID
        productName = PRODUCT_NAME
        serialNumber = SERIAL_NUMBER
        softwareVersion = 1.0f
        timestamp = startTime
    }

    private fun buildTimerEvent(time: DateTime, type: EventType) = EventMesg().apply {
        timestamp = time
        event = Event.TIMER
        eventType = type
    }

    private fun buildLap(
        start: DateTime, end: DateTime, r: SportRecord, distance: Float, ride: RideAggregates?,
    ) = LapMesg().apply {
        messageIndex = 0
        timestamp = end
        startTime = start
        totalElapsedTime = r.totalTimeMs / 1000.0f
        totalTimerTime = r.totalTimeMs / 1000.0f
        totalDistance = distance
        totalCalories = r.totalCaloriesKcal
        r.summary.avgHeartRate.takeIf { it > 0 }?.let { avgHeartRate = it.toShort() }
        r.summary.maxHeartRate.takeIf { it > 0 }?.let { maxHeartRate = it.toShort() }
        ride?.applyTo(this)
    }

    private fun buildSession(
        start: DateTime, end: DateTime, r: SportRecord,
        sport: HuaweiSportType, distance: Float, ride: RideAggregates?,
    ) = SessionMesg().apply {
        messageIndex = 0
        timestamp = end
        startTime = start
        totalElapsedTime = r.totalTimeMs / 1000.0f
        totalTimerTime = r.totalTimeMs / 1000.0f
        this.sport = sport.fitSport
        this.subSport = sport.fitSubSport
        firstLapIndex = 0
        numLaps = 1
        totalDistance = distance
        totalCalories = r.totalCaloriesKcal

        val s = r.summary
        s.avgHeartRate.takeIf { it > 0 }?.let { avgHeartRate = it.toShort() }
        s.maxHeartRate.takeIf { it > 0 }?.let { maxHeartRate = it.toShort() }
        s.minHeartRate.takeIf { it > 0 }?.let { minHeartRate = it.toShort() }
        if (sport.cadenceIsStepRate) {
            s.avgStepRate.takeIf { it > 0 }?.let { avgCadence = (it / 2).toShort() }
            s.maxStepRate.takeIf { it > 0 }?.let { maxCadence = (it / 2).toShort() }
        }
        s.maxAltitude.takeIf { it != 0f }?.let { maxAltitude = it }
        s.minAltitude.takeIf { it != 0f }?.let { minAltitude = it }
        s.trainingLoadPeak.takeIf { it > 0 }?.let { trainingLoadPeak = it / 65536.0f }
        s.trainingEffect.takeIf { it > 0 }?.let { totalTrainingEffect = it / 10.0f }
        ride?.applyTo(this)
    }

    private fun buildActivity(end: DateTime, r: SportRecord) = ActivityMesg().apply {
        timestamp = end
        numSessions = 1
        totalTimerTime = r.totalTimeMs / 1000.0f
    }

    private class RideAggregates(
        val avgCadence: Short?,
        val maxCadence: Short?,
        val avgPower: Int?,
        val maxPower: Int?,
        val avgSpeedMs: Float?,
        val maxSpeedMs: Float?,
        val totalAscent: Int?,
        val totalDescent: Int?,
    ) {
        fun applyTo(lap: LapMesg) {
            avgCadence?.let { lap.avgCadence = it }
            maxCadence?.let { lap.maxCadence = it }
            avgPower?.let { lap.avgPower = it }
            maxPower?.let { lap.maxPower = it }
            avgSpeedMs?.let { lap.avgSpeed = it; lap.enhancedAvgSpeed = it }
            maxSpeedMs?.let { lap.maxSpeed = it; lap.enhancedMaxSpeed = it }
            totalAscent?.let { lap.totalAscent = it }
            totalDescent?.let { lap.totalDescent = it }
        }

        fun applyTo(session: SessionMesg) {
            avgCadence?.let { session.avgCadence = it }
            maxCadence?.let { session.maxCadence = it }
            avgPower?.let { session.avgPower = it }
            maxPower?.let { session.maxPower = it }
            avgSpeedMs?.let { session.avgSpeed = it; session.enhancedAvgSpeed = it }
            maxSpeedMs?.let { session.maxSpeed = it; session.enhancedMaxSpeed = it }
            totalAscent?.let { session.totalAscent = it }
            totalDescent?.let { session.totalDescent = it }
        }
    }

    private fun computeRideAggregates(
        record: SportRecord,
        cad: List<TimedShort>,
        power: List<TimedShort>,
        alt: List<TimedFloat>,
        totalDistance: Float,
        maxSpeedMs: Float,
    ): RideAggregates {
        val cadenceVals = cad.map { it.value.toInt() }.filter { it > 0 }
        val powerVals = power.map { it.value.toInt() }.filter { it >= 0 }
        val totalSec = record.totalTimeMs / 1000.0
        val (ascent, descent) = altitudeAscentDescent(alt)
        return RideAggregates(
            avgCadence = cadenceVals.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toShort(),
            maxCadence = cadenceVals.maxOrNull()?.toShort(),
            avgPower = powerVals.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            maxPower = powerVals.maxOrNull(),
            avgSpeedMs = if (totalSec > 0 && totalDistance > 0f) (totalDistance / totalSec).toFloat() else null,
            maxSpeedMs = maxSpeedMs.takeIf { it > 0f },
            totalAscent = ascent,
            totalDescent = descent ?: record.summary.totalDescent.takeIf { it > 0f }?.roundToInt(),
        )
    }

    private fun altitudeAscentDescent(alt: List<TimedFloat>): Pair<Int?, Int?> {
        if (alt.size < 2) return null to null
        var up = 0.0f
        var down = 0.0f
        for (i in 1 until alt.size) {
            val delta = alt[i].value - alt[i - 1].value
            if (delta > 0) up += delta else down -= delta
        }
        return up.roundToInt() to down.roundToInt()
    }

    private fun convertGps(track: List<GpsPoint>, isGcj02: Boolean): List<GpsPoint> {
        if (!isGcj02) return track
        return track.map { p ->
            val (wLat, wLon) = CoordinateConverter.gcj02ToWgs84(p.lat, p.lon)
            GpsPoint(p.timeMs, wLat, wLon)
        }
    }

    private data class RecordPoint(
        val timeMs: Long,
        var lat: Double? = null,
        var lon: Double? = null,
        var heartRate: Short? = null,
        var altitude: Float? = null,
        var cadence: Short? = null,
        var power: Short? = null,
        var speed: Float? = null,
    )

    private fun buildRecords(
        gps: List<GpsPoint>,
        hr: List<TimedShort>,
        alt: List<TimedFloat>,
        cad: List<TimedShort>,
        power: List<TimedShort>,
        speed: List<TimedFloat>,
        startMs: Long,
        endMs: Long,
    ): List<RecordPoint> {
        val timestamps = sortedSetOf<Long>()
        gps.forEach { timestamps.add(it.timeMs) }
        hr.forEach { timestamps.add(it.timeMs) }
        alt.forEach { timestamps.add(it.timeMs) }
        cad.forEach { timestamps.add(it.timeMs) }
        power.forEach { timestamps.add(it.timeMs) }
        speed.forEach { timestamps.add(it.timeMs) }
        if (timestamps.isEmpty()) return emptyList()
        timestamps.add(max(timestamps.first(), startMs))

        val recordMap = LinkedHashMap<Long, RecordPoint>(timestamps.size * 2)
        val records = timestamps.map { RecordPoint(it).also { r -> recordMap[r.timeMs] = r } }

        for (p in gps) {
            recordMap[p.timeMs]?.let { it.lat = p.lat; it.lon = p.lon }
        }
        interpolateShort(recordMap, hr) { r, v -> r.heartRate = v }
        interpolateFloat(recordMap, alt) { r, v -> r.altitude = v }
        interpolateShort(recordMap, cad) { r, v -> r.cadence = v }
        interpolateShort(recordMap, power) { r, v -> r.power = v }
        interpolateFloat(recordMap, speed) { r, v -> r.speed = v }

        return records
    }

    private inline fun interpolateShort(
        records: LinkedHashMap<Long, RecordPoint>,
        source: List<TimedShort>,
        setter: (RecordPoint, Short) -> Unit,
    ) {
        if (source.isEmpty()) return
        var idx = 0
        for ((ts, rec) in records) {
            while (idx < source.size - 1 && source[idx + 1].timeMs <= ts) idx++
            val v = when {
                ts <= source.first().timeMs -> source.first().value
                ts >= source.last().timeMs -> source.last().value
                else -> {
                    val lo = source[idx]
                    val hi = if (idx + 1 < source.size) source[idx + 1] else lo
                    val range = hi.timeMs - lo.timeMs
                    if (range > 0) {
                        val ratio = (ts - lo.timeMs).toDouble() / range
                        (lo.value + (hi.value - lo.value) * ratio).toInt().toShort()
                    } else lo.value
                }
            }
            setter(rec, v)
        }
    }

    private inline fun interpolateFloat(
        records: LinkedHashMap<Long, RecordPoint>,
        source: List<TimedFloat>,
        setter: (RecordPoint, Float) -> Unit,
    ) {
        if (source.isEmpty()) return
        var idx = 0
        for ((ts, rec) in records) {
            while (idx < source.size - 1 && source[idx + 1].timeMs <= ts) idx++
            val v = when {
                ts <= source.first().timeMs -> source.first().value
                ts >= source.last().timeMs -> source.last().value
                else -> {
                    val lo = source[idx]
                    val hi = if (idx + 1 < source.size) source[idx + 1] else lo
                    val range = hi.timeMs - lo.timeMs
                    if (range > 0) {
                        val ratio = (ts - lo.timeMs).toFloat() / range
                        lo.value + (hi.value - lo.value) * ratio
                    } else lo.value
                }
            }
            setter(rec, v)
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return (r * 2 * asin(sqrt(a))).toFloat()
    }
}
