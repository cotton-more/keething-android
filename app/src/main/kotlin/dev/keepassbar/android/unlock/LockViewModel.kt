package dev.keepassbar.android.unlock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.storage.AllFilesAccess
import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.vault.session.SessionState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The database file as the lock screen describes it. */
internal data class FileInfo(val exists: Boolean, val modified: Instant?)

internal class LockViewModel(private val database: DatabaseSession) : ViewModel() {
    /**
     * The typed master password. Kept here, not in the screen's saved state: a saved state would put it
     * into a Bundle. The `String`s the field makes cannot be wiped (accepted, SPEC §4); the field is
     * cleared as soon as the unlock starts.
     */
    val password = TextFieldState()

    val session: StateFlow<SessionState> = database.session.state

    private val mutableFileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = mutableFileInfo.asStateFlow()

    private val mutableAccessGranted = MutableStateFlow(AllFilesAccess.isGranted())
    val accessGranted: StateFlow<Boolean> = mutableAccessGranted.asStateFlow()

    /** On every return to the screen: the access may have been granted, the file may have changed. */
    fun refresh() {
        mutableAccessGranted.value = AllFilesAccess.isGranted()
        viewModelScope.launch {
            mutableFileInfo.value = withContext(Dispatchers.IO) {
                val version = runCatching { database.store.version() }.getOrNull()
                FileInfo(exists = version != null, modified = version?.modified)
            }
        }
    }

    fun unlock() {
        if (password.text.isEmpty()) return
        val secret = Secret.of(password.text.toString())
        database.session.unlock(secret)
        secret.wipe()
        password.clearText()
    }

    override fun onCleared() {
        password.clearText()
    }
}
