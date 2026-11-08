package dev.keething.android.databases

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.generator.RandomSource
import dev.keething.generator.SystemRandom
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.databases.FileLocation
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What New database asks of the files (the Mac's `DatabaseCreating`): measuring this phone, key files, the database
 * itself, and a note of what is being made, so that what an ended process left half-made goes at the next start
 * ([CreationNote]). Each call blocks: called on the io dispatcher. Failures are [CreateFailure]s.
 */
internal interface DatabaseMaking {
    /** The iterations (or rounds) for [encryption]'s time on this phone; [checkCancelled] throws to stop between runs. */
    fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit): MeasuredEncryption

    /** Creating [name] begins: what is made from here on is noted until [end]. */
    fun begin(name: String)

    /** A new key file at [path], never over a file there; its key, which the caller wipes. */
    fun createKeyFile(path: String): Secret

    /** The key of a key file the user has; the caller wipes it. */
    fun readKeyFile(path: String): Secret

    /** Removes the key file [createKeyFile] made at [path], and only that one: its database was not made. */
    fun removeKeyFile(path: String)

    /** Writes the empty database at [path], never over a file there; the key it was made with, derived. */
    fun create(path: String, name: String, password: Secret, keyFile: Secret?, measured: MeasuredEncryption): DerivedKey

    /**
     * Creating is over. [made]: the files are the user's, the note of them goes. Not made: what could not be removed
     * stays noted, for the next start.
     */
    fun end(made: Boolean)
}

/**
 * What New database looks at on the phone's storage, through the folders and files the user granted (keething-spec
 * databases.saf AC11): each named by its path on the phone, the form's words. Called while the screen draws: what is
 * read is kept until [lookAgain].
 */
internal interface NewDatabaseFiles {
    /** Anything at [path], a file or a folder, its name case aside; false where no grant reaches. */
    fun exists(path: String): Boolean

    fun isFolder(path: String): Boolean

    /** [folder] is in a folder Syncthing syncs: it, or one above it the grant reaches, has Syncthing's `.stfolder` marker. */
    fun syncs(folder: String): Boolean

    /** What was read is read again: a file made or moved meanwhile. */
    fun lookAgain() {}

    /** [read] is called on the main thread whenever something asked before is known: the form asks again. */
    fun whenRead(read: () -> Unit) {}
}

/** A database just made, for the app to open (keething-spec databases.new-database AC8). */
internal class CreatedDatabase(
    /** Where it was made, by its path on the phone (the form's words): the app knows which grant reaches it. */
    val path: String,
    val keyFilePath: String?,
    /** The key it was made with, derived: its first opening derives nothing. */
    val key: DerivedKey,
    /** The master password typed, for the fingerprint to store when [useFingerprint]; wiped after [NewDatabaseModel.onCreated]. */
    val password: Secret,
    val useFingerprint: Boolean,
    /** The form offered the fingerprint: chosen either way, its lock screen does not ask it (keething-spec unlock.biometric AC4). */
    val fingerprintChosen: Boolean,
    /** The app's lock count when Create was tapped: a lock since, and it is not opened. */
    val lockCount: Int,
)

/**
 * The New database screen (SPEC §6.2; keething-spec databases.new-database, databases.new-database-create; the Mac's
 * `NewDatabaseModel`, D9): the name (it makes the file name and is written into the file), Save in, the master password
 * twice with its strength and one eye for both, a suggested passphrase, a key file to also require (made new or one the
 * user has), the fingerprint, the encryption line and its page. Create measures this phone, writes the file and reads
 * it back, then the app opens it. A file already there is never replaced.
 *
 * It lives in the app's [scope], not a screen's: Create goes on when the screen is gone (the app removed from recents
 * keeps its process for the short foreground service, plan AD7). Call it on the main thread.
 */
internal class NewDatabaseModel(
    private val making: DatabaseMaking,
    /**
     * The folders granted for the list's databases, the current one's first: where Save in starts (keething-spec
     * databases.saf AC11). None: a folder is chosen first.
     */
    folders: List<String>,
    /** The shared storage's root, for the words ("Sync/KeePass"). */
    private val root: String,
    private val files: NewDatabaseFiles,
    /** The app's Unlock with the fingerprint is on: the new database's own switch is offered, checked. */
    val offersFingerprint: Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** The app's lock count (the button, the timeout), read when Create is tapped. */
    private val lockCount: () -> Int,
    private val random: RandomSource = SystemRandom,
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    /** How long the Encryption page waits after the last change before it measures (a stepper tapped through). */
    private val measureDelay: Duration = 300.milliseconds,
) {
    enum class Page { BASIC, ENCRYPTION }

    sealed interface Phase {
        data object Editing : Phase

        /** Measuring this phone (Cancel stops it), then writing the file (nothing stops that). */
        data class Creating(val writing: Boolean) : Phase

        /** Made and handed to the app. */
        data object Created : Phase
    }

    /** A key file to also require. */
    sealed interface KeyFileChoice {
        /** Made at Create in [folder], named as the database. */
        data class New(val folder: String) : KeyFileChoice

        /** One the user has. */
        data class Existing(val path: String) : KeyFileChoice
    }

    /** A folder of Save in, granted; [note]: "this database’s" for the current one's. */
    data class Folder(val path: String, val title: String, val note: String? = null)

    /** After Create: the app opens it (the list, its session) and the screen closes. */
    var onCreated: suspend (CreatedDatabase) -> Unit = {}

    /** Creating begins (true) and ends (false): the app keeps its process alive meanwhile. */
    var onCreating: (Boolean) -> Unit = {}

    /** The form closed without a database made: the app gives back what was granted for it alone. */
    var onClosed: () -> Unit = {}

    /** A folder granted in a picker of the form: the path the form names it by (the app keeps which grant reaches it). */
    var folderGranted: (GrantedPlace) -> String = { it.place.toString() }

    /** A key file granted alone in the form's picker: the path the form names it by. */
    var keyFileGranted: (FileLocation.Granted) -> String = { it.at.toString() }

    var name: String by mutableStateOf("")
        private set

    /** Null until one is chosen: nothing is granted yet. */
    var folder: String? by mutableStateOf(folders.firstOrNull())
        private set

    var folders: List<Folder> by mutableStateOf(emptyList())
        private set

    /** The master password twice, its strength and a suggested passphrase. */
    val fields = MasterPasswordFields(random) { listOf(databaseName) }

    val password: TextFieldState get() = fields.password
    val again: TextFieldState get() = fields.again

    /** The eye: both fields in clear. */
    var reveals: Boolean by fields::reveals

    /** A passphrase offered, shown once in clear: Another, Use this. */
    val suggestion: String? get() = fields.suggestion

    val passphraseWords: Int get() = fields.passphraseWords

    var requiresKeyFile: Boolean by mutableStateOf(false)
        private set

    var keyFile: KeyFileChoice? by mutableStateOf(null)
        private set

    var useFingerprint: Boolean by mutableStateOf(offersFingerprint)

    var encryption: DatabaseEncryption by mutableStateOf(DatabaseEncryption.recommended(cores))
        private set

    var page: Page by mutableStateOf(Page.BASIC)
        private set

    var phase: Phase by mutableStateOf(Phase.Editing)
        private set

    /** The measurement of [encryption] (or of its kind, scaled to its time): the Encryption page's line. */
    var measured: MeasuredEncryption? by mutableStateOf(null)
        private set

    var isMeasuring: Boolean by mutableStateOf(false)
        private set

    /** Why the Encryption page's last measurement failed, until the next one. */
    private var measureFailure: String? by mutableStateOf(null)

    /** What the last Create could not do, and what was in the form then: shown until something changes. */
    private var failure: Failure? by mutableStateOf(null)

    private class Failure(val text: String, val onName: Boolean, val form: Int)

    /** Counts [lookAgain]: what is read of the files is read again. */
    private var looked by mutableIntStateOf(0)

    /** The screen is shown again: a file made or moved meanwhile. */
    fun lookAgain() {
        files.lookAgain()
        looked++
    }

    /** What is at [path] now; read again after [lookAgain]. */
    private fun there(path: String): Boolean = looked.let { files.exists(path) }

    private fun folderThere(path: String): Boolean = looked.let { files.isFolder(path) }

    /** A real measurement, which a change of the time alone scales. */
    private var baseline: MeasuredEncryption? = null
    private var measuring: Job? = null
    private var creation: Job? = null

    init {
        files.whenRead { looked++ }
        val paths = folders.map { File(it).path }.distinct()
        this.folders = paths.mapIndexed { i, path -> folderAt(path, isCurrent = i == 0) }
    }

    // Name and place

    /** The name as written into the file (`Meta/DatabaseName`, the root group): trimmed, a typed `.kdbx` dropped. */
    val databaseName: String
        get() {
            var trimmed = name.trim()
            if (trimmed.lowercase(Locale.ROOT).endsWith(".kdbx")) trimmed = trimmed.dropLast(5).trim()
            return trimmed.filterNot { it.isISOControl() }
        }

    /** The file's name: `/` and `:` as `-`, no leading dot (a hidden file), and `.kdbx`; null without a name. */
    val fileName: String?
        get() {
            val base = databaseName.replace('/', '-').replace(':', '-').trimStart('.').trim()
            return if (base.isEmpty()) null else "$base.kdbx"
        }

    /** Where it will be; null without a name. */
    val path: String? get() = folder?.let { folder -> fileName?.let { File(folder, it).path } }

    /** Under Save in: "Sync/KeePass/Family Shared.kdbx" (the folder and "…" while there is no name). */
    val pathText: String
        get() = path?.let(::shown) ?: folder?.let { "${shown(it)}/…" } ?: "Keething saves only in a folder you choose."

    /** A file is already where it would go (or Create found one): the name is red, and the line says why. */
    val nameIsTaken: Boolean get() = path?.let(::there) == true || failure?.takeIf { it.form == form() }?.onName == true

    fun changeName(text: String) {
        name = text
    }

    fun chooseFolder(path: String) {
        val chosen = File(path).path
        if (folders.none { it.path == chosen }) folders = folders + folderAt(chosen, isCurrent = false)
        folder = chosen
    }

    // The key

    /** Null while the second field is empty. */
    val match: MasterPasswordFields.Match? get() = fields.match

    /** Null while nothing is typed. A weak one is allowed, and said (the Mac's D9). */
    val strength: MasterPasswordFields.StrengthLine? get() = fields.strength

    fun suggest() = fields.suggest()

    fun changeWords(count: Int) = fields.changeWords(count)

    fun useSuggestion() = fields.useSuggestion()

    fun closeSuggestion() = fields.closeSuggestion()

    fun requireKeyFile(on: Boolean) {
        requiresKeyFile = on
    }

    fun chooseKeyFile(choice: KeyFileChoice) {
        keyFile = when (choice) {
            is KeyFileChoice.New -> KeyFileChoice.New(File(choice.folder).path)
            is KeyFileChoice.Existing -> KeyFileChoice.Existing(File(choice.path).path)
        }
        requiresKeyFile = true
    }

    /** Change…: back to Make a new one… and Use one I have…. */
    fun forgetKeyFile() {
        keyFile = null
    }

    /** The key file Create makes or uses; a new one is named as the database ("Family Shared.keyx"), null until named. */
    val keyFilePath: String?
        get() = when (val choice = keyFile?.takeIf { requiresKeyFile }) {
            is KeyFileChoice.New -> fileName?.let { File(choice.folder, it.removeSuffix(".kdbx") + ".keyx").path }
            is KeyFileChoice.Existing -> choice.path
            null -> null
        }

    /** The chosen key file's name ("Family Shared.keyx"; "….keyx" before a name). */
    val keyFileName: String?
        get() {
            val choice = keyFile?.takeIf { requiresKeyFile } ?: return null
            return keyFilePath?.let { File(it).name } ?: (choice as? KeyFileChoice.New)?.let { "….keyx" }
        }

    /** Under its name: "Documents · made at Create", "Keys". */
    val keyFileDetail: String?
        get() = when (val choice = keyFile?.takeIf { requiresKeyFile }) {
            is KeyFileChoice.New -> "${shown(choice.folder)} · made at Create"
            is KeyFileChoice.Existing -> shown(File(choice.path).parent ?: "/")
            null -> null
        }

    /** A new key file that cannot be made there (a file in its place, its folder gone), or one to use that is not there. */
    val keyFileProblem: String?
        get() {
            val choice = keyFile?.takeIf { requiresKeyFile } ?: return null
            return when (choice) {
                is KeyFileChoice.New -> when {
                    !folderThere(choice.folder) -> "The key file's folder, ${shown(choice.folder)}, is not there any more."
                    keyFilePath?.let(::there) == true ->
                        "There is already a ${keyFileName} in ${shown(choice.folder)}. A key file is never replaced: choose another folder."
                    else -> null
                }
                is KeyFileChoice.Existing -> if (there(choice.path)) null else "${File(choice.path).name} is not there any more."
            }
        }

    /** A key file kept with the database protects little: whoever gets the folder gets both. */
    val keyFileWarning: String?
        get() {
            val keyPath = keyFilePath ?: return null
            if (keyFileProblem != null) return null
            val keyFolder = File(keyPath).parent ?: return null
            return when {
                keyFolder == folder -> "Beside the database, whoever gets the folder gets both. Keep it apart: a folder that does not sync."
                files.syncs(keyFolder) -> "In a folder that syncs, it goes wherever the database goes. Keep it apart: a folder that does not sync."
                else -> null
            }
        }

    // Encryption (keething-spec databases.new-database-create)

    /** "KDBX 4 · AES-256 · Argon2d · opens in about 1 s on this phone · read by every current KeePass app". */
    val encryptionLine: String
        get() {
            val e = encryption.clamped
            val seconds = measured?.takeIf { it.encryption == e }?.seconds ?: e.seconds
            val readers = if (e.keyDerivation == DatabaseEncryption.KeyDerivation.ARGON2ID) {
                "read by KeePass 2.47 and later and every current KeePass app"
            } else {
                "read by every current KeePass app"
            }
            return "KDBX 4 · ${e.cipher.label} · ${e.keyDerivation.label} · opens in about ${seconds(seconds)} on this phone · $readers"
        }

    val isRecommended: Boolean get() = encryption.clamped == DatabaseEncryption.recommended(cores)

    /** Back to recommended. */
    fun recommend() {
        changeEncryption(DatabaseEncryption.recommended(cores))
    }

    /** Under the time on the Encryption page: "1.0 s on this phone: 16 iterations, measured. A computer usually opens it faster." */
    val measuredLine: String
        get() {
            val measured = measured?.takeIf { it.encryption == encryption.clamped }
                ?: return measureFailure?.takeUnless { isMeasuring } ?: "Measuring on this phone…"
            val count = String.format(Locale.US, "%,d", measured.units)
            val unit = when {
                measured.encryption.keyDerivation == DatabaseEncryption.KeyDerivation.AES_KDF -> "rounds"
                measured.units == 1L -> "iteration"
                else -> "iterations"
            }
            return "${String.format(Locale.US, "%.1f", measured.seconds)} s on this phone: $count $unit, measured. A computer usually opens it faster."
        }

    fun changeEncryption(changed: DatabaseEncryption) {
        if (changed == encryption) return
        encryption = changed
        encryptionChanged()
    }

    fun showPage(shown: Page) {
        if (shown == page) return
        page = shown
        if (shown == Page.ENCRYPTION) measureForPage()
    }

    // Create

    /** What keeps Create from being tapped, the first first; empty: it can. */
    val blockers: List<String>
        get() {
            val why = mutableListOf<String>()
            val folder = folder
            if (folder == null) why += "Choose the folder to save it in."
            val fileName = fileName
            if (fileName == null) why += "Name it."
            // A file name has 255 bytes at most (UTF-8: a Cyrillic letter is two).
            if (fileName != null && fileName.encodeToByteArray().size > 255) why += "The name is too long for a file name: shorten it."
            if (nameIsTaken && fileName != null) why += takenText(fileName)
            if (folder != null && !folderThere(folder)) why += "That folder is not there any more. Choose another."
            fields.blocker?.let { why += it }
            if (requiresKeyFile && keyFile == null) why += "Make a key file, or choose one you have."
            keyFileProblem?.let { why += it }
            return why
        }

    val canCreate: Boolean get() = phase == Phase.Editing && blockers.isEmpty()

    /** The red line over Create: what Create could not do, or a file already there. */
    val problem: String?
        get() {
            failure?.takeIf { it.form == form() }?.let { return it.text }
            val fileName = fileName
            return if (nameIsTaken && fileName != null) takenText(fileName) else null
        }

    /** "Creating Family Shared…". */
    val creatingTitle: String get() = "Creating $databaseName…"

    /** "Measuring this phone’s speed for 1 s to open, then writing the file and reading it back." */
    val creatingDetail: String
        get() = if (phase == Phase.Creating(writing = true)) {
            "Writing the file and reading it back."
        } else {
            "Measuring this phone’s speed for ${seconds(encryption.clamped.seconds)} to open, then writing the file and reading it back."
        }

    /** The file (and a new key file) is being written: nothing stops it, the screen stays. */
    val isWriting: Boolean get() = phase == Phase.Creating(writing = true)

    /** Cancel: stops measuring (back to the form); nothing stops the writing. */
    val canCancel: Boolean get() = phase == Phase.Creating(writing = false)

    /**
     * Measures this phone (unless the Encryption page did), makes the key file, writes the database, reads it back, then
     * hands it to the app. The password is taken from the field at once. Its job (the tests wait for it).
     */
    fun create(): Job? {
        val path = path
        if (!canCreate || path == null) return null
        failure = null
        val secret = Secret.of(password.text.toString())
        val name = databaseName
        val keyFile = keyFile?.takeIf { requiresKeyFile }
        val keyFilePath = keyFilePath
        val useFingerprint = offersFingerprint && useFingerprint
        val locks = lockCount()
        // A measurement of the page still running is waited for: two at once would each measure a slower phone.
        val previous = measuring
        previous?.cancel()
        isMeasuring = false
        phase = Phase.Creating(writing = false)
        onCreating(true)
        val job = scope.launch {
            try {
                previous?.join()
                run(path, name, secret, keyFile, keyFilePath, useFingerprint, locks)
            } catch (cancelled: CancellationException) {
                // Cancel while measuring (or waiting for the page's measurement): back to the form, nothing made.
                if (phase == Phase.Creating(writing = false)) phase = Phase.Editing
                throw cancelled
            } finally {
                secret.wipe()
                onCreating(false)
            }
        }
        creation = job
        return job
    }

    /** Cancel while measuring: back to the form, nothing made. */
    fun cancelCreating() {
        if (phase == Phase.Creating(writing = false)) creation?.cancel()
    }

    /** The screen closes: a measurement still running stops; what was typed goes. Not while writing. */
    fun close() {
        if (isWriting) return
        measuring?.cancel()
        cancelCreating()
        fields.clear()
        onClosed()
    }

    /** The Encryption page's measurement, done (the tests wait for it). */
    suspend fun waitForMeasuring() {
        measuring?.join()
    }

    private suspend fun run(
        path: String,
        name: String,
        password: Secret,
        keyFile: KeyFileChoice?,
        keyFilePath: String?,
        useFingerprint: Boolean,
        locks: Int,
    ) {
        val e = encryption.clamped
        val measurement = measured?.takeIf { it.encryption == e } ?: try {
            measure(e)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error, Step.MEASURE)
            return
        }
        measured = measurement
        baseline = measurement
        phase = Phase.Creating(writing = true)
        // From here nothing cancels it: Cancel is gone, and the app's scope outlives the screen.
        withContext(NonCancellable) { write(path, name, password, keyFile, keyFilePath, useFingerprint, locks, measurement) }
    }

    private suspend fun write(
        path: String,
        name: String,
        password: Secret,
        keyFile: KeyFileChoice?,
        keyFilePath: String?,
        useFingerprint: Boolean,
        locks: Int,
        measurement: MeasuredEncryption,
    ) {
        try {
            withContext(io) { making.begin(name) }
            var fileKey: Secret? = null
            var made: String? = null
            try {
                when (keyFile) {
                    is KeyFileChoice.New -> {
                        val keyPath = checkNotNull(keyFilePath)
                        fileKey = withContext(io) { making.createKeyFile(keyPath) }
                        made = keyPath
                    }
                    is KeyFileChoice.Existing -> fileKey = withContext(io) { making.readKeyFile(keyFile.path) }
                    null -> Unit
                }
            } catch (error: Throwable) {
                fail(error, if (keyFile is KeyFileChoice.New) Step.NEW_KEY_FILE else Step.KEY_FILE)
                return
            }
            val key = try {
                withContext(io) { making.create(path, name, password, fileKey, measurement) }
            } catch (error: Throwable) {
                // A key file made for it was never used: it goes (the Mac's D9).
                made?.let { keyPath -> withContext(io) { runCatching { making.removeKeyFile(keyPath) } } }
                fail(error, Step.DATABASE)
                return
            } finally {
                fileKey?.wipe()
            }
            fields.clear()
            phase = Phase.Created
            // Made: from here the file is the user's, whatever the app makes of it (it is never removed once made).
            runCatching { onCreated(CreatedDatabase(path, keyFilePath, key, password, useFingerprint, offersFingerprint, locks)) }
        } catch (error: Throwable) {
            // The note itself could not be written: nothing was made yet.
            fail(error, Step.DATABASE)
        } finally {
            // What was not removed stays noted for the next start (a new key file, removed or not, is the note's to know).
            withContext(io) { runCatching { making.end(made = phase == Phase.Created) } }
        }
    }

    private suspend fun measure(e: DatabaseEncryption): MeasuredEncryption = withContext(io) {
        val job = coroutineContext.job
        making.measure(e) { if (!job.isActive) throw CancellationException("measuring stopped") }
    }

    private enum class Step { MEASURE, NEW_KEY_FILE, KEY_FILE, DATABASE }

    private fun fail(error: Throwable, step: Step) {
        phase = Phase.Editing
        val message = when (error) {
            is CreateFailure.Exists -> if (step == Step.DATABASE) {
                takenText(error.name)
            } else {
                "There is already a ${error.name} there. A key file is never replaced: choose another folder."
            }
            is CreateFailure.FolderNotFound -> "That folder is not there any more. Choose another."
            is CreateFailure.KeyFileUnusable -> "${error.name} cannot be read as a key file."
            is CreateFailure.WriteFailed -> "The file could not be written: ${error.why}."
            is CreateFailure.VerificationFailed -> "The new file did not check out (${error.why}), so it was not kept."
            is CreateFailure.Unsupported -> "Not written: ${error.what}."
            is CreateFailure.NotEnoughMemory -> "This phone has not enough memory for ${encryption.clamped.memoryMiB} MiB: choose less on the Encryption page."
            else -> if (step == Step.MEASURE) {
                measuringFailure(error)
            } else {
                "Something went wrong (${error.message ?: error.javaClass.simpleName})."
            }
        }
        // "Not created: there is already…", but a file's own name as it is.
        val lowered = if (message.startsWith("Th") || message.startsWith("Something")) message.replaceFirstChar { it.lowercaseChar() } else message
        failure = Failure("Not created: $lowered", onName = error is CreateFailure.Exists && step == Step.DATABASE, form = form())
    }

    /** Why measuring failed, as the Encryption page and Create say it. */
    private fun measuringFailure(error: Throwable): String = if (error is CreateFailure.NotEnoughMemory) {
        "This phone has not enough memory for ${encryption.clamped.memoryMiB} MiB: choose less."
    } else {
        "This phone’s speed could not be measured. Try again, or choose AES-KDF."
    }

    private fun takenText(fileName: String): String =
        "There is already a $fileName in ${folder?.let(::shown)}. A database is never replaced: choose another name or folder."

    /** What the form holds now: a failure is shown until it changes (the name, the folder, the password, a key file, the encryption). */
    private fun form(): Int = listOf(name, folder, password.text.toString(), keyFile, requiresKeyFile, encryption).hashCode()

    private fun shown(path: String): String = DatabaseText.abbreviated(path, root)

    private fun folderAt(path: String, isCurrent: Boolean): Folder = Folder(path, shown(path), "this database’s".takeIf { isCurrent })

    private fun encryptionChanged() {
        val e = encryption.clamped
        // The time alone changed: the iterations scale with it, nothing to measure again.
        val base = baseline
        if (base != null && base.encryption.copy(seconds = e.seconds) == e && base.seconds > 0) {
            val aes = e.keyDerivation == DatabaseEncryption.KeyDerivation.AES_KDF
            val scaled = (base.units * e.seconds / base.seconds).roundToLong()
            val units = scaled.coerceIn(if (aes) 100_000L else 2L, if (aes) 1_000_000_000_000_000L else Int.MAX_VALUE.toLong())
            measuring?.cancel()
            isMeasuring = false
            measured = MeasuredEncryption(e, units, base.seconds * units / base.units)
            return
        }
        if (page == Page.ENCRYPTION) measureForPage()
    }

    /**
     * After a pause (a stepper tapped through measures once), and one at a time: a cancelled one still running is
     * waited for (it stops only between runs), so two never measure each other's phone.
     */
    private fun measureForPage() {
        val e = encryption.clamped
        if (measured?.encryption == e) return
        val previous = measuring
        previous?.cancel()
        isMeasuring = true
        measuring = scope.launch {
            delay(measureDelay)
            previous?.join()
            val result = try {
                measure(e)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                isMeasuring = false
                if (e == encryption.clamped) measureFailure = measuringFailure(error)
                return@launch
            }
            isMeasuring = false
            measureFailure = null
            if (result.encryption != encryption.clamped) return@launch
            measured = result
            baseline = result
        }
    }

    companion object {
        /** "1 s", "0.5 s", "2.5 s". */
        fun seconds(value: Double): String {
            val rounded = Math.round(value * 10) / 10.0
            return if (rounded == Math.floor(rounded) && rounded >= 1) "${rounded.toInt()} s" else String.format(Locale.US, "%.1f s", rounded)
        }
    }
}
