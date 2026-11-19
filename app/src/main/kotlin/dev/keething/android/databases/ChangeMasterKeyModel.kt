package dev.keething.android.databases

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.keething.android.R
import dev.keething.android.editor.problem
import dev.keething.android.editor.syncWarning
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiText
import dev.keething.android.unlock.describe
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.NewFile
import dev.keething.generator.RandomSource
import dev.keething.generator.SystemRandom
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.databases.CurrentDatabase
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.session.KeyConfirmation
import dev.keething.vault.session.SaveOutcome
import dev.keething.vault.storage.FolderNewFile
import dev.keething.vault.storage.KeyFiles
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Change master key… asks of the files (blocking: called on the io dispatcher). Failures are [CreateFailure]s. */
internal interface KeyChangeFiles {
    /** A new key file at [path], never over a file there; its key, which the caller wipes. */
    fun createKeyFile(path: String): Secret

    /** Removes the key file [createKeyFile] made at [path], and only that one: the key was not changed. */
    fun removeKeyFile(path: String)

    /** The key of the key file at [at]; the caller wipes it. */
    fun readKeyFile(at: FileLocation): Secret

    /** Where the key file at [path] is reached (a granted folder, or granted alone); null for nowhere. */
    fun location(path: String): FileLocation?
}

/** [KeyChangeFiles] through the grants of [places], as New database makes and reads key files ([FileDatabaseMaking]). */
internal class GrantedKeyChangeFiles(
    private val places: NewDatabasePlaces,
    private val folders: (grant: String) -> GrantedFolder?,
    private val keyFiles: KeyFiles,
) : KeyChangeFiles {
    private val creator = DatabaseCreator(now = Instant::now)
    private val made = mutableMapOf<String, NewFile>()

    override fun createKeyFile(path: String): Secret {
        val at = places.inFolder(path) ?: throw CreateFailure.FolderNotFound()
        val target = FolderNewFile(folders(at.grant) ?: throw CreateFailure.FolderNotFound(), at.path)
        val key = creator.createKeyFile(target, keyFiles.at(at))
        synchronized(made) { made[path] = target }
        return key
    }

    override fun removeKeyFile(path: String) {
        synchronized(made) { made.remove(path) }?.removeCreated()
    }

    override fun readKeyFile(at: FileLocation): Secret = creator.readKeyFile(keyFiles.at(at))

    override fun location(path: String): FileLocation? = places.keyFile(path)
}

/**
 * Change master key… (SPEC §6.2; keething-spec databases.change-master-key; agreed on the canvas "Database name, colour
 * and master key"): the key the database is open with asked first, then the new key, a password (kept, new, or none
 * with a key file) and a key file (kept, made new, one the user has, or none), then the change, saved as one write of
 * the file. It lives in the app's [scope], not a screen's: a change goes on when the screen is gone. Call it on the main
 * thread.
 */
internal class ChangeMasterKeyModel(
    val id: DatabaseId,
    /** The database's label ("Work"), its file ("Sync/KeePass/Work.kdbx") and its file's name. */
    val label: String,
    val fileText: String,
    val fileName: String,
    /** The database's folder, by its path on the phone: a key file made there is warned about. */
    private val databaseFolder: String?,
    /** Its key file now (the record's), with its name and where it is in words; null: none. */
    val currentKeyFile: CurrentKeyFile?,
    private val confirm: suspend (Secret) -> KeyConfirmation,
    private val change: (password: Secret?, keyFile: FileLocation?, keyFileKey: Secret?) -> Deferred<CurrentDatabase.KeyChanged>?,
    private val files: KeyChangeFiles,
    private val look: NewDatabaseFiles,
    /** The shared storage's root, for the words ("Documents/Keys"). */
    private val root: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    random: RandomSource = SystemRandom,
) {
    class CurrentKeyFile(val name: String, val placeText: String, val location: FileLocation)

    enum class Step { CURRENT, NEW, DONE }

    enum class PasswordChoice { KEEP, NEW, NONE }

    sealed interface KeyFileChoice {
        /** Its key file as it is. */
        data object Keep : KeyFileChoice

        /** No key file: removed, or none as before. */
        data object None : KeyFileChoice

        /** Made at Change in [folder]. */
        data class New(val folder: String) : KeyFileChoice

        /** One the user has. */
        data class Existing(val path: String) : KeyFileChoice
    }

    /** Close: the form and what was typed go (not while the change is written). */
    var onClosed: () -> Unit = {}

    /** A folder granted in the form's picker: the path the form names it by. */
    var folderGranted: (GrantedPlace) -> String = { it.place.toString() }

    /** A key file granted alone in the form's picker: the path the form names it by. */
    var keyFileGranted: (FileLocation.Granted) -> String = { it.at.toString() }

    var step: Step by mutableStateOf(Step.CURRENT)
        private set

    /** The key it is open with, as typed. */
    val current = TextFieldState()
    var revealsCurrent: Boolean by mutableStateOf(false)

    var checking: Boolean by mutableStateOf(false)
        private set

    var changing: Boolean by mutableStateOf(false)
        private set

    /** What the last Continue or Change could not do: until something is typed or chosen again. */
    var problem: UiText? by mutableStateOf(null)
        private set

    val fields = MasterPasswordFields(random) { listOf(label) }

    var passwordChoice: PasswordChoice by mutableStateOf(PasswordChoice.NEW)
        private set

    var keyFile: KeyFileChoice by mutableStateOf(if (currentKeyFile != null) KeyFileChoice.Keep else KeyFileChoice.None)
        private set

    /** Once changed: why the new password could not be stored for the fingerprint, and the key file's name now. */
    var fingerprintProblem: String? by mutableStateOf(null)
        private set
    var keyFileAfter: String? by mutableStateOf(null)
        private set
    var passwordAfter: Boolean by mutableStateOf(true)
        private set

    /** Once changed: the new file's time came out too close for a sync app (databases.saf AC6). */
    var mayNotSync: Boolean by mutableStateOf(false)
        private set

    /** Once changed: the database locked while the change was written, after it was in the file (AC6). */
    var lockedMeanwhile: UiText? by mutableStateOf(null)
        private set

    /** The key typed at the first step, while the form is open: the password kept, when it is kept. */
    private var confirmed: Secret? = null
    private var confirmedWithPassword = true
    private var job: Job? = null
    private var looked by mutableIntStateOf(0)

    init {
        look.whenRead { looked++ }
    }

    fun lookAgain() {
        look.lookAgain()
        looked++
    }

    // The key it is open with

    val canConfirm: Boolean get() = step == Step.CURRENT && !checking && (current.text.isNotEmpty() || currentKeyFile != null)

    /** Continue: the key typed must be the one it is open with (keething-spec databases.change-master-key AC1). */
    fun confirmCurrent(): Job? {
        if (!canConfirm) return null
        val typed = Secret.of(current.text.toString())
        checking = true
        problem = null
        return scope.launch {
            try {
                when (val result = confirm(typed)) {
                    is KeyConfirmation.Confirmed -> {
                        confirmed?.wipe()
                        confirmed = typed.copy()
                        confirmedWithPassword = result.withPassword
                        current.clearText()
                        revealsCurrent = false
                        // A database that opens with its key file alone keeps having no password unless one is typed.
                        passwordChoice = if (result.withPassword) PasswordChoice.NEW else PasswordChoice.KEEP
                        step = Step.NEW
                    }
                    KeyConfirmation.Wrong -> problem = currentKeyFile?.let { uiText(R.string.databases_change_key_wrong_with_file, it.name) }
                        ?: uiText(R.string.databases_change_key_wrong)
                    is KeyConfirmation.Failed -> problem = result.failure.describe(fileText)
                    KeyConfirmation.Locked -> problem = uiText(R.string.databases_change_key_locked)
                }
            } finally {
                typed.wipe()
                checking = false
            }
        }
    }

    // The new key

    /** The password choices offered: None only while a key file stays. */
    val noneAllowed: Boolean get() = keyFile != KeyFileChoice.None

    /** Keep: what is kept, said ("The password stays as it is." / none, with a key file alone). */
    val keepsNoPassword: Boolean get() = !confirmedWithPassword

    fun choosePassword(choice: PasswordChoice) {
        if (choice == PasswordChoice.NONE && !noneAllowed) return
        passwordChoice = choice
        problem = null
    }

    fun chooseKeyFile(choice: KeyFileChoice) {
        keyFile = when (choice) {
            is KeyFileChoice.New -> KeyFileChoice.New(File(choice.folder).path)
            is KeyFileChoice.Existing -> KeyFileChoice.Existing(File(choice.path).path)
            else -> choice
        }
        // No key file and no password: the password is asked for.
        if (keyFile == KeyFileChoice.None && passwordChoice == PasswordChoice.NONE) passwordChoice = PasswordChoice.NEW
        if (keyFile == KeyFileChoice.None && passwordChoice == PasswordChoice.KEEP && !confirmedWithPassword) passwordChoice = PasswordChoice.NEW
        problem = null
    }

    /** A new key file's path: `<database>.keyx` in its folder, or `<database> 2.keyx` and on when that is taken. */
    val newKeyFilePath: String?
        get() {
            val folder = (keyFile as? KeyFileChoice.New)?.folder ?: return null
            val stem = fileName.substringBeforeLast('.').ifEmpty { fileName }
            return (1..99).asSequence()
                .map { n -> File(folder, if (n == 1) "$stem.keyx" else "$stem $n.keyx").path }
                .firstOrNull { looked.let { _ -> !look.exists(it) } }
        }

    /** The chosen key file's name and where, in words; null for Keep and None. */
    val keyFileText: UiText?
        get() = when (val choice = keyFile) {
            is KeyFileChoice.New -> newKeyFilePath?.let { uiText(R.string.databases_file_in_folder, File(it).name, shown(choice.folder)) }
            is KeyFileChoice.Existing -> uiText(R.string.databases_file_in_folder, File(choice.path).name, shown(File(choice.path).parent ?: "/"))
            else -> null
        }

    /** A new key file that cannot be made there, or one to use that is not there. */
    val keyFileProblem: UiText?
        get() = when (val choice = keyFile) {
            is KeyFileChoice.New -> when {
                looked.let { look.isFolder(choice.folder) }.not() -> uiText(R.string.databases_change_key_folder_gone, shown(choice.folder))
                newKeyFilePath == null -> uiText(R.string.databases_change_key_no_free_name)
                else -> null
            }
            is KeyFileChoice.Existing -> if (looked.let { look.exists(choice.path) }) null else uiText(R.string.databases_change_key_file_gone, File(choice.path).name)
            else -> null
        }

    /** A key file kept with the database protects little: whoever gets the folder gets both. */
    val keyFileWarning: UiText?
        get() {
            val keyFolder = when (val choice = keyFile) {
                is KeyFileChoice.New -> choice.folder
                is KeyFileChoice.Existing -> File(choice.path).parent
                else -> null
            } ?: return null
            if (keyFileProblem != null) return null
            return when {
                keyFolder == databaseFolder -> uiText(R.string.databases_key_file_beside)
                look.syncs(keyFolder) -> uiText(R.string.databases_key_file_syncs)
                else -> null
            }
        }

    /** What keeps Change from being tapped, the first first; empty: it can. */
    val blockers: List<UiText>
        get() {
            val why = mutableListOf<UiText>()
            if (passwordChoice == PasswordChoice.NEW) fields.blocker?.let { why += it }
            keyFileProblem?.let { why += it }
            val passwordAfter = when (passwordChoice) {
                PasswordChoice.KEEP -> confirmedWithPassword
                PasswordChoice.NEW -> true
                PasswordChoice.NONE -> false
            }
            if (!passwordAfter && keyFile == KeyFileChoice.None) why += uiText(R.string.databases_change_key_needs_password)
            if (passwordChoice == PasswordChoice.KEEP && keyFile == (if (currentKeyFile != null) KeyFileChoice.Keep else KeyFileChoice.None)) {
                why += uiText(R.string.databases_change_key_nothing_new)
            }
            return why
        }

    val canChange: Boolean get() = step == Step.NEW && !changing && blockers.isEmpty()

    /**
     * Change master key: a new key file made (or one read), the key put together, and the file written with it
     * (keething-spec databases.change-master-key AC4-AC6). A new key file is removed again when the key was not changed
     * (AC5); a lock that came once the file was being replaced leaves it changed, and it is said (AC6). Its job (the tests
     * wait for it).
     */
    fun changeKey(): Job? {
        if (!canChange) return null
        val choice = keyFile
        val newPath = newKeyFilePath
        val password = when (passwordChoice) {
            PasswordChoice.KEEP -> confirmed?.takeIf { confirmedWithPassword }?.copy()
            PasswordChoice.NEW -> Secret.of(fields.password.text.toString())
            PasswordChoice.NONE -> null
        }
        changing = true
        problem = null
        val started = scope.launch {
            var made: String? = null
            var fileKey: Secret? = null
            try {
                val location = withContext(io) {
                    when (choice) {
                        KeyFileChoice.Keep -> currentKeyFile!!.location.also { fileKey = files.readKeyFile(it) }
                        KeyFileChoice.None -> null
                        is KeyFileChoice.New -> {
                            val path = checkNotNull(newPath)
                            fileKey = files.createKeyFile(path)
                            made = path
                            files.location(path) ?: throw CreateFailure.FolderNotFound()
                        }
                        is KeyFileChoice.Existing -> (files.location(choice.path) ?: throw CreateFailure.KeyFileUnusable(File(choice.path).name))
                            .also { fileKey = files.readKeyFile(it) }
                    }
                }
                val pending = change(password, location, fileKey)
                fileKey?.wipe()
                if (pending == null) {
                    failed(made, uiText(R.string.databases_change_key_locked))
                    return@launch
                }
                val result = pending.await()
                val failure = result.outcome.problem(File(fileText))
                if (failure != null) {
                    failed(made, if (result.outcome == SaveOutcome.Interrupted) uiText(R.string.databases_change_key_interrupted) else failure)
                    return@launch
                }
                fingerprintProblem = result.fingerprintProblem
                mayNotSync = result.outcome.syncWarning
                lockedMeanwhile = uiText(R.string.databases_change_key_locked_meanwhile).takeIf { (result.outcome as? SaveOutcome.Saved)?.lockedMeanwhile == true }
                keyFileAfter = location?.fileName
                passwordAfter = password != null
                fields.clear()
                confirmed?.wipe()
                confirmed = null
                step = Step.DONE
            } catch (failure: CreateFailure) {
                failed(made, keyFileFailure(failure))
            } finally {
                fileKey?.wipe()
                password?.wipe()
                changing = false
            }
        }
        job = started
        return started
    }

    private suspend fun failed(made: String?, text: UiText) {
        made?.let { path -> withContext(io) { runCatching { files.removeKeyFile(path) } } }
        problem = text
    }

    private fun keyFileFailure(failure: CreateFailure): UiText = when (failure) {
        is CreateFailure.KeyFileUnusable -> uiText(R.string.databases_change_key_file_unusable, failure.name)
        is CreateFailure.FolderNotFound -> uiText(R.string.databases_change_key_folder_not_found)
        else -> uiText(R.string.databases_change_key_file_not_made, failure.message ?: failure.javaClass.simpleName)
    }

    /** The screen closes: what was typed goes. Not while the change is written. */
    fun close(): Boolean {
        if (changing) return false
        job?.cancel()
        current.clearText()
        fields.clear()
        confirmed?.wipe()
        confirmed = null
        onClosed()
        return true
    }

    private fun shown(path: String): String = DatabaseText.abbreviated(path, root)
}

/** Change master key… for the whole app, over whatever was shown, kept by the app so a change outlives the screen. */
internal class MasterKeyChanges(private val start: (DatabaseId) -> ChangeMasterKeyModel?) {
    var form: ChangeMasterKeyModel? by mutableStateOf(null)
        private set

    fun open(id: DatabaseId) {
        if (form == null) form = start(id)
    }

    /** Back, Close or Done. Not while the change is written. */
    fun close() {
        val shown = form ?: return
        if (shown.close()) form = null
    }

    /**
     * The app locked: the form and what was typed go, unless the change is being written (the lock's grace may let it
     * end), or it ended saying the database locked meanwhile: nothing typed is left in it, and it stays until Done so
     * that is read (keething-spec databases.change-master-key AC6).
     */
    fun locked() {
        val shown = form ?: return
        if (shown.changing || shown.lockedMeanwhile != null) return
        shown.close()
        form = null
    }
}
