package dev.keething.kdbx.xml

import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.PayloadFailure
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/**
 * One way of reading the KeePass XML from the SAX parser's events ([KdbxXmlReader]'s document, [dev.keething.kdbx.tree.XmlTree]'s
 * tree): [failure] says why it stopped the parse, [wipe] overwrites the secrets it read so far.
 */
internal abstract class SaxReading<T> : DefaultHandler2() {
    var failure: KdbxException? = null
        private set

    /** What was read, once the parse is over. */
    abstract fun result(): T

    /** Overwrites every secret read so far (the read failed). */
    abstract fun wipe()

    protected fun fail(failure: KdbxException): Nothing {
        this.failure = failure
        throw SAXException(failure.message)
    }

    // A file has no DOCTYPE; refusing one rules out entity tricks with every parser.
    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        fail(KdbxException.Corrupt("XML with a DOCTYPE"))
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

    override fun fatalError(e: SAXParseException): Unit = throw e
}

internal object Sax {
    /**
     * Parses [input] once, each event going to every one of [readings] in turn: the file's XML is read as it is
     * decrypted, never held whole. On a failure every reading is wiped and the failure thrown as a [KdbxException]:
     * a reading's own, the payload's ([PayloadFailure]: a block's HMAC, the decryption, gzip), or the parser's (only
     * its position: its description may quote the document). [input] is left open: what follows the XML is the caller's.
     */
    fun parse(input: InputStream, vararg readings: SaxReading<*>) {
        val handler = readings.singleOrNull() ?: Tee(readings)
        try {
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            // The parser closes what it read at the end.
            reader.parse(InputSource(object : FilterInputStream(input) { override fun close() {} }))
        } catch (failure: SAXParseException) {
            readings.forEach(SaxReading<*>::wipe)
            throw readings.firstNotNullOfOrNull { it.failure } ?: carried(failure) ?: KdbxException.Corrupt("XML error at line ${failure.lineNumber}")
        } catch (failure: SAXException) {
            readings.forEach(SaxReading<*>::wipe)
            throw readings.firstNotNullOfOrNull { it.failure } ?: carried(failure) ?: KdbxException.Corrupt("XML")
        } catch (failure: IOException) {
            readings.forEach(SaxReading<*>::wipe)
            throw (failure as? PayloadFailure)?.failure ?: KdbxException.Corrupt("XML")
        }
    }

    /** A failure of the payload under the XML, which some parsers hand on wrapped in a [SAXException]. */
    private fun carried(failure: SAXException): KdbxException? =
        generateSequence<Throwable>(failure) { it.cause ?: (it as? SAXException)?.exception }.take(8).filterIsInstance<PayloadFailure>().firstOrNull()?.failure

    /** The events to each reading in turn. */
    private class Tee(private val readings: Array<out SaxReading<*>>) : DefaultHandler2() {
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            for (reading in readings) reading.startElement(uri, localName, qName, attributes)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            for (reading in readings) reading.characters(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            for (reading in readings) reading.endElement(uri, localName, qName)
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            for (reading in readings) reading.startDTD(name, publicId, systemId)
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun fatalError(e: SAXParseException): Unit = throw e
    }
}
