package dev.keething.android.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.keething.domain.settings.SettingsStore
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.StoredEntryIds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SharedPreferencesSettingsStore(private val preferences: SharedPreferences) : SettingsStore {
    override fun string(key: String): String? = preferences.getString(key, null)

    override fun setString(key: String, value: String?) = preferences.edit { putString(key, value) }
}

/**
 * The few settings the app has (SPEC §6.2: lock timeout, how long a request lasts; the databases are `DatabaseList`'s,
 * the fingerprint's `BiometricUnlock`'s). The single database path of before the list (`databasePath`) is left as it
 * was and read no more.
 */
internal class AppSettings(private val store: SettingsStore) {
    private val mutableLockTimeout = MutableStateFlow(
        store.string(LOCK_TIMEOUT_MINUTES)?.toIntOrNull()?.minutes?.takeIf { it in LOCK_TIMEOUTS } ?: DEFAULT_LOCK_TIMEOUT,
    )

    /** How long the app may stay in the background before it locks (SPEC §4). */
    val lockTimeout: StateFlow<Duration> = mutableLockTimeout.asStateFlow()

    fun chooseLockTimeout(timeout: Duration) {
        require(timeout in LOCK_TIMEOUTS)
        store.setString(LOCK_TIMEOUT_MINUTES, timeout.inWholeMinutes.toString())
        mutableLockTimeout.value = timeout
    }

    private val mutableRequestValidity = MutableStateFlow(
        store.string(REQUEST_VALIDITY_DAYS)?.toIntOrNull()?.days?.takeIf { it in REQUEST_VALIDITIES } ?: DEFAULT_REQUEST_VALIDITY,
    )

    /** How long a request for entries stays valid (SPEC §10); then this phone forgets its key. */
    val requestValidity: StateFlow<Duration> = mutableRequestValidity.asStateFlow()

    fun chooseRequestValidity(validity: Duration) {
        require(validity in REQUEST_VALIDITIES)
        store.setString(REQUEST_VALIDITY_DAYS, validity.inWholeDays.toString())
        mutableRequestValidity.value = validity
    }

    private val mutableTheme = MutableStateFlow(ThemeChoice.of(store.string(THEME)))

    /** Light, dark or as the system (keething-spec app.theme-choice); the system is told by whoever chooses it. */
    val theme: StateFlow<ThemeChoice> = mutableTheme.asStateFlow()

    fun chooseTheme(choice: ThemeChoice) {
        store.setString(THEME, choice.stored.takeIf { choice != ThemeChoice.System })
        mutableTheme.value = choice
    }

    /** The name a request was last made with: the next one starts with it. */
    var requestName: String
        get() = store.string(REQUEST_NAME).orEmpty()
        set(value) = store.setString(REQUEST_NAME, value.trim().ifEmpty { null })

    /**
     * The database the last new entry went into (keething-spec databases.one-list AC4): the next one starts there when
     * nothing else says where. Kept across starts, as the process may end between two.
     */
    var lastNewEntryDatabase: DatabaseId?
        get() = store.string(LAST_NEW_ENTRY_DATABASE)?.let(::DatabaseId)
        set(value) = store.setString(LAST_NEW_ENTRY_DATABASE, value?.value)

    private val mutableQuickNoteDatabase = MutableStateFlow(store.string(QUICK_NOTE_DATABASE)?.takeIf { it.isNotEmpty() }?.let(::DatabaseId))

    /**
     * Where quick notes go (keething-spec app.quick-note AC1; SPEC §6.4): the database's id, nothing else; null, the
     * current database ([dev.keething.vault.databases.QuickNoteTarget]). Kept while that database leaves the list:
     * Settings says so, and quick notes go to the current one until another is chosen.
     */
    val quickNoteDatabase: StateFlow<DatabaseId?> = mutableQuickNoteDatabase.asStateFlow()

    fun chooseQuickNoteDatabase(id: DatabaseId?) {
        store.setString(QUICK_NOTE_DATABASE, id?.value)
        mutableQuickNoteDatabase.value = id
    }

    /**
     * Entries whose card was told "Not now" to its offer to type their fields (SPEC §6.3), by ref
     * ([dev.keething.vault.databases.EntryRef]): the offer is not made again, and the same UUID in another database is
     * another entry. Refs only, as Recent keeps them: they say nothing without the database.
     */
    private val dismissedSuggestions: MutableSet<String> =
        store.string(DISMISSED_SUGGESTIONS)?.split(' ')?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

    fun isSuggestionDismissed(ref: String): Boolean = ref in dismissedSuggestions

    fun dismissSuggestion(ref: String) {
        if (dismissedSuggestions.add(ref)) saveDismissed()
    }

    /** The declined offers follow the open databases as Recent does ([StoredEntryIds]). */
    fun keepDeclined(stored: StoredEntryIds) {
        val kept = stored.keep(dismissedSuggestions.toList()).toSet()
        if (kept == dismissedSuggestions) return
        dismissedSuggestions.clear()
        dismissedSuggestions += kept
        saveDismissed()
    }

    private fun saveDismissed() {
        store.setString(DISMISSED_SUGGESTIONS, dismissedSuggestions.joinToString(" ").ifEmpty { null })
    }

    companion object {
        val DEFAULT_LOCK_TIMEOUT = 5.minutes
        val LOCK_TIMEOUTS = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)
        val DEFAULT_REQUEST_VALIDITY = 3.days
        val REQUEST_VALIDITIES = listOf(1.days, 3.days, 7.days)

        private const val LOCK_TIMEOUT_MINUTES = "lockTimeoutMinutes" // not user text
        private const val REQUEST_VALIDITY_DAYS = "requestValidityDays" // not user text
        private const val REQUEST_NAME = "requestName" // not user text
        private const val THEME = "theme"
        private const val DISMISSED_SUGGESTIONS = "dismissedTypeSuggestions" // not user text
        private const val LAST_NEW_ENTRY_DATABASE = "lastNewEntryDatabase" // not user text
        private const val QUICK_NOTE_DATABASE = "quickNoteDatabase" // not user text
    }
}
