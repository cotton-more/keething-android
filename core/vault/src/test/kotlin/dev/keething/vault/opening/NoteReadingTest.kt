package dev.keething.vault.opening

import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.Vault
import dev.keething.kdbx.xml.KdbxXml
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A note as an unlock reads it (keething-spec types.note AC1–AC3; the Mac `NoteTests` reading expectations): its kind
 * kept as stored, Notes' own protection, a code's fields however broken, the settings entry never a note.
 */
class NoteReadingTest {
    private val key = EntryType.CUSTOM_DATA_KEY

    private fun vault(entries: String): Vault =
        VaultAssembler.assemble(
            KdbxXml.readExport("<KeePassFile><Meta></Meta><Root><Group><UUID>R</UUID><Name>P</Name>$entries</Group></Root></KeePassFile>".encodeToByteArray()),
            binarySizes = listOf(1),
        )

    private fun strings(vararg pairs: Pair<String, String>, protected: Set<String> = emptySet()): String =
        pairs.joinToString("") { (k, v) -> "<String><Key>$k</Key><Value${if (k in protected) " ProtectInMemory=\"True\"" else ""}>$v</Value></String>" }

    private val blank = arrayOf("Title" to "T", "UserName" to "", "Password" to "", "URL" to "")

    private fun kind(value: String) = "<CustomData><Item><Key>$key</Key><Value>$value</Value></Item></CustomData>"

    private fun entries(xml: String): List<Entry> = vault(xml).entries.sortedBy { it.id }

    @Test
    fun theKindIsKeptAsStoredAndReadIgnoringCaseAndOuterSpaces() {
        val read = entries(
            """
            <Entry><UUID>a</UUID>${strings(*blank, "Notes" to "")}${kind(" NOTE ")}</Entry>
            <Entry><UUID>b</UUID>${strings(*blank, "Notes" to "Dune, Arrival")}${kind("checklist")}</Entry>
            <Entry><UUID>c</UUID>${strings("Title" to "T", "Password" to "p", "Notes" to "")}${kind("Note")}</Entry>
            <Entry><UUID>d</UUID>${strings(*blank, "Notes" to "Dune")}</Entry>
            """,
        )
        assertEquals(listOf(" NOTE ", "checklist", "Note", null), read.map { it.entryType }, "kept as stored")
        assertEquals(listOf(true, false, true, true), read.map { it.isNote })
    }

    @Test
    fun notesKeepTheirProtectionWhenRead() {
        val read = entries(
            """
            <Entry><UUID>a</UUID>${strings(*blank, "Notes" to "secret text", protected = setOf("Notes"))}</Entry>
            <Entry><UUID>b</UUID>${strings(*blank, "Notes" to "plain")}</Entry>
            <Entry><UUID>c</UUID>${strings(*blank)}</Entry>
            """,
        )
        assertEquals(listOf(true, false, false), read.map { it.notesProtected })
        assertEquals(listOf("secret text", "plain", ""), read.map { it.notes })
    }

    @Test
    fun theRuleReadFromTheFileACodesFieldsCountACodeThatDoesNotReadTooAttachmentsDoNot() {
        val read = entries(
            """
            <Entry><UUID>a</UUID>${strings(*blank, "Notes" to "n")}<Binary><Key>a.png</Key><Value Ref="0"/></Binary></Entry>
            <Entry><UUID>b</UUID>${strings(*blank, "Notes" to "n", "otp" to "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ")}</Entry>
            <Entry><UUID>c</UUID>${strings(*blank, "Notes" to "n", "otp" to "not a link")}</Entry>
            <Entry><UUID>d</UUID>${strings(*blank, "Notes" to "n", "TimeOtp-Secret-Base32" to "!!")}</Entry>
            <Entry><UUID>e</UUID>${strings(*blank, "Notes" to "n", "Seen on" to "feed")}</Entry>
            """,
        )
        assertEquals(listOf(true, false, false, false, false), read.map { it.isNote })
        assertEquals(listOf(false, true, true, true, false), read.map { it.hasCodeFields })
        assertNull(read[2].totp, "the broken code reads as none")
    }

    @Test
    fun theKeethingSettingsEntryIsNeverANote() {
        val read = vault("""<Entry><UUID>k</UUID>${strings("Title" to "Keething", "UserName" to "", "Password" to "", "URL" to "", "Notes" to "Bank = iban")}</Entry>""")
        assertTrue(read.entries.isEmpty())
        assertEquals("k", read.settings?.id)
    }

    @Test
    fun anEntrysCreationTimeIsReadAnUntitledNoteIsNamedByIt() {
        val read = entries(
            """
            <Entry><UUID>a</UUID><Times><CreationTime>2024-05-01T10:30:00Z</CreationTime></Times>${strings(*blank, "Notes" to "x")}</Entry>
            <Entry><UUID>b</UUID>${strings(*blank)}</Entry>
            """,
        )
        assertEquals(listOf(Instant.parse("2024-05-01T10:30:00Z"), null), read.map { it.created })
    }
}
