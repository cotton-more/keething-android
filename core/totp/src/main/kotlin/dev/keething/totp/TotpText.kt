package dev.keething.totp

import dev.keething.domain.text.TextKey

/** The texts core/totp makes (keething-spec plans T13b): each names its resource, `totp_<name in lower case>`. */
public enum class TotpText : TextKey {
    // Why a code cannot be used, said after "This code cannot be used here:"
    UNSUPPORTED_NOT_TOTP,
    UNSUPPORTED_GOOGLE_EXPORT,
    UNSUPPORTED_STEAM,
    UNSUPPORTED_ENCODER,
    UNSUPPORTED_ALGORITHM,
    UNSUPPORTED_DIGITS,
    UNSUPPORTED_PERIOD,
    UNSUPPORTED_SETTINGS,
    UNSUPPORTED_NO_SECRET,
    UNSUPPORTED_NOT_BASE32,
    ;

    override val resource: String get() = "totp_" + name.lowercase()
}
