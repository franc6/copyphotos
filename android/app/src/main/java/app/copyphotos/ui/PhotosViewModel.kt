package app.copyphotos.ui

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.copyphotos.core.CopyRunState
import app.copyphotos.core.CopyService
import app.copyphotos.core.DestinationService
import app.copyphotos.core.FailureReason
import app.copyphotos.core.LibraryAccess
import app.copyphotos.core.ResultBanner
import app.copyphotos.media.DeleteRequest
import app.copyphotos.media.MediaItem
import app.copyphotos.media.MediaRepository
import app.copyphotos.work.CopyJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PhotoFilter { ALL, NOT_COPIED, COPIED }

/** The photo list as the Photos screen shows it. */
data class LibraryState(
    val items: List<MediaItem> = emptyList(),
    val copiedIds: Set<Long> = emptySet(),
    val filter: PhotoFilter = PhotoFilter.ALL,
) {
    fun isCopied(item: MediaItem) = item.id in copiedIds

    val visible: List<MediaItem>
        get() = when (filter) {
            PhotoFilter.ALL -> items
            PhotoFilter.NOT_COPIED -> items.filter { !isCopied(it) }
            PhotoFilter.COPIED -> items.filter(::isCopied)
        }

    val copiedCount: Int get() = items.count(::isCopied)
    val uncopiedCount: Int get() = items.size - copiedCount
}

/** How deleting copied photos from the device ended. */
sealed class DeleteOutcome {
    /** Deleted directly (Android 8–10). */
    data class Deleted(val count: Int) : DeleteOutcome()
    /** Moved to the device trash after the system confirmations (Android 11+). */
    data class Trashed(val count: Int) : DeleteOutcome()
}

/** "Delete copied photos from device", step by step. */
data class DeleteState(
    val working: Boolean = false,
    val error: FailureReason? = null,
    val done: DeleteOutcome? = null,
    /** System confirmations still to show (Android 11+), each with the IDs it deletes. */
    val pending: List<Pair<PendingIntent, List<Long>>> = emptyList(),
    val deletedTotal: Int = 0,
)

/**
 * Controller for the Photos screen: the photo list and which photos are copied, running
 * copies, copy history, and deleting copied photos from the device.
 */
class PhotosViewModel(
    private val service: CopyService,
    private val jobs: CopyJobs,
    private val media: MediaRepository,
    private val destination: DestinationService,
) : ViewModel() {
    /** Where photos are copied to, or null when no destination is set up. */
    val destinationUrl: StateFlow<String?> = destination.config
        .map { if (it.isComplete) it.displayUrl else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _access = MutableStateFlow(LibraryAccess.NONE)
    val access: StateFlow<LibraryAccess> = _access.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _runState = MutableStateFlow<CopyRunState>(CopyRunState.Idle)
    val runState: StateFlow<CopyRunState> = _runState.asStateFlow()

    private val _banner = MutableStateFlow<ResultBanner?>(null)
    val banner: StateFlow<ResultBanner?> = _banner.asStateFlow()

    private val _delete = MutableStateFlow(DeleteState())
    val delete: StateFlow<DeleteState> = _delete.asStateFlow()

    /** A copy can start: the destination is set up and no copy is running. */
    val canCopy: StateFlow<Boolean> = combine(destination.config, runState) { c, r -> c.isComplete && !r.isActive }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            jobs.state.collect { job ->
                _runState.value = job.runState
                _banner.value = job.banner
                if (job.finishedWorkId != null) refreshCopied()
            }
        }
    }

    // region Library

    /** Called when the app is shown and after permissions change. */
    fun onAccessChanged(access: LibraryAccess, resumed: Boolean) {
        _access.value = access
        if (!access.canRead) return
        refresh()
        if (resumed && destination.config.value.autoCopyEnabled) jobs.copyNew(automatic = true)
    }

    fun setFilter(filter: PhotoFilter) = _library.update { it.copy(filter = filter) }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { media.query() }.getOrNull()
            val copied = service.copiedIdsOnDevice()
            _library.update { it.copy(items = list.orEmpty(), copiedIds = copied) }
        }
    }

    private fun refreshCopied() {
        viewModelScope.launch(Dispatchers.IO) {
            val copied = service.copiedIdsOnDevice()
            _library.update { it.copy(copiedIds = copied) }
        }
    }

    // endregion

    // region Copy

    fun copyNew() {
        if (canCopy.value) jobs.copyNew(automatic = false)
    }

    /** Copies these photos even if they were copied before. */
    fun copyIds(ids: Collection<Long>) {
        if (canCopy.value) jobs.copy(ids)
    }

    /** Copies every photo again, ignoring the copy history. */
    fun copyAllAgain() = copyIds(_library.value.items.map { it.id })

    fun cancel() = jobs.cancel()

    fun dismissBanner() = jobs.dismissBanner()

    /** Treat every photo as not copied; nothing is deleted anywhere. */
    fun forgetHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            service.forgetHistory()
            _library.update { it.copy(copiedIds = emptySet()) }
        }
    }

    // endregion

    // region Delete copied photos from device

    /**
     * Deletes every copied photo still on the device. The only criterion is that the copy was
     * made and verified at the time; the destination is not consulted, because what happens
     * there afterwards has no bearing on the device. On Android 11+ this leaves system
     * confirmations in [DeleteState.pending] for the screen to show one by one.
     */
    fun startDelete() {
        _delete.value = DeleteState(working = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { media.deleteRequest(service.copiedIdsOnDevice()) }
            }.onSuccess { request ->
                when (request) {
                    is DeleteRequest.Done -> {
                        recordDeleted(request.deletedIds)
                        _delete.value = DeleteState(done = DeleteOutcome.Deleted(request.deletedIds.size))
                    }
                    is DeleteRequest.Confirm ->
                        if (request.batches.isEmpty()) _delete.value = DeleteState(done = DeleteOutcome.Deleted(0))
                        else _delete.update { it.copy(pending = request.batches) }
                }
            }.onFailure { e -> _delete.value = DeleteState(error = FailureReason.of(e)) }
        }
    }

    /** The user answered the first pending system confirmation. */
    fun onDeleteConfirmed(confirmed: Boolean) {
        val s = _delete.value
        val batch = s.pending.firstOrNull() ?: return
        _delete.value = if (confirmed) {
            recordDeleted(batch.second)
            val pending = s.pending.drop(1)
            val total = s.deletedTotal + batch.second.size
            s.copy(pending = pending, deletedTotal = total, working = pending.isNotEmpty(), done = DeleteOutcome.Trashed(total))
        } else {
            // The user cancelled: stop asking.
            s.copy(pending = emptyList(), working = false, done = DeleteOutcome.Trashed(s.deletedTotal))
        }
    }

    fun resetDelete() {
        _delete.value = DeleteState()
    }

    /** Photos left the device, so they leave the ledger. The destination is never touched. */
    private fun recordDeleted(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            service.recordDeleted(ids)
            refresh()
        }
    }

    // endregion
}
