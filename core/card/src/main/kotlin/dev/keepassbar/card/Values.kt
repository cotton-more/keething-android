package dev.keepassbar.card

import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.UrlHost
import dev.keepassbar.totp.TotpGenerator
import java.time.Instant

/**
 * Turns an entry's address text into something safe to hand to the browser (Mac `OpenableURL`): only http and
 * https; a scheme-less host (`github.com/login`) gets https. Everything else (javascript:, file:, ssh:, …) is
 * refused.
 */
public object OpenableUrl {
    private val hostLike = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(:[0-9]{1,5})?([/?#].*)?$""")

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
        return "PRIVATE KEY" in label || label == "PEM block"
    }

    private fun pemLabel(value: String): String? {
        val start = "-----BEGIN "
        if (!value.startsWith(start)) return null
        val rest = value.substring(start.length)
        val end = rest.indexOf("-----")
        if (end < 0) return "PEM block"
        val label = rest.substring(0, end)
        val allowed = label.all { (it.code < 128 && (it.isUpperCase() || it.isDigit())) || it == ' ' }
        return if (allowed && label.isNotEmpty() && label.none { it == '\n' || it == '\r' }) "PEM ($label)" else "PEM block"
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
}

/** What a one-time code row shows at a moment: the code, its grouped form and the countdown (Mac `TOTPDisplay`). */
public data class TotpDisplay(
    /** The digits: what is copied. */
    val code: String,
    /** Grouped for reading: "287 082". */
    val formatted: String,
    val secondsRemaining: Int,
    val period: Int,
) {
    /** Fraction of the period left: 1.0 right after the code changes, down to 1/period. */
    val progress: Double get() = secondsRemaining.toDouble() / period

    public companion object {
        public fun of(config: TotpConfig, at: Instant): TotpDisplay {
            val code = TotpGenerator.code(config, at)
            return TotpDisplay(code, group(code), TotpGenerator.secondsRemaining(config, at), maxOf(config.period, 1))
        }

        private fun group(code: String): String =
            if (code.length >= 4 && code.length % 2 == 0) code.substring(0, code.length / 2) + " " + code.substring(code.length / 2) else code
    }
}
