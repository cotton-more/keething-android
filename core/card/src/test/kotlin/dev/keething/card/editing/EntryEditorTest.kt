package dev.keething.card.editing

import dev.keething.domain.text.english
import dev.keething.card.CardSection
import dev.keething.card.EntryCardBuilder
import dev.keething.card.FieldType
import dev.keething.card.field
import dev.keething.card.now
import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's rules (SPEC §6.2; the Mac `EditingTests`, `OneTimeCodeTests` expectations for `EntryEditorModel`). */
class EntryEditorTest {
    private val entry = Entry(
        "e", title = "Mail", username = "me", password = Secret.of("old"), url = "https://mail.example", notes = "n",
        groupPath = listOf("Work"), tags = listOf("a", "b"),
        customFields = listOf(field("PIN", "1", protected = true), field("Note", "x")),
        lastModified = Instant.ofEpochSecond(100),
    )

    private fun EntryDraft.rename(index: Int, name: String) = changeField(fields[index].id) { it.copy(name = name) }

    private fun EntryDraft.value(index: Int, value: String) = changeField(fields[index].id) { it.copy(value = value) }

    @Test
    fun onlyWhatChangedIsSaved() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start
        assertEquals("old", draft.password, "the password is there to edit")
        assertEquals(listOf("a", "b"), draft.tags)
        assertFalse(editor.isDirty(draft) || editor.canSave(draft))

        draft = draft.copy(password = "new").rename(1, "Comment").addField()
        draft = draft.changeField(draft.fields[2].id) { it.copy(name = "Code", value = "42", isProtected = true) }
        draft = draft.removeTag("a").typingTag("c")
        assertEquals(
            EntryChanges(
                password = Secret.of("new"),
                customFields = listOf(field("PIN", "1", protected = true), field("Comment", "x"), field("Code", "42", protected = true)),
                tags = listOf("b", "c"),
            ),
            editor.changes(draft),
        )
        draft = draft.copy(expires = true)
        assertEquals(true, editor.changes(draft).expiry?.expires)
        assertTrue(editor.changes(draft).expiry?.time != null)
        assertTrue(editor.canSave(draft))
        assertEquals(VaultEdit.Update("e", Instant.ofEpochSecond(100), editor.changes(draft)), editor.edit(draft))

        // The standard fields, each only when it changed.
        val standard = editor.start.copy(title = "Mail 2", username = "me2", url = "https://new.example", notes = "n2")
        val changes = editor.changes(standard)
        assertEquals(listOf("Mail 2", "me2", "https://new.example", "n2"), listOf(changes.title, changes.username, changes.url, changes.notes))
        assertEquals(EntryChanges(), editor.changes(editor.start.copy(title = entry.title)))
    }

    @Test
    fun problemsKeepItFromSaving() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start.addField().rename(2, " ").value(2, "v")
        assertEquals("Give the field a name: KeePass cannot keep a value without one.", editor.fieldProblem(draft, draft.fields[2])?.english())
        assertTrue(editor.hasFieldProblems(draft) && !editor.canSave(draft), "said at the field")
        draft = draft.rename(2, "PIN")
        assertEquals(listOf("Another field has this name.", null, "Another field has this name."), draft.fields.map { editor.fieldProblem(draft, it)?.english() })
        draft = draft.rename(2, "otp")
        assertEquals("“otp” is a name KeePass uses itself.", editor.fieldProblem(draft, draft.fields[2])?.english())
        draft = draft.rename(2, "Password")
        assertEquals("“Password” is a name KeePass uses itself.", editor.fieldProblem(draft, draft.fields[2])?.english())
        draft = draft.rename(2, "Fine")
        assertTrue(!editor.hasFieldProblems(draft) && editor.canSave(draft))
    }

    @Test
    fun aNewFieldStartsUnnamedAndBlankOnesAreLeftOut() {
        val editor = EntryEditor.edit(entry, now)
        var draft = editor.start.addField().addField()
        assertEquals("", draft.fields.last().name)
        assertEquals(draft.fields.map { it.id }.distinct().size, 4, "each field its own id")
        assertFalse(editor.hasFieldProblems(draft), "two unnamed, empty fields are no duplicates and no problem")
        assertTrue(editor.changes(draft).isEmpty && !editor.canSave(draft), "nor a change: they are not saved")
        draft = draft.value(3, "secret")
        assertEquals(listOf(false, false, false, true), draft.fields.map { editor.fieldProblem(draft, it) != null })
        draft = draft.rename(3, "Code")
        assertFalse(editor.hasFieldProblems(draft))
        assertEquals(listOf("PIN", "Note", "Code"), editor.changes(draft).customFields?.map { it.name }, "the blank one is left out")
        draft = draft.removeField(draft.fields[3].id)
        assertFalse(editor.isDirty(draft))
    }

    @Test
    fun namesAreSavedTrimmedButTidyingAloneIsNoChange() {
        val editor = EntryEditor.edit(entry.copy(customFields = listOf(field(" PIN ", "1"))), now)
        assertTrue(editor.changes(editor.start).customFields != null, "saved with the next change")
        assertFalse(editor.isDirty(editor.start) || editor.canSave(editor.start))
        val draft = editor.start.copy(title = "Post")
        assertEquals(listOf(field("PIN", "1")), editor.changes(draft).customFields)
    }

    @Test
    fun tagsAreSeparatedByCommasOrSemicolonsOnceEachInAnyCase() {
        val editor = EntryEditor.edit(entry, now)
        assertEquals(listOf("a", "b", "Ü"), editor.changes(editor.start.typingTag(" a;b ,, A, Ü ;ü,")).tags)
        assertNull(editor.changes(editor.start.typingTag("A; b")).tags, "the same tags written differently")
        assertEquals(emptyList(), editor.changes(editor.start.removeTag("a").removeLastTag().typingTag(" , ")).tags)
    }

    @Test
    fun tagChipsACommaOrEnterAddsOneNoDoublesTheTypedRestIsSavedCompletion() {
        val known = listOf("Shopping", "shared", "Bank", "Travel")
        val editor = EntryEditor.edit(entry.copy(tags = listOf("Bank")), now)
        var draft = editor.start
        assertEquals(listOf("Bank"), draft.tags)
        assertEquals(listOf("Shopping", "shared"), draft.typingTag("sh").tagSuggestions(known))
        assertEquals(listOf("Travel"), draft.typingTag("trvl").tagSuggestions(known), "fuzzy, as the search")
        assertEquals(listOf("Shopping"), draft.typingTag("hop").tagSuggestions(known), "inside a word")
        draft = draft.typingTag("travel, bank; Shop")
        assertEquals(listOf("Bank", "travel"), draft.tags, "the typed ones before the last comma become chips; Bank is there")
        assertEquals(" Shop", draft.tagInput)
        assertEquals(listOf("Bank", "travel", "Shop"), editor.changes(draft).tags, "what is still typed is saved too")
        draft = draft.commitTag("Shopping")
        assertEquals(listOf("Bank", "travel", "Shopping"), draft.tags)
        assertEquals("", draft.tagInput)
        assertEquals(emptyList(), draft.tagSuggestions(known), "nothing typed, nothing offered")
        assertEquals(listOf("shared"), draft.typingTag("s").tagSuggestions(known), "a tag on the entry is not offered")
        draft = draft.removeLastTag().removeTag("Bank")
        assertEquals(listOf("travel"), draft.tags)
        assertEquals(listOf("travel"), editor.changes(draft).tags)
        assertEquals(listOf("travel"), draft.typingTag("Travel").commitTag().tags, "the same tag in another case")
        assertEquals(draft, draft.typingTag("x").removeLastTag().copy(tagInput = ""), "backspace with something typed leaves the chips")
        assertEquals("", draft.typingTag("a, ").tagInput, "nothing after the last comma")
    }

    @Test
    fun theExpiryKeepsItsTimeOfDayAndOnlyARealChangeCounts() {
        val zone = ZoneId.of("Europe/Berlin")
        val expiring = entry.copy(expires = true, expiryTime = Instant.parse("2027-03-01T17:30:00Z"))
        val editor = EntryEditor.edit(expiring, now)
        assertEquals(Instant.parse("2027-03-01T17:30:00Z"), editor.start.expiryTime)
        var draft = editor.start.expiringOn(LocalDate.of(2027, 6, 15), zone)
        assertEquals(Instant.parse("2027-06-15T16:30:00Z"), draft.expiryTime, "18:30 in Berlin, summer time now")
        assertEquals(EntryChanges.Expiry(true, draft.expiryTime), editor.changes(draft).expiry)
        draft = editor.start.copy(expiryTime = editor.start.expiryTime.plusMillis(400))
        assertNull(editor.changes(draft).expiry, "less than a second: the file keeps seconds")
        assertEquals(EntryChanges.Expiry(false, null), editor.changes(editor.start.copy(expires = false)).expiry)

        val never = EntryEditor.edit(entry, now)
        assertEquals(now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(), never.start.expiryTime, "a year from now to start with")
        assertNull(never.changes(never.start.copy(expiryTime = now)).expiry, "a date without expiring is no change")

        // KeePassXC keeps the creation time in ExpiryTime of an entry that never expires: switched on, it starts a year ahead.
        val old = EntryEditor.edit(entry.copy(expires = false, expiryTime = Instant.parse("2019-04-01T10:00:00Z")), now)
        assertEquals(now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(), old.start.expiryTime)
        assertNull(old.changes(old.start).expiry)
        assertEquals(EntryChanges.Expiry(true, old.start.expiryTime), old.changes(old.start.copy(expires = true)).expiry)
        val expired = EntryEditor.edit(entry.copy(expires = true, expiryTime = Instant.parse("2019-04-01T10:00:00Z")), now)
        assertEquals(Instant.parse("2019-04-01T10:00:00Z"), expired.start.expiryTime, "an entry that has expired keeps its day")
    }

    @Test
    fun aNewEntryStartsWithTheDefaultUsernameAndNeedsATitleOrAddress() {
        val groups = listOf(VaultGroup("root", "Root", emptyList()), VaultGroup("web", "Web", listOf("Web")))
        val vault = Vault(entries = emptyList(), groups = groups, defaultUsername = "alex")
        val editor = EntryEditor.create(vault, groupId = null, now = now, id = "new")
        assertTrue(editor.isNew)
        assertEquals("root", editor.start.groupId, "the root group when none is given")
        assertEquals("alex", editor.start.username)
        assertFalse(editor.canSave(editor.start), "a new entry needs a title or a website")
        assertFalse(editor.isDirty(editor.start))
        val draft = editor.start.copy(url = "https://shop.example", password = "pw", groupId = "web")
        assertTrue(editor.canSave(draft))
        assertEquals(
            VaultEdit.Create("new", GroupDestination("web"), EntryChanges(username = "alex", password = Secret.of("pw"), url = "https://shop.example")),
            editor.edit(draft),
        )
        assertEquals("web", EntryEditor.create(vault, groupId = "web", now = now).start.groupId)
        assertEquals("root", EntryEditor.create(vault, groupId = "gone", now = now).start.groupId)

        // A path always names somewhere: a group gone by the save is the save's to refuse (SaveFailure.GroupNotFound).
        assertTrue(editor.canSave(draft.copy(groupId = "gone")))
    }

    // spec: groups.entry-group/AC2
    // spec: groups.entry-group/AC4
    @Test
    fun anotherGroupChosenMovesAnExistingEntryWithTheSaveWithAnEditOrAlone() {
        val editor = EntryEditor.edit(entry.copy(groupId = "work"), now)
        assertEquals("work", editor.originalGroupId)
        assertEquals(GroupChoice("work"), editor.start.group, "its own group to start with")
        assertNull(editor.destination(editor.start))
        assertFalse(editor.isMoved(editor.start))

        val moved = editor.start.choosing(GroupChoice("privat"))
        assertTrue(editor.isMoved(moved) && editor.isDirty(moved) && editor.canSave(moved), "a move alone is a change to save")
        assertEquals(GroupDestination("privat"), editor.destination(moved))
        assertEquals(VaultEdit.Update("e", Instant.ofEpochSecond(100), EntryChanges(group = GroupDestination("privat"))), editor.edit(moved))

        val edited = moved.copy(title = "Webmail")
        assertEquals(EntryChanges(title = "Webmail", group = GroupDestination("privat")), (editor.edit(edited) as VaultEdit.Update).changes, "one save")

        val back = moved.choosing(GroupChoice("work"))
        assertEquals(editor.start, back)
        assertFalse(editor.isMoved(back) || editor.isDirty(back), "back where it was")
        assertEquals(EntryChanges(title = "Webmail"), (editor.edit(back.copy(title = "Webmail")) as VaultEdit.Update).changes)
    }

    // spec: groups.new-groups/AC4
    @Test
    fun newGroupsGetTheirIdsOnceForTheEditorAndTheEntryGoesIntoTheLast() {
        val editor = EntryEditor.edit(entry.copy(groupId = "work"), now)
        val draft = editor.start.choosing(GroupChoice("work", listOf("Servers", "Prod")))
        assertTrue(editor.isMoved(draft), "into new groups in its own")
        val destination = editor.destination(draft)!!
        assertEquals("work", destination.groupId)
        assertEquals(listOf("Servers", "Prod"), destination.newGroups.map { it.name })
        assertEquals(2, destination.newGroups.map { it.id }.toSet().size)
        assertEquals(destination.newGroups.last().id, destination.finalGroupId)
        assertEquals(destination, editor.destination(draft), "the same ids while the draft is edited")
        val shorter = editor.destination(draft.choosing(GroupChoice("work", listOf("Servers"))))!!
        assertEquals(destination.newGroups.take(1), shorter.newGroups)

        val groups = listOf(VaultGroup("root", "Root", emptyList()), VaultGroup("web", "Web", listOf("Web")))
        val vault = Vault(entries = emptyList(), groups = groups)
        val new = EntryEditor.create(vault, groupId = "web", now = now, id = "new")
        assertEquals(GroupDestination("web"), new.destination(new.start), "a new entry always has one")
        assertFalse(new.isMoved(new.start.choosing(GroupChoice("root"))), "a new entry is not moved")
        val inNew = new.start.copy(title = "VPN").choosing(GroupChoice("web", listOf("Zed")))
        assertTrue(new.isDirty(new.start.choosing(GroupChoice("web", listOf("Zed")))))
        val create = new.edit(inNew) as VaultEdit.Create
        assertEquals("web", create.group.groupId)
        assertEquals(listOf("Zed"), create.group.newGroups.map { it.name })
        assertEquals(new.destination(inNew), create.group)

        // Another database: its top level, the new levels dropped with the group (plans/android-groups.md G8).
        val other = Vault(entries = emptyList(), groups = listOf(VaultGroup("w-root", "Work", emptyList())))
        val (_, there) = new.movedTo(other, inNew)
        assertEquals(GroupChoice("w-root"), there.group)
    }

    @Test
    fun aNewEntryMovedToAnotherDatabaseTakesItsGroupsRootAndDefaultUsernameKeepingWhatWasTyped() {
        val personal = Vault(
            entries = emptyList(),
            groups = listOf(VaultGroup("p-root", "Personal", emptyList()), VaultGroup("p-bank", "Bank", listOf("Bank"))),
            defaultUsername = "alex",
        )
        val work = Vault(
            entries = emptyList(),
            groups = listOf(VaultGroup("w-root", "Work", emptyList()), VaultGroup("w-eng", "Engineering", listOf("Engineering"))),
            defaultUsername = "alex.morgan@acme.io",
        )
        val editor = EntryEditor.create(personal, groupId = "p-bank", now = now, id = "new")

        // Nothing typed: the other database's start, still nothing to discard.
        val (untouched, start) = editor.movedTo(work, editor.start)
        assertTrue(untouched.isNew)
        assertEquals(work.groups, untouched.groups)
        assertEquals("w-root", start.groupId, "the group goes to its root")
        assertEquals("alex.morgan@acme.io", start.username, "its default username")
        assertFalse(untouched.isDirty(start))
        assertEquals("new", (untouched.edit(start.copy(title = "VPN")) as VaultEdit.Create).entryId, "the same new entry")

        // Typed: kept, the username too once changed; the group still goes to the root (another database's groups).
        val typed = editor.start.copy(title = "VPN", password = "pw", tags = listOf("vpn"), groupId = "p-bank")
        val (moved, draft) = editor.movedTo(work, typed)
        assertEquals(typed.copy(username = "alex.morgan@acme.io", groupId = "w-root"), draft)
        assertTrue(moved.isDirty(draft))
        assertTrue(moved.canSave(draft))
        val changedName = typed.copy(username = "someone")
        assertEquals("someone", editor.movedTo(work, changedName).second.username, "a username typed stays")

        // And back: the first database's default again, its root.
        val (back, backDraft) = moved.movedTo(personal, draft)
        assertEquals(typed.copy(groupId = "p-root"), backDraft)
        assertEquals(personal.groups, back.groups)

        // An existing entry is not moved.
        val existing = EntryEditor.edit(entry, now)
        assertEquals(existing to existing.start, existing.movedTo(work, existing.start))
    }

    @Test
    fun aBlockOfCodesBecomesNumberedProtectedFieldsThatTheCardListsAsCodes() {
        assertEquals(listOf("1234 5678", "abcd-efgh", "zz99", "04711"), EntryEditor.codes("1. 1234 5678\n2) abcd-efgh\n\n• zz99\n  04711  \n"))
        assertEquals(listOf("a1", "b2", "c3"), EntryEditor.codes("a1, b2; c3"), "one line split at commas and semicolons")
        assertEquals(
            listOf("1111-2222", "3333-4444", "5555-6666"),
            EntryEditor.codes("\n1111-2222, 3333-4444, 5555-6666\n\n"),
            "one line with its line end and blank lines around it is still one line",
        )

        val editor = EntryEditor.edit(Entry("e", title = "Shop"), now)
        var draft = assertIs<AddedCodes.Added>(editor.addCodes(editor.start, "1. 1234 5678\n2) abcd-efgh\n\n• zz99\n  04711  \n", " Backup codes ")).draft
        assertEquals(listOf("Backup codes_1", "Backup codes_2", "Backup codes_3", "Backup codes_4"), draft.fields.map { it.name })
        assertEquals(listOf("1234 5678", "abcd-efgh", "zz99", "04711"), draft.fields.map { it.value })
        assertTrue(draft.fields.all { it.isProtected })
        draft = assertIs<AddedCodes.Added>(editor.addCodes(draft, "a1\nb2", "Backup codes")).draft
        assertEquals(listOf("Backup codes_5", "Backup codes_6"), draft.fields.takeLast(2).map { it.name }, "the list's numbers go on")

        assertEquals("No codes: paste or type them, one per line.", assertIs<AddedCodes.Refused>(editor.addCodes(draft, "  \n ", "Backup codes")).problem.english())
        assertEquals("The codes need a name for their list.", assertIs<AddedCodes.Refused>(editor.addCodes(draft, "x", " ")).problem.english())
        assertTrue(assertIs<AddedCodes.Refused>(editor.addCodes(draft, "x", "a_b")).problem.english().contains("“_”"))
        assertTrue(assertIs<AddedCodes.Refused>(editor.addCodes(draft, (1..51).joinToString("\n"), "B")).problem.english().contains("at most 50"))

        val fields = editor.changes(draft).customFields.orEmpty().take(4)
        val card = EntryCardBuilder.build(Entry("e", title = "Shop", customFields = fields), now)
        val section = card.sections.single { it.title == "Backup codes" }
        assertEquals(CardSection.Kind.Codes, section.kind)
        assertEquals(listOf("1", "2", "3", "4"), section.rows.map { it.label.english() })
    }

    @Test
    fun theEditorFoldsAListOfCodesAsTheCardShowsIt() {
        val fields = listOf(field("Note", "x")) + (1..3).map { field("Recovery_$it", "c$it", protected = true) } +
            listOf(field("Two_1", "a"), field("Two_2", "b"), field("Recovery_4", "c4", protected = true))
        val editor = EntryEditor.edit(Entry("e", customFields = fields), now)
        val draft = editor.start
        val lists = editor.codeLists(draft)
        assertEquals(1, lists.size, "two fields are no list of codes")
        assertEquals("Recovery", lists[0].title)
        assertEquals(listOf(1, 2, 3, 6).map { draft.fields[it].id }, lists[0].fieldIds)
        assertTrue(lists[0].allProtected)
    }

    @Test
    fun theChangesSecretsAreTheEditorsOwnToWipe() {
        val editor = EntryEditor.edit(entry, now)
        val changes = editor.changes(editor.start.copy(password = "new").value(0, "2"))
        changes.wipe()
        assertTrue(changes.password!!.isWiped)
        assertTrue((changes.customFields!![0].value as dev.keething.domain.vault.FieldValue.Protected).secret.isWiped)
        assertEquals(Secret.of("old"), entry.password, "the entry's own are not touched")
        assertEquals(CustomField.protected("PIN", "1"), entry.customFields[0])
    }

    // Field types (Mac SPEC §3.12; the Mac `FieldTypeTests` "The editor" expectations)

    @Test
    fun typesComeFromTheEntryFollowARenameAndAreSavedTextOnlyWhereItChangesSomething() {
        val typed = Entry(
            "e", customFields = listOf(field("Ablauf", "2027-10-31"), field("Nr", "12"), field("Stand", "2027-01-01")),
            fieldTypes = mapOf("Ablauf" to "until"),
        )
        val editor = EntryEditor.edit(typed, now)
        var draft = editor.start
        assertEquals(listOf(FieldType.UNTIL, null, null), draft.fields.map { it.type })
        assertEquals(listOf(FieldType.UNTIL, FieldType.TEXT, FieldType.DATE), draft.fields.map { editor.shownType(draft, it) }, "untyped: what it is read as")
        assertFalse(editor.isDirty(draft))

        draft = draft.rename(0, "Gültig bis")
        assertEquals(mapOf("Gültig bis" to "until"), editor.changes(draft).fieldTypes, "the type follows its field")
        draft = draft.withType(draft.fields[1].id, FieldType.TEXT).withType(draft.fields[2].id, FieldType.TEXT)
        assertEquals(mapOf("Gültig bis" to "until", "Stand" to "text"), editor.changes(draft).fieldTypes, "text is kept only where the value would be read as something else")
        draft = draft.withType(draft.fields[2].id, null)
        assertEquals(mapOf("Gültig bis" to "until"), editor.changes(draft).fieldTypes)
        draft = draft.rename(0, "Ablauf")
        assertNull(editor.changes(draft).fieldTypes, "back as loaded: no change")
    }

    @Test
    fun typesOfFieldsTheEntryNoLongerHasGoWithTheNextSaveButAreNoChangeByThemselves() {
        val editor = EntryEditor.edit(Entry("e", customFields = listOf(field("Ablauf", "2027-10-31")), fieldTypes = mapOf("Ablauf" to "until", "Gone" to "date")), now)
        assertFalse(editor.isDirty(editor.start) || editor.canSave(editor.start))
        val draft = editor.start.copy(notes = "n")
        assertEquals(mapOf("Ablauf" to "until"), editor.changes(draft).fieldTypes)
        assertTrue(editor.canSave(draft))
    }

    @Test
    fun aTypeItDoesNotKnowIsKeptNewFieldsAndCodesGetTheirs() {
        val editor = EntryEditor.edit(Entry("e", customFields = listOf(field("a")), fieldTypes = mapOf("a" to "frist")), now)
        var draft = editor.start
        assertEquals("frist", draft.fields[0].typeName)
        draft = draft.copy(title = "T")
        assertNull(editor.changes(draft).fieldTypes, "kept as it is")
        draft = draft.addField(FieldType.URL)
        draft = draft.changeField(draft.fields.last().id) { it.copy(name = "Server", value = "db.example.com") }
        draft = assertIs<AddedCodes.Added>(editor.addCodes(draft, "111\n222", "Backup")).draft
        assertEquals(mapOf("a" to "frist", "Server" to "url", "Backup_1" to "code", "Backup_2" to "code"), editor.changes(draft).fieldTypes)
        draft = draft.addField(FieldType.DATE).let { d -> d.changeField(d.fields.last().id) { it.copy(name = "") } }
        assertEquals(4, editor.changes(draft).fieldTypes?.size, "an unnamed, empty field is not saved, nor its type")
    }

    // spec: types.crypto-wallet/AC9
    @Test
    fun aNewRecoveryPhraseAndItsPassphraseStartProtected() {
        var draft = EntryEditor.edit(Entry("e"), now).start
        for (type in listOf(FieldType.CRYPTO_SEED, FieldType.CRYPTO_PASSPHRASE, FieldType.CRYPTO_XPUB, FieldType.CRYPTO_ADDRESS)) draft = draft.addField(type)
        assertEquals(listOf(true, true, false, false), draft.fields.map { it.isProtected })
    }

    @Test
    fun aNewFieldOfATypeIsNamedAtOnceWithANumberWhenTheNameIsTaken() {
        var draft = EntryEditor.edit(Entry("e", customFields = listOf(field("CVC", "1"))), now).start
        for (type in listOf(FieldType.CARD_NUMBER, FieldType.CARD_CVC, FieldType.CARD_CVC, FieldType.WIFI_SSID, FieldType.PASSWORD, FieldType.URL)) {
            draft = draft.addField(type)
        }
        draft = draft.addField()
        assertEquals(listOf("CVC", "Card number", "CVC 2", "CVC 3", "Network name", "Password 2", "Website", ""), draft.fields.map { it.name }, "“Password” is KeePass's own")
        assertEquals(listOf(null, "card.number", "card.cvc", "card.cvc", "wifi.ssid", "password", "url", null), draft.fields.map { it.typeName })
        draft = draft.withType(draft.fields[7].id, FieldType.CARD_HOLDER)
        assertEquals("Cardholder", draft.fields[7].name, "an unnamed field gets the type's name")
        draft = draft.withType(draft.fields[1].id, FieldType.CARD_PIN)
        assertEquals("Card number", draft.fields[1].name, "a named one keeps its name")
    }

    @Test
    fun aFieldTypedAsADateWhoseValueIsNoneSaysSoAndSavesAllTheSame() {
        val fields = listOf(
            field("Ablauf", "2026-99-99"), field("Karte", "202799"), field("Gut", "20271031"), field("Monat", "202712"),
            field("Leer", ""), field("Geheim", "nonsense", protected = true), field("Text", "2026-99-99"),
        )
        val types = mapOf("Ablauf" to "until", "Karte" to "card.expiry", "Gut" to "date", "Monat" to "card.expiry", "Leer" to "date", "Geheim" to "date")
        val editor = EntryEditor.edit(Entry("e", customFields = fields, fieldTypes = types), now)
        assertEquals(
            listOf(
                "Not a date, shown as text. Write it as 2027-10-31, or 2027-10 for a month.",
                "Not a date, shown as text. Write it as 2027-10.", null, null, null, null, null,
            ),
            editor.start.fields.map { editor.fieldWarning(it)?.english() },
        )
        assertTrue(editor.canSave(editor.start.copy(notes = "changed")), "a warning keeps nothing from saving")
    }

    // spec: types.ssh-login/AC8
    @Test
    fun anSshHostOrPortThatIsNotOneSaysSoAndSavesAllTheSame() {
        val fields = listOf(field("Host", "db example"), field("Port", "70000"), field("Other", "x"), field("Port 2", " 2222 "), field("Secret", "no host", protected = true))
        val types = mapOf("Host" to "ssh.host", "Port" to "ssh.port", "Port 2" to "ssh.port", "Secret" to "ssh.host")
        val editor = EntryEditor.edit(Entry("e", customFields = fields, fieldTypes = types), now)
        assertEquals(
            listOf("Not a host name, shown as a row. Write it as host, user@host or host:port.", "Not a port (1–65535), shown as a row.", null, null, null),
            editor.start.fields.map { editor.fieldWarning(it)?.english() },
        )
        val fixed = editor.start.fields[0].copy(value = "alex@db.example.com:2222")
        assertNull(editor.fieldWarning(fixed))
        assertNull(editor.fieldWarning(fixed.copy(value = "[2001:db8::1]:22")))
        assertTrue(editor.canSave(editor.start.copy(notes = "changed")), "a warning keeps nothing from saving")
    }

    @Test
    fun aDayFromTheCalendarIsWrittenInIsoACardsExpiryAsItsMonth() {
        val editor = EntryEditor.edit(
            Entry("e", customFields = listOf(field("Ablauf", "31.10.2027"), field("Karte", "")), fieldTypes = mapOf("Ablauf" to "until", "Karte" to "card.expiry")),
            now,
        )
        var draft = editor.start
        assertEquals(LocalDate.of(2027, 10, 31), draft.fields[0].date)
        assertNull(draft.fields[1].date)
        val day = LocalDate.of(2028, 2, 29)
        draft = draft.withDate(draft.fields[0].id, day).withDate(draft.fields[1].id, day)
        assertEquals(listOf("2028-02-29", "2028-02"), draft.fields.map { it.value })
    }

    @Test
    fun aListOfCodesTypedCodeIsFoldedAtAnySize() {
        val fields = listOf(field("Backup_1", "a", protected = true), field("Backup_2", "b", protected = true))
        val editor = EntryEditor.edit(Entry("e", customFields = fields, fieldTypes = mapOf("Backup_1" to "code", "Backup_2" to "code")), now)
        assertEquals(listOf("Backup"), editor.codeLists(editor.start).map { it.title })
    }

    @Test
    fun aCardsNumberCvcPinAndANetworksKeyAreMaskedWhileTypedUnprotected() {
        val editor = EntryEditor.edit(Entry("e", title = "T"), now)
        for (type in listOf("card.number", "card.cvc", "card.pin", "wifi.password", "password")) {
            assertTrue(editor.isMasked(FieldDraft(1, "F", "123", isProtected = false, typeName = type)), type)
        }
        for (type in listOf("card.holder", "card.expiry", "wifi.ssid", null)) {
            assertFalse(editor.isMasked(FieldDraft(1, "F", "123", isProtected = false, typeName = type)), "$type")
        }
    }

}
