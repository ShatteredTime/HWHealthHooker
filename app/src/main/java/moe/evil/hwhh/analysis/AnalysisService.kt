package moe.evil.hwhh.analysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import moe.evil.hwhh.MainActivity
import moe.evil.hwhh.R
import moe.evil.hwhh.analyzer.AnalysisOutcome
import moe.evil.hwhh.shared.log.HLog
import kotlin.concurrent.thread

class AnalysisService : Service() {
    companion object {
        const val ACTION_CANCEL = "moe.evil.hwhh.action.CANCEL_ANALYSIS"
        const val WORKER_NAME = "hwhh-analysis"
        const val CHANNEL_ID = "analysis"
        const val PROGRESS_ID = 1001
        const val RESULT_ID = 1002
    }

    private val log = HLog.of<AnalysisService>()
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var worker: Thread? = null

    @Volatile
    private var lastStartId = 0

    private val isWorking get() = worker?.isAlive == true

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.analysis_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        AnalysisController.state
            .filterIsInstance<AnalysisState.Running>()
            .onEach {
                runCatching { manager.notify(PROGRESS_ID, it.progressNotification) }
                    .onFailure { e -> log.warn(e) { "Progress notification dropped" } }
            }
            .launchIn(scope)
    }

    override fun onDestroy() {
        AnalysisController.cancel()
        scope.cancel()
        manager.cancel(PROGRESS_ID)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                AnalysisController.cancel()
                if (!isWorking) stopSelf(startId)
            }

            else -> {
                startForeground(
                    PROGRESS_ID,
                    AnalysisState.Running().progressNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                when {
                    isWorking -> log.warn { "Analysis still running, ignoring start $startId" }

                    AnalysisController.begin() ->
                        worker = thread(name = WORKER_NAME, block = ::runAnalysis)

                    else -> {
                        log.warn { "Orphaned analysis still unwinding, dropping start $startId" }
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runAnalysis() {
        AutoCloseable {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            AnalysisController.release()
        }.use {
            val next = HealthIdAnalysis.run(
                context = this,
                log = {
                    log.info { it }
                    AnalysisController.log(it)
                },
                onProgress = AnalysisController::progress,
                isCancelled = { AnalysisController.isCancelled },
            ).toState()
            AnalysisController.finish(next)
            runCatching { next.resultNotification?.let { manager.notify(RESULT_ID, it) } }
                .onFailure { log.error(it) { "Failed to post result notification" } }
        }
    }

    private fun AnalysisOutcome<AnalysisOutput>.toState() = when (this) {
        is AnalysisOutcome.Done -> AnalysisState.Succeeded(
            value.digest.names.size,
            value.result.elapsedMs
        )

        is AnalysisOutcome.Cancelled -> {
            log.warn { "Analysis was cancelled" }
            AnalysisState.Cancelled
        }

        is AnalysisOutcome.Failed -> {
            log.error(cause) { "An error occurred when running analysis" }
            AnalysisState.Failed("${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun notification(build: Notification.Builder.() -> Unit) =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_analysis)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .apply(build)
            .build()

    private fun result(title: String, text: String) = notification {
        setContentTitle(title)
        setContentText(text)
        setStyle(Notification.BigTextStyle().bigText(text))
        setAutoCancel(true)
    }

    private val cancelAction
        get() = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_analysis),
            getString(R.string.analysis_progress_cancel),
            PendingIntent.getService(
                this,
                1,
                Intent(this, AnalysisService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

    private val AnalysisState.Running.progressNotification
        get() = notification {
            setContentTitle(getString(R.string.analysis_notification_title))
            setContentText(
                if (cancelling) getString(R.string.analysis_notification_cancelling)
                else lines.lastOrNull() ?: getString(R.string.analysis_notification_preparing)
            )
            setSubText(getString(R.string.analysis_progress_percent, percent))
            setProgress(100, percent, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(cancelAction)
        }

    private val AnalysisState.resultNotification
        get() = when (this) {
            is AnalysisState.Succeeded -> result(
                getString(R.string.analysis_notification_done),
                getString(R.string.analysis_result_success_message, names, elapsedMs / 1000)
            )

            is AnalysisState.Failed ->
                result(getString(R.string.analysis_notification_failed), reason)

            else -> null
        }
}
