package app.copyphotos.work

import android.content.SharedPreferences
import androidx.work.WorkManager
import app.copyphotos.core.DestinationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

/**
 * The copies, as WorkManager runs them, one at a time: starting and stopping them, whether one
 * runs, and the banner of the last one. A banner the user closed stays closed, also across
 * launches. A copy keeps running when the user leaves the screen or the app.
 *
 * @param destination the automation constraints of automatic copies.
 * @param scheduler queues and stops the copies.
 * @param workManager reports the copy's state.
 * @param prefs where the closed banner is remembered.
 */
class CopyJobs(
    private val destination: DestinationService,
    private val scheduler: CopyScheduler,
    workManager: WorkManager,
    private val prefs: SharedPreferences,
) {
    private val dismissed = MutableStateFlow(prefs.getString(KEY_DISMISSED_BANNER, null))
    @Volatile private var lastFinished: String? = null

    /** Whether a copy runs, and the banner of the last one unless the user closed it. */
    val state: Flow<CopyJobState> =
        combine(CopyWorkData.observe(workManager).onEach { lastFinished = it.finishedWorkId }, dismissed) { job, closed ->
            if (job.finishedWorkId != null && job.finishedWorkId == closed) job.copy(banner = null) else job
        }

    /**
     * Copies photos that have never been copied (never re-copies anything). An automatic copy
     * waits for the user's automation constraints (Wi-Fi, charging) and leaves the banner alone.
     */
    fun copyNew(automatic: Boolean) {
        if (automatic) {
            scheduler.enqueueNew(CopyScheduler.autoCopyConstraints(destination.config.value))
        } else {
            dismissBanner()
            scheduler.enqueueNew()
        }
    }

    /** Copies these photos even if they were copied before ("copy again"). */
    fun copy(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dismissBanner()
        scheduler.enqueueIds(ids)
    }

    /** Stops the running copy. */
    fun cancel() = scheduler.cancel()

    /** Closes the banner of the last copy, also for later launches. */
    fun dismissBanner() {
        val id = lastFinished ?: return
        prefs.edit().putString(KEY_DISMISSED_BANNER, id).apply()
        dismissed.value = id
    }

    private companion object {
        /** Work ID of the last banner the user closed. */
        const val KEY_DISMISSED_BANNER = "banner.dismissed"
    }
}
