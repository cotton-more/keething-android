package dev.keething.kdbx.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.FieldValue
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.TotpChange
import dev.keething.kdbx.KeePassXcCli
import dev.keething.kdbx.creating.NewDatabase
import dev.keething.kdbx.editing.EntryLifecycle
import dev.keething.kdbx.editing.GroupLifecycle
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxFormat.Cipher
import dev.keething.kdbx.reader.KdbxFormat.Kdf
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.KdbxSchema
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.writer.KdbxWriter
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

/**
 * A large fictitious database for the measurements of keething-spec app.large-database: as a stranger's file after
 * years of use, in KeePassXC's shape. Entries in three levels of groups, each with a username, a protected password,
 * most with a website, some with notes, tags, custom fields (a few protected) and a one-time code; a history of up to
 * ten versions; attachments of random bytes (they do not compress) from a few KB to a few MB; a recycle bin. The content
 * is the same for the same arguments (a fixed seed); the salts and the UUIDs are new each time. Password `pw`.
 *
 * Usage: `task large:database -- <new.kdbx> [entries] [attachments MiB] [Argon2 MiB] [iterations]`
 * (defaults 5000, 40, 64, 2; Argon2id, 2 lanes, AES-256).
 */
fun main(arguments: Array<String>) {
    if (arguments.isEmpty() || arguments.size > 5) fail("usage: MakeLargeDatabase <new.kdbx> [entries] [attachments MiB] [Argon2 MiB] [iterations]")
    val output = File(arguments[0])
    if (output.exists()) fail("$output exists: give a new file")
    val entries = arguments.getOrNull(1)?.toInt() ?: 5000
    val attachmentBytes = (arguments.getOrNull(2)?.toLong() ?: 40L) shl 20
    val kdf = Kdf.Argon2(
        Kdf.Argon2.Variant.ID,
        iterations = arguments.getOrNull(4)?.toLong() ?: 2,
        memoryBytes = (arguments.getOrNull(3)?.toLong() ?: 64L) shl 20,
        parallelism = 2,
    )

    val random = Random(SEED)
    val now = Instant.now()
    val root = NewDatabase.document("Large (fictitious)", output.name, now)
    val top = EntryLifecycle.uuidText(root.first("Root")!!.first("Group")!!)

    // Three levels: 12 top groups, each with up to 5 subgroups, some with a third level.
    val groups = mutableListOf(top)
    for (a in TOP_GROUPS) {
        val first = GroupLifecycle.create(listOf(NewGroup(Entry.newId(), a)), top, root, now)
        groups += EntryLifecycle.uuidText(first)
        repeat(random.nextInt(1, 6)) { b ->
            val second = GroupLifecycle.create(listOf(NewGroup(Entry.newId(), SUBGROUPS[(b + a.length) % SUBGROUPS.size])), EntryLifecycle.uuidText(first), root, now)
            groups += EntryLifecycle.uuidText(second)
            if (random.nextInt(4) == 0) {
                groups += EntryLifecycle.uuidText(GroupLifecycle.create(listOf(NewGroup(Entry.newId(), "Archive")), EntryLifecycle.uuidText(second), root, now))
            }
        }
    }

    val binaries = mutableListOf<Secret>()
    var attached = 0L
    val ids = mutableListOf<String>()
    val created = Duration.ofDays(365L * 6)
    for (n in 1..entries) {
        val site = SITES[random.nextInt(SITES.size)]
        val word = WORDS[random.nextInt(WORDS.size)]
        val title = if (random.nextInt(3) == 0) "$site ($word $n)" else "$site $n"
        val changes = EntryChanges(
            title = title,
            username = "${WORDS[random.nextInt(WORDS.size)]}.${WORDS[random.nextInt(WORDS.size)]}${random.nextInt(100)}@example.com",
            password = Secret.of(password(random)),
            url = if (random.nextInt(10) < 8) "https://${site.lowercase().replace(' ', '-')}.example.com/login" else null,
            notes = notes(random),
            tags = if (random.nextInt(10) < 3) List(random.nextInt(1, 4)) { TAGS[random.nextInt(TAGS.size)] }.distinct() else null,
            customFields = customFields(random),
            totp = if (random.nextInt(100) < 15) {
                TotpChange.Fields(listOf(CustomField("otp", FieldValue.Protected(Secret.of(otp(random, site))))))
            } else {
                null
            },
        )
        val id = Entry.newId()
        val when_ = now.minus(Duration.ofSeconds(random.nextLong(created.seconds)))
        val entry = EntryLifecycle.create(id, groups[random.nextInt(groups.size)], changes, root, when_)
        changes.wipe()
        ids += id

        // Attachments: about one entry in twenty-five until the size is reached, a few large ones.
        if (attached < attachmentBytes && random.nextInt(25) == 0) {
            repeat(random.nextInt(1, 4)) {
                val size = attachmentSize(random).coerceAtMost((attachmentBytes - attached).coerceAtLeast(1024))
                attach(entry, "${ATTACHMENT_NAMES[random.nextInt(ATTACHMENT_NAMES.size)]}-$n-$it", random.nextBytes(size.toInt()), binaries)
                attached += size
            }
        }
        // History: most entries changed a few times, some ten (KeePassXC keeps ten).
        repeat(history(random)) { history(entry, random) }
    }

    // The recycle bin: one entry in fifty.
    for (id in ids.shuffled(random).take(entries / 50)) EntryLifecycle.moveToRecycleBin(id, root, now)

    KdbxSchema.violations(root).minOrNull()?.let { fail("KeePass's schema: ${it.describe()}") }
    val template = NewDatabase.template(Cipher.AES256, kdf)
    val (file, writing) = measureTimedValue {
        KdbxWriter.write(root, binaries, List(binaries.size) { 0 }, template, DatabaseKey.of(Secret.of(PASSWORD)))
    }
    output.writeBytes(file)
    println("written in $writing: ${file.size shr 20} MiB, $entries entries, ${groups.size} groups, ${binaries.size} attachments (${attached shr 20} MiB)")

    val (read, reading) = measureTimedValue { KdbxReader.open(file, DatabaseKey.of(Secret.of(PASSWORD))) }
    println("read back in $reading (key derivation ${read.keyDerivation}): ${read.document.entries.size} entries")
    read.binaries.forEach { it.wipe() }
    if (KeePassXcCli.isInstalled) {
        val info = KeePassXcCli.run("db-info", output.path)
        if (info.code != 0) fail("KeePassXC cannot open the file: ${info.err}")
        println("KeePassXC: opens it")
    }
    println("RESULT: $output written (password $PASSWORD)")
}

private const val PASSWORD = "pw"
private const val SEED = 20261107

private val TOP_GROUPS = listOf(
    "Banking", "Shopping", "Social", "Work", "Email", "Games", "Travel", "Utilities", "Health", "Family", "Servers", "Old",
)
private val SUBGROUPS = listOf("Personal", "Shared", "Projects", "Accounts", "Subscriptions", "Legacy", "Testing")
private val SITES = listOf(
    "Northwind", "Contoso", "Fabrikam", "Lighthouse", "Bluebird", "Copperfield", "Driftwood", "Evergreen", "Foxglove",
    "Granite Bank", "Harbor Mail", "Ironwood", "Juniper Cloud", "Kestrel", "Larkspur", "Meridian", "Nimbus", "Oakridge",
    "Pinecone", "Quartz", "Riverside", "Saltmarsh", "Tidewater", "Umbra", "Velvet", "Willow", "Yarrow", "Zephyr",
)
private val WORDS = listOf(
    "amber", "basil", "cedar", "delta", "ember", "fjord", "gale", "harbor", "indigo", "jade", "kelp", "lumen", "maple",
    "nova", "orbit", "pebble", "quill", "raven", "sable", "thistle", "umber", "vale", "wren", "xenon", "yew", "zinc",
)
private val TAGS = listOf("work", "personal", "2fa", "old", "shared", "finance", "family", "travel", "important")
private val ATTACHMENT_NAMES = listOf("recovery-codes.txt", "contract.pdf", "scan.jpg", "id_ed25519", "invoice.pdf", "photo.png")

private fun password(random: Random): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*-_"
    return String(CharArray(random.nextInt(12, 33)) { alphabet[random.nextInt(alphabet.length)] })
}

private fun notes(random: Random): String? = when (random.nextInt(10)) {
    in 0..5 -> null
    in 6..8 -> List(random.nextInt(1, 6)) { "${WORDS[random.nextInt(WORDS.size)]}: ${WORDS[random.nextInt(WORDS.size)]} ${random.nextInt(10_000)}" }.joinToString("\n")
    else -> List(random.nextInt(20, 120)) { WORDS[random.nextInt(WORDS.size)] }.chunked(12).joinToString("\n") { it.joinToString(" ") }
}

private fun customFields(random: Random): List<CustomField>? {
    if (random.nextInt(5) != 0) return null
    return List(random.nextInt(1, 6)) { i ->
        val name = listOf("Security question", "PIN", "Account number", "Recovery email", "Customer ID", "Note")[(i + random.nextInt(6)) % 6] + " $i"
        val value = "${WORDS[random.nextInt(WORDS.size)]}-${random.nextInt(100_000)}"
        CustomField(name, if (random.nextInt(4) == 0) FieldValue.Protected(Secret.of(value)) else FieldValue.Plain(value))
    }
}

private fun otp(random: Random, site: String): String {
    val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val secret = String(CharArray(32) { base32[random.nextInt(32)] })
    return "otpauth://totp/${site.replace(" ", "%20")}:me?secret=$secret&period=30&digits=6&issuer=${site.replace(" ", "%20")}"
}

private fun history(random: Random): Int = when (random.nextInt(10)) {
    in 0..3 -> 0
    in 4..7 -> random.nextInt(1, 4)
    else -> random.nextInt(4, 11)
}

/** Log-spread from 4 KB to 4 MB, most of them small. */
private fun attachmentSize(random: Random): Long = (4096.0 * Math.pow(1024.0, random.nextDouble().let { it * it })).toLong()

private fun attach(entry: XElement, name: String, bytes: ByteArray, binaries: MutableList<Secret>) {
    val binary = XElement("Binary")
    binary.append(XElement("Key", name))
    binary.append(XElement("Value").apply { setAttribute("Ref", binaries.size.toString()) })
    binaries += Secret.adopt(bytes)
    entry.insertAfter(binary, entry.all("Binary").lastOrNull() ?: entry.all("String").last())
}

/** The entry as it is now goes into its history; then its password changes, as an edit in KeePassXC does. */
private fun history(entry: XElement, random: Random) {
    val snapshot = entry.copy()
    snapshot.first("History")?.let(snapshot.children::remove)
    entry.first("History")!!.append(snapshot)
    val password = entry.all("String").first { it.first("Key")?.text == "Password" }.first("Value")!!
    password.secret = Secret.of(password(random))
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
