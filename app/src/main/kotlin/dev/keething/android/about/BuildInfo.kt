package dev.keething.android.about

import android.content.res.AssetManager
import dev.keething.android.R
import dev.keething.android.ui.UiText
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What About says of this build (SPEC §6.2 "About"): written by Gradle at every build into the asset
 * `build-info.properties` (app/build.gradle.kts, `BuildInfo`).
 */
internal data class BuildInfo(
    /** Empty when the asset did not say. */
    val version: String,
    val versionCode: String,
    /** When it was built; null when the asset did not say. */
    val built: Instant?,
    /** The commit it was built from, short; empty outside a git checkout. */
    val commit: String,
    /** Built with changes not committed. */
    val dirty: Boolean,
) {
    /** "0.0.1 · build 1": the name for people, then Android's version code, said what it is. */
    val versionText: UiText get() = uiText(R.string.about_version_text, if (version.isEmpty()) uiText(R.string.about_unknown) else uiPlain(version), versionCode)

    /** "becc85f", "becc85f with uncommitted changes", or "unknown". */
    val commitText: UiText
        get() = when {
            commit.isEmpty() -> uiText(R.string.about_unknown)
            dirty -> uiText(R.string.about_commit_dirty, commit)
            else -> uiPlain(commit)
        }

    /** "2 November 2026, 19:56" in the phone's time zone; "unknown" without a time. */
    fun builtText(zone: ZoneId = ZoneId.systemDefault()): UiText =
        built?.let { uiPlain(DateTimeFormatter.ofPattern(BUILT_PATTERN, Locale.ENGLISH).withZone(zone).format(it)) } ?: uiText(R.string.about_unknown)

    companion object {
        private const val ASSET = "build-info.properties"
        private const val BUILT_PATTERN = "d MMMM yyyy, HH:mm" // not user text: a date pattern

        /** The lines `key=value` Gradle wrote; what is missing or unreadable reads as unknown. */
        fun parse(text: String): BuildInfo {
            val fields = text.lineSequence().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
            }.toMap()
            return BuildInfo(
                version = fields["version"].orEmpty(),
                versionCode = fields["versionCode"].orEmpty().ifEmpty { "?" }, // not user text
                built = fields["built"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
                commit = fields["commit"].orEmpty(),
                dirty = fields["dirty"] == "true",
            )
        }

        fun read(assets: AssetManager): BuildInfo =
            parse(runCatching { assets.open(ASSET).use { it.readBytes().decodeToString() } }.getOrDefault(""))
    }
}
