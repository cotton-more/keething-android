package dev.keething.vault.storage

import dev.keething.kdbx.reader.KdbxReader
import java.io.InputStream

/** What a backup's file shows without a key (keething-spec databases.backups-window AC5). */
public object BackupCheck {
    /**
     * Whether the file read from [input] is damaged: not a KeePass database, or a KDBX 4 header that is cut short or
     * does not match its own SHA-256. Reads the header only; nothing is decrypted.
     */
    public fun isDamaged(input: InputStream): Boolean = KdbxReader.isHeaderDamaged(input)
}
