package moe.evil.hwhh.xposed.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.model.HealthExportManifest
import moe.evil.hwhh.xposed.model.HealthExportSummary
import moe.evil.hwhh.xposed.model.HealthMetadata
import moe.evil.hwhh.xposed.model.HealthQueryRequest
import moe.evil.hwhh.xposed.model.HealthQuerySlice
import moe.evil.hwhh.xposed.model.HealthSliceFailure
import java.io.File

private const val LINE_FEED = '\n'.code
private val sampleJson = Json
private val lineJson = Json { encodeDefaults = true }

@OptIn(ExperimentalSerializationApi::class)
fun Sequence<HealthQuerySlice>.writeNdjson(
    out: File,
    request: HealthQueryRequest,
    metadata: Map<Int, HealthMetadata> = emptyMap(),
    isCancelled: () -> Boolean = { false },
    onProgress: (done: Int, total: Int, rows: Int) -> Unit = { _, _, _ -> },
): HealthExportSummary {
    val total = request.sliceCount.coerceAtLeast(1)
    val observed = HashSet(request.types)
    val failures = mutableListOf<HealthSliceFailure>()
    var rows = 0
    var cancelled = false
    return out.outputStream().buffered().use { stream ->
        lineJson.encodeToStream(
            HealthExportManifest(
                requestedTypes = request.types,
                startTimeMs = request.startTimeMs,
                endTimeMs = request.endTimeMs,
                sliceDays = request.sliceDays,
                sliceCount = total,
                metadata = metadata,
            ),
            stream,
        )
        stream.write(LINE_FEED)
        for (slice in this) {
            when (slice) {
                is HealthQuerySlice.Loaded -> {
                    observed += slice.types
                    rows += slice.samples.size
                    slice.samples.forEach {
                        sampleJson.encodeToStream(it, stream)
                        stream.write(LINE_FEED)
                    }
                }

                is HealthQuerySlice.Failed -> failures += HealthSliceFailure(
                    index = slice.index,
                    startTimeMs = slice.startTimeMs,
                    endTimeMs = slice.endTimeMs,
                    error = slice.cause.describe(),
                )
            }
            onProgress(slice.index + 1, total, rows)
            if (isCancelled()) {
                cancelled = true
                break
            }
        }
        HealthExportSummary(
            count = rows,
            observedTypes = observed.sorted(),
            failedSlices = failures,
            cancelled = cancelled,
        ).also {
            lineJson.encodeToStream(it, stream)
            stream.write(LINE_FEED)
        }
    }
}
