package moe.evil.hwhh.xposed.fit

import com.garmin.fit.Decode
import com.garmin.fit.Field
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream

object FitInspector {

    fun inspect(fitFile: File) = buildJsonObject {
        put("file", fitFile.name)
        put("size", fitFile.length())
        val messages = mutableListOf<JsonElement>()
        val counts = HashMap<String, Int>()
        Decode().apply {
            addListener(MesgListener { mesg ->
                counts.merge(mesg.name ?: "unknown", 1, Int::plus)
                messages.add(mesgToJson(mesg))
            })
            FileInputStream(fitFile).use { read(it) }
        }
        put("summary", buildJsonObject {
            counts.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
        })
        put("messages", JsonArray(messages))
    }

    private fun mesgToJson(mesg: Mesg) = buildJsonObject {
        put("type", mesg.name ?: "unknown")
        put("num", mesg.num)
        put("fields", buildJsonObject {
            mesg.fields.forEach { put(it.name, fieldToJson(it)) }
        })
    }

    private fun fieldToJson(field: Field) = runCatching {
        when (val n = field.numValues) {
            0 -> JsonNull
            1 -> valueToJson(field.getValue(0))
            else -> JsonArray((0 until n).map { valueToJson(field.getValue(it)) })
        }
    }.getOrDefault(JsonNull)

    private fun valueToJson(v: Any?) = when (v) {
        null -> JsonNull
        is Float -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
        is Double -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
