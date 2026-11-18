package app.copyphotos

import app.copyphotos.core.BrowseLocation
import app.copyphotos.core.CameraMetadata.ShutterSpeed
import app.copyphotos.core.CameraMetadata.VideoResolution
import app.copyphotos.core.CopiedFile
import app.copyphotos.core.CopyEngine
import app.copyphotos.core.CopyItem
import app.copyphotos.core.CopyOptions
import app.copyphotos.core.CopyError
import app.copyphotos.core.CopyFailure
import app.copyphotos.core.CopyRecord
import app.copyphotos.core.CopyResult
import app.copyphotos.core.CopyService
import app.copyphotos.core.CopySource
import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.SmbAuth
import app.copyphotos.core.DestinationProbe
import app.copyphotos.core.DestinationService
import app.copyphotos.core.DestinationStore
import app.copyphotos.core.BrowseListing
import app.copyphotos.core.ProbeResult
import app.copyphotos.core.RemoteConnector
import app.copyphotos.core.ThumbnailFit
import app.copyphotos.core.Ledger
import app.copyphotos.core.MediaLibrary
import app.copyphotos.core.DestinationError
import app.copyphotos.core.FailureReason
import app.copyphotos.core.Digest
import app.copyphotos.core.RemoteEntry
import app.copyphotos.core.RemoteFileSystem
import app.copyphotos.core.RemoteNotFoundException
import app.copyphotos.core.RemotePath
import app.copyphotos.core.ResultBanner
import app.copyphotos.core.browsableEntries
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryFs : RemoteFileSystem {
    val files = mutableMapOf<String, ByteArray>()
    val dirs = mutableSetOf("")
    var corrupt = false
    /** Runs before each move, e.g. to simulate another device taking the name. */
    var beforeMove: ((MemoryFs, String) -> Unit)? = null
    /** When set, replaces what reading a file back returns. */
    var readBackOverride: ((String) -> Digest)? = null

    override fun ensureDirectory(path: String) {
        var cur = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { cur = RemotePath.join(cur, it); dirs += cur }
    }
    override fun sizeOfItem(path: String) = files[path]?.size?.toLong()
    override fun upload(input: InputStream, path: String, progress: (Long) -> Unit) {
        if (RemotePath.parent(path) !in dirs) throw IOException("no dir")
        if (path in files) throw IOException("exists")
        val data = input.readBytes()
        if (corrupt && data.isNotEmpty()) data[0] = (data[0].toInt() xor 0xFF).toByte()
        files[path] = data
        progress(data.size.toLong())
    }
    val readBack = mutableListOf<String>()
    override fun readBackDigest(path: String): Digest {
        readBack += path
        readBackOverride?.let { return it(path) }
        val d = files[path] ?: throw IOException("missing")
        return Digest(MessageDigest.getInstance("SHA-256").digest(d), d.size.toLong())
    }
    val moves = mutableListOf<Pair<String, String>>()
    override fun move(from: String, to: String) {
        beforeMove?.invoke(this, to)
        if (to in files) throw IOException("exists")
        if (RemotePath.parent(to) !in dirs) throw IOException("no dir")
        files[to] = files.remove(from) ?: throw IOException("missing")
        moves += from to to
    }
    override fun removeFile(path: String) { files.remove(path) }
    // Sizes omitted, like some servers: the engine must use sizeOfItem.
    override fun list(path: String): List<RemoteEntry> {
        if (RemotePath.join(path) !in dirs) throw RemoteNotFoundException(path)
        return files.keys.filter { RemotePath.parent(it) == RemotePath.join(path) }
            .map { RemoteEntry(it.substringAfterLast('/'), false, 0) }
    }
    override fun close() {}
}

class MemorySource(private val data: Map<Long, ByteArray>) : CopySource {
    override fun open(item: CopyItem): InputStream = ByteArrayInputStream(data.getValue(item.mediaId))
}

/**
 * The saved destination settings and password, in memory.
 *
 * @param config the saved settings.
 * @param saved the saved password.
 */
class MemoryStore(var config: DestinationConfig = DestinationConfig(), var saved: String = "") : DestinationStore {
    /** Makes [setPassword] fail, as a secure store that refuses the change. */
    var refusesPassword = false
    override fun loadConfig() = config
    override fun saveConfig(config: DestinationConfig) { this.config = config }
    override val hasPassword: Boolean get() = saved.isNotEmpty()
    override fun setPassword(password: String) {
        if (refusesPassword) throw IllegalStateException("refused")
        saved = password
    }
}

/**
 * Connects every share to [fs], and remembers the destinations it was asked for.
 *
 * @param fs the file system every share connects to.
 */
class MemoryConnector(val fs: MemoryFs = MemoryFs()) : RemoteConnector {
    var shares = emptyList<RemoteEntry>()
    val opened = mutableListOf<DestinationConfig>()
    override fun open(config: DestinationConfig): RemoteFileSystem {
        opened += config
        return fs
    }
    override fun listShares(config: DestinationConfig): List<RemoteEntry> {
        opened += config
        return shares
    }
}

/** A destination that is set up: smb://nas/Share, folder P. */
fun completeConfig() = DestinationConfig(host = "nas", share = "Share", path = "P", username = "u")

class MemoryLedger : Ledger {
    val records = mutableMapOf<Long, CopyRecord>()
    override fun copiedIds() = records.keys.toSet()
    override fun sourceSizes() = records.filterValues { it.sourceSize > 0 }.mapValues { it.value.sourceSize }
    override fun record(id: Long) = records[id]
    override fun add(record: CopyRecord) { records[record.mediaId] = record }
    override fun remove(ids: Collection<Long>) { ids.forEach { records.remove(it) } }
    override fun prune(existing: Set<Long>): Int {
        val stale = records.keys - existing
        remove(stale)
        return stale.size
    }
    override fun clear() = records.clear()
}

class PathTests {
    @Test fun joinNeverClimbsAboveTheShare() {
        // Arrange
        val parts = listOf("../Photos", "2026\\..\\11", "..", "a.jpg")

        // Act
        val path = RemotePath.join(parts)

        // Assert
        assertEquals("Photos/2026/11/a.jpg", path)
    }

    @Test fun paths() {
        assertEquals("Photos/2026/11/a.jpg", RemotePath.join("/Photos/", "", "2026/11", "a.jpg"))
        assertEquals("Photos", RemotePath.parent("Photos/a.jpg"))
        assertEquals("", RemotePath.parent("a.jpg"))
        assertEquals("a_b_c_.jpg", RemotePath.sanitizeFileName("a:b*c?.jpg"))
        assertEquals("photo", RemotePath.sanitizeFileName(" .. "))
        assertEquals("IMG_1_2.HEIC", RemotePath.numbered("IMG_1.HEIC", 2))
        assertEquals("noext_1", RemotePath.numbered("noext", 1))
    }
}

class EngineTests {
    private val item = CopyItem(1, "IMG_0001.JPG", 1_762_128_000_000L, isVideo = false)
    private val hello = "hello".toByteArray()

    private fun engine(fs: MemoryFs, ledger: MemoryLedger, data: ByteArray = hello) =
        CopyEngine(fs, MemorySource(mapOf(1L to data)), ledger, CopyOptions("t", "P"))

    @Test fun copiesVerifiesAndRecords() {
        val fs = MemoryFs(); val ledger = MemoryLedger()
        val out = engine(fs, ledger).copyOne(item)
        assertFalse(out.alreadyPresent)
        assertEquals("P/IMG_0001.JPG", out.record.files.single().remotePath)
        assertEquals(setOf("P/IMG_0001.JPG"), fs.files.keys) // written directly under its final name
        assertTrue(1L in ledger.records)
    }

    @Test fun stagedInUploadFolderThenMoved() {
        val fs = MemoryFs(); val ledger = MemoryLedger()
        val out = engine(fs, ledger).copyOne(item)
        assertEquals(1, fs.moves.size)
        assertTrue(fs.moves[0].first.startsWith("P/.upload/"))
        assertEquals("P/IMG_0001.JPG", fs.moves[0].second)
        assertEquals(setOf(out.record.files.single().remotePath), fs.files.keys)
    }

    @Test fun nameTakenBeforeMoveFailsWithoutReplacing() {
        val fs = MemoryFs().apply { beforeMove = { fs, to -> fs.files.putIfAbsent(to, "someone else".toByteArray()) } }
        val ledger = MemoryLedger()
        try { engine(fs, ledger).copyOne(item); fail() } catch (_: IOException) {}
        assertEquals(setOf("P/IMG_0001.JPG"), fs.files.keys) // staging cleaned up
        assertArrayEquals("someone else".toByteArray(), fs.files["P/IMG_0001.JPG"])
        assertFalse(1L in ledger.records)
    }

    @Test fun corruptUploadRejected() {
        val fs = MemoryFs().apply { corrupt = true }; val ledger = MemoryLedger()
        try {
            engine(fs, ledger).copyOne(item); fail()
        } catch (e: CopyError) {
            assertEquals(CopyError.ChecksumMismatch, e)
        }
        assertTrue("file failing verification is removed", fs.files.isEmpty())
        assertFalse(1L in ledger.records)
    }

    @Test fun identicalExistingNotDuplicated() {
        val fs = MemoryFs().apply { ensureDirectory("P"); files["P/IMG_0001.JPG"] = hello.copyOf() }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertTrue(out.alreadyPresent)
        assertEquals(1, fs.files.size)
    }

    @Test fun differentExistingKeepsBoth() {
        val fs = MemoryFs().apply { ensureDirectory("P"); files["P/IMG_0001.JPG"] = "other".toByteArray() }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertEquals("P/IMG_0001_1.JPG", out.record.files.single().remotePath)
        assertArrayEquals("other".toByteArray(), fs.files["P/IMG_0001.JPG"])
    }

    @Test fun differentSizeIsDifferentAndNeverRead() {
        val fs = MemoryFs().apply { ensureDirectory("P"); files["P/IMG_0001.JPG"] = "longer content".toByteArray() }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertEquals("P/IMG_0001_1.JPG", out.record.files.single().remotePath)
        assertFalse("different size must not be read", "P/IMG_0001.JPG" in fs.readBack)
    }

    @Test fun sameSizeDifferentContentIsDifferent() {
        val fs = MemoryFs().apply { ensureDirectory("P"); files["P/IMG_0001.JPG"] = "HELLO".toByteArray() }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertEquals("P/IMG_0001_1.JPG", out.record.files.single().remotePath)
        assertArrayEquals("HELLO".toByteArray(), fs.files["P/IMG_0001.JPG"])
    }

    @Test fun identicalAtLaterNumberIsNotDuplicated() {
        // _1 is free (e.g. deleted), _2 is identical: nothing is written.
        val fs = MemoryFs().apply {
            ensureDirectory("P"); files["P/IMG_0001.JPG"] = "other".toByteArray(); files["P/IMG_0001_2.JPG"] = "hello".toByteArray()
        }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertTrue(out.alreadyPresent)
        assertEquals("P/IMG_0001_2.JPG", out.record.files.single().remotePath)
        assertEquals(2, fs.files.size)
    }

    @Test fun identicalNameDifferingInCaseIsNotDuplicated() {
        val fs = MemoryFs().apply { ensureDirectory("P"); files["P/img_0001.jpg"] = "hello".toByteArray() }
        val out = engine(fs, MemoryLedger()).copyOne(item)
        assertTrue(out.alreadyPresent)
        assertEquals("P/img_0001.jpg", out.record.files.single().remotePath)
    }

    @Test fun recopyAfterDestinationDeletion() {
        val fs = MemoryFs(); val ledger = MemoryLedger()
        val e = engine(fs, ledger)
        e.copyOne(item)
        fs.files.clear() // deleted on the destination: no effect on the ledger
        assertTrue(1L in ledger.records)
        assertFalse(e.copyOne(item).alreadyPresent) // a manual copy-again still works
    }

    @Test fun copyAllContinuesAfterFailure() {
        val fs = MemoryFs(); val ledger = MemoryLedger()
        val source = object : CopySource {
            override fun open(item: CopyItem): InputStream =
                if (item.mediaId == 2L) throw IOException("gone") else ByteArrayInputStream(hello)
        }
        val e = CopyEngine(fs, source, ledger, CopyOptions("t", "P"))
        val s = e.copy(listOf(item, CopyItem(2, "b.jpg", null, isVideo = false), CopyItem(3, "c.jpg", null, isVideo = false)), { false }) {}
        assertEquals(2, s.copied.size)
        assertEquals(listOf(CopyFailure("b.jpg", FailureReason.Other("gone"))), s.failures)
        assertEquals(setOf(1L, 3L), ledger.records.keys)
    }
}

class CameraMetadataTests {
    private val f = app.copyphotos.core.CameraMetadata

    @Test fun shutterAndBias() {
        assertEquals(ShutterSpeed.Fraction(120), f.shutterSpeed(1.0 / 120))
        assertEquals(ShutterSpeed.Fraction(2), f.shutterSpeed(0.5))
        assertEquals(ShutterSpeed.Seconds(0.8), f.shutterSpeed(0.8))
        assertEquals(ShutterSpeed.Seconds(2.5), f.shutterSpeed(2.54))
        assertEquals(null, f.shutterSpeed(0.0))
        assertEquals(0.7, f.exposureBias(0.6666))
        assertEquals(-1.3, f.exposureBias(-1.3))
        assertEquals(0.0, f.exposureBias(-0.04)) // never "-0"
        assertEquals(null, f.exposureBias(Double.NaN))
    }

    @Test fun sizes() {
        assertEquals(12.0, f.megapixels(4032, 3024))
        assertEquals(0.3, f.megapixels(640, 480))
        assertEquals(8.3, f.megapixels(3840, 2160))
        assertEquals(null, f.megapixels(0, 10))
        assertEquals(VideoResolution.Uhd4K, f.videoResolution(3840, 2160))
        assertEquals(VideoResolution.Lines(1080), f.videoResolution(1080, 1920))
        assertEquals(VideoResolution.Uhd8K, f.videoResolution(7680, 4320))
    }

    @Test fun timeZoneOffsetOutOfRangeIsNull() {
        // Arrange
        val offsets = listOf("+19:00", "-02:60")

        // Act
        val parsed = offsets.map { f.timeZoneOffset(it) }

        // Assert
        assertEquals(listOf(null, null), parsed)
    }

    @Test fun zuluTimeZoneOffsetIsZero() {
        // Arrange
        val offset = " Z "

        // Act
        val parsed = f.timeZoneOffset(offset)

        // Assert
        assertEquals(0, parsed)
    }

    @Test fun locationAndZone() {
        assertEquals(48.8584 to 2.2945, f.parseIso6709("+48.8584+002.2945/"))
        assertEquals(37.3317 to -122.031, f.parseIso6709("+37.3317-122.0310+012.5/"))
        assertEquals(null, f.parseIso6709("+00.0000+000.0000/"))
        assertEquals(null, f.parseIso6709("garbage"))
        assertEquals(7200, f.timeZoneOffset("+02:00"))
        assertEquals(-19800, f.timeZoneOffset("-05:30"))
        assertEquals(null, f.timeZoneOffset("+2:00"))
        val utc = java.time.ZoneOffset.UTC
        assertEquals(1344436189000L, f.parseExifDateTime("2012:08:08 14:29:49", null, utc))
        assertEquals(1344436189000L - 7_200_000, f.parseExifDateTime("2012:08:08 14:29:49", 7200, utc))
        assertEquals(null, f.parseExifDateTime("0000:00:00 00:00:00", null, utc))
        assertEquals(null, f.parseExifDateTime(null, null, utc))
    }

    @Test fun names() {
        assertEquals("H.264", f.codecName("video/avc"))
        assertEquals("HEVC", f.codecName("video/hevc"))
        assertEquals("JPEG", f.formatName("image/jpeg", "a.jpg"))
        assertEquals("HEIF", f.formatName("image/heif", "a.heic"))
        assertEquals("DNG", f.formatName(null, "a.dng"))
    }

    @Test fun unknownFormatWithoutExtensionHasNoName() {
        // Arrange
        val fileName = "IMG_0001"

        // Act
        val name = f.formatName("application/octet-stream", fileName)

        // Assert
        assertEquals(null, name)
    }

    @Test fun cameraName() {
        assertEquals("Apple iPhone 15 Pro", f.cameraName("Apple", "iPhone 15 Pro"))
        assertEquals("Google Pixel 8", f.cameraName("Google", "Google Pixel 8"))
        assertEquals("Samsung SM-S918B", f.cameraName("samsung", "SM-S918B"))
        assertEquals("Canon", f.cameraName(" Canon\u0000", null))
        assertEquals("X100V", f.cameraName("", "X100V"))
        assertEquals(null, f.cameraName(" ", null))
    }

    @Test fun placeNameParts() {
        assertEquals(listOf("Cupertino", "California"), f.placeNameParts("Cupertino", "Santa Clara", "California", "USA", "1 Infinite Loop"))
        assertEquals(listOf("Paris", "France"), f.placeNameParts(null, "Paris", null, "France", null))
        assertEquals(listOf("Monaco"), f.placeNameParts("Monaco", null, null, "Monaco", null))
        assertEquals(listOf("Pacific Ocean"), f.placeNameParts(null, null, null, null, "Pacific Ocean"))
        assertEquals(emptyList<String>(), f.placeNameParts(null, null, null, null, null))
    }

    @Test fun thumbnailLooksFlat() {
        assertTrue(ThumbnailFit.looksFlat(List(256) { 240.0 }))
        assertTrue(ThumbnailFit.looksFlat(List(256) { 240.0 + it % 3 }))
        assertFalse(ThumbnailFit.looksFlat(List(256) { if (it % 2 == 0) 0.0 else 255.0 }))
        assertFalse(ThumbnailFit.looksFlat(emptyList()))
        assertEquals(255.0, ThumbnailFit.luma(255, 255, 255), 0.001)
    }
}

class MemoryLibrary(private val items: List<CopyItem>, private val fails: Boolean = false) : MediaLibrary {
    override fun allSizes(): Map<Long, Long> =
        if (fails) throw IOException("cannot read") else items.associate { it.mediaId to it.size }
    override fun copyItems(ids: Collection<Long>) = items.filter { it.mediaId in ids }
}

fun ledgerRecord(id: Long, sourceSize: Long = 0) = CopyRecord(id, "d", listOf(CopiedFile("p$id", 1, "x")), 0, sourceSize)

class CopyServiceTests {
    private val new = CopyItem(3, "c.jpg", 300, false)
    private val old = CopyItem(1, "a.jpg", 100, false)
    private val mid = CopyItem(2, "b.jpg", 200, false)

    private fun service(library: MemoryLibrary, ledger: MemoryLedger, fs: MemoryFs = MemoryFs(), config: DestinationConfig = completeConfig()) =
        CopyService(library, MemorySource(mapOf(1L to "o".toByteArray(), 2L to "m".toByteArray(), 3L to "n".toByteArray())),
            ledger, DestinationService(MemoryStore(config), MemoryConnector(fs)))

    @Test fun uncopiedItemsArePrunedAndOldestFirst() {
        val ledger = MemoryLedger().apply { add(ledgerRecord(2)); add(ledgerRecord(99)) } // 99 is no longer on the device
        val items = service(MemoryLibrary(listOf(new, mid, old)), ledger).uncopiedItems()
        assertEquals(listOf(1L, 3L), items.map { it.mediaId })
        assertEquals(setOf(2L), ledger.copiedIds())
    }

    @Test fun itemsByIdAreOldestFirstEvenIfCopied() {
        val ledger = MemoryLedger().apply { add(ledgerRecord(3)) }
        val items = service(MemoryLibrary(listOf(new, mid, old)), ledger).items(listOf(3L, 1L))
        assertEquals(listOf(1L, 3L), items.map { it.mediaId })
    }

    @Test fun recordForADifferentItemUnderTheSameIdIsForgotten() {
        // ID 1 now belongs to a 900-byte photo; the copied one was 500 bytes. ID 2 is unchanged.
        val ledger = MemoryLedger().apply { add(ledgerRecord(1, sourceSize = 500)); add(ledgerRecord(2, sourceSize = 200)) }
        val library = MemoryLibrary(listOf(old.copy(size = 900), mid.copy(size = 200)))
        assertEquals(setOf(2L), service(library, ledger).copiedIdsOnDevice())
        assertEquals(listOf(1L), service(library, ledger).uncopiedItems().map { it.mediaId })
    }

    @Test fun changedItemsNeedsBothSizesKnown() {
        val recorded = mapOf(1L to 10L, 2L to 20L, 3L to 30L)
        val current = mapOf(1L to 11L, 2L to 0L, 3L to 30L)
        assertEquals(setOf(1L), CopyService.changedItems(recorded, current))
        assertEquals(emptySet<Long>(), CopyService.changedItems(mapOf(4L to 40L), current))
    }

    @Test fun copyRecordsTheItemSize() {
        val ledger = MemoryLedger()
        service(MemoryLibrary(listOf(old.copy(size = 1))), ledger).run(listOf(old.copy(size = 1)), { false }) {}
        assertEquals(1L, ledger.record(1)?.sourceSize)
    }

    @Test fun failedLibraryReadDoesNotPrune() {
        val ledger = MemoryLedger().apply { add(ledgerRecord(1)) }
        assertEquals(setOf(1L), service(MemoryLibrary(emptyList(), fails = true), ledger).copiedIdsOnDevice())
    }

    @Test fun recordDeletedAndForgetHistory() {
        val ledger = MemoryLedger().apply { add(ledgerRecord(1)); add(ledgerRecord(2)) }
        val s = service(MemoryLibrary(emptyList()), ledger)
        s.recordDeleted(listOf(1L))
        assertEquals(setOf(2L), ledger.copiedIds())
        s.forgetHistory()
        assertTrue(ledger.copiedIds().isEmpty())
    }

    @Test fun runCopiesAndRecords() {
        val fs = MemoryFs()
        val ledger = MemoryLedger()
        val summary = service(MemoryLibrary(listOf(old)), ledger, fs).run(listOf(old), { false }) {}
        assertEquals(1, summary.copied.size)
        assertEquals("smb://nas/Share", summary.copied.single().destination)
        assertTrue("P/a.jpg" in fs.files)
    }

    @Test fun runRefusesIncompleteDestination() {
        try {
            service(MemoryLibrary(listOf(old)), MemoryLedger(), config = DestinationConfig()).run(listOf(old), { false }) {}
            fail("expected failure")
        } catch (e: DestinationError) {
            assertEquals(DestinationError.Incomplete, e)
        }
    }

    @Test fun failureReasons() {
        assertEquals(FailureReason.NotFound, FailureReason.of(RemoteNotFoundException("x")))
        assertEquals(FailureReason.Destination(DestinationError.InvalidHost), FailureReason.of(DestinationError.InvalidHost))
        assertEquals(FailureReason.Copy(CopyError.ChecksumMismatch), FailureReason.of(CopyError.ChecksumMismatch))
        assertEquals(FailureReason.Other("boom"), FailureReason.of(IOException("boom")))
    }
}

class DestinationProbeTests {
    @Test fun probeWritesOnlyToStagingAndCleansUp() {
        val fs = MemoryFs()
        DestinationProbe.check(fs, "Photos")
        assertTrue("Photos/.upload" in fs.dirs)
        assertTrue(fs.files.isEmpty())
    }

    @Test fun probeFailsOnBadReadBack() {
        val fs = MemoryFs().apply { readBackOverride = { Digest(ByteArray(0), 0) } }
        try {
            DestinationProbe.check(fs, "P")
            fail("expected failure")
        } catch (e: CopyError.SizeMismatch) {
            assertEquals(0L, e.remote)
        }
        assertTrue(fs.files.isEmpty())
    }
}

class DestinationServiceTests {
    private fun make(config: DestinationConfig = completeConfig(), fs: MemoryFs = MemoryFs()): Triple<DestinationService, MemoryStore, MemoryConnector> {
        val store = MemoryStore(config, saved = "pw")
        val connector = MemoryConnector(fs)
        return Triple(DestinationService(store, connector), store, connector)
    }

    @Test fun connectOpensTheConfiguredDestination() {
        // Arrange
        val (service, _, connector) = make()

        // Act
        service.connect()

        // Assert
        assertEquals(listOf(completeConfig()), connector.opened)
    }

    @Test fun refusedPasswordIsReported() {
        // Arrange
        val (service, store, _) = make()
        store.refusesPassword = true

        // Act
        val saved = service.setPassword("new")

        // Assert
        assertFalse(saved)
        assertEquals("pw", store.saved)
        assertTrue(service.hasPassword.value)
    }

    @Test fun savedPasswordIsReported() {
        // Arrange
        val (service, store, _) = make()

        // Act
        val saved = service.setPassword("new")

        // Assert
        assertTrue(saved)
        assertEquals("new", store.saved)
    }

    @Test fun incompleteDestinationNeverConnects() {
        val (service, _, connector) = make(DestinationConfig())
        assertFalse(service.isReady)
        try {
            service.connect()
            fail("expected failure")
        } catch (e: DestinationError) {
            assertEquals(DestinationError.Incomplete, e)
        }
        assertEquals(ProbeResult.Failed(FailureReason.Destination(DestinationError.Incomplete)), service.test())
        assertTrue(connector.opened.isEmpty())
    }

    @Test fun updateSavesOnlyChanges() {
        val (service, store, _) = make()
        assertFalse(service.update { it.copy(host = "nas") })
        assertTrue(service.update { it.copy(path = "Q") })
        assertEquals("Q", store.config.path)
        assertEquals("smb://nas/Share/Q", service.displayUrl)
        service.setPassword("")
        assertFalse(service.hasPassword.value)
    }

    @Test fun testProvesWriteAccess() {
        val fs = MemoryFs()
        val (service, _, _) = make(fs = fs)
        assertEquals(ProbeResult.Succeeded, service.test())
        assertTrue("P/.upload" in fs.dirs)
    }

    @Test fun browsing() {
        val (service, _, connector) = make()
        connector.shares = listOf(RemoteEntry("b", true, 0), RemoteEntry("C$", true, 0))
        assertEquals(listOf("b"), (service.listing(null) as BrowseListing.Entries).entries.map { it.name })
        val root = BrowseLocation("b", "")
        assertEquals(BrowseListing.Missing, service.listing(root.child("Nope")))
        val made = service.createFolder("New/One", root)
        assertEquals(BrowseLocation("b", "New_One"), made)
        assertTrue(service.listing(made) is BrowseListing.Entries)
        assertTrue(service.choose(made))
        assertEquals("b", service.config.value.share)
        assertEquals("New_One", service.config.value.path)
        assertEquals("smb://nas", service.displayUrl(null))
    }

    @Test fun listingSharesNeedsAHost() {
        val (service, _, _) = make(DestinationConfig())
        assertEquals(BrowseListing.Failed(FailureReason.Destination(DestinationError.InvalidHost)), service.listing(null))
    }
}

class PresentationTests {
    @Test fun resultBanner() {
        val okResult = CopyResult(3, 1, 0, emptyList(), false)
        val ok = ResultBanner.from(okResult)
        assertEquals(ResultBanner.Finished(okResult), ok)
        assertFalse(ok.isError)
        val failure = CopyFailure("a", FailureReason.Other("x"))
        val bad = ResultBanner.from(CopyResult(0, 0, 1, listOf(failure), false))
        assertTrue(bad.isError)
        assertEquals(listOf(failure), bad.failures)
        // A run stopped before copying anything still reports what happened.
        val stopped = CopyResult(0, 0, 0, emptyList(), true)
        assertEquals(ResultBanner.Finished(stopped), ResultBanner.from(stopped))
        assertEquals(ResultBanner.NothingNew, ResultBanner.from(CopyResult(0, 0, 0, emptyList(), false)))
        assertTrue(ResultBanner.ConnectFailed(FailureReason.Destination(DestinationError.TimedOut("nas"))).isError)
        assertFalse(ResultBanner.Stopped.isError)
    }

    @Test fun browseStack() {
        assertEquals(emptyList<BrowseLocation>(), BrowseLocation.stack(DestinationConfig(share = "")))
        val c = DestinationConfig(share = "S", path = "/Photos//Phone/")
        assertEquals(
            listOf(BrowseLocation("S", ""), BrowseLocation("S", "Photos"), BrowseLocation("S", "Photos/Phone")),
            BrowseLocation.stack(c),
        )
        assertEquals("Phone", c.location.name)
        assertEquals("S", c.copy(path = "").location.name)
    }

    @Test fun browsableEntriesHideDotFilesAndPutFoldersFirst() {
        val e = browsableEntries(listOf(
            RemoteEntry("b.jpg", false, 0), RemoteEntry(".upload", true, 0), RemoteEntry("Z", true, 0), RemoteEntry("a.jpg", false, 0),
        ))
        assertEquals(listOf("Z", "a.jpg", "b.jpg"), e.map { it.name })
    }
}

class EncryptionRuleTests {
    @Test fun passwordSignInIsEncryptedGuestAndAnonymousAreNot() {
        assertTrue(DestinationConfig(smbAuth = SmbAuth.USER_PASSWORD).encrypted)
        assertFalse(DestinationConfig(smbAuth = SmbAuth.GUEST).encrypted)
        assertFalse(DestinationConfig(smbAuth = SmbAuth.ANONYMOUS).encrypted)
    }
}
