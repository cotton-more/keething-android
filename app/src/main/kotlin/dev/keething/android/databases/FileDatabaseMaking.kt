package dev.keething.android.databases

import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.FolderItem
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.StoragePlace
import dev.keething.domain.storage.folderOf
import dev.keething.domain.storage.nameOf
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.CreationNote
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.creating.DerivedKey
import dev.keething.vault.creating.EncryptionMeasurement
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.FolderNewFile
import dev.keething.vault.storage.KeyFiles
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The places New database reaches (keething-spec databases.saf AC9, AC11): the folders the list is reached through and
 * those granted in the form, and key files granted alone, each named by its path on the phone ([root]: the phone's own
 * storage), which is the form's words; a path is never opened by it (AC1). [taken]: the grants given in the form, for the
 * app to offer to the list once the form is done. Called from the main thread and the io dispatcher.
 */
internal class NewDatabasePlaces(private val root: String, listed: List<GrantedPlace>) {
    private val folders = listed.toMutableList()
    private val keyFiles = mutableMapOf<String, FileLocation.Granted>()
    private val grants = mutableListOf<String>()

    val taken: List<String> @Synchronized get() = grants.toList()

    /** The folders granted for the list, as the form's folders (its paths), in the list's order. */
    val listedPaths: List<String> @Synchronized get() = folders.map { DatabaseText.pathOf(it.place, root) }

    /** A folder granted in the form's picker: its path, the form's way to name it. */
    @Synchronized
    fun folderGranted(folder: GrantedPlace): String {
        grants += folder.grant
        folders += folder
        return DatabaseText.pathOf(folder.place, root)
    }

    /** A key file granted alone in the form's picker: its path. */
    @Synchronized
    fun keyFileGranted(file: FileLocation.Granted): String {
        grants += file.grant
        val path = DatabaseText.pathOf(file.at, root)
        keyFiles[path] = file
        return path
    }

    /** The granted folder that holds [path] (itself, or one above it) and the path inside it; null for none. */
    @Synchronized
    fun reach(path: String): Pair<GrantedPlace, String>? {
        val place = StoragePlace.ofPath(path) ?: return null
        for (folder in folders) return folder to (place.inside(folder.place) ?: continue)
        return null
    }

    /** The file at [path] in a granted folder; null where none reaches. */
    fun inFolder(path: String): FileLocation.InFolder? =
        reach(path)?.takeIf { it.second.isNotEmpty() }?.let { (folder, inside) -> FileLocation.InFolder(folder.grant, folder.place, inside) }

    /** The key file at [path]: through a granted folder, else granted alone; null for neither. */
    @Synchronized
    fun keyFile(path: String): FileLocation? = inFolder(path) ?: keyFiles[path]
}

/**
 * [NewDatabaseFiles] through the grants of [places]: what a folder holds as its provider lists it ([folders]), a key file
 * granted alone as the provider says ([fileThere]). The form asks while it draws, on the main thread: what is not known
 * yet is read on [io] and told through [whenRead] (until then a file is not there, a folder is, nothing syncs), and kept
 * until [lookAgain]. Nothing reached says nothing is there.
 */
internal class GrantedNewDatabaseFiles(
    private val places: NewDatabasePlaces,
    private val folders: (grant: String) -> GrantedFolder?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val fileThere: (FileLocation.Granted) -> Boolean,
) : NewDatabaseFiles {
    /** A folder's listing ([NO_FOLDER]: none there), by its grant and path; what is being read is absent and in [reading]. */
    private val listings = HashMap<Pair<String, String>, List<FolderItem>>()
    private val files = HashMap<String, Boolean>()
    private val reading = HashSet<Any>()
    private var generation = 0
    private var read: () -> Unit = {}

    override fun whenRead(read: () -> Unit) {
        this.read = read
    }

    /** [key]'s value as read, or null while it is read on [io] (then [whenRead] tells). */
    @Synchronized
    private fun <K : Any, V : Any> known(cache: HashMap<K, V>, key: K, load: () -> V): V? {
        cache[key]?.let { return it }
        if (!reading.add(key)) return null
        val asked = generation
        scope.launch {
            val value = withContext(io) { load() }
            synchronized(this@GrantedNewDatabaseFiles) {
                reading -= key
                if (asked != generation) return@launch
                cache[key] = value
            }
            read()
        }
        return null
    }

    private fun listing(folder: GrantedPlace, path: String): List<FolderItem>? =
        known(listings, folder.grant to path) { runCatching { folders(folder.grant)?.list(path) }.getOrNull() ?: NO_FOLDER }

    override fun exists(path: String): Boolean {
        val (folder, inside) = places.reach(path) ?: return granted(path)
        if (inside.isEmpty()) return listing(folder, "")?.let { it !== NO_FOLDER } ?: false
        // The storage takes names regardless of case.
        return listing(folder, folderOf(inside))?.any { it.name.equals(nameOf(inside), ignoreCase = true) } == true
    }

    /** A key file granted alone, as its provider says. */
    private fun granted(path: String): Boolean {
        val file = places.keyFile(path) as? FileLocation.Granted ?: return false
        return known(files, path) { runCatching { fileThere(file) }.getOrDefault(false) } ?: true
    }

    override fun isFolder(path: String): Boolean {
        val (folder, inside) = places.reach(path) ?: return false
        return listing(folder, inside)?.let { it !== NO_FOLDER } ?: true
    }

    override fun syncs(folder: String): Boolean {
        val (granted, inside) = places.reach(folder) ?: return false
        var at = inside
        while (true) {
            if (listing(granted, at)?.any { it.name == ".stfolder" } == true) return true
            if (at.isEmpty()) return false
            at = folderOf(at)
        }
    }

    @Synchronized
    override fun lookAgain() {
        generation++
        listings.clear()
        files.clear()
        reading.clear()
    }

    private companion object {
        /** No folder at that path, or none reached. */
        val NO_FOLDER: List<FolderItem> = listOf(FolderItem("", isFolder = false))
    }
}

/**
 * [DatabaseMaking] through the grants of [places]: the creator of plan AD2 making each file in its granted folder, never
 * over a file there ([FolderNewFile]), each noted in [note] before it is written; a key file the user has read through
 * its grant ([keyFiles]). [ready]: waits until the start has dealt with what an ended Create left ([CreationNote.leftovers]),
 * so a new Create's note is never taken for it.
 */
internal class FileDatabaseMaking(
    private val places: NewDatabasePlaces,
    private val folders: (grant: String) -> GrantedFolder?,
    private val keyFiles: KeyFiles,
    private val note: CreationNote,
    private val ready: () -> Unit = {},
) : DatabaseMaking {
    private val creator = DatabaseCreator(now = Instant::now)

    /** The key files made by this Create, by path: only those are removed again. */
    private val madeKeyFiles = mutableMapOf<String, NewFile>()

    override fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit): MeasuredEncryption =
        EncryptionMeasurement.measure(encryption, checkCancelled)

    override fun begin(name: String) {
        ready()
        note.begin(name)
    }

    override fun createKeyFile(path: String): Secret {
        val at = places.inFolder(path) ?: throw CreateFailure.FolderNotFound()
        val target = note.noting(newFile(at), at)
        val key = creator.createKeyFile(target, keyFiles.at(at))
        synchronized(madeKeyFiles) { madeKeyFiles[path] = target }
        return key
    }

    override fun readKeyFile(path: String): Secret {
        val at = places.keyFile(path) ?: throw CreateFailure.KeyFileUnusable(nameOf(path))
        return creator.readKeyFile(keyFiles.at(at))
    }

    override fun removeKeyFile(path: String) {
        synchronized(madeKeyFiles) { madeKeyFiles.remove(path) }?.removeCreated()
    }

    override fun create(path: String, name: String, password: Secret, keyFile: Secret?, measured: MeasuredEncryption): DerivedKey {
        val at = places.inFolder(path) ?: throw CreateFailure.FolderNotFound()
        return creator.create(note.noting(newFile(at), at), name, password, keyFile, measured)
    }

    override fun end(made: Boolean) {
        synchronized(madeKeyFiles) { madeKeyFiles.clear() }
        note.end(made)
    }

    /** A new file in its granted folder; one whose grant is gone has no folder to be made in. */
    private fun newFile(at: FileLocation.InFolder): NewFile = FolderNewFile(folders(at.grant) ?: throw CreateFailure.FolderNotFound(), at.path)
}
