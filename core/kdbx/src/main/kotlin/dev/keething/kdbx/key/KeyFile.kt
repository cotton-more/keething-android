package dev.keething.kdbx.key

import dev.keething.domain.secret.Secret
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/** Why a key file cannot be used (keething-spec kdbx.key-files AC4, AC5). */
public sealed class KeyFileException(message: String) : Exception(message) {
    /** An empty file opens nothing (KeePassXC refuses it too). */
    public class Empty : KeyFileException("empty")

    /** A `KeyFile` XML of a version other than 1.0 or 2.0 ("none" when it has none). */
    public class UnsupportedVersion(public val version: String) : KeyFileException("version $version")

    /** A `KeyFile` XML without a usable key (no `Key/Data`, bad base64 or hex, not 32 bytes). */
    public class Malformed(public val why: String) : KeyFileException(why)

    /** A 2.0 file whose `Hash` does not match its key: mistyped or damaged. */
    public class HashMismatch : KeyFileException("hash mismatch")
}

/**
 * A KeePass key file (keething-spec kdbx.key-files; Mac `KeyFile`): any file whose 32-byte key is part of the
 * database's key. Read as KeePass and KeePassXC read it (KeePassXC's `FileKey.cpp`), in this order:
 * 1. **XML** with a `KeyFile` root: `Meta/Version` 1.0 — `Key/Data` is the key in base64; 2.0 (what KeePassXC and
 *    KeePass 2.47+ write, `.keyx`) — `Key/Data` is the key in hex, whitespace anywhere, and its `Hash` attribute, the
 *    first 4 bytes of the key's SHA-256 in hex, must match. Such XML that fails is an error, never hashed instead:
 *    it would open nothing and hide the mistake.
 * 2. exactly **32 bytes**: the key as it is;
 * 3. exactly **64 hex characters**: the key in hex;
 * 4. anything else: the **SHA-256 of the whole file**. An empty file is an error.
 *
 * A pure function of the file's bytes; reads no files itself. The XML is parsed as the database's is (no DOCTYPE,
 * no external entities): a key file with a DOCTYPE is not read as XML, so it is hashed. The XML's text passes
 * through `String`s, which cannot be wiped; the file holds the key in the clear on disk anyway.
 */
public object KeyFile {
    /** The 32-byte key of a key file with these [contents]; the caller wipes it. */
    public fun key(contents: ByteArray): Secret {
        if (contents.isEmpty()) throw KeyFileException.Empty()
        xmlKey(contents)?.let { return Secret.adopt(it) }
        if (contents.size == 32) return Secret.copyOf(contents)
        if (contents.size == 64) hex(contents.decodeToString())?.let { return Secret.adopt(it) }
        return Secret.adopt(MessageDigest.getInstance("SHA-256").digest(contents))
    }

    /** The key of a `KeyFile` XML; null when the contents are not one (not XML, or another root). */
    private fun xmlKey(contents: ByteArray): ByteArray? {
        if (!looksLikeXml(contents)) return null
        val parsed = parse(contents) ?: return null
        if (parsed.root != "KeyFile") return null
        val version = parsed.version.trim()
        val data = parsed.data ?: throw KeyFileException.Malformed("no Key/Data")
        val digits = data.filterNot(Char::isWhitespace)
        return when {
            version.startsWith("1.0") -> {
                val key = try {
                    Base64.getDecoder().decode(digits)
                } catch (_: IllegalArgumentException) {
                    throw KeyFileException.Malformed("Key/Data is not base64")
                }
                checkSize(key)
            }
            version.startsWith("2.0") -> {
                val key = checkSize(hex(digits) ?: throw KeyFileException.Malformed("Key/Data is not hex"))
                parsed.hash?.let { expected ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(key)
                    val wanted = hex(expected.filterNot(Char::isWhitespace))
                    val matches = wanted != null && MessageDigest.isEqual(wanted, digest.copyOf(4))
                    digest.fill(0)
                    if (!matches) {
                        key.fill(0)
                        throw KeyFileException.HashMismatch()
                    }
                }
                key
            }
            else -> throw KeyFileException.UnsupportedVersion(version.ifEmpty { "none" })
        }
    }

    private fun checkSize(key: ByteArray): ByteArray {
        if (key.size == 32) return key
        key.fill(0)
        throw KeyFileException.Malformed("the key is ${key.size} bytes, not 32")
    }

    /** Starts with `<` after a UTF-8 byte order mark and blanks: a binary key is not handed to the parser. */
    private fun looksLikeXml(contents: ByteArray): Boolean {
        val bom = contents.size >= 3 && contents[0] == 0xEF.toByte() && contents[1] == 0xBB.toByte() && contents[2] == 0xBF.toByte()
        val first = ((if (bom) 3 else 0) until contents.size).firstOrNull { contents[it].toInt().toChar() !in " \t\r\n" }
        return first != null && contents[first] == '<'.code.toByte()
    }

    private class Parsed(val root: String, val version: String, val data: String?, val hash: String?)

    /** The parts of the XML a key file has; null when it does not parse. */
    private fun parse(contents: ByteArray): Parsed? {
        val handler = Handler()
        return try {
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(ByteArrayInputStream(contents)))
            Parsed(handler.root ?: return null, handler.version?.toString() ?: "", handler.data?.toString(), handler.hash)
        } catch (_: SAXException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** The first `Meta/Version` and `Key/Data` under the root, and the latter's `Hash`. */
    private class Handler : DefaultHandler2() {
        var root: String? = null
        var version: StringBuilder? = null
        var data: StringBuilder? = null
        var hash: String? = null
        private val path = ArrayList<String>()
        private var collecting: StringBuilder? = null

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            val name = qName.ifEmpty { localName }
            if (path.isEmpty()) root = name
            path += name
            collecting = when {
                path.size != 3 -> null
                version == null && path[1] == "Meta" && name == "Version" -> StringBuilder().also { version = it }
                data == null && path[1] == "Key" && name == "Data" -> StringBuilder().also {
                    data = it
                    hash = attributes.getValue("Hash")
                }
                else -> null
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            collecting?.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            path.removeAt(path.lastIndex)
            collecting = null
        }

        // A key file has no DOCTYPE; refusing one rules out entity tricks with every parser.
        override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit = throw SAXException("DOCTYPE")

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun fatalError(e: SAXParseException): Unit = throw e
    }

    /** Hex digits (either case) to bytes; null for an odd count or anything else. */
    internal fun hex(digits: String): ByteArray? {
        if (digits.length % 2 != 0) return null
        val out = ByteArray(digits.length / 2)
        for (i in out.indices) {
            val high = digits[2 * i].hexValue()
            val low = digits[2 * i + 1].hexValue()
            if (high < 0 || low < 0) {
                out.fill(0)
                return null
            }
            out[i] = (high shl 4 or low).toByte()
        }
        return out
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> -1
    }
}
