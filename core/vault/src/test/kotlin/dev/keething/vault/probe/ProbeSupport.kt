package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess
import org.w3c.dom.Element
import org.w3c.dom.Node

// What the probes on a copy of a database share: KeePassXC as the independent reader, the password from standard input.

/** KeePassXC's command-line tool: an independent reader of what was written. */
internal object KeePassXc {
    private val executable = File("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")

    /** The file's XML export, parsed; null when it cannot be opened. */
    fun export(file: File, password: Secret): Element? {
        if (!executable.canExecute()) fail("keepassxc-cli not found at $executable")
        val process = ProcessBuilder(executable.path, "export", "-q", "-f", "xml", file.path).start()
        process.outputStream.use { out -> password.useBytes(out::write); out.write('\n'.code) }
        val xml = process.inputStream.use { it.readBytes() }
        process.errorStream.use { it.readBytes() }
        if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) return null
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        xml.fill(0)
        return document.documentElement.also(::sortCustomData)
    }

    /** KeePassXC writes CustomData items in a random order (a hash map): sorted by key before comparing. */
    fun sortCustomData(element: Element) {
        if (element.tagName == "CustomData") {
            val items = element.children().filter { it.tagName == "Item" }.sortedBy { it.child("Key")?.textContent.orEmpty() }
            items.forEach { element.removeChild(it); element.appendChild(it) }
        }
        element.children().forEach(::sortCustomData)
    }
}

internal fun Element.children(): List<Element> =
    (0 until childNodes.length).map(childNodes::item).filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

internal fun Element.child(name: String): Element? = children().firstOrNull { it.tagName == name }

internal fun Element.entryCount(): Int {
    if (tagName == "History") return 0
    return (if (tagName == "Entry") 1 else 0) + children().sumOf { it.entryCount() }
}

/** Where two trees differ: element paths and the kind of difference, never a value. */
internal fun differences(a: Element, b: Element, limit: Int = 1000): List<String> {
    val found = mutableListOf<String>()
    fun walk(x: Element, y: Element, path: String) {
        if (found.size >= limit) return
        if (x.tagName != y.tagName) return run { found += "$path: element ${x.tagName} vs ${y.tagName}" }
        val xa = (0 until x.attributes.length).map { x.attributes.item(it).let { a -> "${a.nodeName}=${a.nodeValue}" } }
        val ya = (0 until y.attributes.length).map { y.attributes.item(it).let { a -> "${a.nodeName}=${a.nodeValue}" } }
        if (xa != ya) found += "$path: attributes differ"
        val xe = x.children()
        val ye = y.children()
        if (xe.isEmpty() && ye.isEmpty()) {
            if (x.textContent != y.textContent) found += "$path: text differs (${x.textContent.length} vs ${y.textContent.length} characters)"
            return
        }
        if (xe.size != ye.size) found += "$path: ${xe.size} vs ${ye.size} child elements"
        val seen = mutableMapOf<String, Int>()
        for ((cx, cy) in xe.zip(ye)) {
            val n = seen.merge(cx.tagName, 1, Int::plus)!! - 1
            walk(cx, cy, "$path/${cx.tagName}[$n]")
        }
    }
    walk(a, b, a.tagName)
    return found
}

/** One line of standard input, as bytes: the password is never a String here, and the buffer is wiped. */
internal fun readPasswordLine(): Secret {
    val buffer = ByteArray(4096)
    var length = 0
    while (length < buffer.size) {
        val b = System.`in`.read()
        if (b == -1 || b == '\n'.code) break
        if (b != '\r'.code) buffer[length++] = b.toByte()
    }
    return Secret.copyOf(buffer, 0, length).also { buffer.fill(0) }
}

internal fun fail(message: String): Nothing {
    println(message)
    exitProcess(1)
}
