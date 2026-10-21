package dev.keepassbar.kdbx.xml

import dev.keepassbar.domain.vault.CustomIcon
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.kdbx.crypto.ChaCha20
import dev.keepassbar.kdbx.reader.KdbxException
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac `ParsingComponentTests` expectations for the reader, and the protected values' stream. */
class KdbxXmlReaderTest {
    private fun read(xml: String, stream: ChaCha20? = null) = KdbxXmlReader.read(xml.encodeToByteArray(), stream = stream)

    private fun doc(meta: String = "", root: String) = "<KeePassFile><Meta>$meta</Meta><Root>$root</Root></KeePassFile>"

    private fun entry(uuid: String, strings: List<Pair<String, String>> = emptyList(), extra: String = "") =
        "<Entry><UUID>$uuid</UUID>" +
            strings.joinToString("") { "<String><Key>${it.first}</Key><Value>${it.second}</Value></String>" } +
            extra + "</Entry>"

    private fun plain(key: String, text: String) = KdbxField(key, FieldValue.Plain(text))

    @Test
    fun groupPathExcludesTheRootGroupAndFollowsNesting() {
        val d = read(
            doc(
                root = """
                <Group><UUID>R</UUID><Name>Passwords</Name>
                  ${entry("e0")}
                  <Group><UUID>A</UUID><Name>A</Name>
                    <Group><UUID>B</UUID><Name>B</Name>${entry("e1")}</Group>
                    ${entry("e2")}
                  </Group>
                  ${entry("e3")}
                </Group>
                """,
            ),
        )

        assertEquals(
            listOf("e0" to listOf(), "e1" to listOf("A", "B"), "e2" to listOf("A"), "e3" to listOf()),
            d.entries.map { it.uuid to it.groupPath },
        )
        assertEquals(listOf("R", "A", "B"), d.entries[1].ancestorGroupUuids)
        assertEquals(listOf("R", "A", "B"), d.groups.map { it.uuid }, "groups in file order, the root first")
        assertEquals(listOf("A", "B"), d.groups[2].path)
    }

    @Test
    fun historyItemsAreSkippedAndDoNotLeakIntoTheParentEntry() {
        val history = "<History>" + entry("old", listOf("Title" to "OLD")) + "</History>"

        val d = read(doc(root = "<Group><UUID>R</UUID><Name>P</Name>" + entry("cur", listOf("Title" to "NEW"), history) + "</Group>"))

        assertEquals(1, d.entries.size)
        assertEquals("cur", d.entries[0].uuid)
        assertEquals(listOf(plain("Title", "NEW")), d.entries[0].fields)
    }

    @Test
    fun valuesKeepWhitespaceCdataAndTheProtectedFlag() {
        val d = read(
            doc(
                root = """
                <Group><UUID>R</UUID><Name>P</Name><Entry><UUID>e</UUID>
                  <String><Key>Notes</Key><Value>  line1&#10;line2  </Value></String>
                  <String><Key>Raw</Key><Value><![CDATA[<b>&</b>]]></Value></String>
                  <String><Key>Secret</Key><Value ProtectInMemory="True">s</Value></String>
                  <String><Key>Empty</Key><Value/></String>
                  <String><Key>CR</Key><Value>a&#13;&#10;b</Value></String>
                </Entry></Group>
                """,
            ),
        )

        val fields = d.entries[0].fields
        assertEquals(plain("Notes", "  line1\nline2  "), fields[0])
        assertEquals(plain("Raw", "<b>&</b>"), fields[1])
        assertEquals("s", (fields[2].value as FieldValue.Protected).secret.reveal())
        assertEquals(plain("Empty", ""), fields[3])
        assertEquals(plain("CR", "a\r\nb"), fields[4])
    }

    @Test
    fun timesTagsAndAttachmentReferences() {
        val d = read(
            doc(
                root = """
                <Group><UUID>R</UUID><Name>P</Name><Entry><UUID>e</UUID><Tags>a;b</Tags>
                  <Times><Expires>True</Expires><ExpiryTime>2025-01-01T00:00:00Z</ExpiryTime>
                    <LastModificationTime>JUxv6A4AAAA=</LastModificationTime>
                    <CreationTime>2019-03-12T10:00:00Z</CreationTime></Times>
                  <Binary><Key>f.pdf</Key><Value Ref="3"/></Binary>
                  <Binary><Key>broken</Key><Value Ref="x"/></Binary>
                </Entry></Group>
                """,
            ),
        )

        val e = d.entries[0]
        assertEquals("a;b", e.tags)
        assertTrue(e.expires)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), e.expiryTime)
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), e.lastModified, "KDBX 4: base64 seconds since year 1")
        assertEquals(Instant.parse("2019-03-12T10:00:00Z"), e.created)
        assertEquals(listOf(KdbxAttachmentRef("f.pdf", 3), KdbxAttachmentRef("broken", null)), e.attachments)
    }

    @Test
    fun expiryFlagAndDates() {
        fun expiry(expires: String, time: String) = read(
            doc(root = "<Group><UUID>R</UUID><Name>P</Name>${entry("e", extra = "<Times><Expires>$expires</Expires><ExpiryTime>$time</ExpiryTime></Times>")}</Group>"),
        ).entries[0]

        assertTrue(expiry(" true ", "").expires)
        assertFalse(expiry("False", "").expires)
        assertFalse(expiry("", "").expires)
        assertEquals(Instant.parse("2025-01-01T00:00:00.500Z"), expiry("True", "2025-01-01T00:00:00.500Z").expiryTime)
        assertNull(expiry("True", "not a date").expiryTime)
    }

    @Test
    fun groupTimesAreNotMistakenForEntryTimes() {
        val d = read(doc(root = "<Group><UUID>R</UUID><Name>P</Name><Times><Expires>True</Expires></Times>${entry("e")}</Group>"))

        assertFalse(d.entries[0].expires)
    }

    @Test
    fun metaGivesTheRecycleBinTheDefaultUserNameAndTheCustomIcons() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val d = read(
            doc(
                meta = """
                <DefaultUserName>me</DefaultUserName>
                <CustomIcons><Icon><UUID>ICON==</UUID><Data>${Base64.getEncoder().encodeToString(png)}</Data></Icon></CustomIcons>
                <RecycleBinEnabled>False</RecycleBinEnabled>
                <RecycleBinUUID>BIN==</RecycleBinUUID>
                """,
                root = "<Group><UUID>R</UUID><Name>P</Name>" +
                    entry("e", extra = "<CustomIconUUID>ICON==</CustomIconUUID>") +
                    entry("f", extra = "<CustomIconUUID>AAAAAAAAAAAAAAAAAAAAAA==</CustomIconUUID>") + "</Group>",
            ),
        )

        assertEquals(KdbxMeta("BIN==", recycleBinEnabled = false, "me", mapOf("ICON==" to CustomIcon(png))), d.meta)
        assertEquals("ICON==", d.entries[0].customIconUuid)
        assertNull(d.entries[1].customIconUuid, "KeePass's zero UUID is no icon")
    }

    @Test
    fun protectedValuesAreDecryptedInDocumentOrderHistoriesIncluded() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val encrypt = ChaCha20(key, nonce)
        fun protect(text: String): String {
            val bytes = text.encodeToByteArray()
            encrypt.apply(bytes)
            return Base64.getEncoder().encodeToString(bytes)
        }
        // Encrypted in the order the document lists them.
        val first = protect("first <&> 🔑")
        val old = protect("old-password")
        val second = protect("second-password")
        val xml = doc(
            root = """
            <Group><UUID>R</UUID><Name>P</Name>
              <Entry><UUID>a</UUID>
                <String><Key>Password</Key><Value Protected="True">$first</Value></String>
                <String><Key>Empty</Key><Value Protected="True"/></String>
                <History><Entry><UUID>a</UUID>
                  <String><Key>Password</Key><Value Protected="True">$old</Value></String>
                </Entry></History>
              </Entry>
              <Entry><UUID>b</UUID>
                <String><Key>Password</Key><Value Protected="True">$second</Value></String>
              </Entry>
            </Group>
            """,
        )

        val d = read(xml, ChaCha20(key, nonce))

        fun FieldValue.text() = (this as FieldValue.Protected).secret.reveal()
        assertEquals(listOf("first <&> 🔑", ""), d.entries[0].fields.map { it.value.text() })
        // After the history's value in the stream: right only if the skipped history moved the stream on.
        assertEquals("second-password", d.entries[1].fields.single().value.text())
    }

    @Test
    fun externalEntitiesAndDoctypesAreRefused() {
        val xml = """
            <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/hosts">]>
            <KeePassFile><Root><Group><UUID>R</UUID><Name>P</Name>${entry("e", listOf("Title" to "&e;"))}</Group></Root></KeePassFile>
        """.trimIndent()

        val failure = assertFailsWith<KdbxException.Corrupt> { read(xml) }

        assertEquals("XML with a DOCTYPE", failure.detail)
    }

    @Test
    fun nonKeePassMalformedAndEmptyXmlAreRefused() {
        assertEquals("not KeePass XML", assertFailsWith<KdbxException.Corrupt> { read("<html><body/></html>") }.detail)
        val truncated = doc(root = "<Group><UUID>R</UUID><Name>P</Name>${entry("e")}").dropLast(10)
        assertTrue(assertFailsWith<KdbxException.Corrupt> { read(truncated) }.detail.startsWith("XML error at line"))
        assertFailsWith<KdbxException.Corrupt> { read("") }
    }

    @Test
    fun aProtectedValueWithoutAStreamIsRefused() {
        val xml = doc(root = "<Group><UUID>R</UUID><Name>P</Name><Entry><UUID>e</UUID><String><Key>P</Key><Value Protected=\"True\">AAAA</Value></String></Entry></Group>")

        assertFailsWith<KdbxException.Corrupt> { read(xml) }
    }

    @Test
    fun anEntrysCustomDataIsItsOwnNotTheDatabasesAGroupsOrTheHistorys() {
        fun item(key: String, value: String) = "<Item><Key>$key</Key><Value>$value</Value></Item>"
        val d = read(
            doc(
                meta = "<CustomData>${item("K", "meta")}</CustomData>",
                root = """
                <Group><UUID>R</UUID><Name>P</Name>
                  <CustomData>${item("K", "group")}</CustomData>
                  <Entry><UUID>a</UUID><String><Key>Ablauf</Key><Value>2027-01-01</Value></String>
                    <CustomData>${item("Other", "x")}${item("K", "{&quot;Ablauf&quot;:&quot;until&quot;}")}<Item><Key>Empty</Key><Value/></Item></CustomData>
                    <History><Entry><UUID>a</UUID><CustomData>${item("K", "old")}</CustomData></Entry></History>
                  </Entry>
                  ${entry("b")}
                </Group>
                """,
            ),
        )

        assertEquals(listOf(mapOf("Other" to "x", "K" to "{\"Ablauf\":\"until\"}", "Empty" to ""), emptyMap()), d.entries.map { it.customData })
    }
}
