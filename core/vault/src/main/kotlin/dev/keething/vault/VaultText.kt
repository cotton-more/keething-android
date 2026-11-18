package dev.keething.vault

import dev.keething.domain.text.TextKey

/** The texts core/vault makes (keething-spec plans T13b): each names its resource, `vault_<name in lower case>`. */
public enum class VaultText : TextKey {
    // Locate…: why the file or folder chosen is not taken
    LOCATE_IN_LIST_ALREADY,
    LOCATE_DATABASE_GONE,
    LOCATE_NOT_ON_STORAGE,
    LOCATE_FOLDER_LACKS_FILE,

    // The chooser's run over the checked databases
    RUN_STEP,
    RUN_OPEN,
    RUN_SKIPPED,
    RUN_NOT_OPENED,
    // Why the run skipped a database by itself, said after its name and a colon
    WHY_NOT_IN_LIST,
    WHY_CLOSED,
    WHY_OPENING,
    WHY_NOT_FOUND,
    WHY_KEY_FILE_NOT_THERE,
    WHY_KEY_FILE_UNUSABLE,
    WHY_CANNOT_BE_READ,

    // Why the fingerprint is not asked for
    FINGERPRINT_OFF_IN_SETTINGS,
    FINGERPRINT_OFF_FOR_DATABASE,
    FINGERPRINT_NO_DATABASE,

    // Why a key file cannot be used, said after "The key file cannot be used:"
    KEY_FILE_NOT_READ,
    KEY_FILE_TOO_LARGE,
    KEY_FILE_NO_ACCESS,
    KEY_FILE_EMPTY,
    KEY_FILE_VERSION,
    KEY_FILE_MALFORMED,
    KEY_FILE_HASH_MISMATCH,
    ;

    override val resource: String get() = "vault_" + name.lowercase()
}
