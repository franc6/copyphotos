package app.copyphotos.core

/** One file written to the destination. */
data class CopiedFile(
    /** Path relative to the share root. */
    val remotePath: String,
    val size: Long,
    val sha256Hex: String,
)

/** One verified copy of a device photo or video (all of its files). */
data class CopyRecord(
    /** MediaStore ID of the item. */
    val mediaId: Long,
    /** Human-readable destination, e.g. "smb://nas/Photos". */
    val destination: String,
    val files: List<CopiedFile>,
    /** Epoch millis. */
    val copiedAt: Long,
    /**
     * Size MediaStore reported for the item when it was copied (0 if unknown). MediaStore IDs
     * can be given to a different photo (e.g. after the media database is rebuilt), so a record
     * only counts while the item's size still matches; see [CopyService.changedItems].
     */
    val sourceSize: Long = 0,
)

/**
 * Which device photos have been copied and verified. The ledger only ever describes photos
 * that are still on the device, and nothing here ever deletes anything on the destination.
 */
interface Ledger {
    fun copiedIds(): Set<Long>
    /** [CopyRecord.sourceSize] of every record whose size is known, by ID. */
    fun sourceSizes(): Map<Long, Long>
    fun record(id: Long): CopyRecord?
    fun add(record: CopyRecord)
    /** Forget that these photos were copied (e.g. after they were deleted from the device). */
    fun remove(ids: Collection<Long>)
    /** Drops records for photos that are no longer on the device. Returns how many were removed. */
    fun prune(existing: Set<Long>): Int
    /** Forgets every record. */
    fun clear()
}
