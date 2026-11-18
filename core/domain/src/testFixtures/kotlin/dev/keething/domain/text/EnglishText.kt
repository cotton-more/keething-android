package dev.keething.domain.text

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A [UserText] in the app's English, read from `app/src/main/res/values/` by its resource's name (the core has no
 * resources): so a core test still says the words the user sees, and fails when a key names no resource.
 */
public fun UserText.english(): String = when (this) {
    is UserText.Of -> EnglishResources.string(key.resource).formatted(args)
    is UserText.Counted -> EnglishResources.plural(key.resource, count).formatted(args)
    is UserText.Plain -> text
    is UserText.Listed -> when (items.size) {
        0 -> ""
        1 -> items[0].english()
        else -> EnglishResources.string("list_last").formatted(
            listOf(items.dropLast(1).joinToString(EnglishResources.string("list_separator")) { it.english() }, items.last()),
        )
    }
    is UserText.Joined -> items.joinToString(separator) { it.english() }
}

/** [english], or null for null. */
public fun UserText?.englishOrNull(): String? = this?.english()

private fun String.formatted(args: List<Any>): String =
    if (args.isEmpty()) this else String.format(Locale.US, this, *args.map { if (it is UserText) it.english() else it }.toTypedArray())

private object EnglishResources {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        // Set by the core modules' convention plugin: the app's res/values, beside them in the repository.
        val folder = File(System.getProperty("keething.texts") ?: error("keething.texts is not set"))
        val factory = DocumentBuilderFactory.newInstance()
        folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.forEach { file ->
            val root = factory.newDocumentBuilder().parse(file).documentElement
            root.children("string").forEach { strings[it.getAttribute("name")] = unescaped(it.textContent) }
            root.children("plurals").forEach { p ->
                plurals[p.getAttribute("name")] = p.children("item").associate { it.getAttribute("quantity") to unescaped(it.textContent) }
            }
        }
    }

    fun string(name: String): String = strings[name] ?: error("no string resource $name")

    fun plural(name: String, count: Int): String {
        val items = plurals[name] ?: error("no plurals resource $name")
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
