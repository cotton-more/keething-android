package dev.keething.kdbx.tree

import dev.keething.domain.secret.Secret
import dev.keething.domain.text.XmlText
import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.crypto.KeyStream
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.xml.Sax
import dev.keething.kdbx.xml.SaxReading
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import org.xml.sax.Attributes

/**
 * Reads the KeePass XML into an [XElement] tree and writes it back (SPEC §3.3; Mac `XMLTree`). Text is unescaped
 * in the tree and escaped again on writing (`&`, `<`, `>`, and a carriage return as `&#13;` so that it survives
 * the next read; in attributes also `"`, a newline and a tab). What XML 1.0 cannot hold is left out of the text
 * written ([XmlText]; keething-spec kdbx.hardening AC7), as KeePass 2.x and KeePassXC do; a protected value is base64.
 */
internal object XmlTree {
    /**
     * The deepest nesting of elements read (keething-spec kdbx.hardening AC8): a deeper file is refused as damaged, by
     * this reader and by [KdbxXmlReader]. Writing the tree, copying it and its other walks recurse once per level; the
     * whole save is tested at this depth on a stack a quarter of the size the app's background threads have. No KeePass
     * app nests groups anywhere near so deep.
     */
    const val MAX_DEPTH = 128

    /**
     * A value marked `Protected="True"` (base64 of its bytes XORed with [stream], in document order) becomes a
     * secret; so does one marked `ProtectInMemory="True"`, how `keepassxc-cli export` writes a protected value in
     * the clear (the fixtures).
     */
    fun parse(xml: ByteArray, stream: KeyStream? = null): XElement = parse(ByteArrayInputStream(xml), stream)

    /** As the XML is decrypted: [input] is read to the end of the document and left open. */
    fun parse(input: InputStream, stream: KeyStream? = null): XElement = reading(stream).also { Sax.parse(input, it) }.result()

    /** The tree built from the parser's events: for one parse that feeds others too ([Sax.parse]). */
    fun reading(stream: KeyStream?): SaxReading<XElement> = Builder(stream)

    /**
     * Writes the document as UTF-8. [protect] turns a secret into what the file stores ([seal]); a tree with a
     * secret is never written without it.
     */
    fun write(root: XElement, out: OutputStream, protect: ((Secret) -> String)?) {
        val sink = Utf8Sink(out)
        sink.ascii("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        write(root, sink, protect)
        sink.ascii("\n")
        sink.flush()
    }

    /** The document as UTF-8 with every protected value left empty (still marked protected): for the schema's checks. */
    fun withoutSecrets(root: XElement): ByteArray = ByteArrayOutputStream().also { write(root, it) { "" } }.toByteArray()

    /** The secret's bytes XORed with [stream] (which moves on by their length), in base64. */
    fun seal(secret: Secret, stream: ChaCha20): String = secret.useBytes { bytes ->
        val sealed = bytes.copyOf()
        stream.apply(sealed)
        Base64.getEncoder().encodeToString(sealed).also { sealed.fill(0) }
    }

    private fun write(element: XElement, sink: Utf8Sink, protect: ((Secret) -> String)?) {
        val secret = element.secret
        sink.ascii("<")
        sink.text(element.name, attribute = false)
        for ((name, value) in element.attributes) {
            // A value read as `Protected="False"` and protected since: its one `Protected` is written below.
            if (secret != null && name == "Protected") continue
            sink.ascii(" ")
            sink.text(name, attribute = false)
            sink.ascii("=\"")
            sink.text(value, attribute = true)
            sink.ascii("\"")
        }
        if (secret != null) {
            checkNotNull(protect) { "a protected value is written only sealed" }
            sink.ascii(" Protected=\"True\"")
            if (secret.isEmpty()) {
                sink.ascii("/>")
                return
            }
            sink.ascii(">")
            sink.ascii(protect(secret))
        } else {
            val only = element.onlyText
            val nodes = if (only == null) element.nodes else emptyList()
            if (only == null && nodes.isEmpty()) {
                sink.ascii("/>")
                return
            }
            sink.ascii(">")
            if (only != null) sink.text(only, attribute = false)
            for (child in nodes) {
                when (child) {
                    is XElement -> write(child, sink, protect)
                    is XText -> sink.text(child.text, attribute = false)
                }
            }
        }
        sink.ascii("</")
        sink.text(element.name, attribute = false)
        sink.ascii(">")
    }

    private class Builder(private val stream: KeyStream?) : SaxReading<XElement>() {
        private var root: XElement? = null

        private val stack = ArrayList<XElement>()

        /** Whether each element on [stack] holds a protected value: sealed with the stream, or in the clear. */
        private val protections = ArrayList<Protection>()
        private val pending = StringBuilder()

        /** One instance per name, and per run of whitespace: a large file repeats them a few hundred thousand times. */
        private val names = HashMap<String, String>()
        private val whitespace = HashMap<String, XText>()
        private val values = HashMap<String, String>()

        override fun result(): XElement = root ?: throw KdbxException.Corrupt("empty XML")

        override fun wipe() {
            root?.wipeSecrets()
            stack.forEach(XElement::wipeSecrets)
        }

        private fun name(name: String) = names.getOrPut(name) { name }

        /** The text before a child element: between elements it is whitespace, the same few runs over and over. */
        private fun flushText() {
            if (pending.isEmpty()) return
            val top = stack.lastOrNull()
            if (top != null) {
                val text = pending.toString()
                top.children.add(if (text.isBlank()) whitespace.getOrPut(text) { XText(text) } else XText(text))
            }
            pending.setLength(0)
        }

        /** The text after the last child, or all an element holds: then it is kept as the text itself. */
        private fun endText(element: XElement) {
            if (pending.isEmpty()) return
            if (element.onlyText == null && element.nodes.isEmpty()) {
                element.text = value(pending)
                pending.setLength(0)
            } else {
                flushText()
            }
        }

        /** A short value (True, False, a field's name, a count) is one string however often the file has it. */
        private fun value(text: CharSequence): String {
            val value = text.toString()
            if (value.length > SHORT_VALUE) return value
            return values[value] ?: value.also { if (values.size < MAX_VALUES) values[it] = it }
        }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            flushText()
            if (stack.size == MAX_DEPTH) fail(KdbxException.Corrupt("XML nested deeper than $MAX_DEPTH levels"))
            val element = XElement(name(qName.ifEmpty { localName }))
            var protection = Protection.NONE
            for (i in 0 until attributes.length) {
                val name = attributes.getQName(i).ifEmpty { attributes.getLocalName(i) }
                val value = attributes.getValue(i)
                when {
                    name == "Protected" && value.equals("true", ignoreCase = true) -> protection = Protection.SEALED
                    name == "ProtectInMemory" && value.equals("true", ignoreCase = true) -> protection = Protection.CLEAR
                    else -> element.setAttribute(name(name), value)
                }
            }
            if (protection == Protection.SEALED && stream == null) fail(KdbxException.Corrupt("protected value without a stream"))
            val parent = stack.lastOrNull()
            if (parent == null) root = element else parent.children.add(element)
            stack.add(element)
            protections.add(protection)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            pending.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val element = stack.last()
            when (protections.removeAt(protections.lastIndex)) {
                Protection.SEALED -> element.secret = unseal()
                Protection.CLEAR -> {
                    element.secret = Secret.of(pending.toString())
                    pending.setLength(0)
                }
                Protection.NONE -> endText(element)
            }
            element.trim()
            stack.removeAt(stack.lastIndex)
        }

        /** The value that just ended; the stream moves on by its length. */
        private fun unseal(): Secret {
            val bytes = try {
                Base64.getMimeDecoder().decode(pending.toString())
            } catch (_: IllegalArgumentException) {
                fail(KdbxException.Corrupt("protected value"))
            }
            pending.setLength(0)
            stream!!.apply(bytes)
            return Secret.adopt(bytes)
        }
    }

    /** The longest value [Builder] keeps one string of, and how many of them at most. */
    private const val SHORT_VALUE = 12
    private const val MAX_VALUES = 4096

    private enum class Protection { NONE, SEALED, CLEAR }

    /** UTF-8 into a buffer, escaping as KeePass expects. */
    private class Utf8Sink(private val out: OutputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var used = 0

        fun ascii(text: String) {
            for (char in text) byte(char.code)
        }

        fun text(text: String, attribute: Boolean) {
            var i = 0
            while (i < text.length) {
                val char = text[i]
                when {
                    char == '&' -> ascii("&amp;")
                    char == '<' -> ascii("&lt;")
                    char == '>' -> ascii("&gt;")
                    char == '\r' -> ascii("&#13;")
                    char == '"' && attribute -> ascii("&quot;")
                    char == '\n' && attribute -> ascii("&#10;")
                    char == '\t' && attribute -> ascii("&#9;")
                    !XmlText.holds(char) -> {}
                    char.code < 0x80 -> byte(char.code)
                    char.code < 0x800 -> {
                        byte(0xC0 or (char.code shr 6))
                        byte(0x80 or (char.code and 0x3F))
                    }
                    char.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        val code = Character.toCodePoint(char, text[i + 1])
                        byte(0xF0 or (code shr 18))
                        byte(0x80 or ((code shr 12) and 0x3F))
                        byte(0x80 or ((code shr 6) and 0x3F))
                        byte(0x80 or (code and 0x3F))
                        i++
                    }
                    char.isSurrogate() -> {
                        // A lone surrogate is no character: U+FFFD.
                        byte(0xEF)
                        byte(0xBF)
                        byte(0xBD)
                    }
                    else -> {
                        byte(0xE0 or (char.code shr 12))
                        byte(0x80 or ((char.code shr 6) and 0x3F))
                        byte(0x80 or (char.code and 0x3F))
                    }
                }
                i++
            }
        }

        private fun byte(value: Int) {
            if (used == buffer.size) flush()
            buffer[used++] = value.toByte()
        }

        fun flush() {
            out.write(buffer, 0, used)
            used = 0
        }
    }
}
