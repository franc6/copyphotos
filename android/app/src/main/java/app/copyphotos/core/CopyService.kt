package app.copyphotos.core

/** How much of the device's photo library the app may see. */
enum class LibraryAccess {
    /** Not asked yet. */
    NOT_DETERMINED,
    NONE,
    /** Only the photos the user selected. */
    LIMITED,
    FULL;

    val canRead: Boolean get() = this == LIMITED || this == FULL
}

/** The photos and videos on the device. */
interface MediaLibrary {
    /**
     * Every photo and video on the device: ID to the size MediaStore reports (0 if unknown).
     * iOS's `allIDs` returns only IDs; Android needs the sizes too, see [CopyService.changedItems].
     */
    fun allSizes(): Map<Long, Long>
    /** These items, if they are still on the device, in any order. */
    fun copyItems(ids: Collection<Long>): List<CopyItem>
}

/**
 * The copy use cases, shared by the screens and the background work: which photos to copy,
 * running a copy, and keeping the ledger in step with the device. All calls block.
 */
class CopyService(
    private val library: MediaLibrary,
    private val source: CopySource,
    private val ledger: Ledger,
    private val destination: DestinationService,
) {
    /**
     * IDs of copied photos that are still on the device. Photos deleted from the device are
     * forgotten first, so the ledger only ever describes what is still there. If the library
     * can't be read, nothing is forgotten.
     */
    fun copiedIdsOnDevice(): Set<Long> {
        runCatching { library.allSizes() }.onSuccess { runCatching { syncLedger(it) } }
        return ledger.copiedIds()
    }

    /**
     * Photos that have never been copied, oldest first so an interrupted run makes steady
     * progress. Every automatic copy uses this, so nothing is ever copied again automatically.
     */
    fun uncopiedItems(): List<CopyItem> {
        val all = library.allSizes()
        runCatching { syncLedger(all) }
        val uncopied = all.keys - ledger.copiedIds()
        if (uncopied.isEmpty()) return emptyList()
        return oldestFirst(library.copyItems(uncopied))
    }

    /** These photos, oldest first, whether or not they were copied before ("copy again"). */
    fun items(ids: Collection<Long>): List<CopyItem> =
        if (ids.isEmpty()) emptyList() else oldestFirst(library.copyItems(ids))

    fun record(id: Long): CopyRecord? = ledger.record(id)

    /** Copies [items] to the destination, verifying each file. */
    fun run(items: List<CopyItem>, isCancelled: () -> Boolean, progress: (CopyProgress) -> Unit): CopySummary {
        val options = destination.copyOptions
        return destination.connect().use { fs ->
            CopyEngine(fs, source, ledger, options).copy(items, isCancelled, progress)
        }
    }

    /** Forgets all copy history, so automatic copying will copy everything again. Nothing is deleted. */
    fun forgetHistory() = ledger.clear()

    /** These photos left the device, so they leave the ledger. The destination is never touched. */
    fun recordDeleted(ids: Collection<Long>) = ledger.remove(ids)

    /** Forgets photos that left the device, and photos whose ID now belongs to a different item. */
    private fun syncLedger(current: Map<Long, Long>) {
        ledger.prune(current.keys)
        changedItems(ledger.sourceSizes(), current).takeIf { it.isNotEmpty() }?.let(ledger::remove)
    }

    private fun oldestFirst(items: List<CopyItem>) = items.sortedBy { it.takenAtMillis ?: 0 }

    companion object {
        /**
         * IDs whose item is no longer the one that was copied: both sizes are known and differ.
         * MediaStore reuses IDs when its database is rebuilt, and an edit saved over the original
         * changes the size too; either way the photo on the device is not the copy at the destination.
         * iOS doesn't need this: PHAsset local identifiers are UUIDs and are never reused.
         */
        fun changedItems(recorded: Map<Long, Long>, current: Map<Long, Long>): Set<Long> =
            recorded.filter { (id, size) -> current[id]?.let { it > 0 && size > 0 && it != size } == true }.keys
    }
}

