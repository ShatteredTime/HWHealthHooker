package moe.evil.hwhh.xposed.hooks

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.util.SparseArray
import androidx.core.util.size
import com.highcapable.kavaref.extension.classOf
import com.huawei.hihealth.HiDataReadOption
import com.huawei.hihealth.HiHealthData
import com.huawei.hihealth.api.HiHealthApi
import com.huawei.hihealth.data.listener.HiDataReadResultListener
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostMethod
import moe.evil.hwhh.kdxref.describe
import moe.evil.hwhh.kdxref.firstMethodOrNullLogged
import moe.evil.hwhh.kdxref.hostMethod
import moe.evil.hwhh.kdxref.safeHook
import moe.evil.hwhh.shared.DebugToggle
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.model.HealthQueryRequest
import moe.evil.hwhh.xposed.model.HealthQueryResponse
import moe.evil.hwhh.xposed.model.HealthSample
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HookApi
import moe.evil.hwhh.xposed.utils.ifDebugPref
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal interface HealthQueryApi : HookApi {
    fun queryData(ctx: Context, request: HealthQueryRequest): Result<HealthQueryResponse>
}

@OptIn(ExperimentalSerializationApi::class)
internal object HealthQueryHooker : DexKitHooker<HealthQueryApi>() {
    private const val API_CLASS = "com.huawei.hihealth.api.HiHealthNativeApi"
    private const val ACTION_QUERY = "moe.evil.hwhh.action.QUERY_HEALTH"
    private const val RESULT_ACCEPTED = -1
    private const val RESULT_REJECTED = 1
    private val log = HLog.of<HealthQueryHooker>()
    private val receiverRegistered = AtomicBoolean(false)
    private val json = Json
    private var apiFactory: HostMethod<HiHealthApi>? = null
    private val metadata by require { HealthMetadataHooker }

    override val providedApi = object : HealthQueryApi {
        override fun queryData(ctx: Context, request: HealthQueryRequest) = runCatching {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Health query blocks for up to ${request.timeoutSec}s, refusing the main thread"
            }
            val api = checkNotNull(apiFactory?.invoke(ctx.applicationContext)) {
                "HiHealthNativeApi unavailable"
            }
            val option = HiDataReadOption().apply {
                setType(request.types.toIntArray())
                setTimeInterval(request.startTimeMs, request.endTimeMs)
                if (request.count > 0) setCount(request.count)
                setSortOrder(0)
            }
            var payload: Any? = null
            var err = 0
            val latch = CountDownLatch(1)
            api.readHiHealthData(option, object : HiDataReadResultListener {
                override fun onResult(data: Any?, errCode: Int, index: Int) {
                    payload = data; err = errCode; latch.countDown()
                }

                override fun onResultIntent(readIntent: Int, data: Any?, errCode: Int, index: Int) {
                    payload = data; err = errCode; latch.countDown()
                }
            })
            check(latch.await(request.timeoutSec, TimeUnit.SECONDS)) {
                "HiHealth read timed out after ${request.timeoutSec}s"
            }
            check(err == 0) { "HiHealth read failed: err=$err" }
            val rows = mutableListOf<HiHealthData>()
            val types = HashSet(request.types)
            collectRows(payload, rows, types)
            HealthQueryResponse.read(
                request,
                rows.mapNotNull { it.toSample() },
                metadata.resolveAll(types),
            )
        }
    }

    override fun onHookWithDexKit(bridge: HostBridge) {
        apiFactory = hostMethod<HiHealthApi>("HiHealthNativeApi#getInstance") {
            declaredClass = API_CLASS
            returnType = API_CLASS
            paramTypes(classOf<Context>())
        } ?: return

        ifDebugPref(DebugToggle.HEALTH_QUERY) {
            classOf<Instrumentation>().firstMethodOrNullLogged {
                name = "callApplicationOnCreate"
                parameters(classOf<Application>())
            }?.safeHook {
                after {
                    val app = args(0).cast<Application?>() ?: return@after
                    if (!receiverRegistered.compareAndSet(false, true)) return@after
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val ordered = isOrderedBroadcast
                            val pending = goAsync()
                            val ctx = context.applicationContext
                            val request = runCatching {
                                val types = intent.getIntArrayExtra("types")?.toList()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: intent.getIntExtra("type", 0).takeIf { it > 0 }
                                        ?.let(::listOf)
                                    ?: error(
                                        "Missing 'types' (non-empty int array) or " +
                                                "'type' (positive int)"
                                    )
                                val start = intent.getLongExtra("start", -1)
                                val end = intent.getLongExtra("end", -1)
                                check(start >= 0 && end >= 0) {
                                    "Missing 'start' and/or 'end' (epoch millis)"
                                }
                                HealthQueryRequest(
                                    types = types,
                                    startTimeMs = start,
                                    endTimeMs = end,
                                    count = intent.getIntExtra("count", 0),
                                )
                            }
                            thread {
                                AutoCloseable(pending::finish).use {
                                    request.mapCatching { query ->
                                        val dir = checkNotNull(ctx.ensureExportDir()) {
                                            "Export dir unavailable"
                                        }
                                        val response =
                                            providedApi.queryData(ctx, query).getOrThrow()
                                        val out = File(
                                            dir,
                                            "health_query_${query.types.first()}_" +
                                                    "${System.currentTimeMillis()}.json",
                                        )
                                        out.outputStream().buffered().use { stream ->
                                            json.encodeToStream(response, stream)
                                        }
                                        log.debug {
                                            "Query types=${query.types} " +
                                                    "count=${response.count} -> ${out.name}"
                                        }
                                    }.fold(
                                        onSuccess = {
                                            if (ordered) pending.setResultCode(RESULT_ACCEPTED)
                                        },
                                        onFailure = { cause ->
                                            val reason = cause.describe()
                                            log.error { "Query rejected: $reason" }
                                            if (ordered) {
                                                pending.setResultCode(RESULT_REJECTED)
                                                pending.setResultData(reason)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    runCatching {
                        app.registerReceiver(
                            receiver,
                            IntentFilter(ACTION_QUERY),
                            Context.RECEIVER_NOT_EXPORTED
                        )
                    }.fold(
                        onSuccess = {
                            log.debug { "Health-query receiver registered (root only): $ACTION_QUERY" }
                        },
                        onFailure = {
                            receiverRegistered.set(false)
                            log.warn { "Health-query receiver not registered: ${it.describe()}" }
                        },
                    )
                }
            }
        }
    }

    private fun collectRows(
        data: Any?,
        out: MutableList<HiHealthData>,
        types: MutableSet<Int>,
    ) {
        when (data) {
            is SparseArray<*> -> for (i in 0 until data.size) {
                val rows = data.valueAt(i) as? List<*> ?: continue
                types += data.keyAt(i)
                rows.mapNotNullTo(out) { it as? HiHealthData }
            }

            is List<*> -> data.forEach { row ->
                (row as? HiHealthData)?.let { out += it; types += it.getType() }
            }

            else -> log.warn { "Unexpected payload ${data?.javaClass?.name}, treated as empty" }
        }
    }

    private fun HiHealthData.toSample(): HealthSample? {
        val v = getValueHolder() ?: return null
        fun str(key: String) = v.getAsString(key)?.takeIf(String::isNotEmpty)
        return HealthSample(
            type = getType(),
            startTimeMs = getStartTime(),
            endTimeMs = getEndTime(),
            value = getValue(),
            subType = getSubType(),
            pointUnit = getPointUnit(),
            deviceUuid = getDeviceUuid()?.takeIf(String::isNotEmpty),
            deviceName = str("device_name"),
            deviceModel = str("device_model"),
            deviceUniqueCode = str("device_uniquecode"),
            trackDeviceType = v.getAsInteger("trackdata_deviceType"),
            timeZone = str("time_zone"),
            modifiedTimeMs = v.getAsLong("modified_time"),
            clientId = v.getAsInteger("client_id"),
            dataId = v.getAsLong("data_id"),
            syncStatus = v.getAsInteger("sync_status"),
            sequenceData = getSequenceData()?.takeIf(String::isNotEmpty),
            simpleData = getSimpleData()?.takeIf(String::isNotEmpty),
            metadata = getMetaData()?.takeIf(String::isNotEmpty),
        )
    }
}
