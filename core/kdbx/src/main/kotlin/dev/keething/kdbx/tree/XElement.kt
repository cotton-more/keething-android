package dev.keething.kdbx.tree

import dev.keething.domain.secret.Secret

/** A child of an element: another element, or text. */
internal sealed interface XNode

/** Text between elements or inside one; immutable, so identical runs of whitespace are shared. */
internal class XText(val text: String) : XNode

/**
 * An element of the whole KeePass XML, to change it and write it back (SPEC §3.3; Mac `XElement`). Everything
 * the file holds is kept, also the text between elements, and written back in order; only what an edit
 * touches changes.
 *
 * A value the file stores protected (`Protected="True"`) is a [secret], decrypted straight into it, never text:
 * the tree holds no password in the clear (SPEC §4). Elements compare by identity.
 */
internal class XElement(val name: String) : XNode {
    constructor(name: String, text: String) : this(name) {
        if (text.isNotEmpty()) content = text
    }

    private var attributeList: ArrayList<Pair<String, String>>? = null

    /**
     * Nothing, the text alone, or every child in order. Most elements of a file are a value with nothing but its text:
     * kept as the string itself, with no list and no [XText] around it, a large file's tree is about half the size
     * (keething-spec app.large-database AC2).
     */
    private var content: Any? = null

    /**
     * Every child in order, to change them. An element holding only its text gets a list of its own when this is
     * asked for: reading the tree goes through [nodes], [text] and [elements] instead.
     */
    @Suppress("UNCHECKED_CAST")
    val children: MutableList<XNode>
        get() = content as? ArrayList<XNode> ?: ArrayList<XNode>(2).also { list ->
            (content as String?)?.let { list.add(XText(it)) }
            content = list
        }

    /** Every child in order, to read. */
    @Suppress("UNCHECKED_CAST")
    val nodes: List<XNode>
        get() = when (val content = content) {
            null -> emptyList()
            is String -> listOf(XText(content))
            else -> content as List<XNode>
        }

    /** In the order of the file; new ones at the end. */
    val attributes: List<Pair<String, String>> get() = attributeList ?: emptyList()

    /**
     * The protected value: written back sealed with the new file's inner stream. Setting one removes the text
     * and wipes the secret it replaces (the tree owns its secrets).
     */
    var secret: Secret? = null
        set(value) {
            if (field !== value) field?.wipe()
            field = value
            if (value != null) removeText()
        }

    /** The text when it is all the element holds: how most values are kept. */
    val onlyText: String? get() = content as? String

    val hasElements: Boolean get() = (content as? ArrayList<*>)?.any { it is XElement } == true

    val elements: List<XElement> get() = (content as? ArrayList<*>)?.filterIsInstance<XElement>() ?: emptyList()

    fun first(name: String): XElement? = (content as? ArrayList<*>)?.firstOrNull { it is XElement && it.name == name } as XElement?

    fun all(name: String): List<XElement> =
        (content as? ArrayList<*>)?.filter { it is XElement && it.name == name }?.map { it as XElement } ?: emptyList()

    /** The text directly inside. Setting it keeps the child elements and drops a [secret]. */
    var text: String
        get() = when (val content = content) {
            null -> ""
            is String -> content
            else -> {
                val list = content as ArrayList<*>
                val only = list.singleOrNull()
                if (only is XText) only.text else buildString { list.forEach { if (it is XText) append(it.text) } }
            }
        }
        set(value) {
            secret = null
            removeText()
            val list = content as? ArrayList<*>
            when {
                value.isEmpty() -> {}
                list == null -> content = value
                else -> children.add(0, XText(value))
            }
        }

    fun attribute(name: String): String? = attributeList?.firstOrNull { it.first == name }?.second

    /** Sets (in its place) or, with null, removes an attribute. */
    fun setAttribute(name: String, value: String?) {
        val list = attributeList
        val index = list?.indexOfFirst { it.first == name } ?: -1
        when {
            value == null -> if (index >= 0) list!!.removeAt(index)
            index >= 0 -> list!![index] = name to value
            else -> (list ?: ArrayList<Pair<String, String>>(1).also { attributeList = it }).add(name to value)
        }
    }

    /** A deep copy with secrets of its own (an entry going into its history). */
    fun copy(): XElement {
        val copy = XElement(name)
        attributeList?.let { copy.attributeList = ArrayList(it) }
        copy.content = when (val content = content) {
            is ArrayList<*> -> ArrayList<XNode>(content.size).also { list -> content.forEach { list.add(if (it is XElement) it.copy() else it as XNode) } }
            else -> content
        }
        secret?.let { copy.secret = it.copy() }
        return copy
    }

    fun append(element: XElement) {
        children.add(element)
    }

    fun remove(element: XElement) {
        (content as? ArrayList<*>)?.removeAll { it === element }
    }

    /** Inserts before [anchor] (at the end when it is not a child). */
    fun insertBefore(element: XElement, anchor: XElement?) {
        val index = indexOf(anchor)
        children.add(if (index >= 0) index else children.size, element)
    }

    /** Inserts after [anchor] (at the end when it is not a child). */
    fun insertAfter(element: XElement, anchor: XElement?) {
        val index = indexOf(anchor)
        children.add(if (index >= 0) index + 1 else children.size, element)
    }

    /** Overwrites every secret in the tree below and at this element. */
    fun wipeSecrets() {
        secret?.wipe()
        (content as? ArrayList<*>)?.forEach { if (it is XElement) it.wipeSecrets() }
    }

    /** The list of children no larger than it is: called once the tree's reading has added the last one. */
    internal fun trim() {
        (content as? ArrayList<*>)?.trimToSize()
    }

    private fun removeText() {
        when (val content = content) {
            is String -> this.content = null
            is ArrayList<*> -> content.removeAll { it is XText }
        }
    }

    private fun indexOf(anchor: XElement?): Int =
        if (anchor == null) -1 else (content as? ArrayList<*>)?.indexOfFirst { it === anchor } ?: -1

    override fun toString(): String = "<$name>"
}
