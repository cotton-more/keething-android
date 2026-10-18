package dev.keepassbar.kdbx.xml

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.CustomIcon
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.kdbx.crypto.ChaCha20
import dev.keepassbar.kdbx.reader.KdbxException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.util.Base64
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/**
 * Event-based reader of the KeePass XML (Mac `KDBXXMLReader`): groups, entries, `Meta`; histories are
 * skipped. Never builds a tree.
 *
 * Unlike the Mac app, which first turns the protected values into plain text inside the XML, a value marked
 * `Protected="True"` (base64 of its bytes XORed with the inner stream, in document order) is decrypted
 * straight into a [Secret]: the XML never holds a password in the clear. The stream runs through the
 * protected values of the histories too, or every later value would come out wrong. A value marked
 * `ProtectInMemory="True"` is how `keepassxc-cli export` writes a protected value in the clear (the
 * fixtures).
 */
internal object KdbxXmlReader {
    fun read(xml: ByteArray, offset: Int = 0, length: Int = xml.size - offset, stream: ChaCha20? = null): KdbxDocument {
        val handler = Handler(stream)
        try {
            val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(ByteArrayInputStream(xml, offset, length)))
            return handler.document()
        } catch (failure: SAXParseException) {
            handler.wipe()
            // Only the position: the parser's description may quote the document.
            throw handler.failure ?: KdbxException.Corrupt("XML error at line ${failure.lineNumber}")
        } catch (failure: SAXException) {
            handler.wipe()
            throw handler.failure ?: KdbxException.Corrupt("XML")
        } catch (_: IOException) {
            handler.wipe()
            throw KdbxException.Corrupt("XML")
        }
    }

    private class GroupFrame(val index: Int) {
        var uuid = ""
        var name = ""
    }

    private class EntryFrame {
        var uuid = ""
        val fields = mutableListOf<KdbxField>()
        var tags = ""
        var expires = ""
        var expiryTime = ""
        var lastModified = ""
        var created = ""
        val attachments = mutableListOf<KdbxAttachmentRef>()
        var customIconUuid = ""
    }

    private class Handler(private val stream: ChaCha20?) : DefaultHandler2() {
        var failure: KdbxException? = null
            private set

        private val stack = ArrayList<String>()

        /** Stack depth of the `History` element being skipped; 0 when not skipping. */
        private var skipDepth = 0
        private val groupFrames = ArrayList<GroupFrame>()
        private val groups = ArrayList<KdbxGroup?>()
        private val entries = ArrayList<KdbxEntry>()
        private var entry: EntryFrame? = null

        private var recycleBinUuid = ""
        private var recycleBinEnabled = ""
        private var defaultUserName = ""
        private val customIcons = LinkedHashMap<String, CustomIcon>()
        private var iconUuid = ""
        private var iconData = ""

        // Text capture for small elements.
        private var capturing = false
        private val text = StringBuilder()

        // The current `String` / `Binary` item of an entry.
        private var itemKey = ""
        private var itemValue: FieldValue? = null
        private var itemInTheClear = false
        private var itemRef: Int? = null

        // The current value marked `Protected="True"`: its base64 text and its depth in the stack.
        private var protectedDepth = 0
        private val protectedText = StringBuilder()

        fun document(): KdbxDocument = KdbxDocument(
            KdbxMeta(recycleBinUuid, !recycleBinEnabled.equals("false", ignoreCase = true), defaultUserName, customIcons),
            groups.filterNotNull(),
            entries,
        )

        /** Overwrites every secret read so far (the read failed). */
        fun wipe() {
            fun wipe(value: FieldValue?) {
                (value as? FieldValue.Protected)?.secret?.wipe()
            }
            entries.forEach { e -> e.fields.forEach { wipe(it.value) } }
            entry?.fields?.forEach { wipe(it.value) }
            wipe(itemValue)
        }

        private fun fail(failure: KdbxException): Nothing {
            this.failure = failure
            throw SAXException(failure.message)
        }

        private fun beginCapture() {
            capturing = true
            text.setLength(0)
        }

        private fun inRoot() = stack.size >= 2 && stack[1] == "Root"

        private fun parent() = stack.getOrNull(stack.size - 2) ?: ""

        private fun grand() = stack.getOrNull(stack.size - 3) ?: ""

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            val name = qName.ifEmpty { localName }
            if (stack.isEmpty() && name != "KeePassFile") fail(KdbxException.Corrupt("not KeePass XML"))
            stack.add(name)
            if (attributes.getValue("Protected").equals("true", ignoreCase = true)) {
                if (stream == null) fail(KdbxException.Corrupt("protected value without a stream"))
                protectedDepth = stack.size
                protectedText.setLength(0)
            }
            if (skipDepth > 0) return

            val parent = parent()
            val grand = grand()
            val inEntry = entry != null
            when (name) {
                "RecycleBinUUID", "RecycleBinEnabled", "DefaultUserName" -> if (parent == "Meta") beginCapture()
                "Icon" -> if (parent == "CustomIcons" && grand == "Meta") {
                    iconUuid = ""
                    iconData = ""
                }
                "Data" -> if (parent == "Icon" && grand == "CustomIcons") beginCapture()
                "Group" -> if (inRoot() && (parent == "Root" || parent == "Group")) {
                    groupFrames.add(GroupFrame(groups.size))
                    groups.add(null)
                }
                "Entry" -> if (inRoot() && parent == "Group" && !inEntry) entry = EntryFrame()
                "History" -> if (inEntry && parent == "Entry") skipDepth = stack.size
                "UUID" -> if (parent == "Group" || (parent == "Entry" && inEntry) ||
                    (parent == "Icon" && grand == "CustomIcons")
                ) {
                    beginCapture()
                }
                "Name" -> if (parent == "Group") beginCapture()
                "Tags", "CustomIconUUID" -> if (parent == "Entry" && inEntry) beginCapture()
                "Expires", "ExpiryTime", "LastModificationTime", "CreationTime" ->
                    if (parent == "Times" && grand == "Entry" && inEntry) beginCapture()
                "String" -> if (parent == "Entry" && inEntry) {
                    itemKey = ""
                    itemValue = null
                }
                "Key" -> if (grand == "Entry" && (parent == "String" || parent == "Binary") && inEntry) beginCapture()
                "Value" -> if (grand == "Entry" && inEntry) {
                    if (parent == "String") {
                        itemInTheClear = attributes.getValue("ProtectInMemory").equals("true", ignoreCase = true)
                        beginCapture()
                    } else if (parent == "Binary") {
                        itemRef = attributes.getValue("Ref")?.trim()?.toIntOrNull()
                    }
                }
                "Binary" -> if (parent == "Entry" && inEntry) {
                    itemKey = ""
                    itemRef = null
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (protectedDepth > 0) {
                protectedText.append(ch, start, length)
            } else if (capturing) {
                text.append(ch, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val name = qName.ifEmpty { localName }
            var secret = if (protectedDepth == stack.size) revealProtected() else null
            try {
                if (skipDepth > 0) {
                    if (name == "History" && stack.size == skipDepth) skipDepth = 0
                    return
                }
                val parent = parent()
                val grand = grand()
                val inEntry = entry != null
                val captured = if (capturing) text.toString() else ""
                capturing = false
                text.setLength(0)
                val trimmed = captured.trim()

                when (name) {
                    "RecycleBinUUID" -> if (parent == "Meta") recycleBinUuid = trimmed
                    "RecycleBinEnabled" -> if (parent == "Meta") recycleBinEnabled = trimmed
                    "DefaultUserName" -> if (parent == "Meta") defaultUserName = captured
                    "Data" -> if (parent == "Icon" && grand == "CustomIcons") iconData = trimmed
                    "Icon" -> if (parent == "CustomIcons" && grand == "Meta") addIcon()
                    "UUID" -> when {
                        parent == "Group" -> groupFrames.lastOrNull()?.uuid = trimmed
                        parent == "Entry" && inEntry -> entry?.uuid = trimmed
                        parent == "Icon" && grand == "CustomIcons" -> iconUuid = trimmed
                    }
                    "Name" -> if (parent == "Group") groupFrames.lastOrNull()?.name = captured
                    "Group" -> if (inRoot() && groupFrames.isNotEmpty()) {
                        val frame = groupFrames.last()
                        groups[frame.index] = KdbxGroup(
                            frame.uuid, frame.name, groupFrames.drop(1).map { it.name }, groupFrames.map { it.uuid },
                        )
                        groupFrames.removeAt(groupFrames.lastIndex)
                    }
                    "Tags" -> if (parent == "Entry" && inEntry) entry?.tags = captured
                    "CustomIconUUID" -> if (parent == "Entry" && inEntry) entry?.customIconUuid = trimmed
                    "Expires" -> if (parent == "Times" && grand == "Entry" && inEntry) entry?.expires = trimmed
                    "ExpiryTime" -> if (parent == "Times" && grand == "Entry" && inEntry) entry?.expiryTime = trimmed
                    "LastModificationTime" ->
                        if (parent == "Times" && grand == "Entry" && inEntry) entry?.lastModified = trimmed
                    "CreationTime" -> if (parent == "Times" && grand == "Entry" && inEntry) entry?.created = trimmed
                    "Key" -> if (grand == "Entry" && (parent == "String" || parent == "Binary") && inEntry) {
                        itemKey = captured
                    }
                    "Value" -> if (grand == "Entry" && parent == "String" && inEntry) {
                        itemValue = when {
                            secret != null -> FieldValue.Protected(secret).also { secret = null }
                            itemInTheClear -> FieldValue.Protected(Secret.of(captured))
                            else -> FieldValue.Plain(captured)
                        }
                    }
                    "String" -> if (parent == "Entry" && inEntry) {
                        entry?.fields?.add(KdbxField(itemKey, itemValue ?: FieldValue.Plain("")))
                        itemValue = null
                    }
                    "Binary" -> if (parent == "Entry" && inEntry) entry?.attachments?.add(KdbxAttachmentRef(itemKey, itemRef))
                    "Entry" -> if (parent == "Group" && inEntry) finishEntry()
                }
            } finally {
                secret?.wipe()
                stack.removeAt(stack.lastIndex)
            }
        }

        /** Decrypts the value that just ended; the stream moves on by its length. */
        private fun revealProtected(): Secret {
            protectedDepth = 0
            val bytes = try {
                Base64.getMimeDecoder().decode(protectedText.toString())
            } catch (_: IllegalArgumentException) {
                fail(KdbxException.Corrupt("protected value"))
            }
            protectedText.setLength(0)
            stream!!.apply(bytes)
            return Secret.adopt(bytes)
        }

        private fun addIcon() {
            val image = try {
                Base64.getMimeDecoder().decode(iconData)
            } catch (_: IllegalArgumentException) {
                return // an unreadable icon is no reason to refuse the database
            }
            if (iconUuid.isNotEmpty() && image.isNotEmpty()) customIcons[iconUuid] = CustomIcon(image)
        }

        private fun finishEntry() {
            val e = entry ?: return
            entry = null
            entries += KdbxEntry(
                uuid = e.uuid,
                fields = e.fields.toList(),
                tags = e.tags,
                expires = e.expires.equals("true", ignoreCase = true),
                expiryTime = KdbxTime.parse(e.expiryTime),
                lastModified = KdbxTime.parse(e.lastModified),
                created = KdbxTime.parse(e.created),
                attachments = e.attachments.toList(),
                groupPath = groupFrames.drop(1).map { it.name },
                ancestorGroupUuids = groupFrames.map { it.uuid },
                customIconUuid = e.customIconUuid.takeUnless { it.isEmpty() || it == NO_UUID },
            )
        }

        // A file has no DOCTYPE; refusing one rules out entity tricks with every parser.
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            fail(KdbxException.Corrupt("XML with a DOCTYPE"))
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun fatalError(e: SAXParseException): Unit = throw e
    }

    /** KeePass's "no UUID": 16 zero bytes. */
    private const val NO_UUID = "AAAAAAAAAAAAAAAAAAAAAA=="
}
