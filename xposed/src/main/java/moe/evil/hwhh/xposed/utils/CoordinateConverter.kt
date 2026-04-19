package moe.evil.hwhh.xposed.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordinateConverter {
    private const val A = 6378245.0
    private const val EE = 0.006693421622965943
    private const val LON_MIN = 72.004
    private const val LON_MAX = 137.8347
    private const val LAT_MIN = 0.8293
    private const val LAT_MAX = 55.8271

    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (gcjLat, gcjLon) = gcj02Encrypt(lat, lon)
        return (lat - (gcjLat - lat)) to (lon - (gcjLon - lon))
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon !in LON_MIN..LON_MAX || lat < LAT_MIN || lat > LAT_MAX

    private fun gcj02Encrypt(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        dLon += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        dLon += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * PI
        val magic = 1.0 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }
}
