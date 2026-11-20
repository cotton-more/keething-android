package dev.keething.card

import dev.keething.domain.vault.Attachment
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where an entry's SSH key is, and broken OpenSSH keys read as unreadable, never thrown out of the parser. */
class SshKeysTest {
    private fun guess(vararg names: String) = SshKeyLocation.of(null, names.map { Attachment(it, 1) })

    @Test
    fun withoutSettingsTheKeyIsGuessedFromTheAttachments() {
        assertEquals(SshKeyLocation.InAttachment("id_ed25519"), guess("id_ed25519", "KeeAgent.settings"))
        assertEquals(SshKeyLocation.InAttachment("work.key"), guess("KeeAgent.settings", "work.key", "work.key.pub"))
        assertEquals(SshKeyLocation.InAttachment("id_rsa"), guess("KeeAgent.settings", "notes.pdf", "id_rsa"))
        assertNull(guess("KeeAgent.settings", "a.key", "b.key"), "cannot tell")
        assertNull(guess("KeeAgent.settings"))
    }

    @Test
    fun settingsThatNameNoKeyFallBackToTheGuess() {
        val attachments = listOf(Attachment("KeeAgent.settings", 1), Attachment("work.key", 1), Attachment("work.key.pub", 1))
        for (bytes in listOf(settings("attachment", "").toByteArray(Charsets.UTF_16LE), settings("file", "").toByteArray(Charsets.UTF_16LE))) {
            assertEquals(SshKeyLocation.InAttachment("work.key"), SshKeyLocation.of(bytes, attachments))
        }
        assertNull(SshKeyLocation.of(settings("attachment", "").toByteArray(Charsets.UTF_16LE), listOf(Attachment("a.key", 1), Attachment("b.key", 1))))
    }

    @Test
    fun settingsAreReadWithEitherByteOrderMark() {
        val text = settings("attachment", "id_work")
        val attachments = listOf(Attachment("KeeAgent.settings", 1), Attachment("id_work", 1), Attachment("notes", 1))
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(SshKeyLocation.InAttachment("id_work"), SshKeyLocation.of(littleEndian, attachments))
        assertEquals(SshKeyLocation.InAttachment("id_work"), SshKeyLocation.of(bigEndian, attachments))
    }

    @Test
    fun brokenLengthsAreUnreadableKeys() {
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey(openssh(MAGIC + uint32(0x80000000.toInt()) + "none".encodeToByteArray())), "a cipher name's length of 0x80000000")
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey(openssh(MAGIC + uint32(0x7FFFFFF0) + ByteArray(64))), "a cipher name's length of 0x7FFFFFF0")
        val typeTooLong = uint32(0x7FFFFFFE) + ByteArray(8)
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey(openssh(MAGIC + string("none") + string("none") + string("") + uint32(1) + uint32(typeTooLong.size) + typeTooLong)), "a key type's length of 0x7FFFFFFE")
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey(openssh(MAGIC + uint32(16_976) + ByteArray(16_976) + ByteArray(64))), "a cipher name of 16 976 bytes")
        val body = body(MoreFacesTest.KEY)
        body[15] = 0x80.toByte()
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey(openssh(body)), "the test key with its cipher name's length's first byte 0x80")
    }

    @Test
    fun otherFormatsAndBrokenTextAreToldApart() {
        assertEquals(UNREADABLE, SshPublicKey.ofPrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----".encodeToByteArray()))
        assertEquals(
            UNREADABLE,
            SshPublicKey.ofPrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END".encodeToByteArray()),
            "cut off inside the public key",
        )
        assertEquals(SshPublicKey.Read.OtherFormat, SshPublicKey.ofPrivateKey(PKCS8.encodeToByteArray()))
        assertEquals(SshPublicKey.Read.OtherFormat, SshPublicKey.ofPrivateKey("just some notes".encodeToByteArray()))
    }

    private fun settings(type: String, name: String): String =
        """<?xml version="1.0" encoding="UTF-16"?><EntrySettings><AllowUseOfSshKey>true</AllowUseOfSshKey><Location>""" +
            "<SelectedType>$type</SelectedType><AttachmentName>$name</AttachmentName><FileName>$name</FileName></Location></EntrySettings>"

    private fun openssh(body: ByteArray): ByteArray =
        ("-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(body) + "\n-----END OPENSSH PRIVATE KEY-----\n").encodeToByteArray()

    private fun body(key: String): ByteArray = Base64.getMimeDecoder().decode(key.lines().filter { !it.startsWith("-----") }.joinToString(""))

    private fun uint32(value: Int): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun string(value: String): ByteArray = value.encodeToByteArray().let { uint32(it.size) + it }

    private companion object {
        val UNREADABLE = SshPublicKey.Read.Unreadable
        val MAGIC = "openssh-key-v1\u0000".encodeToByteArray()

        /** The Mac's PKCS#8 key (SSHKeyTests.swift): another format. */
        const val PKCS8 = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIMynd2X7ovIyjNiv5oRN8piz/LwYsolh7MDaPYfwbY6o\n-----END PRIVATE KEY-----\n"
    }
}
