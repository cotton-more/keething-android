package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.Fixtures
import dev.keething.kdbx.editing.EditingFixtures.key
import dev.keething.kdbx.editing.EditingFixtures.open
import dev.keething.kdbx.key.DatabaseKey
import dev.keething.kdbx.reader.KdbxException
import dev.keething.kdbx.reader.KdbxReader
import dev.keething.kdbx.reader.VariantDictionary
import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.xml.KdbxTime
import java.security.SecureRandom
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A new master key written into the file (keething-spec databases.change-master-key AC4). */
class MasterKeyEditingTest {
    private val now = Instant.ofEpochSecond(1_900_000_000)

    private fun parameters(file: ByteArray, key: DatabaseKey): VariantDictionary {
        val editable = KdbxReader.openForEditing(file, key)
        return VariantDictionary.parse(editable.template.kdf.identity).also { editable.wipe() }
    }

    private fun meta(file: ByteArray, key: DatabaseKey): XElement {
        val editable = KdbxReader.openForEditing(file, key)
        return editable.root.first("Meta")!!.copy().also { editable.wipe() }
    }

    // spec: databases.change-master-key/AC4
    @Test
    fun theNewKeyAloneOpensTheFileWithANewSaltAndTheCostsAsTheyWere() {
        for (name in listOf("kdbx4-aes-argon2d", "kdbx4-aes-aeskdf", "kdbx4-chacha20-argon2id")) {
            val original = Fixtures.bytes("$name.kdbx")
            val editable = open(name)
            val before = VariantDictionary.parse(editable.template.kdf.identity)
            val newKey = DatabaseKey.of(Secret.of("a new password"))

            editable.changeKey(newKey, now)
            val written = editable.write().file

            val document = KdbxReader.open(written, DatabaseKey.of(Secret.of("a new password"))).document
            assertEquals(KdbxReader.open(original, key).document.entries.size, document.entries.size, name)
            assertFailsWith<KdbxException.WrongPassword>(name) { KdbxReader.open(written, DatabaseKey.of(Secret.of("pw"))) }
            val after = parameters(written, newKey)
            assertEquals(before.keys(), after.keys(), "$name: the same parameters in the same order")
            for (parameter in before.keys() - "S") {
                assertEquals(before.uint64Bits(parameter) ?: before.uint32(parameter), after.uint64Bits(parameter) ?: after.uint32(parameter), "$name: $parameter")
                before.bytes(parameter)?.let { assertContentEquals(it, after.bytes(parameter), "$name: $parameter") }
            }
            assertEquals(32, after.bytes("S")!!.size)
            assertFalse(before.bytes("S")!!.contentEquals(after.bytes("S")), "$name: a new salt")
            assertEquals(KdbxReader.open(original, key).format, KdbxReader.open(written, newKey).format, "$name: the cipher and the derivation as they were")
        }
    }

    @Test
    fun aKeyFileAloneIsAKeyOfItsOwnNotAnEmptyPassword() {
        val keyFileKey = Secret.adopt(ByteArray(32) { it.toByte() })
        val editable = open("kdbx4-aes-argon2d")

        editable.changeKey(DatabaseKey.of(null, keyFileKey), now)
        val written = editable.write().file

        KdbxReader.open(written, DatabaseKey.of(null, keyFileKey))
        assertFailsWith<KdbxException.WrongPassword> { KdbxReader.open(written, DatabaseKey.of(Secret.of(""), keyFileKey)) }
    }

    // spec: databases.change-master-key/AC4
    @Test
    fun masterKeyChangedIsSetAndAForcedChangeIsDone() {
        val editable = open("kdbx4-aes-argon2d")
        val meta = editable.root.first("Meta")!!
        val force = meta.first("MasterKeyChangeForceOnce") ?: XElement("MasterKeyChangeForceOnce").also { meta.insertAfter(it, meta.first("MasterKeyChangeForce")) }
        force.text = "True"
        val newKey = DatabaseKey.of(Secret.of("new"))

        editable.changeKey(newKey, now, SecureRandom())
        val after = meta(editable.write().file, newKey)

        assertEquals(now, KdbxTime.parse(after.first("MasterKeyChanged")!!.text))
        assertEquals("False", after.first("MasterKeyChangeForceOnce")!!.text)
    }

    @Test
    fun theSaltIsReplacedWhereItStandsAndNothingElseOfTheDictionary() {
        val bytes = VariantDictionary.Writer().bytes("\$UUID", ByteArray(16) { 1 }).uint32("V", 0x13).bytes("S", ByteArray(32)).uint64("I", 2).finish()
        val replaced = VariantDictionary.parse(VariantDictionary.parse(bytes).replacingBytes("S", ByteArray(32) { 7 }))

        assertEquals(listOf("\$UUID", "V", "S", "I"), replaced.keys())
        assertContentEquals(ByteArray(32) { 7 }, replaced.bytes("S"))
        assertEquals(0x13L, replaced.uint32("V"))
        assertTrue(replaced.uint64("I") == 2L)
    }
}
