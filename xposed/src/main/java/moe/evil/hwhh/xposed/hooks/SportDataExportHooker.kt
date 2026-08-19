package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.highcapable.kavaref.extension.classOf
import com.huawei.hwfoundationmodel.trackmodel.MotionPath
import com.huawei.hwfoundationmodel.trackmodel.MotionPathSimplify
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.kdxref.HostBridge
import moe.evil.hwhh.kdxref.HostField
import moe.evil.hwhh.kdxref.describe
import moe.evil.hwhh.kdxref.firstMethodOrNullLogged
import moe.evil.hwhh.kdxref.hostField
import moe.evil.hwhh.kdxref.safeHook
import moe.evil.hwhh.kdxref.toClassOrLog
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.model.SportRecordExportOutcome
import moe.evil.hwhh.xposed.model.SportRecordParser
import moe.evil.hwhh.xposed.sportdata.exporter.RecordExporter
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.ShareExporter
import moe.evil.hwhh.xposed.utils.ShareOutcome
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import java.io.File
import kotlin.concurrent.thread

@HookRoot(order = 3)
object SportDataExportHooker : DexKitBaseHooker() {
    private const val TITLE_BAR_ID = "track_detail_title_bar"
    private const val TRACK_DETAIL_PACKAGE = "com.huawei.healthcloud.plugintrack.ui.activity"
    private val log = HLog.of<SportDataExportHooker>()
    private val commonUi by require { CommonUIHooker }
    private var simplifyField: HostField<MotionPathSimplify>? = null
    private var motionPathField: HostField<MotionPath>? = null

    override fun onHookWithDexKit(bridge: HostBridge) {
        val trackDetailClazz = context(this@SportDataExportHooker) {
            "com.huawei.healthcloud.plugintrack.ui.activity.TrackDetailActivity".toClassOrLog()
        } ?: return

        simplifyField = hostField("TrackDetail#simplify", TRACK_DETAIL_PACKAGE) {
            declaredClass(trackDetailClazz)
        }
        motionPathField = hostField("TrackDetail#motionPath", TRACK_DETAIL_PACKAGE) {
            declaredClass(trackDetailClazz)
        }

        trackDetailClazz.firstMethodOrNullLogged {
            name = "onCreate"
            parameters(classOf<Bundle>())
        }?.safeHook {
            after {
                val activity = instanceOrNull as? Activity ?: return@after
                addExportButton(activity)
            }
        }
    }

    private fun exportCurrentRecord(activity: Activity, dir: File): SportRecordExportOutcome {
        val simplify = checkNotNull(simplifyField?.on(activity)) { "MotionPathSimplify unreadable" }
        val motionPath = checkNotNull(motionPathField?.on(activity)) { "MotionPath unreadable" }
        return RecordExporter.export(SportRecordParser.parse(simplify, motionPath), dir)
    }

    private fun addExportButton(activity: Activity) {
        val titleBarId = activity.resources.getIdentifier(TITLE_BAR_ID, "id", HOOK_TARGET_PACKAGE)
            .asResIdOrNull() ?: run {
            log.warn { "Resource '$TITLE_BAR_ID' not found in ${activity.javaClass.simpleName}" }
            return
        }
        val titleBar = activity.findViewById<CustomTitleBar>(titleBarId) ?: run {
            log.warn { "View '$TITLE_BAR_ID' not in layout of ${activity.javaClass.simpleName} (id=$titleBarId)" }
            return
        }
        val icon = runCatching {
            activity.resources.getIdentifier("ic_public_export", "drawable", HOOK_TARGET_PACKAGE)
                .asResIdOrNull()?.let { activity.getDrawable(it) }
        }.getOrNull() ?: runCatching {
            activity.getDrawable(android.R.drawable.stat_sys_upload_done)
        }.getOrNull()
        titleBar.setRightThirdKeyBackground(icon, activity.moduleString(R.string.hwhh_export_fit))
        titleBar.setRightThirdKeyVisibility(View.VISIBLE)
        titleBar.setRightThirdKeyOnClickListener { onExportClicked(activity) }
        log.debug { "Export button added to TrackDetailActivity title bar" }
    }

    private fun onExportClicked(activity: Activity) {
        val progress = commonUi.createProgressDialog(
            activity,
            activity.moduleString(R.string.hwhh_exporting),
        ).gracefulShow()
        thread {
            runCatching {
                val dir = activity.applicationContext.ensureExportDir()
                if (dir == null) {
                    commonUi.createNoTitleCustomAlertDialog(
                        activity = activity,
                        message = activity.moduleString(R.string.hwhh_export_dir_failed),
                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_export_dir_failed))
                    }
                    return@runCatching
                }
                when (val outcome = exportCurrentRecord(activity, dir)) {
                    is SportRecordExportOutcome.Success -> ShareExporter.share(
                        activity = activity,
                        target = outcome.file,
                        chooserTitle = activity.moduleString(R.string.hwhh_share_title),
                    ).let { shareOutcome ->
                        if (shareOutcome !is ShareOutcome.Failed) return@let
                        val cause = shareOutcome.error.describe()
                        commonUi.createCustomTextAlertDialog(
                            activity = activity,
                            title = activity.moduleString(R.string.hwhh_share_failed_title),
                            message = activity.moduleString(R.string.hwhh_error_detail, cause),
                            positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                        ).gracefulShow {
                            toast(moduleString(R.string.hwhh_share_failed, cause))
                        }
                    }

                    is SportRecordExportOutcome.Unsupported -> {
                        val message = activity.moduleString(
                            R.string.hwhh_record_export_unsupported,
                            outcome.record.sportType,
                            dir.absolutePath,
                        )
                        commonUi.createCustomTextAlertDialog(
                            activity = activity,
                            title = activity.moduleString(
                                R.string.hwhh_record_export_unsupported_title,
                            ),
                            message = message,
                            positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                        ).gracefulShow { toast(message) }
                    }

                    is SportRecordExportOutcome.FitFailed -> {
                        val message = activity.moduleString(
                            R.string.hwhh_record_export_fit_failed,
                            outcome.error.describe(),
                            dir.absolutePath,
                        )
                        commonUi.createCustomTextAlertDialog(
                            activity = activity,
                            title = activity.moduleString(
                                R.string.hwhh_record_export_fit_failed_title,
                            ),
                            message = message,
                            positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                        ).gracefulShow { toast(message) }
                    }
                }
            }.onFailure { e ->
                log.error(e) { "Export failed" }
                commonUi.createCustomTextAlertDialog(
                    activity = activity,
                    title = activity.moduleString(R.string.hwhh_export_failed_title),
                    message = activity.moduleString(R.string.hwhh_error_detail, e.describe()),
                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                ).gracefulShow {
                    toast(moduleString(R.string.hwhh_export_failed, e.describe()))
                }
            }
            progress.dismiss()
        }
    }
}
