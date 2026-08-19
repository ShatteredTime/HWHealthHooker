package moe.evil.hwhh.xposed.sportdata.exporter

import android.content.Context
import moe.evil.hwhh.shared.log.HLog
import java.io.File

private val log = HLog("Export")

fun Context.ensureExportDir() = File(getExternalFilesDir(null), "HWHealthExport").let { dir ->
    dir.takeIf { it.exists() || it.mkdirs() }
        ?: null.also { log.warn { "Export dir not usable: ${dir.path}" } }
}
