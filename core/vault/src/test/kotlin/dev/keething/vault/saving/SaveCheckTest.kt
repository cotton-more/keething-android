package dev.keething.vault.saving

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import dev.keething.kdbx.editing.AppliedEdit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The check of a written file against the file before it (the Mac `KDBXFileSaver.verify`). */
class SaveCheckTest {
    private val now = Instant.parse("2026-10-20T12:00:00.750Z")
    private val seconds = Instant.parse("2026-10-20T12:00:00Z")
    private val root = VaultGroup("R", "Root", emptyList())
    private val work = VaultGroup("W", "Work", listOf("Work"))
    private val bin = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin"))
    private val a = Entry("a", title = "A", password = Secret.of("pa"), groupPath = listOf("Work"), groupId = "W", lastModified = seconds.minusSeconds(60))
    private val b = Entry("b", title = "B", groupPath = listOf("Work"), groupId = "W")
    private val before = Vault(entries = listOf(a, b), groups = listOf(root, work))

    private fun check(after: Vault, edit: VaultEdit, applied: AppliedEdit = AppliedEdit(null), from: Vault = before) =
        SaveCheck.verify(from, after, edit, applied, now)

    private fun fails(detail: String, after: Vault, edit: VaultEdit, applied: AppliedEdit = AppliedEdit(null), from: Vault = before) {
        val failure = assertFailsWith<SaveCheck.Failed> { check(after, edit, applied, from) }
        assertEquals(detail, failure.detail)
    }

    @Test
    fun anUpdatePassesWhenItCameOutAsAskedAndNothingElseChanged() {
        val changes = EntryChanges(
            title = "A2", password = Secret.of("new"), customFields = listOf(CustomField.protected("PIN", "1")),
            tags = listOf(" x ", ""), expiry = EntryChanges.Expiry(true, now),
        )
        val edited = a.copy(
            title = "A2", password = Secret.of("new"), customFields = listOf(CustomField.protected("PIN", "1")),
            tags = listOf("x"), expires = true, expiryTime = seconds, lastModified = seconds,
        )

        check(before.copy(entries = listOf(edited, b)), VaultEdit.Update("a", a.lastModified, changes))
    }

    @Test
    fun fieldTypesMustComeOutAsAsked() {
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(fieldTypes = mapOf("PIN" to "password")))

        check(before.copy(entries = listOf(a.copy(fieldTypes = mapOf("PIN" to "password"), lastModified = seconds), b)), edit)
        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(lastModified = seconds), b)), edit)
        fails("another entry changed", before.copy(entries = listOf(a.copy(fieldTypes = mapOf("PIN" to "password")), b.copy(fieldTypes = mapOf("x" to "url")))), edit)
    }

    @Test
    fun aNotesProtectionAndTheKindMustComeOutAsAskedAndStayOnOtherEntries() {
        // spec: types.note/AC1
        val note = a.copy(notes = "Films", notesProtected = true, entryType = "note")
        val from = before.copy(entries = listOf(note, b))
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(notes = "Films 2"))
        val edited = note.copy(notes = "Films 2", lastModified = seconds)

        check(before.copy(entries = listOf(edited, b)), edit, from = from)
        fails("the edit did not come out as asked", before.copy(entries = listOf(edited.copy(notesProtected = false), b)), edit, from = from)
        fails("the edit did not come out as asked", before.copy(entries = listOf(edited.copy(entryType = null), b)), edit, from = from)
        val unprotecting = VaultEdit.Update("a", a.lastModified, EntryChanges(notesProtected = false))
        fails("the edit did not come out as asked", before.copy(entries = listOf(note.copy(lastModified = seconds), b)), unprotecting, from = from)
        val marking = VaultEdit.Update("a", a.lastModified, EntryChanges(entryType = EntryTypeChange.Set(EntryType.NOTE)))
        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(lastModified = seconds), b)), marking)
        val other = VaultEdit.Update("b", b.lastModified, EntryChanges(title = "B2"))
        val b2 = b.copy(title = "B2", lastModified = seconds)
        fails("another entry changed", before.copy(entries = listOf(note.copy(notesProtected = false), b2)), other, from = from)
        fails("another entry changed", before.copy(entries = listOf(note.copy(entryType = "NOTE"), b2)), other, from = from)
    }

    @Test
    fun anUpdateThatCameOutOtherwiseIsRefused() {
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(title = "A2"))

        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(title = "A3"), b)), edit)
        fails("the edit did not come out as asked", before.copy(entries = listOf(a.copy(title = "A2", password = Secret.of("x")), b)), edit)
        fails("another entry changed", before.copy(entries = listOf(a.copy(title = "A2"), b.copy(notes = "?"))), edit)
        fails("entries differ", before.copy(entries = listOf(a.copy(title = "A2"))), edit)
        fails("the groups changed", before.copy(entries = listOf(a.copy(title = "A2"), b), groups = listOf(root)), edit)
        fails("the groups changed", before.copy(entries = listOf(a.copy(title = "A2"), b), recycleBinEnabled = false), edit)
    }

    @Test
    fun aNewEntryIsInItsGroupWithItsFieldsAndKeePassXcsTimes() {
        val fields = EntryChanges(title = "New", username = "me", password = Secret.of("pw1"))
        val created = Entry(
            "n", title = "New", username = "me", password = Secret.of("pw1"), groupPath = listOf("Work"), groupId = "W",
            expiryTime = seconds, created = seconds, lastModified = seconds,
        )
        val edit = VaultEdit.Create("n", GroupDestination("W"), fields)

        check(before.copy(entries = listOf(a, b, created)), edit, AppliedEdit("W"))
        fails("the new entry did not come out as asked", before.copy(entries = listOf(a, b, created.copy(groupId = "R", groupPath = emptyList()))), edit, AppliedEdit("W"))
        fails("the new entry is missing", before, edit, AppliedEdit("W"))
    }

    @Test
    fun aNewEntryWhoseNewLevelAnotherAppMadeMeanwhileIsInThatGroup() {
        val created = Entry("n", title = "New", groupPath = listOf("Work"), groupId = "W", expiryTime = seconds, created = seconds, lastModified = seconds)
        val edit = VaultEdit.Create("n", GroupDestination("R", listOf(NewGroup("X", "work"))), EntryChanges(title = "New"))

        check(before.copy(entries = listOf(a, b, created)), edit, AppliedEdit("W", destination = GroupDestination("W")))
    }

    @Test
    fun aRecycledEntryIsInTheBinOtherwiseUnchanged() {
        val recycled = a.copy(groupPath = listOf("Recycle Bin"), groupId = "BIN", lastModified = seconds)
        val edit = VaultEdit.MoveToRecycleBin("a", a.lastModified)

        check(before.copy(entries = listOf(b), recycleBin = listOf(recycled)), edit, AppliedEdit("BIN"))
        fails("the entry did not move", before, edit, AppliedEdit("BIN"))
        fails("the moved entry changed", before.copy(entries = listOf(b), recycleBin = listOf(recycled.copy(title = "?"))), edit, AppliedEdit("BIN"))
    }

    @Test
    fun anEntryDeletedForGoodIsGoneAndNothingElseChanged() {
        val binned = a.copy(groupPath = listOf("Recycle Bin"), groupId = "BIN")
        val inBin = Vault(entries = listOf(b), groups = listOf(root, work), recycleBin = listOf(binned))
        val edit = VaultEdit.DeleteForGood("a", null)

        check(inBin.copy(recycleBin = emptyList()), edit, from = inBin)
        fails("the entry is still there", inBin, edit, from = inBin)
        fails("another entry changed", inBin.copy(entries = listOf(b.copy(title = "?")), recycleBin = emptyList()), edit, from = inBin)
    }

    @Test
    fun aRestoredEntryIsBackInItsGroupAndGroupsMadeOnTheWayAreAllowed() {
        val binned = a.copy(groupPath = listOf("Recycle Bin"), groupId = "BIN")
        val inBin = Vault(entries = listOf(b), groups = listOf(root, work), recycleBin = listOf(binned))
        val made = VaultGroup("M", "Old", listOf("Old"))
        val back = a.copy(groupPath = listOf("Old"), groupId = "M", lastModified = seconds)

        SaveCheck.verify(inBin, inBin.copy(entries = listOf(b, back), groups = listOf(root, work, made), recycleBin = emptyList()),
            VaultEdit.Restore("a", null), AppliedEdit("M", createdGroupIds = listOf("M")), now)
        assertFailsWith<SaveCheck.Failed> {
            SaveCheck.verify(inBin, inBin.copy(entries = listOf(b, back), groups = listOf(root, work, made), recycleBin = emptyList()),
                VaultEdit.Restore("a", null), AppliedEdit("M"), now)
        }
    }

    @Test
    fun theSettingsEntryAndTheBinAreEntriesLikeAnyOther() {
        val settings = Entry("s", title = Vault.SETTINGS_TITLE)
        val withSettings = before.copy(settings = settings, recycleBin = listOf(b.copy(id = "old", groupId = bin.id)))

        SaveCheck.verify(withSettings, withSettings.copy(entries = listOf(a.copy(notes = "n", lastModified = seconds), b)),
            VaultEdit.Update("a", null, EntryChanges(notes = "n")), AppliedEdit(null), now)
        assertFailsWith<SaveCheck.Failed> {
            SaveCheck.verify(withSettings, withSettings.copy(settings = null), VaultEdit.Update("a", null, EntryChanges()), AppliedEdit(null), now)
        }
    }

    // The groups' changes (keething-spec groups.manage AC8, groups.entry-group AC5–AC7; the Mac's `expectedStructure`)

    private val home = VaultGroup("H", "Home", listOf("Home"))
    private val mail = VaultGroup("M", "Mail", listOf("Work", "Mail"))
    // Two groups of one name in one parent (KeePassXC makes them): each is itself, by its id.
    private val twin = VaultGroup("X1", "Mail", listOf("Home", "Mail"))
    private val sub = VaultGroup("S", "Sub", listOf("Home", "Mail", "Sub"))
    private val other = VaultGroup("X2", "Mail", listOf("Home", "Mail"))
    private fun Entry.at(group: VaultGroup) = copy(groupPath = group.path, groupId = group.id)
    private val inMail = Entry("m", title = "M").at(mail)
    private val inTwin = Entry("x1", title = "X1").at(twin)
    private val inOther = Entry("x2", title = "X2").at(other)
    private val top = Entry("t", title = "T").at(root)
    private val binned = Entry("z", title = "Z", groupPath = listOf("Recycle Bin"), groupId = bin.id)

    /** Groups in file order: a group's groups follow it. */
    private val tree = Vault(
        entries = listOf(a, b, inMail, inTwin, inOther, top),
        groups = listOf(root, work, mail, home, twin, sub, other),
        recycleBin = listOf(binned),
    )

    @Test
    fun aRenamedGroupAndEverythingBelowItTakeTheNewPathAndNothingElseChanges() {
        val office = work.copy(name = "Office", path = listOf("Office"))
        val officeMail = mail.copy(path = listOf("Office", "Mail"))
        val edit = VaultEdit.RenameGroup("W", "  Office ")
        val after = tree.copy(
            entries = listOf(a.at(office), b.at(office), inMail.at(officeMail), inTwin, inOther, top),
            groups = listOf(root, office, officeMail, home, twin, sub, other),
        )

        check(after, edit, from = tree)
        fails("the groups did not come out as asked", tree, edit, from = tree)
        fails("the groups did not come out as asked", after.copy(groups = listOf(root, office, mail, home, twin, sub, other)), edit, from = tree)
        fails("the groups did not come out as asked", after.copy(groups = listOf(root, office, officeMail, home.copy(name = "H2", path = listOf("H2")), twin, sub, other)), edit, from = tree)
        fails("an entry changed", after.copy(entries = listOf(a.at(office), b, inMail.at(officeMail), inTwin, inOther, top)), edit, from = tree)
        fails("an entry changed", after.copy(entries = listOf(a.at(office).copy(title = "?"), b.at(office), inMail.at(officeMail), inTwin, inOther, top)), edit, from = tree)
        fails("an entry changed", after.copy(recycleBin = listOf(binned.copy(groupPath = listOf("Office")))), edit, from = tree)
        fails("entries differ", after.copy(entries = listOf(a.at(office), b.at(office), inMail.at(officeMail), inTwin, top)), edit, from = tree)
        fails("the groups did not come out as asked", after.copy(recycleBinEnabled = false), edit, from = tree)
    }

    @Test
    fun oneOfTwoGroupsOfOneNameIsChangedByItsIdTheOtherKeepsItsPath() {
        val post = twin.copy(name = "Post", path = listOf("Home", "Post"))
        val postSub = sub.copy(path = listOf("Home", "Post", "Sub"))
        val after = tree.copy(
            entries = listOf(a, b, inMail, inTwin.at(post), inOther, top),
            groups = listOf(root, work, mail, home, post, postSub, other),
        )

        check(after, VaultEdit.RenameGroup("X1", "Post"), from = tree)
        fails(
            "the groups did not come out as asked",
            after.copy(groups = listOf(root, work, mail, home, post, postSub, other.copy(name = "Post", path = listOf("Home", "Post")))),
            VaultEdit.RenameGroup("X1", "Post"), from = tree,
        )
        fails("an entry changed", after.copy(entries = listOf(a, b, inMail, inTwin.at(post), inOther.at(post), top)), VaultEdit.RenameGroup("X1", "Post"), from = tree)
    }

    @Test
    fun aMovedGroupTakesEverythingInItToItsNewPlace() {
        val moved = home.copy(path = listOf("Work", "Home"))
        val movedTwin = twin.copy(path = listOf("Work", "Home", "Mail"))
        val movedSub = sub.copy(path = listOf("Work", "Home", "Mail", "Sub"))
        val movedOther = other.copy(path = listOf("Work", "Home", "Mail"))
        val edit = VaultEdit.MoveGroup("H", "W")
        val after = tree.copy(
            entries = listOf(a, b, inMail, inTwin.at(movedTwin), inOther.at(movedOther), top),
            groups = listOf(root, work, mail, moved, movedTwin, movedSub, movedOther),
        )

        check(after, edit, from = tree)
        fails("the groups did not come out as asked", after.copy(groups = listOf(root, work, mail, moved, movedTwin, movedSub, other)), edit, from = tree)
        fails("an entry changed", after.copy(entries = listOf(a, b, inMail, inTwin.at(movedTwin), inOther, top)), edit, from = tree)
        fails("the group is missing", after, VaultEdit.MoveGroup("H", "gone"), from = tree)
    }

    @Test
    fun aNewGroupIsAddedInItsParentWithItsNameTrimmed() {
        val made = VaultGroup("N", "New", listOf("Work", "New"))
        val edit = VaultEdit.CreateGroup("W", NewGroup("N", " New "))

        check(tree.copy(groups = listOf(root, work, mail, made, home, twin, sub, other)), edit, from = tree)
        fails("the groups did not come out as asked", tree, edit, from = tree)
        fails("the groups did not come out as asked", tree.copy(groups = tree.groups + made.copy(name = " New ", path = listOf("Work", " New "))), edit, from = tree)
        fails("the groups did not come out as asked", tree.copy(groups = tree.groups + made.copy(path = listOf("New"))), edit, from = tree)
        fails("the groups did not come out as asked", tree.copy(groups = tree.groups + made), edit, from = tree)
        fails("an entry changed", tree.copy(groups = listOf(root, work, mail, made, home, twin, sub, other), entries = listOf(a.at(made), b, inMail, inTwin, inOther, top)), edit, from = tree)
    }

    @Test
    fun anEmptyGroupDeletedIsGoneAndNothingElseAndTheResultSaysSo() {
        val edit = VaultEdit.DeleteEmptyGroup("S")
        val after = tree.copy(groups = listOf(root, work, mail, home, twin, other))
        val applied = AppliedEdit(null, deletedGroupIds = listOf("S"))

        check(after, edit, applied, from = tree)
        fails("the groups did not come out as asked", tree, edit, applied, from = tree)
        fails("the groups did not come out as asked", after.copy(groups = listOf(root, work, mail, home, twin)), edit, applied, from = tree)
        fails("the groups deleted are not those reported", after, edit, AppliedEdit(null), from = tree)
        fails("the groups deleted are not those reported", after, edit, AppliedEdit(null, deletedGroupIds = listOf("S", "X2")), from = tree)
    }

    // An entry's move

    private val old = VaultGroup("O", "Old", listOf("Old"))
    private val deep = VaultGroup("D", "Deep", listOf("Old", "Deep"))
    private val lone = Entry("c", title = "C", lastModified = seconds.minusSeconds(60)).at(deep)
    private val moving = Vault(entries = listOf(a, b, lone), groups = listOf(root, work, old, deep))

    @Test
    fun aMovedEntryIsInItsNewGroupAndTheGroupsItLeftEmptyAreGoneInnermostFirst() {
        val edit = VaultEdit.Update("c", lone.lastModified, EntryChanges(group = GroupDestination("W")))
        val moved = lone.at(work).copy(lastModified = seconds)
        val after = moving.copy(entries = listOf(a, b, moved), groups = listOf(root, work))
        val applied = AppliedEdit("W", destination = GroupDestination("W"), deletedGroupIds = listOf("D", "O"))

        check(after, edit, applied, from = moving)
        fails("the groups changed", after.copy(groups = listOf(root, work, old)), edit, applied, from = moving)
        fails("the groups changed", after, edit, applied.copy(deletedGroupIds = listOf("D")), from = moving)
        fails("a group that was not empty went", after, edit, applied.copy(deletedGroupIds = listOf("O", "D")), from = moving)
        fails("the edit did not come out as asked", moving.copy(entries = listOf(a, b, lone.copy(lastModified = seconds))), edit, applied.copy(deletedGroupIds = emptyList()), from = moving)
        fails("the edit did not come out as asked", after.copy(entries = listOf(a, b, moved.copy(title = "?"))), edit, applied, from = moving)
        fails("another entry changed", after.copy(entries = listOf(a, b.copy(title = "?"), moved)), edit, applied, from = moving)
    }

    @Test
    fun aGroupStillHoldingAnotherEntryTheRootOrTheGroupItGoesIntoIsNeverDeletedForAMove() {
        // A leaves Work, B is still there.
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(group = GroupDestination("R")))
        val moved = a.at(root).copy(lastModified = seconds)
        fails(
            "a group that was not empty went", moving.copy(entries = listOf(moved, b, lone), groups = listOf(root, old, deep)), edit,
            AppliedEdit("R", destination = GroupDestination("R"), deletedGroupIds = listOf("W")), from = moving,
        )
        // C goes from Deep into Old: Old holds it.
        val up = VaultEdit.Update("c", lone.lastModified, EntryChanges(group = GroupDestination("O")))
        val upApplied = AppliedEdit("O", destination = GroupDestination("O"), deletedGroupIds = listOf("D"))
        check(moving.copy(entries = listOf(a, b, lone.at(old).copy(lastModified = seconds)), groups = listOf(root, work, old)), up, upApplied, from = moving)
        fails(
            "a group that was not empty went", moving.copy(entries = listOf(a, b, lone.at(old).copy(lastModified = seconds)), groups = listOf(root, work)), up,
            upApplied.copy(deletedGroupIds = listOf("D", "O")), from = moving,
        )
        // The root group, alone with an entry.
        val bare = Vault(entries = listOf(top), groups = listOf(root, work))
        fails(
            "a group that was not empty went", bare.copy(entries = listOf(top.at(work).copy(lastModified = seconds)), groups = listOf(work)),
            VaultEdit.Update("t", null, EntryChanges(group = GroupDestination("W"))),
            AppliedEdit("W", destination = GroupDestination("W"), deletedGroupIds = listOf("R")), from = bare,
        )
    }

    @Test
    fun aMovedEntrysNewGroupsAreTheAskedNamesTrimmedEachInsideTheOneBefore() {
        val asked = GroupDestination("W", listOf(NewGroup("S", " Servers "), NewGroup("P", "Prod")))
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(title = "A2", group = asked))
        val servers = VaultGroup("S", "Servers", listOf("Work", "Servers"))
        val prod = VaultGroup("P", "Prod", listOf("Work", "Servers", "Prod"))
        val after = before.copy(entries = listOf(a.at(prod).copy(title = "A2", lastModified = seconds), b), groups = listOf(root, work, servers, prod))
        val resolved = GroupDestination("W", listOf(NewGroup("S", "Servers"), NewGroup("P", "Prod")))
        val applied = AppliedEdit("P", createdGroupIds = listOf("S", "P"), destination = resolved)

        check(after, edit, applied)
        fails("the groups changed", after.copy(groups = listOf(root, work, servers.copy(name = " Servers ", path = listOf("Work", " Servers ")), prod)), edit, applied)
        fails("the groups changed", after.copy(groups = listOf(root, work, servers, prod.copy(path = listOf("Work", "Prod")))), edit, applied)
        fails("the edit did not come out as asked", after.copy(entries = listOf(a.at(servers).copy(title = "A2", lastModified = seconds), b)), edit, applied)
        fails("the new groups are not those asked", after, edit, applied.copy(destination = GroupDestination("W", listOf(NewGroup("S", "Servers"), NewGroup("Q", "Prod")))))
        fails("the new groups are not those asked", after, edit, applied.copy(expectedGroupId = "S"))
    }

    @Test
    fun aMovedEntrysLevelAnotherAppMadeMeanwhileIsThatAppsGroup() {
        // "Work / Mail" typed as new under Top level; Work is in the file by the save, so only Mail is made.
        val asked = GroupDestination("R", listOf(NewGroup("X", "work"), NewGroup("Y", "Mail")))
        val made = VaultGroup("Y", "Mail", listOf("Work", "Mail"))
        val after = before.copy(entries = listOf(a.at(made).copy(lastModified = seconds), b), groups = listOf(root, work, made))
        val applied = AppliedEdit("Y", createdGroupIds = listOf("Y"), destination = GroupDestination("W", listOf(NewGroup("Y", "Mail"))))

        check(after, VaultEdit.Update("a", a.lastModified, EntryChanges(group = asked)), applied)
        fails("the new groups are not those asked", after, VaultEdit.Update("a", a.lastModified, EntryChanges(group = asked)), applied.copy(destination = GroupDestination("gone", listOf(NewGroup("Y", "Mail")))))
    }

    // Each group in its parent, by the file's order (the review of AG2: a path alone does not tell two namesakes apart)

    @Test
    fun aNewOrMovedGroupIsInTheGroupAskedNotInANamesakeOfIt() {
        val made = VaultGroup("N", "New", listOf("Home", "Mail", "New"))
        val create = VaultEdit.CreateGroup("X1", NewGroup("N", "New"))
        check(tree.copy(groups = listOf(root, work, mail, home, twin, sub, made, other)), create, from = tree)
        fails("the groups did not come out as asked", tree.copy(groups = listOf(root, work, mail, home, twin, sub, other, made)), create, from = tree)

        val movedWork = work.copy(path = listOf("Home", "Mail", "Work"))
        val movedMail = mail.copy(path = listOf("Home", "Mail", "Work", "Mail"))
        val move = VaultEdit.MoveGroup("W", "X2")
        val entries = listOf(a.at(movedWork), b.at(movedWork), inMail.at(movedMail), inTwin, inOther, top)
        check(tree.copy(entries = entries, groups = listOf(root, home, twin, sub, other, movedWork, movedMail)), move, from = tree)
        fails("the groups did not come out as asked", tree.copy(entries = entries, groups = listOf(root, home, twin, sub, movedWork, movedMail, other)), move, from = tree)
    }

    @Test
    fun aMovedEntrysNewGroupIsInTheGroupAskedNotInANamesakeOfIt() {
        val made = VaultGroup("N", "New", listOf("Home", "Mail", "New"))
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(group = GroupDestination("X2", listOf(NewGroup("N", "New")))))
        val applied = AppliedEdit("N", createdGroupIds = listOf("N"), destination = GroupDestination("X2", listOf(NewGroup("N", "New"))))
        val entries = listOf(a.at(made).copy(lastModified = seconds), b, inMail, inTwin, inOther, top)

        check(tree.copy(entries = entries, groups = listOf(root, work, mail, home, twin, sub, other, made)), edit, applied, from = tree)
        fails("the groups changed", tree.copy(entries = entries, groups = listOf(root, work, mail, home, twin, sub, made, other)), edit, applied, from = tree)
    }

    @Test
    fun theLevelsTheFileHadAreWalkedByTheirNamesFromTheGroupAsked() {
        val from = before.copy(groups = listOf(root, work, home))
        val asked = GroupDestination("R", listOf(NewGroup("X", "wörk"), NewGroup("Y", "Mail")))
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(group = asked))
        val inWork = VaultGroup("Y", "Mail", listOf("Work", "Mail"))
        val inHome = VaultGroup("Y", "Mail", listOf("Home", "Mail"))

        check(
            from.copy(entries = listOf(a.at(inWork).copy(lastModified = seconds), b), groups = listOf(root, work, inWork, home)), edit,
            AppliedEdit("Y", createdGroupIds = listOf("Y"), destination = GroupDestination("W", listOf(NewGroup("Y", "Mail")))), from = from,
        )
        fails(
            "the new groups are not those asked",
            from.copy(entries = listOf(a.at(inHome).copy(lastModified = seconds), b), groups = listOf(root, work, home, inHome)), edit,
            AppliedEdit("Y", createdGroupIds = listOf("Y"), destination = GroupDestination("H", listOf(NewGroup("Y", "Mail")))), from = from,
        )
    }

    // The recycle bin's own groups, which the vault leaves out: the file's groups are given as well

    private val binInWork = VaultGroup("BIN", "Recycle Bin", listOf("Work", "Recycle Bin"))
    private val binOld = VaultGroup("BO", "Old", listOf("Work", "Recycle Bin", "Old"))
    private val recycled = Entry("z", title = "Z").at(binOld)
    private val withBin = Vault(entries = listOf(a, b), groups = listOf(root, work, mail), recycleBin = listOf(recycled))
    private val withBinFile = listOf(root, work, binInWork, binOld, mail)

    @Test
    fun aGroupHoldingTheRecycleBinTakesItAlongAndTheBinsEntriesTakeTheirNewPath() {
        val office = work.copy(name = "Office", path = listOf("Office"))
        val officeMail = mail.copy(path = listOf("Office", "Mail"))
        val officeBin = binInWork.copy(path = listOf("Office", "Recycle Bin"))
        val officeOld = binOld.copy(path = listOf("Office", "Recycle Bin", "Old"))
        val edit = VaultEdit.RenameGroup("W", "Office")
        val after = withBin.copy(entries = listOf(a.at(office), b.at(office)), groups = listOf(root, office, officeMail), recycleBin = listOf(recycled.at(officeOld)))
        fun verify(after: Vault, file: List<VaultGroup>) = SaveCheck.verify(withBin, after, edit, AppliedEdit(null), now, withBinFile, file)

        verify(after, listOf(root, office, officeBin, officeOld, officeMail))
        assertEquals(
            "the groups did not come out as asked",
            assertFailsWith<SaveCheck.Failed> { verify(after, listOf(root, office, officeBin, officeOld.copy(name = "Older"), officeMail)) }.detail,
        )
        assertEquals(
            "an entry changed",
            assertFailsWith<SaveCheck.Failed> { verify(after.copy(recycleBin = listOf(recycled)), listOf(root, office, officeBin, officeOld, officeMail)) }.detail,
        )
    }

    @Test
    fun anEntrysEditLeavesTheBinsGroupsAsTheyWereButTheBinARecycledEntryMakes() {
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(notes = "n"))
        val after = withBin.copy(entries = listOf(a.copy(notes = "n", lastModified = seconds), b))
        SaveCheck.verify(withBin, after, edit, AppliedEdit(null), now, withBinFile, withBinFile)
        assertEquals(
            "the recycle bin's groups changed",
            assertFailsWith<SaveCheck.Failed> {
                SaveCheck.verify(withBin, after, edit, AppliedEdit(null), now, withBinFile, listOf(root, work, binInWork, binOld.copy(name = "Older"), mail))
            }.detail,
        )

        val recycle = VaultEdit.MoveToRecycleBin("a", a.lastModified)
        val newBin = VaultGroup("BIN", "Recycle Bin", listOf("Recycle Bin"))
        val recycledA = before.copy(entries = listOf(b), recycleBin = listOf(a.at(newBin).copy(lastModified = seconds)))
        SaveCheck.verify(before, recycledA, recycle, AppliedEdit("BIN"), now, before.groups, before.groups + newBin)
        assertEquals(
            "the recycle bin's groups changed",
            assertFailsWith<SaveCheck.Failed> {
                SaveCheck.verify(before, recycledA, recycle, AppliedEdit("BIN"), now, before.groups, before.groups + newBin + VaultGroup("Q", "Q", listOf("Recycle Bin", "Q")))
            }.detail,
        )
    }

    // A new group never beside a namesake (the second review of AG2: a level the file had, made again)

    @Test
    fun aNewLevelOrANewGroupWhoseNameTheParentHasIsRefused() {
        val asked = GroupDestination("R", listOf(NewGroup("X", "Work"), NewGroup("Y", "Mail")))
        val edit = VaultEdit.Update("a", a.lastModified, EntryChanges(group = asked))
        val again = VaultGroup("X", "Work", listOf("Work"))
        val mailInIt = VaultGroup("Y", "Mail", listOf("Work", "Mail"))
        fails(
            "the new groups are not those asked",
            before.copy(entries = listOf(a.at(mailInIt).copy(lastModified = seconds), b), groups = listOf(root, work, again, mailInIt)), edit,
            AppliedEdit("Y", createdGroupIds = listOf("X", "Y"), destination = asked),
        )
        fails(
            "the groups did not come out as asked",
            before.copy(groups = listOf(root, work, VaultGroup("N", "WÖRK", listOf("WÖRK")))), VaultEdit.CreateGroup("R", NewGroup("N", "WÖRK")),
        )
    }

    @Test
    fun theBinARecycledEntryMakesIsInTheRootGroup() {
        val recycle = VaultEdit.MoveToRecycleBin("a", a.lastModified)
        val nested = VaultGroup("BIN", "Recycle Bin", listOf("Work", "Recycle Bin"))
        val recycledA = before.copy(entries = listOf(b), recycleBin = listOf(a.at(nested).copy(lastModified = seconds)))
        assertEquals(
            "the recycle bin's groups changed",
            assertFailsWith<SaveCheck.Failed> { SaveCheck.verify(before, recycledA, recycle, AppliedEdit("BIN"), now, before.groups, listOf(root, work, nested)) }.detail,
        )
    }
}
