package dev.keething.vault.probe

import dev.keething.domain.secret.Secret
import dev.keething.domain.storage.ConflictCopy
import dev.keething.domain.storage.DatabaseRead
import dev.keething.domain.storage.DatabaseStore
import dev.keething.domain.storage.FileVersion
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.schema.SchemaViolation
import dev.keething.kdbx.schema.XsdValidator
import dev.keething.vault.opening.OpenResult
import dev.keething.vault.opening.VaultOpener
import dev.keething.vault.saving.SaveResult
import dev.keething.vault.saving.VaultSaver
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess
import kotlin.time.TimeSource
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The phone's writing tried on a COPY of a real database (ROADMAP step 7; the Mac's `KDBXProbe --roundtrip`
 * and `--lifecycle`). Prints counts, times and where things differ, never a value, a title or a name.
 *
 * Usage: `tools/kdbx-probe.sh <copy.kdbx>` (asks for the password, passes it on standard input).
 * 1. Round trip: read and write back unchanged; KeePassXC's XML exports of the copy and the written file must
 *    be the same, the attachments byte for byte.
 * 2. Lifecycle on the copy, each through the save path with its checks: a new entry, an edit of it, an edit of
 *    an existing entry (its notes), into the recycle bin and back, for both.
 * 3. The copy read again from scratch: every other entry and group as before; KeePassXC opens it.
 * Throughout, KeePass's XML schema (SPEC §3.3): the copy, the file written back and every saved file checked against
 * `KDBX_XML.xsd` (fixtures/) and by the app's own check, which none may add a violation to.
 */
fun main(arguments: Array<String>) {
    val copy = File(arguments.singleOrNull() ?: fail("usage: KdbxProbe <copy.kdbx>"))
    val password = readPasswordLine()
    val key = DatabaseKey.fromPassword(password)
    val bytes = copy.readBytes()
    val format = KdbxReader.format(bytes)
    println("File:   ${bytes.size / 1024} KiB, $format")

    // Opening, as an unlock does.
    val store = FileStore(copy)
    var started = TimeSource.Monotonic.markNow()
    val before = when (val opened = VaultOpener().open(store, key)) {
        is OpenResult.Opened -> opened.opened.also {
            println("Open:   ${started.elapsedNow()} (key derivation ${it.keyDerivation})")
        }.vault
        is OpenResult.Failed -> fail("Cannot open: ${opened.failure}")
    }
    val attachments = (before.entries + before.recycleBin).sumOf { it.attachments.size }
    println("Vault:  ${before.entries.size} entries, ${before.groups.size} groups, recycle bin ${if (before.recycleBinEnabled) "on" else "OFF"} with ${before.recycleBin.size}, $attachments attachments")

    schemaBefore = checkSchema(bytes, key, "the database")

    roundTrip(copy, bytes, key, password)
    lifecycle(store, key, before)

    // From scratch: a new key, as the next unlock would read it.
    val fresh = DatabaseKey.fromPassword(password.copy())
    val after = (VaultOpener().open(store, fresh) as? OpenResult.Opened)?.opened?.vault ?: fail("The copy no longer opens")
    compare(before, after)
    val cli = KeePassXc.export(copy, password) ?: fail("RESULT: KeePassXC cannot open the copy")
    println("KeePassXC: opens the copy, ${cli.entryCount()} entries outside the history")
    println("Memory: ${peakHeapMiB()} MiB peak Java heap")
    println("RESULT: every step saved and checked; the copy holds the same entries and groups as before, plus the probe's entry in the bin")
}

private val touched = mutableSetOf<String>()
private var probeId = ""

private fun roundTrip(copy: File, bytes: ByteArray, key: DatabaseKey, password: Secret) {
    val started = TimeSource.Monotonic.markNow()
    val editable = KdbxReader.openForEditing(bytes, key)
    val written = editable.write(key)
    val reread = KdbxReader.open(written.file, key)
    val sameBinaries = reread.binaries.size == editable.binaries.size &&
        reread.binaries.zip(editable.binaries).all { (a, b) -> a.hasSameBytes(b) }
    println("Write:  ${started.elapsedNow()} to read and write back unchanged (${written.file.size / 1024} KiB), attachments ${editable.binaries.size}, ${if (sameBinaries) "identical" else "DIFFERENT"}")
    editable.wipe()
    reread.binaries.forEach(Secret::wipe)
    written.binaries.forEach(Secret::wipe)
    if (!sameBinaries) fail("RESULT: attachments differ")
    if (!keepsSchema(written.file, key, "the file written back")) fail("RESULT: the file written back breaks KeePass's schema")
    val out = File(copy.parentFile, "roundtrip.kdbx")
    out.writeBytes(written.file)
    try {
        val original = KeePassXc.export(copy, password) ?: fail("KeePassXC cannot export the copy")
        val again = KeePassXc.export(copy, password) ?: fail("KeePassXC cannot export the copy")
        val roundTripped = KeePassXc.export(out, password) ?: fail("RESULT: KeePassXC cannot open the written file")
        val noise = differences(original, again).toSet()
        if (noise.isNotEmpty()) println("KeePassXC's own export differs between two runs in ${noise.size} places (left out)")
        val diff = differences(original, roundTripped).filterNot { it in noise }
        if (diff.isNotEmpty()) {
            println("Original vs written:")
            diff.take(12).forEach { println("  $it") }
            fail("RESULT: exports differ")
        }
        println("Round trip: KeePassXC's exports of the copy and the written file are identical")
    } finally {
        out.delete()
    }
}

private fun lifecycle(store: FileStore, key: DatabaseKey, start: Vault) {
    val saver = VaultSaver(store) { _, _, _ -> }
    var vault = start
    var total = 0.0
    var saves = 0
    fun step(name: String, edit: (Vault) -> VaultEdit) {
        val change = edit(vault)
        val started = TimeSource.Monotonic.markNow()
        when (val result = saver.save(change, key, Instant.now())) {
            is SaveResult.Saved -> {
                // The vault opened first is the comparison's "before": kept.
                if (vault !== start) vault.wipe()
                vault = result.opened.vault
                val elapsed = started.elapsedNow()
                total += elapsed.inWholeMilliseconds / 1000.0
                saves++
                println("  $name: saved and checked in $elapsed")
                if (!keepsSchema(store.read().let { (it as DatabaseRead.Found).bytes }, key, name)) fail("RESULT: $name breaks KeePass's schema")
            }
            is SaveResult.Failed -> fail("  $name: NOT saved: ${result.failure}")
        }
        when (change) {
            is VaultEdit.Update -> change.changes.wipe()
            is VaultEdit.Create -> change.fields.wipe()
            else -> {}
        }
    }
    fun entry(id: String) = (vault.entries + vault.recycleBin).first { it.id == id }

    val group = start.groups.firstOrNull { !it.isRoot } ?: start.groups.first()
    // The richest entry: the most custom fields, then attachments; its notes get a line.
    val existing = start.entries.maxBy { it.customFields.size * 100 + it.attachments.size }
    println("Lifecycle (the existing entry has ${existing.customFields.size} custom fields, ${existing.attachments.size} attachments):")
    probeId = Entry.newId()
    touched += listOf(probeId, existing.id)
    step("new entry") {
        VaultEdit.Create(
            probeId, group.id,
            EntryChanges(
                title = "Keething probe", username = "probe", password = Secret.of("probe-1"), url = "https://probe.invalid",
                notes = "probe", customFields = listOf(CustomField.plain("Probe field", "x"), CustomField.protected("Probe secret", "y")),
                tags = listOf("probe"),
            ),
        )
    }
    step("edit it") {
        VaultEdit.Update(
            probeId, entry(probeId).lastModified,
            EntryChanges(password = Secret.of("probe-2"), customFields = listOf(CustomField.protected("Probe secret 2", "z"))),
        )
    }
    step("edit an existing entry") { VaultEdit.Update(existing.id, entry(existing.id).lastModified, EntryChanges(notes = existing.notes + "\n(probe)")) }
    step("existing into the bin") { VaultEdit.MoveToRecycleBin(existing.id, entry(existing.id).lastModified) }
    step("existing restored") { VaultEdit.Restore(existing.id, entry(existing.id).lastModified) }
    step("new into the bin") { VaultEdit.MoveToRecycleBin(probeId, entry(probeId).lastModified) }
    step("new restored") { VaultEdit.Restore(probeId, entry(probeId).lastModified) }
    step("new into the bin again") { VaultEdit.MoveToRecycleBin(probeId, entry(probeId).lastModified) }
    println("Saving: %.2f s on average over %d saves (read, apply, write, read back, check, replace)".format(total / saves, saves))
    vault.wipe()
}

private var schemaBefore: SchemaCheckResult? = null

/** What KeePass's schema says of a file: valid by `KDBX_XML.xsd`, and the app's own violations. */
private class SchemaCheckResult(val valid: Boolean, val own: Set<SchemaViolation>)

/**
 * The file checked against `KDBX_XML.xsd` and by the app's own check, with how long that takes. The validator's
 * messages quote values: only their line and error code are printed.
 */
private fun checkSchema(bytes: ByteArray, key: DatabaseKey, label: String): SchemaCheckResult {
    val editable = KdbxReader.openForEditing(bytes, key)
    val xml = editable.xmlWithoutSecrets()
    try {
        val errors = XsdValidator.errors(xml)
        val started = TimeSource.Monotonic.markNow()
        val own = editable.schemaViolations()
        val took = started.elapsedNow()
        println(
            "Schema: $label: KDBX_XML.xsd ${if (errors.isEmpty()) "valid" else "${errors.size} errors"}, " +
                "the app's check ${if (own.isEmpty()) "valid" else "${own.size} violations"} ($took)",
        )
        // "line 12: cvc-datatype-valid.1.2.1: '…' is not …": the line and the code, never the value.
        errors.take(20).forEach { println("  xsd: ${it.split(": ").take(2).joinToString(": ")}") }
        own.sorted().take(20).forEach { println("  own: $it") }
        if (errors.isEmpty() != own.isEmpty()) println("  the two DISAGREE")
        return SchemaCheckResult(errors.isEmpty(), own)
    } finally {
        xml.fill(0)
        editable.wipe()
    }
}

/** A written file adds nothing to what the database had, as a save requires. */
private fun keepsSchema(bytes: ByteArray, key: DatabaseKey, label: String): Boolean {
    val before = schemaBefore ?: return false
    val now = checkSchema(bytes, key, label)
    val added = (now.own - before.own).sorted()
    added.forEach { println("  NEW: $it") }
    return added.isEmpty() && (now.valid || !before.valid)
}

/** The copy read again against the original: only the probe's entry and the existing entry's notes changed. */
private fun compare(before: Vault, after: Vault) {
    val untouchedBefore = before.entries.filter { it.id !in touched }
    val untouchedAfter = after.entries.filter { it.id !in touched }
    if (untouchedBefore != untouchedAfter) fail("RESULT: ${untouchedBefore.size} entries before, ${untouchedAfter.size} after; ${untouchedBefore.zip(untouchedAfter).count { (a, b) -> a != b }} differ")
    if (before.recycleBin.filter { it.id !in touched } != after.recycleBin.filter { it.id !in touched }) fail("RESULT: the recycle bin's other entries differ")
    val existingBefore = before.entries.first { it.id in touched }
    val existingAfter = after.entries.firstOrNull { it.id == existingBefore.id } ?: fail("RESULT: the existing entry is not back in its place")
    val asBefore = existingAfter.copy(notes = existingBefore.notes, lastModified = existingBefore.lastModified)
    if (asBefore != existingBefore || existingAfter.notes != existingBefore.notes + "\n(probe)") fail("RESULT: the existing entry changed more than its notes")
    if (existingAfter.groupId != existingBefore.groupId) fail("RESULT: the existing entry is in another group")
    if (after.recycleBin.none { it.id == probeId }) fail("RESULT: the probe's entry is not in the bin")
    val newGroups = after.groups.filter { g -> before.groups.none { it.id == g.id } }
    if (before.groups.any { it !in after.groups } || newGroups.isNotEmpty()) fail("RESULT: the groups changed (${newGroups.size} new)")
    println("Compared: ${untouchedAfter.size} other entries, ${after.groups.size} groups and the bin as before")
}

/** The copy, replaced as the phone replaces its file: a temporary file in the same folder, then a rename. */
internal class FileStore(private val file: File) : DatabaseStore {
    override val fileName: String get() = file.name

    override fun read(): DatabaseRead = DatabaseRead.Found(file.readBytes(), version()!!)

    override fun version(): FileVersion = FileVersion(file.length(), Instant.ofEpochMilli(file.lastModified()), null)

    override fun replace(bytes: ByteArray) {
        val temporary = File(file.parentFile, ".probe.tmp")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun conflictCopies(): List<ConflictCopy> = emptyList()

    override fun watch(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
}

private fun peakHeapMiB(): Long =
    ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used } / 1_048_576
