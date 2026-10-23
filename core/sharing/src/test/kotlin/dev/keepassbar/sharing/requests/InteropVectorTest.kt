package dev.keepassbar.sharing.requests

import dev.keepassbar.domain.secret.Secret
import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.CustomField
import dev.keepassbar.sharing.crypto.hex
import dev.keepassbar.sharing.message.ShareAnswer
import dev.keepassbar.sharing.message.ShareCode
import dev.keepassbar.sharing.message.ShareRequest
import dev.keepassbar.sharing.payload.AnswerPayload
import dev.keepassbar.sharing.payload.SharedEntry
import dev.keepassbar.sharing.pictures.Pictures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * One request and its answer, every byte fixed, for another implementation to check against (the Mac's handover,
 * `~/Projects/keepass-bar/HANDOVER-sharing.md`). The keys are RFC 9180 A.2's (skRm the asker's, skEm the
 * ephemeral one). [CRYPTOKIT_ANSWER] was sealed by Apple's CryptoKit (`HPKE.Sender`, Curve25519 / HKDF-SHA256 /
 * AES-GCM-256) for the same request and content: both implementations open each other's answers.
 */
class InteropVectorTest {
    private val askerKey = hex("497b4502664cfea5d5af0b39934dac72242a74f8480451e1aee7d6a53320333d")
    private val ephemeralKey = hex("179d4b53b6365c45b600c4163b61d95cbc2f4d9e36f1695558dce265ab8bab11")

    /** Alex asks for "Sparkasse login for the tax return", made 2026-10-23T16:00:00Z, valid 3 days. */
    private val request = ShareRequest.decode(
        hex(REQUEST),
    )

    private fun payload() = AnswerPayload(
        "Anna",
        listOf(
            SharedEntry(
                title = "Sparkasse Online-Banking", username = "4711 0815", password = Secret.of("geheim!42"),
                url = "https://sparkasse.de", notes = "PIN letter in the drawer",
                customFields = listOf(CustomField.protected("PIN", "2468"), CustomField.plain("Valid until", "2027-03-31")),
                fieldTypes = mapOf("Valid until" to "until"), tags = listOf("banking"),
                expires = Instant.parse("2027-12-31T00:00:00Z"),
                totp = TotpConfig(Secret.of("12345678901234567890")),
            ),
        ),
    )

    @Test
    fun theRequestItsIdItsPicturesAndItsText() {
        assertEquals("085a3dac8cbd1c1d177b737aa4fa7588", request.id)
        assertEquals(listOf(30, 29, 40, 43, 7, 13), Pictures.of(request))
        assertEquals(
            "keething:AQEAAAAAatuEgAAAAABq33kAQw9LmFlmUUWmsbonQCRIe9ZvA6LdV313U8aNfX0AwAwEQWxleCJTcGFya2Fzc2UgbG9naW4gZm9yIHRoZSB0YXggcmV0dXJu.",
            ShareCode.text(request.encoded()),
        )
    }

    @Test
    fun theContentAndTheAnswerAreTheseBytes() {
        assertContentEquals(hex(PAYLOAD), payload().encode().useBytes { it.copyOf() })
        assertContentEquals(hex(ANSWER), Answers.seal(request, payload(), ephemeralKey).encoded())
    }

    @Test
    fun bothAnswersOpenToTheSameContent() {
        for (answer in listOf(ANSWER, CRYPTOKIT_ANSWER)) {
            val opened = Answers.open(ShareAnswer.decode(hex(answer)), MyRequest(request, Secret.copyOf(askerKey), Pictures.of(request)))
            assertEquals("Anna", opened.from)
            val entry = opened.entries.single()
            assertEquals("Sparkasse Online-Banking", entry.title)
            assertEquals(Secret.of("geheim!42"), entry.password)
            assertEquals(CustomField.protected("PIN", "2468"), entry.customFields.first())
            assertEquals(TotpConfig(Secret.of("12345678901234567890")), entry.totp)
        }
    }

    private companion object {
        const val REQUEST =
            "0101000000006adb8480000000006adf7900430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00" +
            "c00c04416c657822537061726b61737365206c6f67696e20666f7220746865207461782072657475726e"

        /** The content, sealed: 256 bytes, padded with zeros to a multiple of 128. */
        const val PAYLOAD =
            "0104416e6e610118537061726b61737365204f6e6c696e652d42616e6b696e6709343731312030383135096765686569" +
            "6d2134321468747470733a2f2f737061726b617373652e64651850494e206c657474657220696e207468652064726177" +
            "6572010762616e6b696e6701000000006d16ce80020350494e0104323436380b56616c696420756e74696c000a323032" +
            "372d30332d3331010b56616c696420756e74696c05756e74696c01143132333435363738393031323334353637383930" +
            "061e003c0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000"

        const val ANSWER =
            "0102085a3dac8cbd1c1d177b737aa4fa75886c93e09869df3402d7bf231bf540fadd35cd56be14f97178f0954db94b7f" +
            "c2568c3dd5f663a7755cc0a22c16c8f912fd78ded444dfd9ad12a2f9ac313ff1dfb5f6e625ad6a8ed23a32843469de13" +
            "21767db80bdcb120402146668861c5a06adb2994e1bb305d83b1afb4843197133c719f334901451aa8bc02d0e9350c1e" +
            "417736febf0316e601f219d342fb6b639796ae0558690681c90e25535e9e7686f7a54d97b9712b3ec9e1c4f68f073e46" +
            "223ee543e94ede1f1cfcc385d414b998db4c9f95e41e97f076cd7cb9aece8b42423d6be2957c2b4779680070637922fc" +
            "554e538129d4685d76611a2b43b8fc32b188b6594a378322a676e5fee8a44fb3d9b3284b6b15ee904cdfa760e5c1b2b9" +
            "8eee2dc30f75ecf8d7c31203861ff31e0dd21f3fee8b4421be1fb9fdce6fd4c73b61"

        const val CRYPTOKIT_ANSWER =
            "0102085a3dac8cbd1c1d177b737aa4fa7588a9d266c0da645d5aa11d283a0e271133b3a01caaa984b8f6f7023927e4cf" +
            "c72c35512ce0bdef063b55dd5a3e30070482797aeede061d574c354426e52796e316ee4b182e72307304feb6ea331ad5" +
            "4104d36e0e24cfe81ee712fc7cb49fc97a9a08fd85bc480e11811ec16a82cf65b883dd82e66eb98146cfd0162c271033" +
            "bcf4f21b5e87c9531631ec6869bfed45ea26acc011e18394fbae13986431ce8b0a3575df704fad3ec714c9023531df26" +
            "57edd1abb307d1d63c0fa41cd39919edc1bc2d41c4b2fa6fc8bf3b908859a3b55b46c5e42db6a536ea3876497d369fac" +
            "36b55e96d785be58c7bea6c9005baa53f6f023b085b5668a9e857c49e1553b54e79beb8c90f6d1424f5739d32aa8f684" +
            "ad9a900ef7dcb93ee167186d0574fa50bf957f63e6b708d1f70372ca01b1e186f612"
    }
}
