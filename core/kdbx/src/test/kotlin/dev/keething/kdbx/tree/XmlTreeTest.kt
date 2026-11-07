package dev.keething.kdbx.tree

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.crypto.ChaCha20
import dev.keething.kdbx.reader.KdbxException
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tree that keeps everything (Mac `XMLTree` and its tests in `KDBXWriteTests`). */
class XmlTreeTest {
    private fun stream(seed: Int) = ChaCha20(ByteArray(32) { (it + seed).toByte() }, ByteArray(12) { seed.toByte() })

    private fun write(root: XElement, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val stream = stream(seed)
        XmlTree.write(root, out) { secret -> XmlTree.seal(secret, stream) }
        return out.toByteArray()
    }

    private fun read(xml: ByteArray, seed: Int = 1): XElement = XmlTree.parse(xml, stream = stream(seed))

    @Test
    fun textWithMarkupCrAndQuotesSurvivesAWriteAndARead() {
        val root = XElement("A").apply { setAttribute("k", "a\"b<&\n\t") }
        root.append(XElement("B", "x < y & \"z\"\r\nline 2 🔑"))
        root.append(XElement("C"))

        val again = read(write(root))

        assertEquals("a\"b<&\n\t", again.attribute("k"))
        assertEquals("x < y & \"z\"\r\nline 2 🔑", again.first("B")?.text)
        assertTrue(again.first("C")!!.children.isEmpty())
    }

    @Test
    fun aValueIsKeptAsItsTextAloneAndTakesChildrenOnlyWhenTheyAreAddedWrittenAsBefore() {
        val xml = "<A>\n\t<B>True</B>\n\t<C>True</C>\n\t<D>mixed<E/>after</D>\n</A>"
        val root = read(xml.encodeToByteArray())
        val (b, c) = root.first("B")!! to root.first("C")!!

        assertEquals("True", b.onlyText)
        assertTrue(b.onlyText === c.onlyText, "a short value read once is one string")
        assertNull(root.first("D")!!.onlyText)
        assertEquals("mixedafter", root.first("D")!!.text)
        assertEquals(xml, write(root).decodeToString().substringAfter("?>\n").trimEnd())

        b.append(XElement("F", "x"))
        c.text = ""
        c.text = "False"
        assertEquals("True", b.text)
        assertEquals("<B>True<F>x</F></B>", write(b).decodeToString().substringAfter("?>\n").trimEnd())
        assertEquals("<C>False</C>", write(c).decodeToString().substringAfter("?>\n").trimEnd())
        assertEquals(listOf("True"), b.copy().nodes.filterIsInstance<XText>().map { it.text })
    }

    @Test
    fun aDocumentWithoutChangesIsWrittenAsItWasRead() {
        val xml = """
            |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            |<KeePassFile>
            |	<Meta>
            |		<Generator>KeePassXC</Generator>
            |		<DatabaseDescription/>
            |	</Meta>
            |	<Root>
            |		<Group>
            |			<Name>A &amp; B &lt;c&gt;</Name>
            |			<Value Ref="2"/>
            |		</Group>
            |	</Root>
            |</KeePassFile>
            |""".trimMargin()

        assertEquals(xml, write(read(xml.encodeToByteArray())).decodeToString())
    }

    @Test
    fun theFixturesXmlReadsBackTheSameAfterAWrite() {
        for (name in listOf("sample.xml", "kdbx4-export.xml")) {
            val root = XmlTree.parse(Fixtures.bytes(name))

            val again = read(write(root))

            assertSameTree(root, again, name)
        }
    }

    @Test
    fun protectedValuesAreSecretsThatTheStreamSealsInDocumentOrder() {
        val sealed = stream(7)
        fun protect(text: String): String {
            val bytes = text.encodeToByteArray()
            sealed.apply(bytes)
            return Base64.getEncoder().encodeToString(bytes)
        }
        val xml = """<KeePassFile><Root><Entry>""" +
            """<String><Key>Password</Key><Value Protected="True">${protect("first")}</Value></String>""" +
            """<String><Key>Empty</Key><Value Protected="True"/></String>""" +
            """<History><Entry><String><Key>Password</Key><Value Protected="True">${protect("old")}</Value></String></Entry></History>""" +
            """<String><Key>PIN</Key><Value Protected="True">${protect("1234")}</Value></String>""" +
            """</Entry></Root></KeePassFile>"""

        val root = read(xml.encodeToByteArray(), seed = 7)
        val values = root.descendants().filter { it.name == "Value" }

        assertEquals(listOf("first", "", "old", "1234"), values.map { it.secret!!.reveal() })
        assertTrue(values.none { it.attribute("Protected") != null }, "the mark goes with the secret")
        assertEquals("", values[0].text, "no text: the value is only the secret")
        val written = write(root, seed = 9).decodeToString()
        assertTrue("first" !in written && "1234" !in written, "sealed again, not in the clear")
        assertTrue("""<Value Protected="True"/>""" in written, "an empty secret stays an empty element")
        assertEquals(listOf("first", "", "old", "1234"), read(written.encodeToByteArray(), seed = 9).descendants()
            .filter { it.name == "Value" }.map { it.secret!!.reveal() })
    }

    @Test
    fun aValueMarkedProtectedFalseThatBecomesASecretIsWrittenWithOneProtectedAttribute() {
        // Some writers mark a plain value Protected="False"; protecting it (Notes' switch) must not write the attribute twice.
        val root = XmlTree.parse("""<String><Key>Notes</Key><Value Protected="False">plain</Value></String>""".encodeToByteArray())
        root.first("Value")!!.secret = Secret.of("now protected")

        val written = write(root).decodeToString()

        assertEquals(1, Regex("Protected=").findAll(written).count(), written)
        assertEquals("now protected", read(written.encodeToByteArray()).first("Value")?.secret?.reveal())
    }

    @Test
    fun aSecretIsNeverWrittenWithoutTheStream() {
        val root = XElement("Value").apply { secret = Secret.of("pw") }

        assertFailsWith<IllegalStateException> { XmlTree.write(root, ByteArrayOutputStream(), protect = null) }
    }

    @Test
    fun cdataBecomesText() {
        val root = XmlTree.parse("<A><![CDATA[a < b & c]]></A>".encodeToByteArray())

        assertEquals("a < b & c", root.text)
        assertEquals("<A>a &lt; b &amp; c</A>", write(root).decodeToString().substringAfter("\n").trim())
    }

    @Test
    fun aDoctypeIsRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE A [<!ENTITY x "boom">]><A>&x;</A>"""

        assertFailsWith<KdbxException.Corrupt> { XmlTree.parse(xml.encodeToByteArray()) }
    }

    @Test
    fun brokenXmlIsCorrupt() {
        assertFailsWith<KdbxException.Corrupt> { XmlTree.parse("<A><B></A>".encodeToByteArray()) }
    }

    @Test
    fun aCopyIsDeepAndHasSecretsOfItsOwn() {
        val root = XmlTree.parse("<Entry><String><Key>Password</Key><Value ProtectInMemory=\"True\">pw</Value></String></Entry>".encodeToByteArray())
        val value = root.first("String")!!.first("Value")!!

        val copy = root.copy()
        value.secret!!.wipe()
        value.text = "changed"

        val copied = copy.first("String")!!.first("Value")!!
        assertEquals("pw", copied.secret!!.reveal())
        assertEquals("", copied.text)
    }

    @Test
    fun childrenAreFoundInsertedAndRemoved() {
        val root = XElement("G")
        val a = XElement("A").also(root::append)
        val c = XElement("C").also(root::append)
        val b = XElement("B")
        root.insertAfter(b, a)
        root.insertBefore(XElement("Z"), a)
        root.insertAfter(XElement("End"), null)

        assertEquals(listOf("Z", "A", "B", "C", "End"), root.elements.map { it.name })
        root.remove(c)
        assertEquals(listOf("Z", "A", "B", "End"), root.elements.map { it.name })
        assertEquals(b, root.first("B"))
        assertNull(root.first("C"))
        root.append(XElement("B"))
        assertEquals(2, root.all("B").size)
    }

    @Test
    fun settingTextReplacesTheTextAndKeepsTheElements() {
        val element = XmlTree.parse("<A>old<B/></A>".encodeToByteArray())

        element.text = "new"
        assertEquals("new", element.text)
        assertEquals(listOf("B"), element.elements.map { it.name })
        element.text = ""
        assertEquals("", element.text)
    }

    @Test
    fun wipingReachesEverySecretInTheTree() {
        val root = XmlTree.parse(
            "<E><Value ProtectInMemory=\"True\">a</Value><H><Value ProtectInMemory=\"True\">b</Value></H></E>".encodeToByteArray(),
        )
        val secrets = root.descendants().mapNotNull { it.secret }

        root.wipeSecrets()

        assertEquals(2, secrets.size)
        assertTrue(secrets.all { it.isWiped })
    }
}
