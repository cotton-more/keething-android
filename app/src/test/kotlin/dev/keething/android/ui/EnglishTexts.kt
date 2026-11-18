package dev.keething.android.ui

import dev.keething.android.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A [UiText] in the app's English, read from `src/main/res/values/` as the phone would (a unit test has no
 * resources): so a test still says the words the user sees.
 */
internal fun UiText.english(): String = when (this) {
    is UiText.Res -> EnglishTexts.string(id).formatted(args)
    is UiText.Plural -> EnglishTexts.plural(id, count).formatted(args)
    is UiText.Plain -> text
    is UiText.Listed -> when (items.size) {
        0 -> ""
        1 -> items[0].english()
        else -> EnglishTexts.string(R.string.list_last).formatted(
            listOf(items.dropLast(1).joinToString(EnglishTexts.string(R.string.list_separator)) { it.english() }, items.last()),
        )
    }
    is UiText.Inside -> inside(text.english(), Locale.US)
}

private fun String.formatted(args: List<Any>): String =
    if (args.isEmpty()) this else String.format(Locale.US, this, *args.map { if (it is UiText) it.english() else it }.toTypedArray())

private object EnglishTexts {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()
    private val stringNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }

    init {
        val factory = DocumentBuilderFactory.newInstance()
        File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.forEach { file ->
            val root = factory.newDocumentBuilder().parse(file).documentElement
            root.children("string").forEach { strings[it.getAttribute("name")] = unescaped(it.textContent) }
            root.children("plurals").forEach { p ->
                plurals[p.getAttribute("name")] = p.children("item").associate { it.getAttribute("quantity") to unescaped(it.textContent) }
            }
        }
    }

    fun string(id: Int): String = strings.getValue(stringNames.getValue(id))

    fun plural(id: Int, count: Int): String {
        val items = plurals.getValue(pluralNames.getValue(id))
        return (if (count == 1) items["one"] else null) ?: items.getValue("other")
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    /** aapt's escapes: `\'`, `\"`, `\n`, `\t`, `\@`, `\?`, `\\`, `\uXXXX`. */
    private fun unescaped(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 == raw.length) { append(c); i++; continue }
            when (val n = raw[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'u' -> { append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> append(n)
            }
            i += 2
        }
    }
}
