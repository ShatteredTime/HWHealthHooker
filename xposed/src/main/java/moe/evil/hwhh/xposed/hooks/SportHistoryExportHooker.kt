package moe.evil.hwhh.xposed.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.huawei.ui.commonui.titlebar.CustomTitleBar
import moe.evil.hwhh.shared.HOOK_TARGET_PACKAGE
import moe.evil.hwhh.shared.HookRoot
import moe.evil.hwhh.shared.log.HLog
import moe.evil.hwhh.shared.log.describe
import moe.evil.hwhh.xposed.R
import moe.evil.hwhh.xposed.sportdata.exporter.SportHistoryExporter
import moe.evil.hwhh.xposed.sportdata.exporter.ensureExportDir
import moe.evil.hwhh.xposed.utils.DateRangeRow
import moe.evil.hwhh.xposed.utils.DexKitBaseHooker
import moe.evil.hwhh.xposed.utils.ShareExporter
import moe.evil.hwhh.xposed.utils.ShareOutcome
import moe.evil.hwhh.xposed.utils.asResIdOrNull
import moe.evil.hwhh.xposed.utils.dialogContent
import moe.evil.hwhh.xposed.utils.moduleString
import moe.evil.hwhh.xposed.utils.toast
import moe.evil.hwhh.xposed.utils.wrapper.HostBridge
import moe.evil.hwhh.xposed.utils.wrapper.classOf
import moe.evil.hwhh.xposed.utils.wrapper.requireClass
import moe.evil.hwhh.xposed.utils.wrapper.requireMethod
import moe.evil.hwhh.xposed.utils.wrapper.safeHook
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@HookRoot(order = 4)
object SportHistoryExportHooker : DexKitBaseHooker() {
    private const val TITLE_BAR_ID = "sport_history_titlebar"
    private val log = HLog.of<SportHistoryExportHooker>()
    private val commonUi by require { CommonUIHooker }
    private val history by require { SportHistoryHooker }

    override fun onHookWithDexKit(bridge: HostBridge) {
        bridge.requireClass("com.huawei.ui.main.stories.history.SportHistoryActivity")
            .requireMethod {
                name = "onCreate"
                parameters(classOf<Bundle>())
            }.safeHook {
                after { (instanceOrNull as? Activity)?.let(::addExportButton) }
            }
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
        titleBar.setRightThirdKeyBackground(icon, activity.moduleString(R.string.hwhh_batch_export))
        titleBar.setRightThirdKeyVisibility(View.VISIBLE)
        titleBar.setRightThirdKeyOnClickListener { onExportClicked(activity) }
        log.debug { "Batch export button added to SportHistoryActivity" }
    }

    private fun onExportClicked(activity: Activity) {
        val timeRange = DateRangeRow(activity, activity.moduleString(R.string.hwhh_row_time)) {
            add(Calendar.YEAR, -6)
        }
        commonUi.createCustomViewDialog(
            activity = activity,
            title = activity.moduleString(R.string.hwhh_batch_export),
            contentView = activity.dialogContent(timeRange.view),
            positive = DialogButton(activity.moduleString(R.string.hwhh_export)) {
                startExport(activity, timeRange.range)
            },
            negative = DialogButton(activity.moduleString(R.string.hwhh_cancel)),
        ).gracefulShow()
    }

    private fun startExport(activity: Activity, range: LongRange) {
        val cancelled = AtomicBoolean(false)
        val progress = commonUi.createProgressDialog(
            activity,
            activity.moduleString(R.string.hwhh_batch_preparing),
        ) { cancelled.set(true) }.gracefulShow()
        thread {
            runCatching {
                val dir = activity.applicationContext.ensureExportDir() ?: run {
                    commonUi.createNoTitleCustomAlertDialog(
                        activity = activity,
                        message = activity.moduleString(R.string.hwhh_export_dir_failed),
                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_export_dir_failed))
                    }
                    return@runCatching
                }
                val runDir = File(dir, "batch_${System.currentTimeMillis()}").apply { mkdirs() }
                log.debug { "Querying range start=${range.first} end=${range.last}" }
                val result = SportHistoryExporter.exportSupported(
                    history,
                    activity.applicationContext,
                    runDir,
                    range.first,
                    range.last,
                    isCancelled = cancelled::get,
                ) { done, total ->
                    progress.setProgress(done * 100 / total)
                    progress.setMessage("$done / $total")
                }
                val statsMessage = activity.moduleString(
                    R.string.hwhh_batch_done,
                    result.exported,
                    result.missingSequence,
                    result.failed,
                    result.candidates,
                    runDir.absolutePath,
                )
                val shareOutcome = if (cancelled.get()) null else {
                    cancelled.set(false)
                    progress.setMessage(activity.moduleString(R.string.hwhh_share_zipping))
                    ShareExporter.share(
                        activity = activity,
                        target = runDir,
                        chooserTitle = activity.moduleString(R.string.hwhh_share_title),
                        isCancelled = cancelled::get,
                    ) { done, total ->
                        progress.setProgress(done * 100 / total)
                        progress.setMessage("$done / $total")
                    }
                }
                if (shareOutcome is ShareOutcome.Failed) {
                    val cause = shareOutcome.error.describe()
                    commonUi.createCustomTextAlertDialog(
                        activity = activity,
                        title = activity.moduleString(R.string.hwhh_share_failed_title),
                        message = activity.moduleString(R.string.hwhh_error_detail, cause),
                        positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                    ).gracefulShow {
                        toast(moduleString(R.string.hwhh_share_failed, cause))
                    }
                    return@runCatching
                }
                commonUi.createCustomTextAlertDialog(
                    activity = activity,
                    title = activity.moduleString(R.string.hwhh_batch_done_title),
                    message = statsMessage,
                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                ).gracefulShow { toast(statsMessage) }
            }.onFailure { e ->
                log.error(e) { "Batch export failed" }
                commonUi.createCustomTextAlertDialog(
                    activity = activity,
                    title = activity.moduleString(R.string.hwhh_batch_failed_title),
                    message = activity.moduleString(R.string.hwhh_error_detail, e.describe()),
                    positive = DialogButton(activity.moduleString(R.string.hwhh_ok)),
                ).gracefulShow {
                    toast(moduleString(R.string.hwhh_batch_failed, e.describe()))
                }
            }
            progress.dismiss()
        }
    }
}
