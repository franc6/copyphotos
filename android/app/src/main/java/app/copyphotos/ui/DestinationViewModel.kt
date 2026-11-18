package app.copyphotos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.copyphotos.core.BrowseListing
import app.copyphotos.core.BrowseLocation
import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.DestinationService
import app.copyphotos.core.ProbeResult
import app.copyphotos.work.CopyScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller for the Destination screen: the settings form, testing the connection, and the
 * location browser. The settings, the password and the connections belong to
 * [DestinationService]; this screen is the one place that shows the settings themselves.
 */
class DestinationViewModel(
    private val destination: DestinationService,
    /** Reschedules automatic copying when the settings change. */
    private val scheduler: CopyScheduler,
) : ViewModel() {
    val config: StateFlow<DestinationConfig> = destination.config
    val hasPassword: StateFlow<Boolean> = destination.hasPassword

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _passwordNotSaved = MutableStateFlow(false)
    /** The secure store refused the last password typed. */
    val passwordNotSaved: StateFlow<Boolean> = _passwordNotSaved.asStateFlow()

    private val _testResult = MutableStateFlow<ProbeResult?>(null)
    val testResult: StateFlow<ProbeResult?> = _testResult.asStateFlow()

    /** Changes the settings and reschedules automatic copying. */
    fun update(transform: (DestinationConfig) -> DestinationConfig) {
        if (destination.update(transform)) scheduler.applyAutoCopy(destination.config.value)
    }

    /**
     * Saves the password as it is typed; an empty one removes it.
     * @param value the password typed so far.
     */
    fun setPassword(value: String) {
        _passwordNotSaved.value = !destination.setPassword(value)
    }

    fun testConnection() {
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            _testResult.value = withContext(Dispatchers.IO) { destination.test() }
            _testing.value = false
        }
    }

    // region Location browser

    /** Where the browser opens: the folder already chosen, with its parents on the back stack. */
    fun browseStack(): List<BrowseLocation> = destination.browseStack()

    /** The server's shares ([location] null) or the contents of a folder. */
    suspend fun listing(location: BrowseLocation?): BrowseListing =
        withContext(Dispatchers.IO) { destination.listing(location) }

    /** Creates a folder in [location] and returns it. */
    suspend fun createFolder(name: String, location: BrowseLocation): Result<BrowseLocation> =
        withContext(Dispatchers.IO) { runCatching { destination.createFolder(name, location) } }

    /** Sets the share and the folder together, and closes the browser's connections. */
    fun choose(location: BrowseLocation) {
        if (destination.choose(location)) scheduler.applyAutoCopy(destination.config.value)
    }

    fun displayUrl(location: BrowseLocation?): String = destination.displayUrl(location)

    fun closeBrowser() = destination.endBrowsing()

    // endregion

    override fun onCleared() {
        closeBrowser()
    }
}
