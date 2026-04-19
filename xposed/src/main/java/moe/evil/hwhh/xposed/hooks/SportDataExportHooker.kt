package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.xposed.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.xposed.exporter.RecordExporter
import moe.evil.hwhh.xposed.exporter.ensureExportDir
import moe.evil.hwhh.xposed.model.ExportOutcome
import moe.evil.hwhh.xposed.model.SportRecordParser
import moe.evil.hwhh.xposed.utils.DexKitHooker
import moe.evil.hwhh.xposed.utils.HLog
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.firstMethodOrNullLogged
import moe.evil.hwhh.xposed.utils.rtField
import moe.evil.hwhh.xposed.utils.safeHook
import moe.evil.hwhh.xposed.utils.toClassOrLog
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.tryHookWithDexKit
import java.io.File
import kotlin.concurrent.thread

object SportDataExportHooker : DexKitHooker() {
    private const val TITLE_BAR_ID = "track_detail_title_bar"

    private val log = HLog.of<SportDataExportHooker>()

    private var simplifyFieldName: String? = null
    private var motionPathFieldName: String? = null

    override fun onHook() = tryHookWithDexKit { bridge ->
        val trackDetailClazz = context(this@SportDataExportHooker) {
            "com.huawei.healthcloud.plugintrack.ui.activity.TrackDetailActivity".toClassOrLog()
        } ?: return@tryHookWithDexKit

        simplifyFieldName = bridge.findField {
            searchPackages("com.huawei.healthcloud.plugintrack.ui.activity")
            matcher {
                declaredClass = trackDetailClazz.name
                type = MotionPathSimplify::class.java.name
            }
        }.singleOrNull()?.name
        motionPathFieldName = bridge.findField {
            searchPackages("com.huawei.healthcloud.plugintrack.ui.activity")
            matcher {
                declaredClass = trackDetailClazz.name
                type = MotionPath::class.java.name
            }
        }.singleOrNull()?.name

        trackDetailClazz.firstMethodOrNullLogged {
            name = "onCreate"
            parameters(Bundle::class)
        }?.safeHook {
            after {
                val activity = instanceOrNull as? Activity ?: return@after
                addExportButton(activity)
            }
        }
    }

    private fun exportCurrentRecord(activity: Activity, dir: File): ExportOutcome? {
        val sfName = simplifyFieldName ?: return null
        val mpName = motionPathFieldName ?: return null
        val simplifyObj = activity.rtField<MotionPathSimplify>(sfName) ?: return null
        val motionPathObj = activity.rtField<MotionPath>(mpName) ?: return null
        val record = SportRecordParser.parse(simplifyObj, motionPathObj)
        return RecordExporter.export(record, dir)
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
        titleBar.setRightThirdKeyBackground(icon, "Export FIT")
        titleBar.setRightThirdKeyVisibility(View.VISIBLE)
        titleBar.setRightThirdKeyOnClickListener { onExportClicked(activity) }
        log.debug { "Export button added to TrackDetailActivity title bar" }
    }

    private fun onExportClicked(activity: Activity) {
        activity.toast("Exporting...")
        thread {
            runCatching {
                val dir = activity.applicationContext.ensureExportDir()
                    ?: return@thread activity.toast("Failed to create export directory")
                val outcome = exportCurrentRecord(activity, dir)
                activity.toast(buildToastMessage(outcome, dir))
            }.onFailure { e ->
                log.error(e) { "Export failed" }
                activity.toast("Export failed: ${e.message}")
            }
        }
    }

    private fun buildToastMessage(outcome: ExportOutcome?, dir: File): String = when (outcome) {
        null -> "No data on this record.\n${dir.absolutePath}"
        is ExportOutcome.Success ->
            "Export done! JSON + FIT (${outcome.sport.displayName})\n${dir.absolutePath}"

        is ExportOutcome.Unsupported ->
            "JSON only — sportType=${outcome.record.sportType} not yet supported for FIT.\n${dir.absolutePath}"

        is ExportOutcome.FitFailed ->
            "JSON done. FIT failed: ${outcome.error.message}\n${dir.absolutePath}"
    }
}
