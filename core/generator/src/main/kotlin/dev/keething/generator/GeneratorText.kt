package dev.keething.generator

import dev.keething.domain.text.TextKey

/** The texts core/generator makes (keething-spec plans T13b): each names its resource, `generator_<name in lower case>`. */
public enum class GeneratorText : TextKey {
    // Why a typed password is weak or fair, said after "Weak:" or "Fair:"
    REASON_EMPTY,
    REASON_COMMON_PASSWORD,
    REASON_CONTEXT,
    REASON_COMMON_WORD,
    REASON_KEYBOARD_PATTERN,
    REASON_YEAR,
    REASON_REPEATED,
    REASON_SEQUENCE,
    REASON_SHORTER_THAN,
    REASON_DIGITS_ONLY,
    REASON_TOO_SHORT,
    REASON_COULD_BE_LONGER,
    ;

    override val resource: String get() = "generator_" + name.lowercase()
}
