package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.MethodResolver
import com.huawei.hihealth.HiHealthData
import com.huawei.hwbasemgr.IBaseResponseCallback
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.exporter.SportHistoryExporter
import moe.evil.hwhh.xposed.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HLog
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.tryHookWithDexKit
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

object SportHistoryExportHooker : DexKitHooker() {
    private const val TITLE_BAR_ID = "sport_history_titlebar"
    private const val YEARS_BACK = 20L
    private const val MS_PER_YEAR = 365L * 24 * 3600 * 1000

    private val log = HLog.of<SportHistoryExportHooker>()

    private var exporter: SportHistoryExporter? = null

    override fun onHook() = tryHookWithDexKit { bridge ->
        val activityClazz = context(this@SportHistoryExportHooker) {
            "com.huawei.ui.main.stories.history.SportHistoryActivity".toClassOrLog()
        } ?: return@tryHookWithDexKit

        val summaryMethod = resolveStaticMethod(
            bridge = bridge,
            label = "summary (bnf#b)",
            paramTypes = listOf("long", "long", "com.huawei.hwbasemgr.IBaseResponseCallback"),
            returnType = "void",
            markerStrings = listOf("getRecordListByTime workoutList.size"),
            kavaParams = arrayOf(
                Long::class,
                Long::class,
                IBaseResponseCallback::class.java,
            ),
        ) ?: run {
            log.warn { "Summary method not resolved" }
            return@tryHookWithDexKit
        }

        val detailMarkerInners = bridge.findClass {
            matcher {
                usingStrings = listOf("requestTrackDetailData onResult map is empty.")
            }
        }.filter { it.name.contains('$') }
        log.debug { "Detail (jac#c): marker inner classes=${detailMarkerInners.map { it.name }}" }

        fun findDetailBuilder(innerName: String, requireReadHiHealthData: Boolean) =
            bridge.findMethod {
                matcher {
                    declaredClass = innerName.substringBeforeLast('$')
                    paramTypes("long", "long", "com.huawei.hwbasemgr.IBaseResponseCallback")
                    returnType = "void"
                    addInvoke {
                        name = "<init>"
                        declaredClass = innerName
                    }
                    if (requireReadHiHealthData) addInvoke { name = "readHiHealthData" }
                }
            }.singleOrNull { Modifier.isStatic(it.modifiers) }
        val detailMd = detailMarkerInners.firstNotNullOfOrNull { findDetailBuilder(it.name, true) }
            ?: detailMarkerInners.singleOrNull()?.name?.let { findDetailBuilder(it, false) }
        log.debug { "Detail (jac#c): picked=${detailMd?.className}.${detailMd?.name}" }
        val detailMethod = detailMd?.let { md ->
            val cls = context(this@SportHistoryExportHooker) { md.className.toClassOrLog() }
            cls?.resolve()?.optional(silent = true)?.firstMethodOrNull {
                name = md.name
                parameters(
                    Long::class,
                    Long::class,
                    IBaseResponseCallback::class.java,
                )
            }
        } ?: run {
            log.warn { "Detail method not resolved" }
            return@tryHookWithDexKit
        }

        val mrcMethod = resolveStaticMethod(
            bridge = bridge,
            label = "mrc.m128921e",
            paramTypes = listOf(
                "com.huawei.hihealth.HiHealthData",
                "com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify",
            ),
            returnType = "java.lang.String",
            markerStrings = listOf(
                "should not enter this branch,do not set",
                "Track_SportDataConvertUtil",
            ),
            kavaParams = arrayOf(HiHealthData::class.java, MotionPathSimplify::class.java),
        ) ?: run {
            log.warn { "mrc.m128921e not resolved" }
            return@tryHookWithDexKit
        }

        val ifhMethod = resolveStaticMethod(
            bridge = bridge,
            label = "ifh.m108033a",
            paramTypes = listOf("android.content.Context", "java.lang.String", "int"),
            returnType = "com.huawei.hwfoundationmodel.trackmodel.MotionPath",
            markerStrings = listOf("readTemporaryMotionPath savePath is empty"),
            kavaParams = arrayOf(
                Context::class.java,
                String::class.java,
                Int::class,
            ),
        ) ?: run {
            log.warn { "ifh.m108033a not resolved" }
            return@tryHookWithDexKit
        }

        activityClazz.firstMethodOrNullLogged {
            name = "onCreate"
            parameters(Bundle::class)
        }?.safeHook {
            after {
                val activity = instanceOrNull as? Activity ?: return@after
                if (exporter == null) {
                    exporter = SportHistoryExporter(
                        summaryMethod = summaryMethod,
                        detailMethod = detailMethod,
                        mrcConvertMethod = mrcMethod,
                        ifhReadMethod = ifhMethod,
                    )
                }
                addExportButton(activity)
            }
        }
    }

    private fun resolveStaticMethod(
        bridge: DexKitBridge,
        label: String,
        paramTypes: List<String>,
        returnType: String,
        markerStrings: List<String>,
        kavaParams: Array<out Any>,
    ): MethodResolver<*>? {
        val matches = bridge.findMethod {
            matcher {
                paramTypes(*paramTypes.toTypedArray())
                this.returnType = returnType
                usingStrings = markerStrings
            }
        }
        val md = matches.singleOrNull { Modifier.isStatic(it.modifiers) }
        log.debug { "$label: ${matches.size} match(es), picked=${md?.className}.${md?.name}" }
        if (md == null) return null
        val cls = context(this@SportHistoryExportHooker) { md.className.toClassOrLog() }
            ?: return null
        return cls.resolve().optional(silent = true).firstMethodOrNull {
            name = md.name
            parameters(*kavaParams)
        }
    }

    private fun addExportButton(activity: Activity) {
        val titleBarId = activity.resources.getIdentifier(TITLE_BAR_ID, "id", HOOK_TARGET_PACKAGE)
            .asResIdOrNull() ?: run {
            log.warn { "Resource '$TITLE_BAR_ID' not found in ${activity.javaClass.simpleName}" }
            return
        }
        val titleBar = activity.findViewById<CustomTitleBar>(titleBarId) ?: run {
            log.warn { "'$TITLE_BAR_ID' view not in layout of ${activity.javaClass.simpleName} (id=$titleBarId)" }
            return
        }
        val icon = runCatching {
            activity.resources.getIdentifier("ic_public_export", "drawable", HOOK_TARGET_PACKAGE)
                .asResIdOrNull()?.let { activity.getDrawable(it) }
        }.getOrNull() ?: runCatching {
            activity.getDrawable(android.R.drawable.stat_sys_upload_done)
        }.getOrNull()
        titleBar.setRightThirdKeyBackground(icon, "Batch Export")
        titleBar.setRightThirdKeyVisibility(View.VISIBLE)
        titleBar.setRightThirdKeyOnClickListener { onExportClicked(activity) }
        log.debug { "Batch export button added to SportHistoryActivity" }
    }

    private fun onExportClicked(activity: Activity) {
        val b = exporter ?: run {
            activity.toast("Batch export unavailable (lookups failed)")
            return
        }
        activity.toast("Batch exporting activities...")
        thread {
            runCatching {
                val dir = activity.applicationContext.ensureExportDir() ?: run {
                    activity.toast("Failed to create export directory")
                    return@thread
                }
                val now = System.currentTimeMillis()
                val start = now - YEARS_BACK * MS_PER_YEAR
                log.debug { "Querying range start=$start end=$now" }
                val result = b.exportSupported(
                    activity.applicationContext,
                    dir,
                    start,
                    now
                ) { done, total ->
                    if (done == 1 || done % 10 == 0 || done == total) {
                        activity.toast("$done / $total")
                    }
                }
                activity.toast(
                    "Batch done: ${result.exported} ok, ${result.missingSequence} no-GPS, ${result.failed} failed (total ${result.candidates})\n${dir.absolutePath}",
                )
            }.onFailure { e ->
                log.error(e) { "Batch export failed" }
                activity.toast("Batch failed: ${e.message}")
            }
        }
    }
}
