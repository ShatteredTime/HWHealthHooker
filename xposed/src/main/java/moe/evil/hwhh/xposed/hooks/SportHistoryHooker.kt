package moe.evil.hwhh.xposed.hooks

import android.content.Context
import android.util.SparseArray
import androidx.core.util.valueIterator
import com.highcapable.kavaref.extension.classOf
import com.huawei.basefitnessadvice.model.intplan.RecordData
import com.huawei.hihealth.HiHealthData
import com.huawei.hwbasemgr.IBaseResponseCallback
import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostMethod
import moe.evil.hwhh.kdxref.HostMethodData
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.model.SportRecord
import moe.evil.hwhh.xposed.model.SportRecordParser
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal sealed interface TrackSource {
    data class Track(val record: SportRecord) : TrackSource
    data class NoSequence(val fileUrl: String?) : TrackSource
}

internal interface SportHistoryApi : HookApi {
    fun summaries(startMs: Long, endMs: Long): Result<List<RecordData>>
    fun detail(startMs: Long, endMs: Long): Result<HiHealthData?>
    fun trackOf(context: Context, data: HiHealthData): TrackSource
}

internal object SportHistoryHooker : DexKitHooker<SportHistoryApi>() {
    private const val QUERY_TIMEOUT_SEC = 5L
    private val log = HLog.of<SportHistoryHooker>()

    private class Members(
        val summary: HostMethod<Unit>,
        val detail: HostMethod<Unit>,
        val convert: HostMethod<String>,
        val readMotionPath: HostMethod<MotionPath>,
    )

    @Volatile
    private var members: Members? = null

    override val providedApi = object : SportHistoryApi {
        override fun summaries(startMs: Long, endMs: Long) = runCatching {
            checkNotNull(members) { "Summaries unavailable, hooker not ready" }
                .summary.awaitList(startMs, endMs)
                .filterIsInstance<RecordData>()
        }

        override fun detail(startMs: Long, endMs: Long) = runCatching {
            checkNotNull(members) { "Detail unavailable, hooker not ready" }
                .detail.awaitList(startMs, endMs)
                .filterIsInstance<HiHealthData>()
                .firstOrNull()
        }

        override fun trackOf(context: Context, data: HiHealthData): TrackSource {
            val resolved = checkNotNull(members) { "Track unavailable, hooker not ready" }
            val simplify = MotionPathSimplify()
            val fileUrl = resolved.convert(data, simplify)
            if (fileUrl.isNullOrBlank()) return TrackSource.NoSequence(null)
            val path = resolved.readMotionPath(context, fileUrl, 0)
                ?: return TrackSource.NoSequence(fileUrl)
            return TrackSource.Track(SportRecordParser.parse(simplify, path))
        }
    }

    private fun HostMethod<Unit>.awaitList(startMs: Long, endMs: Long): List<*> {
        val latch = CountDownLatch(1)
        var outcome: Result<List<*>> = Result.failure(IllegalStateException("No response: $label"))
        runCatching {
            // Never a SAM lambda here: R8 turns those into a synthetic class no keep rule
            // reaches, renames onResponse (the stub interface is compileOnly and thus
            // invisible to it), and the host's callback dies on AbstractMethodError.
            invoke(startMs, endMs, object : IBaseResponseCallback {
                override fun onResponse(errCode: Int, data: Any?) {
                    outcome = when {
                        errCode != 0 -> Result.failure(
                            IllegalStateException("Query failed: err=$errCode $label"),
                        )

                        data is List<*> -> Result.success(data)
                        data is SparseArray<*> -> Result.success(
                            data.valueIterator().asSequence()
                                .filterIsInstance<List<*>>()
                                .firstOrNull { it.isNotEmpty() }
                                .orEmpty(),
                        )

                        data == null -> Result.success(emptyList<Any?>())
                        else -> Result.failure(
                            IllegalStateException("Unexpected payload ${data.javaClass.name}"),
                        )
                    }
                    latch.countDown()
                }
            })
        }.onFailure {
            outcome = Result.failure(it)
            latch.countDown()
        }
        check(latch.await(QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            "Timed out after ${QUERY_TIMEOUT_SEC}s: $label"
        }
        return outcome.getOrThrow()
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        val summary = hostMethod<Unit>(
            label = "sportHistory#getRecordListByTime",
            pick = { singleOrNull(HostMethodData::isStatic) },
        ) {
            paramTypes(classOf<Long>(), classOf<Long>(), classOf<IBaseResponseCallback>())
            usingStrings = listOf("getRecordListByTime workoutList.size")
        } ?: error("getRecordListByTime not resolved")

        val detailMarkerInners = bridge.findClass {
            matcher {
                usingStrings = listOf("requestTrackDetailData onResult map is empty.")
            }
        }.filter { it.name.contains('$') }
        log.debug { "Request track detail data markers=${detailMarkerInners.map { it.name }}" }

        fun detailBuilderOf(innerName: String, requireReadHiHealthData: Boolean) =
            hostMethod<Unit>(
                label = "sportHistory#requestTrackDetailData",
                pick = { singleOrNull(HostMethodData::isStatic) },
            ) {
                declaredClass = innerName.substringBeforeLast('$')
                paramTypes(classOf<Long>(), classOf<Long>(), classOf<IBaseResponseCallback>())
                addInvoke {
                    name = "<init>"
                    declaredClass = innerName
                }
                if (requireReadHiHealthData) addInvoke { name = "readHiHealthData" }
            }

        val detail = detailMarkerInners.firstNotNullOfOrNull { detailBuilderOf(it.name, true) }
            ?: detailMarkerInners.singleOrNull()?.name?.let { detailBuilderOf(it, false) }
            ?: error("requestTrackDetailData not resolved")

        val convert = hostMethod<String>(
            label = "SportDataConvertUtil#convertHiDataToTrackData",
            pick = { singleOrNull(HostMethodData::isStatic) },
        ) {
            paramTypes(classOf<HiHealthData>(), classOf<MotionPathSimplify>())
            usingStrings = listOf(
                "should not enter this branch,do not set",
                "Track_SportDataConvertUtil",
            )
        } ?: error("convertHiDataToTrackData not resolved")

        val readMotionPath = hostMethod<MotionPath>(
            label = "trackFile#readTemporaryMotionPath",
            pick = { singleOrNull(HostMethodData::isStatic) },
        ) {
            paramTypes(classOf<Context>(), classOf<String>(), classOf<Int>())
            usingStrings = listOf("readTemporaryMotionPath savePath is empty")
        } ?: error("readTemporaryMotionPath not resolved")

        members = Members(summary, detail, convert, readMotionPath)
    }
}
