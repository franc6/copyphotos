package app.copyphotos.core

/** Whether a copy is running, as the Photos screen shows it. */
sealed class CopyRunState {
    data object Idle : CopyRunState()
    /** Queued, waiting for its conditions (e.g. a network connection). */
    data object Waiting : CopyRunState()
    /** Running; [progress] is null until the first report arrives. */
    data class Running(override val progress: CopyProgress?) : CopyRunState()

    val isActive: Boolean get() = this != Idle
    /** Progress of a running copy, or null. */
    open val progress: CopyProgress? get() = null
}

/** The outcome of a copy run, reduced to what the result banner shows. */
data class CopyResult(
    val copied: Int,
    val alreadyPresent: Int,
    /** How many items failed. */
    val failed: Int,
    /** The failed items (may be fewer than [failed] if shortened). */
    val failures: List<CopyFailure>,
    val cancelled: Boolean,
) {
    constructor(summary: CopySummary) : this(
        summary.copied.size, summary.alreadyPresent.size, summary.failures.size, summary.failures, summary.cancelled,
    )
}

/** Which banner the Photos screen shows after a copy (same choice on iOS and Android). */
sealed class ResultBanner {
    /** The copy ran (possibly stopped part-way, possibly with failures). */
    data class Finished(val result: CopyResult) : ResultBanner()
    /** There was nothing to copy. */
    data object NothingNew : ResultBanner()
    /** Stopped before it reported a result. */
    data object Stopped : ResultBanner()
    /** The copy could not start (e.g. could not connect). */
    data class ConnectFailed(val reason: FailureReason) : ResultBanner()

    val isError: Boolean
        get() = when (this) {
            is Finished -> result.failed > 0
            is ConnectFailed -> true
            NothingNew, Stopped -> false
        }

    val failures: List<CopyFailure> get() = (this as? Finished)?.result?.failures.orEmpty()

    companion object {
        fun from(r: CopyResult): ResultBanner =
            if (r.copied == 0 && r.alreadyPresent == 0 && r.failed == 0 && !r.cancelled) NothingNew else Finished(r)
    }
}
