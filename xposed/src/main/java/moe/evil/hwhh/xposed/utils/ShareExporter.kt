package moe.evil.hwhh.xposed.utils

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.log.HLog
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed interface ShareOutcome {
    data object Shared : ShareOutcome
    data object Cancelled : ShareOutcome
    data object Empty : ShareOutcome
    data class Failed(val error: Throwable) : ShareOutcome
}

object ShareExporter {
    private const val AUTHORITY = "$HOOK_TARGET_PACKAGE.fileprovider"
    private const val ZIP_MIME_TYPE = "application/zip"
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    private val log = HLog.of<ShareExporter>()

    private fun File.shareAs(activity: Activity, chooserTitle: String, mimeType: String) =
        runCatching {
            val uri = FileProvider.getUriForFile(activity, AUTHORITY, this)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, chooserTitle))
        }.fold(
            onSuccess = { ShareOutcome.Shared },
            onFailure = { e ->
                log.error(e) { "Share intent failed for $name" }
                ShareOutcome.Failed(e)
            },
        )

    fun share(
        activity: Activity,
        target: File,
        chooserTitle: String,
        mimeType: String = DEFAULT_MIME_TYPE,
        isCancelled: () -> Boolean = { false },
        onZipProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ShareOutcome {
        if (!target.isDirectory) return target.shareAs(activity, chooserTitle, mimeType)
        val entries = target.listFiles(File::isFile).orEmpty()
        if (entries.isEmpty()) return ShareOutcome.Empty
        val archive = File(target.parentFile, "${target.name}.zip")
        return runCatching {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                entries.forEachIndexed { index, entry ->
                    if (isCancelled()) return ShareOutcome.Cancelled.also { archive.delete() }
                    zip.putNextEntry(ZipEntry(entry.name))
                    entry.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onZipProgress(index + 1, entries.size)
                }
            }
        }.fold(
            onSuccess = { archive.shareAs(activity, chooserTitle, ZIP_MIME_TYPE) },
            onFailure = { e ->
                archive.delete()
                log.error(e) { "Zip failed for ${target.name}" }
                ShareOutcome.Failed(e)
            },
        )
    }

}
