package app.copyphotos.ui

import androidx.lifecycle.ViewModel
import app.copyphotos.core.CopyRecord
import app.copyphotos.core.CopyService
import app.copyphotos.media.MediaDetails
import app.copyphotos.media.MediaDetailsReader
import app.copyphotos.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Controller for the photo Details panel: what the photo is, where it was taken, and whether
 * (and where) CopyPhotos copied it.
 */
class PhotoDetailsViewModel(
    private val service: CopyService,
    private val reader: MediaDetailsReader,
) : ViewModel() {
    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private val _place = MutableStateFlow<List<String>>(emptyList())
    /** The parts of the place name ("Paris", "France"); empty while unknown. */
    val place: StateFlow<List<String>> = _place.asStateFlow()

    private val _record = MutableStateFlow<CopyRecord?>(null)
    val record: StateFlow<CopyRecord?> = _record.asStateFlow()

    private val _itemId = MutableStateFlow<Long?>(null)
    /** The item the state above belongs to. */
    val itemId: StateFlow<Long?> = _itemId.asStateFlow()

    private var item: MediaItem? = null

    /** Shows [item]: its details, then the place name; and the copy record. */
    suspend fun load(item: MediaItem) {
        this.item = item
        _itemId.value = item.id
        _record.value = null
        _details.value = null
        _place.value = emptyList()
        reloadRecord()
        val d = withContext(Dispatchers.IO) { reader.read(item) }
        if (this.item != item) return
        _details.value = d
        if (d.latitude != null && d.longitude != null) {
            val place = withContext(Dispatchers.IO) { reader.placeName(d.latitude, d.longitude) }
            if (this.item == item) _place.value = place
        }
    }

    /** Reads the copy record again (after the photo was copied or its history forgotten). */
    suspend fun reloadRecord() {
        val id = item?.id ?: return
        _record.value = withContext(Dispatchers.IO) { service.record(id) }
    }
}
