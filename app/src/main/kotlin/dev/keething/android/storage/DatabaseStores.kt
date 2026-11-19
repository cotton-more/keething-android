package dev.keething.android.storage

import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.GrantedFolder
import dev.keething.domain.time.Clock
import dev.keething.vault.databases.FileLocation
import dev.keething.vault.storage.FolderDatabaseStore
import dev.keething.vault.storage.SaveNotes

/**
 * A database of the list through its granted folder (keething-spec databases.saf AC2). One kept by its path is never
 * read by it (AC1, AC13), nor one reached through a grant to its file alone, which this version never writes but a later
 * one or a list edited by hand might (app.upgrade-safety AC3): both read as no access, and Locate… reaches them through
 * a folder.
 */
internal fun databaseStoreOf(file: FileLocation, folderOf: (grant: String) -> GrantedFolder?, notes: SaveNotes, clock: Clock): DatabaseStore = when (file) {
    is FileLocation.ByPath, is FileLocation.Granted -> NoAccessStore(file.fileName)
    is FileLocation.InFolder -> FolderDatabaseStore(folderOf(file.grant) ?: UnreachableFolder(file.grant), file.path, notes, clock)
}
