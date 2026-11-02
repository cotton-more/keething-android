package dev.keething.android.storageprobe

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProbeState(
    /** A fingerprint spike step runs (AD5). */
    val fingerprintBusy: Boolean = false,
    val watchingUnlocks: Boolean = false,
    /** The folder granted for the provider's tests (stage S3). */
    val grantedFolder: String? = null,
    val log: List<String> = emptyList(),
)

/**
 * The debug probe: the fingerprint spike (plans/android-databases.md AD5) and what the storage provider does with names
 * in a granted folder (databases.saf stage S3), each logged.
 */
internal class StorageProbeViewModel(
    private val fingerprint: FingerprintProbe,
    grantProbe: (log: (String) -> Unit) -> GrantProbe,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = mutableState.asStateFlow()

    private val fileWork = Mutex()

    init {
        fingerprint.sink = ::log
        mutableState.update { it.copy(watchingUnlocks = fingerprint.watchingUnlocks) }
    }

    private val grants = grantProbe(::log)

    fun grantFolder(tree: Uri) = io {
        grants.granted(tree)
        mutableState.update { it.copy(grantedFolder = grants.folder?.place?.toString()) }
    }

    fun caseTest() = io { grants.caseTest() }

    fun missingTest() = io { grants.missingTest() }

    /** The fingerprint spike (AD5): one step at a time, on the main thread for the prompt. */
    fun fingerprint(step: suspend FingerprintProbe.() -> Unit) {
        if (state.value.fingerprintBusy) return
        mutableState.update { it.copy(fingerprintBusy = true) }
        viewModelScope.launch {
            runCatching { fingerprint.step() }.onFailure { log("fingerprint: error: $it") }
            mutableState.update { it.copy(fingerprintBusy = false, watchingUnlocks = fingerprint.watchingUnlocks) }
        }
    }

    private fun io(work: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileWork.withLock {
                runCatching { work() }.onFailure { log("error: $it") }
            }
        }
    }

    private fun log(line: String) {
        val stamped = "${LocalTime.now().format(TIME)}  $line"
        Log.i("StorageProbe", line) // file metadata only, never database content
        mutableState.update { it.copy(log = (listOf(stamped) + it.log).take(LOG_LINES)) }
    }

    override fun onCleared() {
        fingerprint.sink = null
    }

    private companion object {
        const val LOG_LINES = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
