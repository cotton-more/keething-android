package dev.keepassbar.sharing.pictures

import dev.keepassbar.sharing.crypto.hex
import dev.keepassbar.sharing.message.ShareRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class PicturesTest {
    @Test
    fun sixtyFourDistinctPicturesWithDistinctNames() {
        assertEquals(64, Pictures.ALL.size)
        assertEquals(64, Pictures.ALL.map { it.emoji }.toSet().size)
        assertEquals(64, Pictures.ALL.map { it.name.lowercase() }.toSet().size)
    }

    @Test
    fun takesSixBitsAPictureHighestFirst() {
        assertEquals(listOf(0, 63, 1, 32, 21, 22), Pictures.indices(hex("03f0 6055 6aff".replace(" ", "")) + ByteArray(26)))
    }

    /**
     * The request of `MessagesTest`; the expected stretch from OpenSSL 4 (`openssl kdf -keylen 32 -kdfopt
     * hexpass:<SHA-256 of the request> -kdfopt salt:"Keething pictures" -kdfopt iter:3 -kdfopt memcost:65536
     * -kdfopt lanes:2 ARGON2ID` = 79da2b1cdd…).
     */
    @Test
    fun theStretchIsArgon2idAsOpenSslComputesIt() {
        val request = ShareRequest.decode(
            hex(
                "0101000000006adb8480000000006adf7900430f4b9859665145a6b1ba274024487bd66f03a2dd577d7753c68d7d7d00c00c" +
                    "04416c657822537061726b61737365206c6f67696e20666f7220746865207461782072657475726e",
            ),
        )

        val pictures = Pictures.of(request)

        assertEquals(listOf(30, 29, 40, 43, 7, 13), pictures)
        assertEquals(listOf("Smiley", "Heart", "Gift", "Pencil", "Rabbit", "Octopus"), Pictures.named(pictures).map { it.name })
    }
}
