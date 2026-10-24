package dev.keething.android.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.keething.domain.settings.SettingsStore
import java.io.File
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
 * The few settings the app has (SPEC §6.2: database file, lock timeout, how long a request lasts; the fingerprint's is
 * `BiometricUnlock`'s).
 */
internal class AppSettings(private val store: SettingsStore) {
    private val mutableDatabase = MutableStateFlow(store.string(DATABASE_PATH)?.let(::File))

    /** The database, remembered by its path (SPEC §3.1). */
    val database: StateFlow<File?> = mutableDatabase.asStateFlow()

    fun chooseDatabase(file: File) {
        store.setString(DATABASE_PATH, file.path)
        mutableDatabase.value = file
    }

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

    /** The name a request was last made with: the next one starts with it. */
    var requestName: String
        get() = store.string(REQUEST_NAME).orEmpty()
        set(value) = store.setString(REQUEST_NAME, value.trim().ifEmpty { null })

    /**
     * Entries whose card was told "Not now" to its offer to type their fields (SPEC §6.3), by id: the offer is not
     * made again. Ids only, as Recent keeps them: they say nothing without the database.
     */
    private val dismissedSuggestions: MutableSet<String> =
        store.string(DISMISSED_SUGGESTIONS)?.split(' ')?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

    fun isSuggestionDismissed(entryId: String): Boolean = entryId in dismissedSuggestions

    fun dismissSuggestion(entryId: String) {
        if (dismissedSuggestions.add(entryId)) store.setString(DISMISSED_SUGGESTIONS, dismissedSuggestions.joinToString(" "))
    }

    companion object {
        val DEFAULT_LOCK_TIMEOUT = 5.minutes
        val LOCK_TIMEOUTS = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)
        val DEFAULT_REQUEST_VALIDITY = 3.days
        val REQUEST_VALIDITIES = listOf(1.days, 3.days, 7.days)

        private const val DATABASE_PATH = "databasePath"
        private const val LOCK_TIMEOUT_MINUTES = "lockTimeoutMinutes"
        private const val REQUEST_VALIDITY_DAYS = "requestValidityDays"
        private const val REQUEST_NAME = "requestName"
        private const val DISMISSED_SUGGESTIONS = "dismissedTypeSuggestions"
    }
}
