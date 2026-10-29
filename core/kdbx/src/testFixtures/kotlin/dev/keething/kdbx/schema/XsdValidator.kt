package dev.keething.kdbx.schema

import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/**
 * KeePass's `KDBX_XML.xsd` (`fixtures/`, as published) through the JDK's validator: for the tests and the probe
 * (SPEC §3.3: every file they write is held to it); the app itself checks with [KdbxSchema] instead. Mac
 * `XSDValidator`.
 */
public object XsdValidator {
    /** The schema as published; its colour pattern escapes `#` as `#`, .NET's syntax, which the JDK refuses. */
    public val official: File = File(System.getProperty("keething.fixtures"), "KDBX_XML.xsd")

    private val schema: Schema by lazy {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.newSchema(StreamSource(StringReader(official.readText().replace("\\u0023", "#"))))
    }

    /**
     * The validator's messages for what in [xml] the schema does not allow (not well-formed XML included); empty
     * when it is valid. The messages may quote values: never print them for a real database.
     */
    public fun errors(xml: ByteArray): List<String> {
        val found = mutableListOf<String>()
        val validator = schema.newValidator()
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) {}

            override fun error(e: SAXParseException) {
                found += "line ${e.lineNumber}: ${e.message}"
            }

            override fun fatalError(e: SAXParseException): Unit = throw e
        }
        try {
            validator.validate(StreamSource(ByteArrayInputStream(xml)))
        } catch (e: SAXException) {
            found += "not well-formed: ${e.message}"
        }
        return found
    }
}
