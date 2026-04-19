package moe.evil.hwhh.xposed.exporter

import android.content.Context
import java.io.File

private const val EXPORT_DIR = "HWHealthExport"

fun Context.ensureExportDir(): File? =
    File(getExternalFilesDir(null), EXPORT_DIR).takeIf { it.exists() || it.mkdirs() }