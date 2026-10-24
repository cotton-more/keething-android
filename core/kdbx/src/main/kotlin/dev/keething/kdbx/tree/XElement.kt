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
        if (text.isNotEmpty()) children.add(XText(text))
    }

    private var attributeList: ArrayList<Pair<String, String>>? = null

    val children: MutableList<XNode> = ArrayList(1)

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
            if (value != null) children.removeAll { it is XText }
        }

    val elements: List<XElement> get() = children.filterIsInstance<XElement>()

    fun first(name: String): XElement? = children.firstOrNull { it is XElement && it.name == name } as XElement?

    fun all(name: String): List<XElement> = children.filter { it is XElement && it.name == name }.map { it as XElement }

    /** The text directly inside. Setting it keeps the child elements and drops a [secret]. */
    var text: String
        get() {
            val only = children.singleOrNull()
            if (only is XText) return only.text
            return buildString { children.forEach { if (it is XText) append(it.text) } }
        }
        set(value) {
            secret = null
            children.removeAll { it is XText }
            if (value.isNotEmpty()) children.add(0, XText(value))
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
        for (child in children) copy.children.add(if (child is XElement) child.copy() else child)
        secret?.let { copy.secret = it.copy() }
        return copy
    }

    fun append(element: XElement) {
        children.add(element)
    }

    fun remove(element: XElement) {
        children.removeAll { it === element }
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
        children.forEach { if (it is XElement) it.wipeSecrets() }
    }

    private fun indexOf(anchor: XElement?): Int = if (anchor == null) -1 else children.indexOfFirst { it === anchor }

    override fun toString(): String = "<$name>"
}
