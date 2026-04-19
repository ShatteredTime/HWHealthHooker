package moe.evil.hwhh.xposed.fit

import com.garmin.fit.*
import com.garmin.fit.File as FitFile
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
import kotlin.math.sin
import kotlin.math.sqrt

object FitEncoder {

    private const val MANUFACTURER = Manufacturer.DEVELOPMENT
    private const val PRODUCT_ID = 0
    private const val PRODUCT_NAME = "HWHealthExport"
    private const val SERIAL_NUMBER = 0x48574848L // "HWHH"

    fun encode(record: SportRecord, outputFile: File) {
        val sport = requireNotNull(record.huaweiSport) {
            "Unsupported Huawei sportType=${record.sportType} for FIT export"
        }
        val gps = convertGps(record.gpsTrack, record.isGcj02)
        val hr = record.heartRateTrack
        val alt = record.altitudeTrack
        val cad = record.cadenceTrack.map { TimedShort(it.timeMs, (it.value / 2).toShort()) }

        val records = buildRecords(gps, hr, alt, cad, record.startTimeMs, record.endTimeMs)
        val fitStart = DateTime(Date(record.startTimeMs))
        val fitEnd = DateTime(Date(record.endTimeMs))

        val encoder = FileEncoder(outputFile, Fit.ProtocolVersion.V2_0)
        encoder.write(buildFileId(fitStart))
        encoder.write(buildDeviceInfo(fitStart))
        encoder.write(buildTimerEvent(fitStart, EventType.START))

        var cumulativeDistance = 0.0f
        var prevGps: GpsPoint? = null
        for (r in records) {
            val rec = RecordMesg()
            rec.timestamp = DateTime(Date(r.timeMs))

            val rLat = r.lat
            val rLon = r.lon
            if (rLat != null && rLon != null) {
                rec.positionLat = SemicirclesConverter.degreesToSemicircles(rLat)
                rec.positionLong = SemicirclesConverter.degreesToSemicircles(rLon)
                if (prevGps != null) {
                    val dist = haversineMeters(prevGps.lat, prevGps.lon, rLat, rLon)
                    cumulativeDistance += dist
                    val dtSec = (r.timeMs - prevGps.timeMs) / 1000.0
                    if (dtSec > 0) rec.enhancedSpeed = (dist / dtSec).toFloat()
                }
                rec.distance = cumulativeDistance
                prevGps = GpsPoint(r.timeMs, rLat, rLon)
            }

            r.heartRate?.let { rec.heartRate = it }
            r.altitude?.let { rec.enhancedAltitude = it }
            r.cadence?.let { rec.cadence = it }

            encoder.write(rec)
        }

        val totalDistance = record.totalDistanceMeters.takeIf { it > 0f } ?: cumulativeDistance
        encoder.write(buildTimerEvent(fitEnd, EventType.STOP_ALL))
        encoder.write(buildLap(fitStart, fitEnd, record, totalDistance))
        encoder.write(buildSession(fitStart, fitEnd, record, sport, totalDistance))
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
        start: DateTime, end: DateTime, r: SportRecord, distance: Float
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
    }

    private fun buildSession(
        start: DateTime, end: DateTime, r: SportRecord,
        sport: HuaweiSportType, distance: Float,
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
        s.avgStepRate.takeIf { it > 0 }?.let { avgCadence = (it / 2).toShort() }
        s.maxStepRate.takeIf { it > 0 }?.let { maxCadence = (it / 2).toShort() }
        s.maxAltitude.takeIf { it != 0f }?.let { maxAltitude = it }
        s.minAltitude.takeIf { it != 0f }?.let { minAltitude = it }
        s.trainingLoadPeak.takeIf { it > 0 }?.let { trainingLoadPeak = it / 65536.0f }
        s.trainingEffect.takeIf { it > 0 }?.let { totalTrainingEffect = it / 10.0f }
    }

    private fun buildActivity(end: DateTime, r: SportRecord) = ActivityMesg().apply {
        timestamp = end
        numSessions = 1
        totalTimerTime = r.totalTimeMs / 1000.0f
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
    )

    private fun buildRecords(
        gps: List<GpsPoint>,
        hr: List<TimedShort>,
        alt: List<TimedFloat>,
        cad: List<TimedShort>,
        startMs: Long,
        endMs: Long,
    ): List<RecordPoint> {
        val timestamps = sortedSetOf<Long>()
        gps.forEach { timestamps.add(it.timeMs) }
        hr.forEach { timestamps.add(it.timeMs) }
        alt.forEach { timestamps.add(it.timeMs) }
        cad.forEach { timestamps.add(it.timeMs) }
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
