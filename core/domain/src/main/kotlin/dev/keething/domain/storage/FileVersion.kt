package dev.keething.domain.storage

import java.time.Instant

/**
 * What tells one version of the database file from another: when any part differs, the file changed.
 * [identity] is the file system's identity of the file (device and inode) when the storage knows it;
 * Syncthing replaces the file by a rename, which changes it even when size and time stay the same.
 */
public data class FileVersion(
    val size: Long,
    val modified: Instant,
    val identity: String?,
)
