package dev.keepassbar.android.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where small settings are kept: a port, so that the rules around them are tested without Android. */
internal interface SettingsStore {
    fun string(key: String): String?

    fun setString(key: String, value: String?)
}

internal class SharedPreferencesSettingsStore(private val preferences: SharedPreferences) : SettingsStore {
    override fun string(key: String): String? = preferences.getString(key, null)

    override fun setString(key: String, value: String?) = preferences.edit { putString(key, value) }
}

/** The few settings the app has (SPEC §6.2: database file, lock timeout; biometrics come with step 3). */
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

    companion object {
        val DEFAULT_LOCK_TIMEOUT = 5.minutes
        val LOCK_TIMEOUTS = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)

        private const val DATABASE_PATH = "databasePath"
        private const val LOCK_TIMEOUT_MINUTES = "lockTimeoutMinutes"
    }
}
