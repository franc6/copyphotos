package app.copyphotos.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.copyphotos.R
import app.copyphotos.core.CopyItem
import app.copyphotos.core.CopyProgress
import app.copyphotos.core.CopyResult
import app.copyphotos.core.CopyService
import app.copyphotos.core.DestinationService
import app.copyphotos.core.FailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

/**
 * Runs a copy in WorkManager, with a foreground notification that has a Stop action. Mode "new"
 * copies only photos never copied before (used by every automatic trigger); mode "ids" copies
 * the listed photos even if already copied ("copy again"). Which photos and how to copy them
 * come from the CopyService; this class only adapts it to WorkManager.
 *
 * @param context the app context.
 * @param params WorkManager's parameters for this run.
 * @param copyService chooses and copies the photos.
 */
class CopyWorker(
    context: Context,
    params: WorkerParameters,
    private val copyService: CopyService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val items: List<CopyItem> = runInterruptible(Dispatchers.IO) {
            when (val ids = CopyWorkData.idsToCopy(applicationContext, inputData)) {
                null -> copyService.uncopiedItems()
                else -> copyService.items(ids)
            }
        }
        if (items.isEmpty()) return Result.success(CopyWorkData.result(CopyResult(0, 0, 0, emptyList(), false)))

        runCatching { setForeground(foregroundInfo(0, items.size, null)) }

        return try {
            val summary = runInterruptible(Dispatchers.IO) {
                copyService.run(items, isCancelled = { isStopped }) { p ->
                    reportProgress(p)
                }
            }
            Result.success(CopyWorkData.result(CopyResult(summary)))
        } catch (e: Exception) {
            if (isStopped || !coroutineContext.isActive) Result.failure()
            else Result.failure(CopyWorkData.error(FailureReason.of(e)))
        }
    }

    private var lastReport = 0L

    private fun reportProgress(p: CopyProgress) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 300 && p.currentName != null) return
        lastReport = now
        setProgressAsync(CopyWorkData.progress(p))
        runCatching { setForegroundAsync(foregroundInfo(p.completed + p.failed, p.total, p.currentName)) }
    }

    private fun foregroundInfo(done: Int, total: Int, name: String?): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_copy)
            .setContentTitle(applicationContext.getString(R.string.notification_copying, done, total))
            .setContentText(name ?: applicationContext.getString(R.string.notification_connecting))
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, applicationContext.getString(R.string.action_stop), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "copy"
        private const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
                }
            }
        }
    }
}

/**
 * Lightweight trigger: queues a "copy new photos" run with the user's automation constraints.
 *
 * @param context the app context.
 * @param params WorkManager's parameters for this run.
 * @param destination whether automatic copying is on, and its constraints.
 * @param scheduler queues the copy and re-arms the media trigger.
 */
class AutoCopyTriggerWorker(
    context: Context,
    params: WorkerParameters,
    private val destination: DestinationService,
    private val scheduler: CopyScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = destination.config.value
        if (config.autoCopyEnabled) {
            scheduler.enqueueNew(CopyScheduler.autoCopyConstraints(config))
            if (inputData.getBoolean(CopyScheduler.KEY_REARM, false)) scheduler.scheduleMediaTrigger()
        }
        return Result.success()
    }
}
