package dev.keething.sharing.payload

import dev.keething.card.CardText
import dev.keething.card.WalletSecrets.Kind
import dev.keething.domain.text.UserText
import dev.keething.totp.TotpText
import dev.keething.domain.secret.Secret
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.TotpChange
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryPartsTest {
    private val code = TotpConfig(Secret.of("12345678901234567890"), digits = 8)
    private val entry = Entry(
        id = "id",
        title = "Amazon",
        username = "alex",
        password = Secret.of("pw"),
        url = "https://amazon.de",
        notes = "",
        tags = listOf("shopping"),
        customFields = listOf(CustomField.protected("PIN", "4711"), CustomField.plain("Valid until", "2027-03-31")),
        fieldTypes = mapOf("PIN" to "card.pin", "Valid until" to "until"),
        totp = code,
        expires = true,
        expiryTime = Instant.parse("2027-01-01T00:00:00Z"),
    )

    @Test
    fun thePartsAreThoseWithAValueTheCodeLast() {
        assertEquals(
            listOf(EntryPart.Username, EntryPart.Password, EntryPart.Website, EntryPart.Field("PIN"), EntryPart.Field("Valid until"), EntryPart.OneTimeCode),
            Sending.parts(entry),
        )
        assertEquals(emptyList(), Sending.parts(Entry("x", title = "Only a title")))
    }

    // spec: card.passkey/AC5
    @Test
    fun aPasskeyIsNeverAPartNorSentItStaysOnThisPhone() {
        val withPasskey = entry.copy(
            passkeyFields = listOf(
                CustomField.plain("KPEX_PASSKEY_RELYING_PARTY", "webauthn.io"),
                CustomField.protected("KPEX_PASSKEY_PRIVATE_KEY_PEM", "-----BEGIN PRIVATE KEY-----"),
            ),
        )
        assertEquals(Sending.parts(entry), Sending.parts(withPasskey))
        assertTrue(Sending.passkeyStays(withPasskey))
        assertFalse(Sending.passkeyStays(entry))
        val shared = Sending.shared(withPasskey, emptySet())
        assertTrue(shared.customFields.none { it.name.startsWith("KPEX") })
    }

    @Test
    fun byDefaultEverythingButTheCodeGoesAsCopies() {
        val shared = Sending.shared(entry, Sending.leftOutByDefault(entry))

        assertEquals("Amazon", shared.title)
        assertEquals("alex", shared.username)
        assertEquals(Secret.of("pw"), shared.password)
        assertEquals(entry.customFields, shared.customFields)
        assertEquals(entry.fieldTypes, shared.fieldTypes)
        assertEquals(listOf("shopping"), shared.tags)
        assertEquals(entry.expiryTime, shared.expires)
        assertNull(shared.totp)
        shared.wipe()
        assertFalse(entry.password.isWiped, "the vault's own secrets stay")

        // Each custom field has a switch, an empty one too: its name can be what it says.
        val room = Entry("r", title = "Office", customFields = listOf(CustomField.plain("Empty", ""), CustomField.plain("Room", "12")))
        assertEquals(listOf(EntryPart.Field("Empty"), EntryPart.Field("Room")), Sending.parts(room))
        assertEquals(emptySet(), Sending.leftOutByDefault(room))
    }

    @Test
    fun aCodeSecretKeptInCustomFieldsIsLeftOutByDefaultAsTheCodeIs() {
        val keePass = Entry("k", title = "KeePass code", customFields = listOf(CustomField.protected("HmacOtp-Secret", "12345678"), CustomField.plain("HmacOtp-Counter", "3"), CustomField.plain("Note", "x")))

        assertEquals(setOf(EntryPart.Field("HmacOtp-Secret"), EntryPart.Field("HmacOtp-Counter")), Sending.leftOutByDefault(keePass))
        assertEquals(setOf(EntryPart.OneTimeCode), Sending.leftOutByDefault(entry))
        assertTrue(Sending.isSecondStep(keePass, EntryPart.Field("timeotp-secret-hex")))
        assertFalse(Sending.isSecondStep(keePass, EntryPart.Field("Note")))
    }

    // spec: sharing.what-goes/AC3
    @Test
    fun aFieldHoldingACodesLinkIsASecondStepWhetherItReadsOrNot() {
        val link = "otpauth://totp/GitHub:alex?secret=JBSWY3DPEHPK3PXP"
        val codes = entry.copy(
            customFields = listOf(
                CustomField.protected("One-time code 2", link),
                CustomField.plain("Backup code", "  OTPAUTH://totp/Bank?secret=JBSWY3DPEHPK3PXP"),
                CustomField.plain("Broken", "otpauth://hotp/x"),
                CustomField.plain("Room", "12"),
            ),
            fieldTypes = emptyMap(),
        )

        assertEquals(
            setOf(EntryPart.Field("One-time code 2"), EntryPart.Field("Backup code"), EntryPart.Field("Broken"), EntryPart.OneTimeCode),
            Sending.leftOutByDefault(codes),
        )
        assertTrue(Sending.isSecondStep(codes, EntryPart.Field("One-time code 2")))
        assertFalse(Sending.isSecondStep(codes, EntryPart.Field("Room")))
        // Its switch stays: turned on, it goes.
        assertEquals(link, (Sending.shared(codes, emptySet()).customFields.first().value as FieldValue.Protected).secret.reveal())
    }

    @Test
    fun codeFieldsThatGiveNoCodeStayBehindWithTheCardsReason() {
        val steam = CustomField.protected("otp", "otpauth://totp/Steam:alex?secret=GEZDGNBVGY3TQOJQ&encoder=steam")
        val entry = Entry("s", title = "Steam", codeFields = listOf(steam), hasCodeFields = true)

        assertEquals(UserText.of(CardText.CODE_UNSUPPORTED, UserText.of(TotpText.UNSUPPORTED_STEAM)), Sending.codeLeftBehind(entry))
        assertFalse(EntryPart.OneTimeCode in Sending.parts(entry))
        assertNull(Sending.codeLeftBehind(this.entry), "a live code goes, its switch off to start with")
        assertNull(Sending.codeLeftBehind(Entry("x", title = "No code")))
    }

    @Test
    fun partsLeftOutAreNotSentAndTheirTypesNeither() {
        val shared = Sending.shared(entry, setOf(EntryPart.Password, EntryPart.Field("PIN"), EntryPart.Username))

        assertTrue(shared.password.isEmpty())
        assertEquals("", shared.username)
        assertEquals(listOf("Valid until"), shared.customFields.map { it.name })
        assertEquals(mapOf("Valid until" to "until"), shared.fieldTypes)
        assertEquals(code, Sending.shared(entry, emptySet()).totp)
        assertNull(Sending.shared(entry.copy(expires = false), emptySet()).expires)
    }

    @Test
    fun aReceivedEntryBecomesAllItsFieldsAndItsCodeAsAnOtpLink() {
        val changes = Receiving.changes(Sending.shared(entry, emptySet()))

        assertEquals("Amazon", changes.title)
        assertEquals(Secret.of("pw"), changes.password)
        assertEquals(entry.customFields, changes.customFields)
        assertEquals(entry.fieldTypes, changes.fieldTypes)
        assertEquals(EntryChanges.Expiry(true, entry.expiryTime), changes.expiry)
        val totp = assertIs<TotpChange.Set>(changes.totp)
        assertEquals(code, totp.config)
        assertTrue(totp.uri.startsWith("otpauth://totp/Amazon:alex?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=30&digits=8"))
        assertNull(Receiving.changes(SharedEntry("x")).expiry)
    }

    @Test
    fun aReceivedEntryIsTaggedReceivedOnceBesidesTheSendersTags() {
        assertEquals(listOf("shopping", "received"), Receiving.changes(Sending.shared(entry, emptySet())).tags)
        assertEquals(listOf("received"), Receiving.changes(SharedEntry("x")).tags)
        assertEquals(listOf("Received", "a"), Receiving.changes(SharedEntry("x", tags = listOf("Received", "a"))).tags)
    }

    private val note = Entry("n", title = "Films to watch", notes = "Dune\nArrival", notesProtected = true, entryType = "note")

    // spec: sharing.content/AC9
    @Test
    fun aNoteGoesWithItsTextProtectedAndItsKindWhateverIsLeftOut() {
        val whole = Sending.shared(note, emptySet())
        assertEquals("Dune\nArrival", whole.notes)
        assertTrue(whole.notesProtected)
        assertEquals("note", whole.entryType)

        val withoutText = Sending.shared(note, setOf(EntryPart.Notes))
        assertEquals("", withoutText.notes)
        assertFalse(withoutText.notesProtected, "Notes' protection goes only with the Notes")
        assertEquals("note", withoutText.entryType, "the kind goes as the title does")

        assertEquals(" Checklist ", Sending.shared(note.copy(entryType = " Checklist "), emptySet()).entryType)
        val ordinary = Sending.shared(entry.copy(notes = "PIN in the drawer"), emptySet())
        assertFalse(ordinary.notesProtected)
        assertNull(ordinary.entryType)
    }

    // spec: sharing.receiving/AC9
    @Test
    fun aReceivedNoteIsSavedAsSentItsKindAsStored() {
        val changes = Receiving.changes(Sending.shared(note, emptySet()))
        assertEquals(true, changes.notesProtected)
        assertEquals(EntryTypeChange.Stored("note"), changes.entryType)

        assertEquals(EntryTypeChange.Stored(" Checklist "), Receiving.changes(SharedEntry("x", entryType = " Checklist ")).entryType)
        // Notes not protected, or an entry of a content 01: as the receiving database protects new Notes.
        val ordinary = Receiving.changes(SharedEntry("x", notes = "milk"))
        assertNull(ordinary.notesProtected)
        assertNull(ordinary.entryType)
    }

    private val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val xprv = "xprv9s21ZrQH143K3h3fDYiay8mocZ3afhfULfb5GX8kCBdno77K4HiA15Tg23wpbeF1pLfs1c5SPmYHrEpTuuRhxMwvKDwqdKiGJS9XFKzUsAF"
    private val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    private val wallet = Entry(
        id = "w",
        title = "Cold wallet",
        username = "satoshi",
        customFields = listOf(
            CustomField.protected("Seed", phrase),
            CustomField.protected("Passphrase", "correct horse"),
            CustomField.plain("Address", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"),
            CustomField.plain("zpub", zpub),
            CustomField.plain("Key", xprv),
            CustomField.plain("Path", "m/84'/0'/0'"),
        ),
        fieldTypes = mapOf(
            "Seed" to "crypto.seed", "Passphrase" to "crypto.passphrase", "Address" to "crypto.address", "zpub" to "crypto.xpub",
            "Key" to "crypto.xpub", "Path" to "crypto.path",
        ),
    )

    @Test
    fun aWalletsPhrasePassphraseAndPrivateKeyAreNeverAmongTheParts() {
        assertEquals(
            mapOf<EntryPart, Kind>(EntryPart.Field("Seed") to Kind.PHRASE, EntryPart.Field("Passphrase") to Kind.PASSPHRASE, EntryPart.Field("Key") to Kind.PRIVATE_KEY),
            Sending.neverSent(wallet),
        )
        assertEquals(listOf(EntryPart.Username, EntryPart.Field("Address"), EntryPart.Field("zpub"), EntryPart.Field("Path")), Sending.parts(wallet))
        assertEquals(emptyMap<EntryPart, Kind>(), Sending.neverSent(entry))
    }

    @Test
    fun theExtendedPublicKeyIsOffToStartWith() {
        assertEquals(setOf(EntryPart.Field("zpub")), Sending.leftOutByDefault(wallet))
        assertTrue(Sending.isWalletKey(wallet, EntryPart.Field("zpub")))
        assertFalse(Sending.isWalletKey(wallet, EntryPart.Field("Address")))
    }

    @Test
    fun whatNeverGoesIsNotSentEvenWhenNothingIsLeftOut() {
        val shared = Sending.shared(wallet, emptySet())

        assertEquals(listOf("Address", "zpub", "Path"), shared.customFields.map { it.name })
        assertEquals(mapOf("Address" to "crypto.address", "zpub" to "crypto.xpub", "Path" to "crypto.path"), shared.fieldTypes)
    }

    @Test
    fun untypedAPhraseOrPrivateKeyIsFoundByItsValueWhereverItIs() {
        val untyped = Entry(
            "u",
            title = "Old wallet",
            password = Secret.of(phrase),
            notes = "  $xprv\n",
            customFields = listOf(
                CustomField.protected("Words", "1. legal 2. winner 3. thank 4. year 5. wave 6. sausage 7. worth 8. useful 9. legal 10. winner 11. thank 12. yellow"),
                CustomField.plain("Recovery phrase", "legal winner thank year wave sausage"),
                CustomField.protected("Mnemonic", "something only I know"),
                CustomField.protected("25th word", "extra"),
                CustomField.plain("WIF", "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"),
                CustomField.protected("Compressed", "KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617"),
                CustomField.plain("Address", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"),
                CustomField.plain("Note", "legal winner thank you"),
            ),
        )

        assertEquals(
            mapOf(
                EntryPart.Password to Kind.PHRASE,
                EntryPart.Notes to Kind.PRIVATE_KEY,
                EntryPart.Field("Words") to Kind.PHRASE,
                EntryPart.Field("Recovery phrase") to Kind.PHRASE,
                EntryPart.Field("Mnemonic") to Kind.PHRASE,
                EntryPart.Field("WIF") to Kind.PRIVATE_KEY,
                EntryPart.Field("Compressed") to Kind.PRIVATE_KEY,
                EntryPart.Field("25th word") to Kind.PASSPHRASE,
            ),
            Sending.neverSent(untyped),
        )
        assertEquals(listOf(EntryPart.Field("Address"), EntryPart.Field("Note")), Sending.parts(untyped))
        assertTrue(Sending.shared(untyped, emptySet()).password.isEmpty())
    }

    @Test
    fun anUntypedLitecoinPrivateKeyOrOneOfAVersionNotKnownIsNeverSent() {
        val keys = Entry(
            "l",
            customFields = listOf(
                CustomField.plain("Ltpv", "Ltpv71G8qDifUiNetfhEfSmD7YzYopWSkhLokecdE1XBBqDsoutBvvUiMvAKypUqUMtCjTF967ixYrEwqjwYK2QgbUKGGnu1wGmJ6pmRJhdnqXg"),
                CustomField.plain("dgpv", "dgpv51eADS3spNJh9ZLLadSaeWTrxKewJcW8TjvzV79cGcuVg3jE8izzadTCfAYffoKViWMKLQ8bw5ZGX3wahDTgfzChUPt4mgrBJ4ShoQxux1C"),
                CustomField.plain("dgub", "dgub8kXBZ7ymNWy2S8Q3jNgVjFUm5ZJ3QLLaSTdAA89ukSv7Q6MSXwE14b7Nv6eDpE9JJXinTKc8LeLVu19uDPrm5uJuhpKNzV2kAgncwo6bNpP"),
            ),
        )

        assertEquals(mapOf<EntryPart, Kind>(EntryPart.Field("Ltpv") to Kind.PRIVATE_KEY, EntryPart.Field("dgpv") to Kind.PRIVATE_KEY), Sending.neverSent(keys))
        assertEquals(listOf<EntryPart>(EntryPart.Field("dgub")), Sending.parts(keys))
    }

    @Test
    fun notCaughtAPhraseInsideLongerNotesAndAWrongChecksum() {
        val notes = Entry("n", notes = "My wallet:\n$phrase", password = Secret.of("legal winner thank year wave sausage worth useful legal winner thank thank"))

        assertEquals(emptyMap<EntryPart, Kind>(), Sending.neverSent(notes))
    }

    @Test
    fun aReceivedAddressIsKnownByItsTypeOrFormatAndAPhraseIsNeverCopied() {
        val shared = SharedEntry(
            "x",
            customFields = listOf(CustomField.plain("To", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"), CustomField.plain("Typed", "anything"), CustomField.plain("Seed", phrase)),
            fieldTypes = mapOf("Typed" to "crypto.address", "Seed" to "crypto.seed"),
        )

        assertEquals(listOf(true, true, false), shared.customFields.map { Receiving.isAddress(shared, it) })
        assertEquals(listOf(false, false, true), shared.customFields.map { Receiving.isNeverCopied(shared, it) })
    }
}
