package dev.keepassbar.kdbx.editing

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.vault.EntryChanges
import dev.keepassbar.domain.vault.FieldValue
import dev.keepassbar.kdbx.Fixtures
import dev.keepassbar.kdbx.key.DatabaseKey
import dev.keepassbar.kdbx.reader.KdbxReader
import dev.keepassbar.kdbx.writer.KdbxWriter
import dev.keepassbar.kdbx.xml.KdbxDocument
import dev.keepassbar.kdbx.xml.KdbxEntry
import dev.keepassbar.kdbx.xml.KdbxField

/** Opening the fixtures for editing, writing them and reading them back (password `pw`). */
internal object EditingFixtures {
    val key: DatabaseKey = DatabaseKey.fromPassword(Secret.of(Fixtures.PASSWORD))

    fun open(name: String): EditableDatabase = KdbxReader.openForEditing(Fixtures.bytes("$name.kdbx"), key)

    fun document(name: String): KdbxDocument = KdbxReader.open(Fixtures.bytes("$name.kdbx"), key).document

    /** Written as the editable stands, without dropping attachments nothing refers to. */
    fun write(editable: EditableDatabase): ByteArray =
        KdbxWriter.write(editable.root, editable.binaries, editable.binaryFlags, editable.template, key)

    fun reread(editable: EditableDatabase): KdbxDocument = KdbxReader.open(write(editable), key).document
}

internal fun KdbxDocument.entry(title: String): KdbxEntry = entries.single { it.field("Title") == FieldValue.Plain(title) }

internal fun KdbxEntry.field(key: String): FieldValue? = fields.lastOrNull { it.key == key }?.value

/** The fields that are not the standard ones or the one-time code's. */
internal fun KdbxEntry.custom(): List<KdbxField> = fields.filter { it.key !in EntryChanges.RESERVED_FIELD_NAMES }

internal fun protected(text: String): FieldValue = FieldValue.Protected(Secret.of(text))

internal fun plain(text: String): FieldValue = FieldValue.Plain(text)
