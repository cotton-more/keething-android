package dev.keething.kdbx.xml

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.FieldValue
import dev.keething.kdbx.crypto.KeyStream
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.tree.XmlTree
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import org.xml.sax.Attributes

/**
 * Event-based reader of the KeePass XML (Mac `KDBXXMLReader`): groups, entries, `Meta`; histories are
 * skipped, but for the one of the entry asked for ([read]'s `historyOf`, SPEC §6.2). Never builds a tree.
 *
 * Unlike the Mac app, which first turns the protected values into plain text inside the XML, a value marked
 * `Protected="True"` (base64 of its bytes XORed with the inner stream, in document order) is decrypted
 * straight into a [Secret]: the XML never holds a password in the clear. The stream runs through the
 * protected values of the histories too, or every later value would come out wrong. A value marked
 * `ProtectInMemory="True"` is how `keepassxc-cli export` writes a protected value in the clear (the
 * fixtures).
 */
internal object KdbxXmlReader {
    /** [historyOf]: the UUID of the entry whose history items are read too ([KdbxDocument.history]). */
    fun read(xml: ByteArray, stream: KeyStream? = null, historyOf: String? = null): KdbxDocument =
        read(ByteArrayInputStream(xml), stream, historyOf)

    /** As the XML is decrypted: [input] is read to the end of the document and left open. */
    fun read(input: InputStream, stream: KeyStream? = null, historyOf: String? = null): KdbxDocument =
        reading(stream, historyOf).also { Sax.parse(input, it) }.result()

    /** The document read from the parser's events: for one parse that feeds others too ([Sax.parse]). */
    fun reading(stream: KeyStream?, historyOf: String? = null): SaxReading<KdbxDocument> = Handler(stream, historyOf, null)

    /**
     * Reads a KDBX 3.1 file's XML (keething-spec kdbx.kdbx3 AC2-AC4): the attachments too, from `Meta/Binaries` (base64,
     * gzip when `Compressed="True"`, decrypted with the stream when `Protected="True"`), in their order, an entry's
     * `Ref` (the attachment's `ID`) turned into its place in that list; and `Meta/HeaderHash`, when there, checked
     * against [headerSha256].
     */
    fun readKdbx3(
        xml: ByteArray,
        stream: KeyStream,
        historyOf: String?,
        headerSha256: ByteArray,
    ): Pair<KdbxDocument, List<Secret>> {
        val handler = Handler(stream, historyOf, headerSha256)
        Sax.parse(ByteArrayInputStream(xml), handler)
        return handler.result() to handler.binaries
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
        val customData = LinkedHashMap<String, String>()
    }

    /** [headerSha256]: a KDBX 3.1 file's, to check `Meta/HeaderHash` against; null for KDBX 4. */
    private class Handler(
        private val stream: KeyStream?,
        private val historyOf: String?,
        private val headerSha256: ByteArray?,
    ) : SaxReading<KdbxDocument>() {
        private val stack = ArrayList<String>()

        /** Stack depth of the `History` element being skipped; 0 when not skipping. */
        private var skipDepth = 0
        private val groupFrames = ArrayList<GroupFrame>()
        private val groups = ArrayList<KdbxGroup?>()
        private val entries = ArrayList<KdbxEntry>()
        private var entry: EntryFrame? = null

        /**
         * Reading the history of the entry [historyOf]: the stack depth of its `History` element (0 when not), the
         * entry itself (set aside meanwhile; [entry] is the history item being read) and the items read.
         */
        private var historyDepth = 0
        private var historyOwner: EntryFrame? = null
        private val history = ArrayList<KdbxEntry>()

        private var recycleBinUuid = ""
        private var templatesGroupUuid = ""
        private var recycleBinEnabled = ""
        private var defaultUserName = ""
        private var databaseName = ""
        private var color = ""
        private val customIcons = LinkedHashMap<String, CustomIcon>()
        private var iconUuid = ""
        private var iconData = ""

        /** KDBX 3.1: the attachments of `Meta/Binaries` in their order, and each one's place by its `ID`. */
        val binaries = ArrayList<Secret>()
        private val binaryPlaces = HashMap<Int, Int>()
        private var binaryId: Int? = null
        private var binaryCompressed = false

        // Text capture for small elements.
        private var capturing = false
        private val text = StringBuilder()

        // The current `String` / `Binary` item of an entry.
        private var itemKey = ""
        private var itemValue: FieldValue? = null
        private var itemInTheClear = false
        private var itemRef: Int? = null

        // The current `CustomData/Item` of an entry.
        private var dataKey = ""
        private var dataValue = ""

        // The current value marked `Protected="True"`: its base64 text and its depth in the stack.
        private var protectedDepth = 0
        private val protectedText = StringBuilder()

        override fun result(): KdbxDocument = KdbxDocument(
            KdbxMeta(recycleBinUuid, !recycleBinEnabled.equals("false", ignoreCase = true), defaultUserName, customIcons, databaseName, color, templatesGroupUuid),
            groups.filterNotNull(),
            entries,
            history,
        )

        override fun wipe() {
            fun wipe(value: FieldValue?) {
                (value as? FieldValue.Protected)?.secret?.wipe()
            }
            entries.forEach { e -> e.fields.forEach { wipe(it.value) } }
            history.forEach { e -> e.fields.forEach { wipe(it.value) } }
            entry?.fields?.forEach { wipe(it.value) }
            historyOwner?.fields?.forEach { wipe(it.value) }
            wipe(itemValue)
            binaries.forEach(Secret::wipe)
        }

        private fun beginCapture() {
            capturing = true
            text.setLength(0)
        }

        private fun inRoot() = stack.size >= 2 && stack[1] == "Root"

        private fun parent() = stack.getOrNull(stack.size - 2) ?: ""

        private fun grand() = stack.getOrNull(stack.size - 3) ?: ""

        /**
         * Inside `Entry/CustomData/Item` (the entry's own, not its group's or the database's): the element on top
         * of the stack is the item ([depth] 0) or one of its children (1).
         */
        private fun inEntryDataItem(depth: Int): Boolean {
            val n = stack.size - depth
            return entry != null && n >= 3 && stack[n - 1] == "Item" && stack[n - 2] == "CustomData" && stack[n - 3] == "Entry"
        }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            val name = qName.ifEmpty { localName }
            if (stack.isEmpty() && name != "KeePassFile") fail(KdbxException.Corrupt("not KeePass XML"))
            if (stack.size == XmlTree.MAX_DEPTH) fail(KdbxException.Corrupt("XML nested deeper than ${XmlTree.MAX_DEPTH} levels"))
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
                "RecycleBinUUID", "RecycleBinEnabled", "DefaultUserName", "DatabaseName", "Color", "EntryTemplatesGroup" -> if (parent == "Meta") beginCapture()
                "HeaderHash" -> if (parent == "Meta" && headerSha256 != null) beginCapture()
                "Icon" -> if (parent == "CustomIcons" && grand == "Meta") {
                    iconUuid = ""
                    iconData = ""
                }
                "Data" -> if (parent == "Icon" && grand == "CustomIcons") beginCapture()
                "Group" -> if (inRoot() && (parent == "Root" || parent == "Group")) {
                    groupFrames.add(GroupFrame(groups.size))
                    groups.add(null)
                }
                "Entry" -> if (!inEntry && ((inRoot() && parent == "Group") || (historyDepth > 0 && parent == "History"))) entry = EntryFrame()
                "History" -> if (inEntry && parent == "Entry") {
                    // The entry's UUID comes first in it, as KeePass writes it.
                    if (historyDepth == 0 && historyOf != null && entry?.uuid == historyOf) {
                        historyDepth = stack.size
                        historyOwner = entry
                        entry = null
                    } else {
                        skipDepth = stack.size
                    }
                }
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
                        val ref = attributes.getValue("Ref")?.trim()?.toIntOrNull()
                        itemRef = if (headerSha256 != null) ref?.let(binaryPlaces::get) else ref
                    }
                }
                "Binary" -> if (parent == "Entry" && inEntry) {
                    itemKey = ""
                    itemRef = null
                } else if (parent == "Binaries" && grand == "Meta" && headerSha256 != null) {
                    binaryId = attributes.getValue("ID")?.trim()?.toIntOrNull()
                    binaryCompressed = attributes.getValue("Compressed").equals("true", ignoreCase = true)
                    beginCapture()
                }
                "Item" -> if (inEntryDataItem(0)) {
                    dataKey = ""
                    dataValue = ""
                }
            }
            if ((name == "Key" || name == "Value") && inEntryDataItem(1)) beginCapture()
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
                    "EntryTemplatesGroup" -> if (parent == "Meta") templatesGroupUuid = trimmed
                    "RecycleBinEnabled" -> if (parent == "Meta") recycleBinEnabled = trimmed
                    "DefaultUserName" -> if (parent == "Meta") defaultUserName = captured
                    "DatabaseName" -> if (parent == "Meta") databaseName = trimmed
                    "Color" -> if (parent == "Meta") color = trimmed
                    "HeaderHash" -> if (parent == "Meta" && headerSha256 != null) checkHeaderHash(trimmed)
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
                    "Binary" -> if (parent == "Entry" && inEntry) {
                        entry?.attachments?.add(KdbxAttachmentRef(itemKey, itemRef))
                    } else if (parent == "Binaries" && grand == "Meta" && headerSha256 != null) {
                        addBinary(secret.also { secret = null }, trimmed)
                    }
                    "Item" -> if (inEntryDataItem(0)) entry?.customData?.put(dataKey, dataValue)
                    "Entry" -> when {
                        parent == "Group" && inEntry -> finishEntry(entries)
                        parent == "History" && inEntry && historyDepth > 0 -> finishEntry(history)
                    }
                    "History" -> if (historyDepth == stack.size) {
                        historyDepth = 0
                        entry = historyOwner
                        historyOwner = null
                    }
                }
                if (inEntryDataItem(1)) {
                    if (name == "Key") dataKey = captured else if (name == "Value") dataValue = captured
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

        private fun checkHeaderHash(text: String) {
            if (text.isEmpty()) return
            val stored = try {
                Base64.getMimeDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                fail(KdbxException.Corrupt("header hash"))
            }
            if (!MessageDigest.isEqual(stored, headerSha256)) fail(KdbxException.Corrupt("header hash"))
        }

        /** [decrypted]: the attachment's bytes when it was protected; otherwise [text] holds them in base64. */
        private fun addBinary(decrypted: Secret?, text: String) {
            var content = decrypted
            try {
                val id = binaryId ?: fail(KdbxException.Corrupt("attachment without an ID"))
                if (id in binaryPlaces) fail(KdbxException.Corrupt("attachment ID $id twice"))
                if (content == null) {
                    content = try {
                        Secret.adopt(Base64.getMimeDecoder().decode(text))
                    } catch (_: IllegalArgumentException) {
                        fail(KdbxException.Corrupt("attachment $id"))
                    }
                }
                if (binaryCompressed) {
                    val packed = content
                    content = try {
                        packed.useBytes { Secret.adopt(KdbxReader.gunzip(it)) }
                    } catch (_: KdbxException) {
                        fail(KdbxException.Corrupt("attachment $id"))
                    } finally {
                        packed.wipe()
                    }
                }
                binaryPlaces[id] = binaries.size
                binaries += content
                content = null
            } finally {
                content?.wipe()
            }
        }

        private fun addIcon() {
            val image = try {
                Base64.getMimeDecoder().decode(iconData)
            } catch (_: IllegalArgumentException) {
                return // an unreadable icon is no reason to refuse the database
            }
            if (iconUuid.isNotEmpty() && image.isNotEmpty()) customIcons[iconUuid] = CustomIcon(image)
        }

        private fun finishEntry(into: MutableList<KdbxEntry>) {
            val e = entry ?: return
            entry = null
            into += KdbxEntry(
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
                customData = e.customData.toMap(),
            )
        }
    }

    /** KeePass's "no UUID": 16 zero bytes. */
    private const val NO_UUID = "AAAAAAAAAAAAAAAAAAAAAA=="
}
