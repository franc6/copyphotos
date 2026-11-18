package app.copyphotos.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.copyphotos.core.DestinationConfig
import java.util.concurrent.TimeUnit

/**
 * Queues copies and schedules automatic copying (hourly, and when new media appears). Every
 * copy runs as the same unique work, so only one runs at a time.
 *
 * @param context for the file that holds the IDs of a "copy again".
 * @param workManager runs the work.
 */
class CopyScheduler(context: Context, private val workManager: WorkManager) {
    private val app = context.applicationContext

    /**
     * Copies only never-copied photos. Does nothing if a copy is already queued/running.
     * @param constraints when the copy may run.
     */
    fun enqueueNew(constraints: Constraints = connected()) {
        enqueue(CopyWorkData.copyNew(), constraints)
    }

    /**
     * Copies exactly these photos, even if they were copied before.
     * @param ids the MediaStore IDs to copy.
     */
    fun enqueueIds(ids: Collection<Long>) {
        enqueue(CopyWorkData.copyIds(app, ids), connected())
    }

    /** Stops the queued or running copy. */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE)
    }

    /**
     * Queues the copy unless one is already queued or running.
     * @param input which photos to copy.
     * @param constraints when the copy may run.
     */
    private fun enqueue(input: Data, constraints: Constraints) {
        val req = OneTimeWorkRequestBuilder<CopyWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Schedules automatic copying for [config], or cancels it if automatic copying is off.
     * @param config whether automatic copying is on, and its constraints.
     */
    fun applyAutoCopy(config: DestinationConfig) {
        val wm = workManager
        if (!config.autoCopyEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(TRIGGER)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<AutoCopyTriggerWorker>(1, TimeUnit.HOURS)
            .setConstraints(autoCopyConstraints(config))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        scheduleMediaTrigger()
    }

    /** One-shot work that fires when MediaStore changes; it re-arms itself after running. */
    fun scheduleMediaTrigger() {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
            .build()
        val req = OneTimeWorkRequestBuilder<AutoCopyTriggerWorker>()
            .setConstraints(c)
            .setInputData(workDataOf(KEY_REARM to true))
            .build()
        workManager.enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        /** Unique work name of the copy. */
        const val UNIQUE = "copy"
        private const val TAG = "copy-worker"
        private const val PERIODIC = "auto-copy-periodic"
        private const val TRIGGER = "auto-copy-media-trigger"
        /** Input flag: the media trigger schedules itself again after it runs. */
        const val KEY_REARM = "rearm"

        /**
         * Any network connection.
         * @return the constraints.
         */
        private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * The user's automation constraints (Wi-Fi only, charging).
         * @param config the automatic-copy settings.
         * @return the constraints.
         */
        fun autoCopyConstraints(config: DestinationConfig): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.autoCopyWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(config.autoCopyWhileCharging)
            .build()
    }
}
