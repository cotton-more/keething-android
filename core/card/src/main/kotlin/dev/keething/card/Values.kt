package dev.keething.card

import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.AndroidAppAddress
import dev.keething.domain.vault.UrlHost
import dev.keething.totp.TotpGenerator
import java.time.Instant

/**
 * Turns an entry's address text into something safe to hand to the browser (Mac `OpenableURL`): only http and
 * https; a scheme-less host (`github.com/login`) gets https. Everything else (javascript:, file:, ssh:, …) is
 * refused.
 */
public object OpenableUrl {
    private val hostLike = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(:[0-9]{1,5})?([/?#].*)?$""") // not user text

    public fun of(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val scheme = trimmed.indexOf("://")
        if (scheme >= 0) {
            val name = trimmed.substring(0, scheme).lowercase()
            if (name != "http" && name != "https") return null
            return trimmed.takeIf { UrlHost.of(it) != null }
        }
        return if (hostLike.matches(trimmed)) "https://$trimmed" else null
    }
}

/** A value that is a secret whatever the database says (Mac `ValueShape.isPlainlySecret`): masked like a protected one. */
internal object ValueShape {
    fun isPlainlySecret(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.lowercase().startsWith("otpauth://")) return true
        if (!trimmed.startsWith("-----BEGIN ")) return false
        val label = pemLabel(trimmed) ?: return false
        return "PRIVATE KEY" in label || label == "PEM block" // not user text
    }

    private fun pemLabel(value: String): String? {
        val start = "-----BEGIN "
        if (!value.startsWith(start)) return null
        val rest = value.substring(start.length)
        val end = rest.indexOf("-----")
        if (end < 0) return "PEM block" // not user text
        val label = rest.substring(0, end)
        val allowed = label.all { (it.code < 128 && (it.isUpperCase() || it.isDigit())) || it == ' ' }
        return if (allowed && label.isNotEmpty() && label.none { it == '\n' || it == '\r' }) "PEM ($label)" else "PEM block" // not user text
    }
}

/**
 * The monogram shown next to an entry (Mac `EntryAvatar`): its first letter or digit and a stable colour from
 * a small palette. The colour comes from FNV-1a over the lowercased, trimmed title, the same on the Mac, so
 * an entry has one colour everywhere and forever.
 */
public data class EntryAvatar(val initial: String, val colorIndex: Int) {
    public companion object {
        public const val PALETTE_SIZE: Int = 8

        /** The quiet colour of untitled entries. */
        public const val NEUTRAL_INDEX: Int = 7

        public fun of(title: String): EntryAvatar {
            val trimmed = title.trim()
            val initial = trimmed.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            val color = if (trimmed.isEmpty()) NEUTRAL_INDEX else (fnv1a(trimmed.lowercase()) % PALETTE_SIZE).toInt()
            return EntryAvatar(initial, color)
        }

        private fun fnv1a(text: String): Long {
            var hash = 2_166_136_261L.toInt()
            for (byte in text.encodeToByteArray()) {
                hash = (hash xor (byte.toInt() and 0xFF)) * 16_777_619
            }
            return hash.toLong() and 0xFFFF_FFFFL
        }
    }
}

/** A group path as the screens show it: `Finanzen › Banken` (empty for the root group). */
public object GroupPathFormatter {
    public fun text(path: List<String>): String = path.joinToString(" › ")

    /**
     * [path] shorter and shorter, for one line (the owner, 2026-10-31, as fish shell writes a directory): the last
     * level, the group's own name, always whole; the levels above it cut to their first letter, one at a time from the
     * left; then "… › " and the last level alone. The first that fits is shown (a database's name before it is never
     * cut).
     */
    public fun shortenings(path: List<String>): List<String> {
        if (path.size < 2) return listOf(text(path))
        val above = path.dropLast(1)
        val result = LinkedHashSet<String>()
        result += text(path)
        for (cut in 1..above.size) result += text(above.mapIndexed { i, name -> if (i < cut) initial(name) else name } + path.last())
        result += text(listOf("…", path.last()))
        return result.toList()
    }

    /** A level cut to its first letter (its first character, a letter of any script or a digit). */
    private fun initial(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) name else trimmed.substring(0, trimmed.offsetByCodePoints(0, 1))
    }
}

/** What a one-time code row shows at a moment: the code, its grouped form, the countdown and the next code (Mac `TOTPDisplay`). */
public data class TotpDisplay(
    /** The digits: what is copied. */
    val code: String,
    /** Grouped for reading: "287 082". */
    val formatted: String,
    val secondsRemaining: Int,
    val period: Int,
    /** The code that follows when this one's period ends (keething-spec card.code-timer): what "Next" copies. */
    val next: String = "",
) {
    /** [next] grouped as [formatted] is. */
    val nextFormatted: String get() = group(next)

    /** Fraction of the period left: 1.0 right after the code changes, down to 1/period. */
    val progress: Double get() = secondsRemaining.toDouble() / period

    public companion object {
        public fun of(config: TotpConfig, at: Instant): TotpDisplay {
            val code = TotpGenerator.code(config, at)
            val seconds = TotpGenerator.secondsRemaining(config, at)
            val next = TotpGenerator.code(config, at.plusSeconds(seconds.toLong()))
            return TotpDisplay(code, group(code), seconds, maxOf(config.period, 1), next)
        }

        internal fun group(code: String): String =
            if (code.length >= 4 && code.length % 2 == 0) code.substring(0, code.length / 2) + " " + code.substring(code.length / 2) else code
    }
}

/** An `androidapp://` address as the card shows it (keething-spec card.app-address AC1, AC6). */
internal object AppAddressStyle {
    private val generic = Regex("""(?i)url( [0-9]+)?|kp2a_url.*""")

    /** [fieldName]: the row's name when it is the user's own (null for the entry's URL and the further URLs). */
    fun of(text: String, fieldName: String?): ValueStyle.App? {
        val packageName = AndroidAppAddress.packageOf(text) ?: return null
        return ValueStyle.App(packageName, fieldName?.trim()?.takeUnless { it.isEmpty() || generic.matches(it) })
    }
}
