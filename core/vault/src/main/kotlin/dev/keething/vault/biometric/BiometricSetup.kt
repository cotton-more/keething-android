package dev.keething.vault.biometric

import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.domain.biometric.BiometricPasswordStores
import dev.keething.domain.biometric.SeveralOpening
import dev.keething.domain.biometric.StoredPasswordOpening
import dev.keething.domain.secret.Secret
import dev.keething.domain.settings.SettingsStore
import dev.keething.domain.time.Clock
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.DatabaseList
import dev.keething.vault.databases.DatabaseRecord
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The fingerprint for the whole app: Settings' switch and days, and which databases have a password stored. */
public data class BiometricSetupState(
    /** The app's switch; off until it is turned on (SPEC §5). */
    val enabled: Boolean,
    /**
     * The app's switch was set, on or off; never set (a new install), a database's question still stands and its Yes
     * turns the app's switch on too (keething-spec unlock.biometric AC1, AC4).
     */
    val set: Boolean = enabled,
    /** Ask for each master password after this many days without it being typed; null: never. */
    val reminderDays: Int? = BiometricState.DEFAULT_REMINDER_DAYS,
    /** Null until the first [BiometricSetup.refresh]. */
    val availability: BiometricAvailability? = null,
    /** The databases with a stored password. */
    val stored: Set<DatabaseId> = emptySet(),
    /** Why a database's last typed unlock could not store its password; gone once one did. */
    val setupFailures: Map<DatabaseId, String> = emptyMap(),
    /** Why a database's stored password went (the fingerprints changed, it was not accepted); gone once one is stored. */
    val setUpAgain: Map<DatabaseId, SetUpAgain> = emptyMap(),
)

/**
 * The fingerprint's part for the whole app (SPEC §5; the Mac `TouchIDUnlock`'s switch and days): the app's switch, the
 * days' choice, and what each database keeps by its id — its stored password, when its password was last typed, and
 * whether its key file changed since an unlock that worked. Each database's own unlock is its [BiometricUnlock].
 *
 * - The fingerprint opens a database while the app's switch and its own Use fingerprint are on. The app's switch allows
 *   a database's to be turned on, and turns none on itself: a database new to the list starts with its own off (the
 *   owner's choice at plan AD5, unlike the Mac's), so no password is stored for a database nobody turned it on for.
 * - Turning the app's switch off deletes every stored password; a database's own Use fingerprint off, or the database
 *   removed from the list, deletes its own (with its days). Choosing another database keeps each one's.
 *
 * Call it on the main thread; the stores' file and keystore work runs on [io], which runs one task at a time in the
 * app, so saves and deletes happen in the order they were asked for.
 */
public class BiometricSetup(
    private val stores: BiometricPasswordStores,
    private val list: DatabaseList,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(
        BiometricSetupState(enabled = settings.string(ENABLED) == ON, set = settings.string(ENABLED) != null, reminderDays = reminderDays()),
    )
    public val state: StateFlow<BiometricSetupState> = mutableState.asStateFlow()

    /**
     * A fingerprint unlock runs, a database's own ([BiometricUnlock]) or the window's ([WindowFingerprint]): one at a
     * time for the app (SPEC §5), from the prompt until what it opened has opened.
     */
    internal var asking: Boolean = false

    init {
        leaveTheOneOfBefore()
    }

    /** Asks the phone again whether it can, and the stores which databases have a password. */
    public suspend fun refresh() {
        val ids = list.records.map(DatabaseRecord::id)
        val availability = withContext(io) { stores.availability() }
        val stored = withContext(io) { ids.filterTo(mutableSetOf()) { runCatching { stores.hasPassword(it.fileName) }.getOrDefault(false) } }
        mutableState.update { it.copy(availability = availability, stored = stored) }
    }

    /**
     * The app's switch, never set until it is turned on or off here, or turned on by a database's question. Turning it on only sets it: each database's password is stored at its next typed unlock, and
     * every database's days count from now. Turning it off deletes every stored password; returns why that failed, or
     * null (the switch is off either way).
     */
    public suspend fun setEnabled(enabled: Boolean): String? {
        if (enabled && !state.value.enabled) {
            // Off, nothing should be stored: what is there is a deletion that failed. It goes first, or the switch stays
            // off: it would open without anything typed since.
            refresh()
            if (state.value.stored.isNotEmpty()) deleteAll()?.let { return it }
        }
        // Off is kept as off: a switch never set lets each database's question turn it on (keething-spec unlock.biometric AC4).
        settings.setString(ENABLED, if (enabled) ON else OFF)
        mutableState.update { it.copy(enabled = enabled, set = true) }
        if (enabled) {
            list.records.forEach { typedNow(it.id) }
            return null
        }
        // Off: nothing is stored whose key file could have changed, nor is anything to be set up.
        list.records.forEach { settings.setString(keyFileChangedKey(it.id), null) }
        mutableState.update { it.copy(setupFailures = emptyMap(), setUpAgain = emptyMap()) }
        return deleteAll()
    }

    /** Ask for each master password after [days] without it being typed; null: never. */
    public fun setReminderDays(days: Int?) {
        settings.setString(REMINDER, days?.toString() ?: NEVER)
        mutableState.update { it.copy(reminderDays = days) }
    }

    /**
     * A database's own Use fingerprint (its page, the lock screen's question), offered while the app's switch is on: the
     * app's switch allows it, it never turns it on (the owner at plan AD5). On: its password is stored at its next typed
     * unlock and its days count from now. Off: its stored password and its days go. Either way it is chosen: the lock
     * screen does not ask it again. Returns why the stored password could not be deleted, or null.
     */
    public suspend fun setUseBiometric(id: DatabaseId, on: Boolean): String? {
        // Off, nothing of it should be stored: a deletion that failed goes first, or its switch stays off.
        if (on && list.record(id)?.useBiometric == false && hasPassword(id)) delete(id)?.let { return it }
        list.update(id) { it.copy(useBiometric = on, fingerprintAsked = true) }
        if (on) {
            typedNow(id)
            return null
        }
        settings.setString(lastTypedKey(id), null)
        settings.setString(reminderPutOffKey(id), null)
        mutableState.update { it.copy(setupFailures = it.setupFailures - id, setUpAgain = it.setUpAgain - id) }
        return delete(id)
    }

    /** No to the lock screen's question (keething-spec unlock.biometric AC4): its switch stays off, and it is not asked again. */
    public fun decline(id: DatabaseId) {
        list.update(id) { it.copy(fingerprintAsked = true) }
    }

    /** The database left the list: its stored password, its days and its key-file note go. Why the deletion failed, or null. */
    public suspend fun forget(id: DatabaseId): String? {
        settings.setString(lastTypedKey(id), null)
        settings.setString(reminderPutOffKey(id), null)
        settings.setString(keyFileChangedKey(id), null)
        mutableState.update { it.copy(setupFailures = it.setupFailures - id, setUpAgain = it.setUpAgain - id) }
        return delete(id)
    }

    /**
     * The database's key file was chosen, changed or forgotten (Settings, the lock screen): until something opens it, a
     * stored password it rejects is kept, as its key file may be what is wrong (keething-spec unlock.key-file AC7; else
     * Forget on a key file the database needs would cost a right password at the next fingerprint).
     */
    public fun keyFileChanged(id: DatabaseId) {
        settings.setString(keyFileChangedKey(id), ON)
    }

    internal fun keyFileChangedSinceOpened(id: DatabaseId): Boolean = settings.string(keyFileChangedKey(id)) != null

    /** An unlock opened the database: a key file changed before is confirmed. */
    internal fun opened(id: DatabaseId) {
        settings.setString(keyFileChangedKey(id), null)
    }

    /** A typed unlock opened it, or its days start again; a question put off is answered. */
    internal fun typedNow(id: DatabaseId) {
        settings.setString(lastTypedKey(id), clock.now().toEpochMilli().toString())
        settings.setString(reminderPutOffKey(id), null)
    }

    /**
     * The reminder is due: [BiometricSetupState.reminderDays] of wall-clock days without the database's password typed
     * (or, for one that opens with its key file alone, without a Yes about its key file), as the user counts them. No
     * time saved yet (a database new to the list) or one in the future (the clock was set back): the days count from
     * now. A database the fingerprint does not open (a switch off) counts nothing: its days start when it is turned on.
     * Due, the fingerprint opens it all the same (keething-spec unlock.reminder-not-a-lock AC1): it is asked once open.
     */
    internal fun isDue(record: DatabaseRecord): Boolean {
        val days = state.value.reminderDays ?: return false
        if (!state.value.enabled || !record.useBiometric) return false
        val now = clock.now()
        val last = settings.string(lastTypedKey(record.id))?.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (last == null || last.isAfter(now)) {
            typedNow(record.id)
            return false
        }
        return Duration.between(last, now) >= Duration.ofDays(days.toLong())
    }

    /**
     * The reminder is due and asks at this opening: not put off within the last day (keething-spec
     * unlock.reminder-not-a-lock AC4; a put-off time in the future, the clock set back, asks).
     */
    internal fun asksNow(record: DatabaseRecord): Boolean {
        if (!isDue(record)) return false
        val now = clock.now()
        val putOff = settings.string(reminderPutOffKey(record.id))?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return true
        return putOff.isAfter(now) || Duration.between(putOff, now) >= Duration.ofDays(1)
    }

    /** Whole days since [id]'s days started (its last typed unlock, or Yes), for the reminder's words; 0 with none saved. */
    public fun daysSince(id: DatabaseId): Long {
        val last = settings.string(lastTypedKey(id))?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return 0
        return Duration.between(last, clock.now()).toDays().coerceAtLeast(0)
    }

    /** The question was put away, Not now, or three tries did not match: asked again a day later at the earliest. */
    internal fun putOff(id: DatabaseId) {
        settings.setString(reminderPutOffKey(id), clock.now().toEpochMilli().toString())
    }

    internal suspend fun availability(): BiometricAvailability =
        withContext(io) { stores.availability() }.also { availability -> mutableState.update { it.copy(availability = availability) } }

    internal suspend fun hasPassword(id: DatabaseId): Boolean {
        val stored = withContext(io) { runCatching { stores.hasPassword(id.fileName) }.getOrDefault(false) }
        noteStored(id, stored)
        return stored
    }

    /** Stores [password] for [id]; returns why that failed, or null. */
    internal suspend fun save(id: DatabaseId, password: Secret): String? {
        val failure = withContext(io) {
            try {
                stores.save(id.fileName, password)
                null
            } catch (failure: Exception) {
                failure.reason()
            }
        }
        noteStored(id, failure == null || withContext(io) { runCatching { stores.hasPassword(id.fileName) }.getOrDefault(false) })
        // Turned off or removed meanwhile: nothing is to be set up, so nothing failed.
        val stillWanted = state.value.enabled && list.record(id)?.useBiometric == true
        mutableState.update {
            it.copy(setupFailures = if (failure == null || !stillWanted) it.setupFailures - id else it.setupFailures + (id to failure))
        }
        return failure
    }

    internal suspend fun open(id: DatabaseId, onRecognised: () -> Unit): StoredPasswordOpening = stores.open(id.fileName, onRecognised)

    internal suspend fun confirm(onRecognised: () -> Unit): StoredPasswordOpening = stores.confirm(onRecognised)

    internal suspend fun openSeveral(ids: List<DatabaseId>, onRecognised: () -> Unit, stillWanted: (DatabaseId) -> Boolean): SeveralOpening {
        val byName = ids.associateBy { it.fileName }
        return stores.openSeveral(ids.map { it.fileName }, onRecognised) { name -> byName[name]?.let(stillWanted) == true }
    }

    /**
     * A backup restored over [id] does not open with the key it was open with (keething-spec databases.backups-window
     * AC7): its stored password goes, and the fingerprint is set up again at the next typed unlock. Why the deletion
     * failed, or null.
     */
    public suspend fun restoredOlderKey(id: DatabaseId): String? {
        if (id !in state.value.stored) return null
        val failure = delete(id)
        setUpAgain(setOf(id), SetUpAgain.PASSWORD_REJECTED)
        return failure
    }

    /** [id]'s stored password; returns why that failed, or null. */
    internal suspend fun delete(id: DatabaseId): String? {
        val failure = withContext(io) {
            try {
                stores.delete(id.fileName)
                null
            } catch (failure: Exception) {
                failure.reason()
            }
        }
        noteStored(id, failure != null && withContext(io) { runCatching { stores.hasPassword(id.fileName) }.getOrDefault(true) })
        return failure
    }

    /** Every database's stored password (the switch off, the fingerprints changed); returns why that failed, or null. */
    internal suspend fun deleteAll(): String? {
        val failure = withContext(io) {
            try {
                stores.deleteAll()
                null
            } catch (failure: Exception) {
                failure.reason()
            }
        }
        if (failure == null) mutableState.update { it.copy(stored = emptySet()) } else refresh()
        return failure
    }

    private fun noteStored(id: DatabaseId, stored: Boolean) {
        mutableState.update { it.copy(stored = if (stored) it.stored + id else it.stored - id, setUpAgain = if (stored) it.setUpAgain - id else it.setUpAgain) }
    }

    /** [ids]' stored passwords went for [why]: their lines say so until one is stored again. */
    internal fun setUpAgain(ids: Set<DatabaseId>, why: SetUpAgain) {
        mutableState.update { it.copy(setUpAgain = it.setUpAgain + ids.associateWith { why }) }
    }

    /** A stored value that is not one of the choices reads as the default (keething-spec unlock.password-reminder AC1). */
    private fun reminderDays(): Int? = when (val saved = settings.string(REMINDER)) {
        NEVER -> null
        else -> saved?.toIntOrNull()?.takeIf { it in BiometricState.REMINDER_CHOICES } ?: BiometricState.DEFAULT_REMINDER_DAYS
    }

    /**
     * Before each database had its own (plan AD5) there was one switch, one time and one key-file note for the app, and
     * the switch was the current database's: it went off whenever another became current. So the current database takes
     * it as its own, the others stay off. The one stored password of then goes with the stores' own (typed once more).
     */
    private fun leaveTheOneOfBefore() {
        if (settings.string(OLD_LAST_TYPED) == null && settings.string(OLD_KEY_FILE_CHANGED) == null) return
        val current = list.currentId
        if (state.value.enabled && current != null) list.update(current) { it.copy(useBiometric = true) }
        settings.setString(OLD_LAST_TYPED, null)
        settings.setString(OLD_KEY_FILE_CHANGED, null)
    }

    private fun Exception.reason(): String = message ?: javaClass.simpleName

    public companion object {
        internal const val ENABLED = "biometricUnlock"
        internal const val REMINDER = "passwordReminderDays"
        private const val ON = "on"
        private const val OFF = "off"
        private const val NEVER = "never"
        private const val OLD_LAST_TYPED = "lastTypedUnlock"
        private const val OLD_KEY_FILE_CHANGED = "biometricKeyFileChanged"

        /** When a typed unlock last opened the database, in milliseconds since 1970 (not a secret). */
        public fun lastTypedKey(id: DatabaseId): String = "lastTypedUnlock.${id.value}" // not user text

        /** When the reminder's question was last left without an answer, in milliseconds since 1970 (not a secret). */
        public fun reminderPutOffKey(id: DatabaseId): String = "reminderPutOff.${id.value}" // not user text

        /** Set while the database's key file was chosen, changed or forgotten and nothing has opened it since. */
        public fun keyFileChangedKey(id: DatabaseId): String = "biometricKeyFileChanged.${id.value}" // not user text
    }
}
