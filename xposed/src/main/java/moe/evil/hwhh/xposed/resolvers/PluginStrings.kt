package moe.evil.hwhh.xposed.resolvers

import android.util.Xml
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.utils.Memo
import org.xmlpull.v1.XmlPullParser
import java.io.File

object PluginStrings {
    private const val REL_PATH = "plugins/ux_model_res"
    private const val BASELINE = "lang/strings.xml"

    private val log = HLog.of<PluginStrings>()
    private val cache = Memo<Map<String, String>>()

    fun name(filesDir: File, key: String) =
        cache.orNull { load(File(filesDir, REL_PATH)) }?.get(key)

    private fun load(root: File) = buildMap {
        root.listFiles().orEmpty().forEach { dir ->
            val xml = File(dir, BASELINE).takeIf { it.isFile } ?: return@forEach
            runCatching { parseInto(xml, this) }
                .onFailure { log.warn { "Plugin strings parse failed ${xml.path}: ${it.describe()}" } }
        }
        log.debug { "Plugin strings loaded: $size keys from ${root.path}" }
    }

    private fun parseInto(xml: File, out: MutableMap<String, String>) =
        xml.inputStream().buffered().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            var key: String? = null
            val text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "string") {
                    key = parser.getAttributeValue(null, "name")
                    text.setLength(0)
                }

                XmlPullParser.TEXT -> if (key != null) text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "string") {
                    key?.let { out[it] = text.toString().trim() }
                    key = null
                }
            }
        }
}
