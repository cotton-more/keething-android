package dev.keething.vault.databases

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.englishOrNull
import dev.keething.vault.creating.DerivedKey

// The list's forms by path, for the tests: their databases and key files are records kept by path before
// keething-spec databases.saf (FileLocation.ByPath). The app never adds one now (AC1).

/** The file at [path], standardized. */
internal fun byPath(path: String): FileLocation.ByPath = FileLocation.ByPath(FileLocation.standardized(path))

internal fun DatabaseList.add(path: String, useBiometric: Boolean = false): DatabaseRecord = add(byPath(path), useBiometric)

internal fun CurrentDatabase.open(path: String): DatabaseId = open(byPath(path))

/** Why [path] is not taken, in the app's English; null when it is. */
internal fun CurrentDatabase.locate(id: DatabaseId, path: String): String? = locate(id, byPath(path)).englishOrNull()

internal fun CurrentDatabase.setKeyFile(id: DatabaseId, path: String) {
    setKeyFile(id, byPath(path))
}

internal suspend fun CurrentDatabase.created(
    path: String,
    keyFilePath: String?,
    useBiometric: Boolean,
    key: DerivedKey,
    password: Secret,
    fingerprintChosen: Boolean = useBiometric,
    lockedSince: () -> Boolean,
): CurrentDatabase.Created = created(byPath(path), keyFilePath?.let(::byPath), useBiometric, key, password, fingerprintChosen, lockedSince)

/** The database's path; null for one through a grant. */
internal val DatabaseRecord.path: String? get() = (file as? FileLocation.ByPath)?.path

/** The key file's path; null for none, or one through a grant. */
internal val DatabaseRecord.keyFilePath: String? get() = (keyFile as? FileLocation.ByPath)?.path

/** The database's file by its path, else its name. */
internal val DatabaseSession.file: java.io.File get() = java.io.File((location as? FileLocation.ByPath)?.path ?: location.fileName)
