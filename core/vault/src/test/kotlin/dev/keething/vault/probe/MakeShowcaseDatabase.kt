package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.DatabaseBackups
import dev.keething.domain.totp.Base32
import dev.keething.domain.vault.AttachmentChange
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.EntryType
import dev.keething.domain.vault.EntryTypeChange
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.TotpChange
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxFormat
import dev.keething.totp.TotpSetup
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/**
 * The showcase's fictitious databases, for the listing's screenshots (fastlane/metadata, plans/android-release-1.0.md
 * T9): "Personal", an everyday database with an entry of every kind the card draws (a payment card, a bank account,
 * a passport, a Wi-Fi network, an SSH login, a crypto wallet, a note), one-time codes, tags, an attachment and dates
 * that end soon, and "Family", a smaller one beside it. Every name, address and number is made up; the card number,
 * the IBAN and the recovery phrase are only valid in form (Luhn, ISO 7064, BIP-39's checksum), the codes' secrets are
 * new at each run, and the dates follow the day it is run. Password `pw`, as the fixtures'.
 *
 * Usage: `task screens:database -- <new folder>`. KeePassXC opening both files is checked before it is called done.
 */
fun main(arguments: Array<String>) {
    if (arguments.size != 1) fail("usage: MakeShowcaseDatabase <new folder>")
    val folder = File(arguments[0])
    if (folder.exists()) fail("$folder exists: give a new folder")
    folder.mkdirs()
    val today = LocalDate.now()
    write(File(folder, "Personal.kdbx"), "Personal", "#1E88E5", personal(today))
    write(File(folder, "Family.kdbx"), "Family", "#F57C00", family(today))
    println("RESULT: $folder written (password $PASSWORD)")
}

/** An entry to write and the group it goes in (made the first time it is named; null: the database's top). */
private class Showcase(val group: String?, val changes: EntryChanges)

private fun write(output: File, name: String, color: String, entries: List<Showcase>) {
    val now = Instant.now()
    val kdf = KdbxFormat.Kdf.Argon2(KdbxFormat.Kdf.Argon2.Variant.ID, iterations = 2, memoryBytes = 64L shl 20, parallelism = 2)
    output.writeBytes(NewDatabase.file(name, output.name, KdbxFormat.Cipher.AES256, kdf, key(), now))

    val store = FileStore(output)
    val saver = VaultSaver(store, NoShowcaseBackups)
    val top = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault?.groups?.single { it.isRoot }
        ?: fail("the new file does not open")
    fun save(edit: VaultEdit) {
        val result = saver.save(edit, key(), now)
        if (result !is SaveResult.Saved) fail("not saved: $result")
        result.opened.vault.wipe()
    }
    save(VaultEdit.NameAndColor(color = color))
    val groups = mutableMapOf<String, String>()
    for (showcase in entries) {
        val destination = when (val group = showcase.group) {
            null -> GroupDestination(top.id)
            in groups -> GroupDestination(groups.getValue(group))
            else -> NewGroup(Entry.newId(), group).let { made ->
                groups[group] = made.id
                GroupDestination(top.id, listOf(made))
            }
        }
        save(VaultEdit.Create(Entry.newId(), destination, showcase.changes))
    }

    val vault = (VaultOpener().open(store, key()) as? OpenResult.Opened)?.opened?.vault ?: fail("${output.name} does not open")
    println("${output.name}: ${vault.entries.size} entries in ${vault.groups.size - 1} groups")
    vault.wipe()
    KeePassXc.export(output, Secret.of(PASSWORD)) ?: fail("KeePassXC cannot open ${output.name}")
    println("${output.name}: KeePassXC opens it")
}

private fun personal(today: LocalDate) = listOf(
    Showcase(
        "Banking",
        login("Alder Bank", "alex.morgan", "Tq7#vR2m!pL9wZ", "https://alderbank.com", code = true, tags = listOf("finance"))
            .copy(customFields = listOf(field("Customer number", "4471 2286"))),
    ),
    Showcase(
        "Banking",
        typed(
            "Alder Bank Visa",
            "Number" to "card.number" to "4539 5781 2364 0192",
            "Holder" to "card.holder" to "ALEX MORGAN",
            "Expiry" to "card.expiry" to today.plusYears(2).withMonth(9).let { "%d-%02d".format(it.year, it.monthValue) },
            "CVC" to "card.cvc" to "381!",
            "PIN" to "card.pin" to "2741!",
            "Type" to "card.type" to "Visa",
        ).copy(tags = listOf("finance")),
    ),
    Showcase(
        "Banking",
        typed(
            "Alder Bank account",
            "IBAN" to "bank.iban" to "DE51 2135 2240 0418 8362 15",
            "BIC" to "bank.bic" to "ALDRDEFF",
            "Bank" to "bank.name" to "Alder Bank",
            "Account holder" to "bank.holder" to "Alex Morgan",
        ).copy(tags = listOf("finance")),
    ),
    Showcase("Banking", login("PayFlow", "alex.morgan@fjordmail.com", "pF-8u!Kd2#xQ", "https://payflow.com", code = true)),
    Showcase(
        "Work",
        login("Kestrel Code", "alexm", "w9!Hc4tZ#q2Lr", "https://kestrelcode.dev", code = true, tags = listOf("work"))
            .copy(customFields = listOf(field("API token", "kc_live_7Fh2Qw9LmX4tR8vZ1yN6", protected = true))),
    ),
    Showcase("Work", login("Teamloop", "alex@fernwork.io", "Lp3#zM8q!tW6", "https://teamloop.app", code = true, tags = listOf("work"))),
    Showcase(
        "Work",
        typed(
            "Build server",
            "Host" to "ssh.host" to "build.fernwork.io",
            "Port" to "ssh.port" to "2222",
            "User" to "ssh.user" to "deploy",
        ).copy(password = Secret.of("dK7!vQ2#mR9x"), tags = listOf("work")),
    ),
    Showcase("Work", login("Office VPN", "amorgan", "Vn6#rT1!kW8s", "https://vpn.fernwork.io", tags = listOf("work"))),
    Showcase(
        "Email & social",
        login("Fjordmail", "alex.morgan@fjordmail.com", "Fm2!xR7#pQ4z", "https://fjordmail.com", code = true)
            .copy(
                customFields = listOf(
                    field("Backup account", otpLink("Fjordmail", "alex.backup@fjordmail.com"), protected = true),
                    // The autofill screenshot's sign-in page (tools/screens/pages), served on the Mac through adb reverse.
                    field("KP2A_URL", "http://localhost:8000"),
                ),
            ),
    ),
    Showcase("Email & social", login("Lumen", "@alexmorgan", "Lm9#tQ2!vX5k", "https://lumen.social", code = true)),
    Showcase("Email & social", login("Pixelnest", "alex.morgan@fjordmail.com", "Px4!nT8#rL2w", "https://pixelnest.photos")),
    Showcase(
        "Home",
        typed(
            "Home Wi-Fi",
            "Network" to "wifi.ssid" to "Morgan Home",
            "Password" to "wifi.password" to "maple-orbit-42-quiet!",
            "Security" to "wifi.security" to "WPA2",
        ),
    ),
    Showcase(
        "Home",
        login("Harbor Fiber", "HF-2290415", "Hb7#kW2!qT9m", "https://harborfiber.net")
            .copy(
                customFields = listOf(field("Contract ends", today.plusDays(36).toString())),
                fieldTypes = mapOf("Contract ends" to "until"),
            ),
    ),
    Showcase("Home", login("Router", "admin", "Rt5!mQ8#zK3v", "http://192.168.178.1")),
    Showcase(
        "Home",
        login("Tidewater Energy", "alex.morgan@fjordmail.com", "Td3#wP7!xM2q", "https://tidewater-energy.com")
            .copy(customFields = listOf(field("Customer number", "TE 5518 0273"), field("Meter number", "1ESY1160482917"))),
    ),
    Showcase(
        "Documents",
        typed(
            "Passport",
            "Type" to "doc.type" to "Passport",
            "Number" to "doc.number" to "C4X9R2T71!",
            "Name" to "doc.name" to "Alex Morgan",
            "Country" to "doc.country" to "Germany",
            "Date of birth" to "doc.birth" to "1990-04-12",
            "Issued" to "doc.issued" to today.minusYears(10).plusMonths(5).toString(),
            "Expiry" to "doc.expiry" to today.plusMonths(5).toString(),
        ).copy(attachments = listOf(AttachmentChange.Add("passport-scan.pdf", Secret.of(PDF)))),
    ),
    Showcase(
        "Documents",
        typed(
            "Driving licence",
            "Type" to "doc.type" to "Driving licence",
            "Number" to "doc.number" to "B072RT9154!",
            "Name" to "doc.name" to "Alex Morgan",
            "Expiry" to "doc.expiry" to today.plusYears(8).toString(),
        ),
    ),
    Showcase(
        "Documents",
        EntryChanges(
            title = "Health insurance",
            customFields = listOf(field("Insurer", "Meridian Health"), field("Member number", "M 204 881 735"), field("Hotline", "+49 30 5550 1920")),
        ),
    ),
    Showcase(
        "Crypto",
        typed(
            "Bitcoin cold wallet",
            "Recovery phrase" to "crypto.seed" to "$PHRASE!",
            "Receiving address" to "crypto.address" to "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            "Derivation path" to "crypto.path" to "m/84'/0'/0'",
        ).copy(tags = listOf("crypto")),
    ),
    Showcase(
        "Crypto",
        typed("Ethereum", "Receiving address" to "crypto.address" to "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed").copy(tags = listOf("crypto")),
    ),
    Showcase("Shopping", login("Pinecone Books", "alex.morgan@fjordmail.com", "Pb8!wK3#tX6q", "https://pineconebooks.com")),
    Showcase(
        "Shopping",
        login("Brightpath Air", "alex.morgan@fjordmail.com", "Ba2#qM7!vR4t", "https://brightpath.air")
            .copy(customFields = listOf(field("Frequent flyer", "BP 418 220 973"))),
    ),
    Showcase("Shopping", login("Streamly", "alex.morgan@fjordmail.com", "St6!zL1#wQ9p", "https://streamly.tv")),
    Showcase(
        null,
        EntryChanges(title = "Gym locker", notes = "Locker 112, combination 4 – 1 – 7 – 9", entryType = EntryTypeChange.Set(EntryType.NOTE)),
    ),
)

private fun family(today: LocalDate) = listOf(
    Showcase(null, login("Streamly (family plan)", "morgan.family@fjordmail.com", "Fs4#tW9!kM2x", "https://streamly.tv")),
    Showcase(
        null,
        typed(
            "Guest Wi-Fi",
            "Network" to "wifi.ssid" to "Morgan Guests",
            "Password" to "wifi.password" to "sunny-garden-17",
            "Security" to "wifi.security" to "WPA2",
        ),
    ),
    Showcase(null, login("School portal", "sam.morgan", "Sp7!rQ3#vK8w", "https://school-portal.example.org", code = true)),
    Showcase(
        null,
        EntryChanges(
            title = "Car insurance",
            customFields = listOf(field("Insurer", "Atlas Mutual"), field("Policy number", "AM-77 410 263"), field("Renewal", today.plusDays(52).toString())),
            fieldTypes = mapOf("Renewal" to "until"),
        ),
    ),
    Showcase(null, EntryChanges(title = "Kids' tablet", notes = "Parental PIN 8 3 6 0", entryType = EntryTypeChange.Set(EntryType.NOTE))),
)

private const val PASSWORD = "pw"

/**
 * A BIP-39 phrase whose checksum passes, made up for the showcase (no wallet was ever made from it); words of six
 * letters at most: an eight-letter one is cut short in the plate's cells on a phone 1080 pixels wide.
 */
private const val PHRASE = "inner mirror cruise melody debate entire autumn stove few hen tiger anchor"

/** A one-page PDF, the passport's "scan". */
private const val PDF = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj " +
    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"

private val random = SecureRandom()

private fun login(title: String, username: String, password: String, url: String, code: Boolean = false, tags: List<String> = emptyList()) =
    EntryChanges(
        title = title, username = username, password = Secret.of(password), url = url, tags = tags,
        totp = if (code) otpLink(title, username).let { TotpChange.Set(TotpSetup.parse(it), it) } else null,
    )

/** An entry of typed fields: name, type and value; a value ending in `!` is protected (the `!` left out). */
private fun typed(title: String, vararg fields: Pair<Pair<String, String>, String>) = EntryChanges(
    title = title,
    customFields = fields.map { (nameAndType, value) -> field(nameAndType.first, value.removeSuffix("!"), protected = value.endsWith("!")) },
    fieldTypes = fields.associate { (nameAndType, _) -> nameAndType },
)

/** A code with a secret new at each run. */
private fun otpLink(issuer: String, account: String): String {
    val secret = Base32.encode(ByteArray(20).also(random::nextBytes))
    val config = TotpSetup.parse(secret)
    return TotpSetup.otpauthUri(config, issuer, account)
}

private fun field(name: String, value: String, protected: Boolean = false) =
    CustomField(name, if (protected) FieldValue.Protected(Secret.of(value)) else FieldValue.Plain(value))

private fun key(): DatabaseKey = DatabaseKey.of(Secret.of(PASSWORD))

private object NoShowcaseBackups : DatabaseBackups {
    override fun keep(database: ByteArray, fileName: String, at: Instant) {}
}
