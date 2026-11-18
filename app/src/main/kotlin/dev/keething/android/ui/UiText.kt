package dev.keething.android.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.keething.android.R
import dev.keething.domain.text.UserText
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * A text the user sees, made where no resources are at hand (a view model, a plain function) and turned into words
 * where it is shown ([resolve]); so a view model's notice follows the phone's language (keething-spec plans T13).
 * An argument may itself be a [UiText]; anything else goes into the format as it is.
 */
internal sealed interface UiText {
    /** A string resource, formatted with [args]. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A plurals resource chosen by [count] and formatted with [args] (by default the count itself). */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Words that are not the app's own and stay as they are: a name, a file name, a message from elsewhere. */
    data class Plain(val text: String) : UiText

    /** "A", "A and B", "A, B and C" in the phone's language. */
    data class Listed(val items: List<UiText>) : UiText

    /** [items] set one after another with [separator] between, a mark that is no word (" · ", "\n"). */
    data class Joined(val items: List<UiText>, val separator: String) : UiText

    /**
     * [text], said on its own elsewhere, set inside another sentence: after a colon its first letter in lower case
     * (in the phone's language, [lowerFirst]: "Not upgraded: the file …"), before more words its full stop dropped
     * ([dropFullStop]).
     */
    data class Inside(val text: UiText, val lowerFirst: Boolean, val dropFullStop: Boolean) : UiText

    /**
     * [value] (a date, a time, or both) written by the pattern in the string resource [pattern] (java.time's letters),
     * in the language shown: "Mon 9 Nov" / «пн, 9 нояб.».
     */
    data class Date(val value: TemporalAccessor, @StringRes val pattern: Int) : UiText
}

internal fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

internal fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

internal fun uiPlain(text: String): UiText = UiText.Plain(text)

internal fun uiDate(value: TemporalAccessor, @StringRes pattern: Int): UiText = UiText.Date(value, pattern)

internal fun uiListed(items: List<UiText>): UiText = UiText.Listed(items)

internal fun UiText.afterColon(): UiText = UiText.Inside(this, lowerFirst = true, dropFullStop = false)

internal fun UiText.withoutFullStop(): UiText = UiText.Inside(this, lowerFirst = false, dropFullStop = true)

internal fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.resolved(resources))
    is UiText.Plural -> resources.getQuantityString(id, count, *args.resolved(resources))
    is UiText.Plain -> text
    is UiText.Listed -> when (items.size) {
        0 -> ""
        1 -> items[0].resolve(resources)
        else -> resources.getString(
            R.string.list_last,
            items.dropLast(1).joinToString(resources.getString(R.string.list_separator)) { it.resolve(resources) },
            items.last().resolve(resources),
        )
    }
    is UiText.Inside -> inside(text.resolve(resources), resources.configuration.locales[0])
    is UiText.Joined -> items.joinToString(separator) { it.resolve(resources) }
    is UiText.Date -> DateTimeFormatter.ofPattern(resources.getString(pattern), resources.configuration.locales[0]).format(value)
}

internal fun UiText.Inside.inside(said: String, locale: Locale): String =
    said.let { if (dropFullStop) it.removeSuffix(".") else it }.let { if (lowerFirst) it.replaceFirstChar { c -> c.lowercase(locale) } else it }

/** The text in the language of the configuration shown. */
@Composable
@ReadOnlyComposable
internal fun UiText.resolve(): String = resolve(LocalResources.current)

private fun List<Any>.resolved(resources: Resources): Array<Any> =
    map {
        when (it) {
            is UiText -> it.resolve(resources)
            is UserText -> it.ui().resolve(resources)
            else -> it
        }
    }.toTypedArray()
