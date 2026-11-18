package dev.keething.card.editing

import dev.keething.domain.text.english
import dev.keething.card.now
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.Attachment
import dev.keething.domain.vault.AttachmentChange
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's attachments (keething-spec edit.attachments AC1, AC3, AC4; the Mac `AttachmentEditingTests` "editor"). */
class AttachmentEditorTest {
    private val entry = Entry(
        "s", title = "Server",
        attachments = listOf(Attachment("KeeAgent.settings", 500), Attachment("id_rsa", 2600), Attachment("notes.txt", 10)),
        lastModified = Instant.ofEpochSecond(100),
    )

    private fun EntryDraft.named(name: String) = attachments.single { it.name == name }

    // spec: edit.attachments/AC1
    @Test
    fun aFileIsAddedUnderAFreeName() {
        val editor = EntryEditor.edit(entry, now)

        var draft = editor.start.addAttachment("notes.txt", Secret.of("x"))
        assertEquals("notes 2.txt", draft.attachments.last().name)
        draft = draft.addAttachment("notes.txt", Secret.of("y")).addAttachment("id_rsa", Secret.of("k")).addAttachment(" ", Secret.of("z"))
        assertEquals(listOf("notes 3.txt", "id_rsa 2", "Attachment"), draft.attachments.takeLast(3).map { it.name })
        assertEquals(".env 2", EntryDraft().addAttachment(".env", Secret.of("a")).addAttachment(".env", Secret.of("b")).attachments.last().name)
        // The last extension, as the Mac's pathExtension.
        assertEquals("scan.tar 2.gz", EntryDraft().addAttachment("scan.tar.gz", Secret.of("a")).addAttachment("scan.tar.gz", Secret.of("b")).attachments.last().name)
        assertTrue(draft.attachments.takeLast(4).all { it.isNew })
        assertEquals(1, draft.attachments.last().size)
    }

    // spec: edit.attachments/AC3
    @Test
    fun twoOfOneNameOrOneWithoutKeepSaveOffUntilOneIsRemoved() {
        // The phone does not rename: only an entry another app made so has such names.
        val odd = entry.copy(attachments = listOf(Attachment("a.txt", 1), Attachment("a.txt", 2), Attachment(" ", 3)))
        val editor = EntryEditor.edit(odd, now)
        val start = editor.start.addAttachment("b.txt", Secret.of("b"))

        assertEquals("Another attachment has this name.", editor.attachmentProblem(start, start.attachments[0])?.english())
        assertEquals("Give the attachment a name.", editor.attachmentProblem(start, start.attachments[2])?.english())
        assertEquals(listOf("Every attachment needs a name.", "Attachment names must differ: a.txt."), editor.attachmentProblems(start).map { it.english() })
        assertFalse(editor.canSave(start))

        val fixed = start.removeAttachment(start.attachments[1].id).removeAttachment(start.attachments[2].id)
        assertNull(editor.attachmentProblem(fixed, fixed.attachments[0]))
        assertTrue(editor.canSave(fixed))
        val other = editor.start.copy(title = "Box")
        assertTrue(editor.canSave(other) && editor.attachmentProblems(other).isEmpty(), "an edit that leaves them alone saves")
    }

    // spec: edit.attachments/AC3
    // spec: edit.attachments/AC4
    @Test
    fun keptRemovedReplacedAndAddedAreSavedInTheListsOrder() {
        val editor = EntryEditor.edit(entry, now)
        val content = Secret.of("x")
        var draft = editor.start
        assertEquals("notes.txt", draft.attachmentNamed(" notes.txt ")?.name, "a file of that name asks Replace or Keep both")
        assertNull(draft.attachmentNamed("readme.txt"))
        assertEquals("notes 2.txt", draft.freeAttachmentName("notes.txt"), "Keep both's name")
        draft = draft.addAttachment("notes.txt", content)
        draft = draft.replaceAttachment(draft.named("id_rsa").id, Secret.of("new key"))
        draft = draft.removeAttachment(draft.named("KeeAgent.settings").id)
        val replaced = draft.named("id_rsa")
        assertTrue(replaced.isNew && editor.replaces(replaced), "replaced, in its place")
        assertFalse(editor.replaces(draft.named("notes 2.txt")))

        val changes = editor.changes(draft)

        assertEquals(
            listOf(AttachmentChange.Add("id_rsa", Secret.of("new key")), AttachmentChange.Keep("notes.txt", "notes.txt"), AttachmentChange.Add("notes 2.txt", Secret.of("x"))),
            changes.attachments,
        )
        val added = assertIs<AttachmentChange.Add>(changes.attachments!!.last())
        assertNotSame(content, added.content, "the change has a copy: wiped after the save, the draft's stays for another try")
        changes.wipe()
        assertFalse(content.isWiped)
        assertEquals(VaultEdit.Update("s", entry.lastModified, editor.changes(draft)), editor.edit(draft))
    }

    // spec: edit.attachments/AC4
    @Test
    fun onlyAListThatDiffersIsAChange() {
        val editor = EntryEditor.edit(entry, now)
        val start = editor.start
        assertEquals(entry.attachments.map { it.name }, start.attachments.map { it.name })
        assertTrue(start.attachments.none { it.isNew })
        assertNull(editor.changes(start.copy(title = "Box")).attachments, "an edit of another field leaves them alone")

        val added = start.addAttachment("a.txt", Secret.of("a"))
        assertTrue(editor.isDirty(added))
        assertFalse(editor.isDirty(added.removeAttachment(added.attachments.last().id)), "added and removed: nothing changed")
        assertTrue(editor.isDirty(start.replaceAttachment(start.named("notes.txt").id, Secret.of("same name"))), "replaced: a change")

        assertEquals(added, added.copy(), "a draft is equal to its copy, the content by identity")
        assertFalse(added == start.addAttachment("a.txt", Secret.of("a")), "another file's content is another attachment")
    }

    @Test
    fun aNewEntryAndANoteTakeAttachmentsToo() {
        val root = VaultGroup("r", "Root", emptyList())
        val vault = Vault(entries = emptyList(), groups = listOf(root))
        val editor = EntryEditor.create(vault, null, now, id = "n")
        val draft = editor.start.copy(title = "Scan").addAttachment("scan.pdf", Secret.of("pdf"))

        assertTrue(editor.canSave(draft))
        assertEquals(VaultEdit.Create("n", GroupDestination("r"), editor.changes(draft)), editor.edit(draft))
        assertEquals(listOf(AttachmentChange.Add("scan.pdf", Secret.of("pdf"))), editor.changes(draft).attachments)

        val note = EntryEditor.createNote(vault, null, now, dates = null, id = "m")
        val noteDraft = note.start.copy(notes = "text").addAttachment("photo.jpg", Secret.of("jpg"))
        assertEquals(listOf("photo.jpg"), note.changes(noteDraft).attachments?.map { it.name })
    }
}
