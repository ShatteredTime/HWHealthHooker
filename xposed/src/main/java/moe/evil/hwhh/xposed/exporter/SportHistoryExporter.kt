package moe.evil.hwhh.xposed.exporter

import android.content.Context
import android.util.SparseArray
import androidx.core.util.valueIterator
import com.highcapable.kavaref.resolver.MethodResolver
import com.huawei.basefitnessadvice.model.intplan.RecordData
import com.huawei.hihealth.HiHealthData
import com.huawei.hwbasemgr.IBaseResponseCallback
import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import moe.evil.hwhh.xposed.fit.FitInspector
import moe.evil.hwhh.xposed.model.ExportOutcome
import moe.evil.hwhh.xposed.model.HuaweiSportType
import moe.evil.hwhh.xposed.model.SportRecordParser
import moe.evil.hwhh.xposed.utils.HLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class HistoryExportStats(
    val candidates: Int,
    val exported: Int,
    val missingSequence: Int,
    val failed: Int,
)

private sealed interface OneOutcome {
    data class Exported(val export: ExportOutcome, val fitFile: File) : OneOutcome
    data class MissingSequence(val fileUrl: String?) : OneOutcome
    data class Error(val cause: Throwable) : OneOutcome
}

private val MethodResolver<*>.label: String
    get() = "${self.declaringClass.simpleName}#${self.name}"

private fun Any?.asDataList(): List<*>? = when (this) {
    is List<*> -> this
    is SparseArray<*> -> valueIterator().asSequence()
        .filterIsInstance<List<*>>()
        .firstOrNull { it.isNotEmpty() }

    else -> null
}

class SportHistoryExporter(
    private val summaryMethod: MethodResolver<*>,
    private val detailMethod: MethodResolver<*>,
    private val mrcConvertMethod: MethodResolver<*>,
    private val ifhReadMethod: MethodResolver<*>,
) {
    private companion object {
        const val QUERY_TIMEOUT_SEC = 5L
        val prettyJson = Json { prettyPrint = true }
        val log = HLog.of<SportHistoryExporter>()
    }

    private inline fun <reified T> invokeQuery(
        method: MethodResolver<*>,
        startMs: Long,
        endMs: Long,
    ): List<T> {
        val methodLabel = method.label
        val latch = CountDownLatch(1)
        var result: List<T>? = null
        val callback = IBaseResponseCallback { _, data ->
            result = data.asDataList()?.filterIsInstance<T>()
            latch.countDown()
        }
        runCatching { method.invoke(startMs, endMs, callback) }
            .onFailure {
                log.warn { "$methodLabel invoke failed: ${it.message}" }
                latch.countDown()
            }
        if (!latch.await(QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            log.warn { "$methodLabel query timed out" }
        }
        return result.orEmpty()
    }

    private fun exportOne(context: Context, hd: HiHealthData, dir: File): OneOutcome = runCatching {
        val mps = MotionPathSimplify()
        val fileUrl = mrcConvertMethod.invoke(hd, mps) as? String
        if (fileUrl.isNullOrBlank()) return@runCatching OneOutcome.MissingSequence(null)
        val mp = ifhReadMethod.invoke(context, fileUrl, 0) as? MotionPath
            ?: return@runCatching OneOutcome.MissingSequence(fileUrl)
        val record = SportRecordParser.parse(mps, mp)
        val basename = RecordExporter.basename(record)
        val export = RecordExporter.export(record, dir, basename)
        OneOutcome.Exported(export, File(dir, "$basename.fit"))
    }.getOrElse { OneOutcome.Error(it) }


    fun exportOutdoorRunning(
        context: Context,
        dir: File,
        startMs: Long,
        endMs: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): HistoryExportStats {
        val summaries = invokeQuery<RecordData>(summaryMethod, startMs, endMs)
        val outdoorRunning = summaries.filter {
            it.sportType == HuaweiSportType.OUTDOOR_RUNNING.code
        }
        log.debug { "ExportOutdoorRunning summaries=${summaries.size} filtered(outdoor running)=${outdoorRunning.size}" }
        if (outdoorRunning.isEmpty()) return HistoryExportStats(0, 0, 0, 0)
        var exported = 0
        var missingSequence = 0
        var failed = 0
        outdoorRunning.forEachIndexed { idx, summary ->
            val start = summary.startTime
            val end = summary.endTime
            val hd = invokeQuery<HiHealthData>(detailMethod, start, end).firstOrNull()
            when {
                hd == null -> {
                    failed++
                    log.warn { "Detail fetch failed @$start" }
                }

                else -> {
                    when (val outcome = exportOne(context, hd, dir)) {
                        is OneOutcome.MissingSequence -> {
                            missingSequence++
                            log.warn { "Sequence file missing @$start (fileUrl=${outcome.fileUrl})" }
                        }

                        is OneOutcome.Exported -> when (outcome.export) {
                            is ExportOutcome.Success -> {
                                exported++
                                if (RecordExporter.verboseExport) {
                                    val fitFile = outcome.fitFile
                                    runCatching {
                                        val inspection = FitInspector.inspect(fitFile)
                                        File(
                                            fitFile.parentFile,
                                            "${fitFile.nameWithoutExtension}.fit.json"
                                        )
                                            .writeText(
                                                prettyJson.encodeToString(
                                                    JsonObject.serializer(),
                                                    inspection
                                                )
                                            )
                                    }.onFailure {
                                        log.warn { "Inspect failed @${fitFile.name}: ${it.message}" }
                                    }
                                }
                            }

                            is ExportOutcome.Unsupported -> {
                                failed++
                                log.warn { "Unsupported sportType @$start" }
                            }

                            is ExportOutcome.FitFailed -> {
                                failed++
                                log.error(outcome.export.error) { "FIT encode failed @$start" }
                            }
                        }

                        is OneOutcome.Error -> {
                            failed++
                            log.error(outcome.cause) { "Unexpected error @$start" }
                        }
                    }
                }
            }
            onProgress(idx + 1, outdoorRunning.size)
        }
        return HistoryExportStats(outdoorRunning.size, exported, missingSequence, failed)
    }
}
