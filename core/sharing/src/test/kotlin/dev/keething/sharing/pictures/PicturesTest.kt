package dev.keething.sharing.pictures

import dev.keething.sharing.crypto.hex
import dev.keething.sharing.message.ShareRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PicturesTest {
    @Test
    fun sixtyFourDistinctPicturesWithDistinctNames() {
        assertEquals(64, Pictures.ALL.size)
        assertEquals(64, Pictures.ALL.map { it.emoji }.toSet().size)
        assertEquals(64, Pictures.ALL.map { it.name.lowercase() }.toSet().size)
        // What another implementation must match (keething-spec plans/sharing-interop.md §3): the names in order, the
        // emoji to the byte, the seven written with U+FE0F.
        assertEquals(
            "Dog, Cat, Lion, Horse, Unicorn, Pig, Elephant, Rabbit, Panda, Rooster, Penguin, Turtle, Fish, Octopus, Butterfly, " +
                "Flower, Tree, Cactus, Mushroom, Globe, Moon, Cloud, Fire, Banana, Apple, Strawberry, Corn, Pizza, Cake, Heart, " +
                "Smiley, Robot, Hat, Glasses, Spanner, Santa, Thumbs Up, Umbrella, Hourglass, Clock, Gift, Light Bulb, Book, " +
                "Pencil, Paperclip, Scissors, Lock, Key, Hammer, Telephone, Flag, Train, Bicycle, Aeroplane, Rocket, Trophy, Ball, " +
                "Guitar, Trumpet, Bell, Anchor, Headphones, Folder, Pin",
            Pictures.ALL.joinToString(", ") { it.name },
        )
        val emoji = java.security.MessageDigest.getInstance("SHA-256").digest(Pictures.ALL.joinToString("") { it.emoji }.encodeToByteArray())
        assertEquals("092fe4b933b3d78a50b2042919920dd5d5720dc069f342cac0b5733cc3760096", emoji.joinToString("") { "%02x".format(it) })
        assertEquals(
            listOf("Cloud", "Heart", "Umbrella", "Pencil", "Scissors", "Telephone", "Aeroplane"),
            Pictures.ALL.filter { it.emoji.endsWith("\uFE0F") }.map { it.name },
        )
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

    private val ten = listOf(0, 1, 36, 41, 63, 21, 29, 10, 54, 47)

    @Test
    fun pastedPicturesAreFoundByEmojiOrNameInOrder() {
        val names = Pictures.named(ten)
        val asSent = "The ten pictures that open the Keething entries I sent you:\n" +
            names.take(5).joinToString(" · ") { "${it.emoji} ${it.name}" } + "\n" +
            names.drop(5).joinToString(" · ") { "${it.emoji} ${it.name}" } + "\nIn Keething, after reading the code: Paste the pictures."

        assertEquals(ten, Pictures.find(asSent, 10).pictures)
        assertEquals(ten, Pictures.find(names.joinToString(" ") { it.name.lowercase() }, 10).pictures)
        assertEquals(ten, Pictures.find(names.joinToString("") { it.emoji }, 10).pictures)
        assertEquals(ten, Pictures.find(names.mapIndexed { i, p -> "${i + 1}. ${p.name}" }.joinToString("\n"), 10).pictures)
        assertEquals(ten, Pictures.find(names.joinToString(", ") { "${it.name} ${it.emoji}" }, 10).pictures)
    }

    @Test
    fun emojiWithoutTheirVariationSelectorAndTwoWordNamesAreFound() {
        // Cloud, Heart, Umbrella and Thumbs Up, Light Bulb: a messenger may drop U+FE0F.
        val pictures = listOf(21, 29, 37, 36, 41, 21, 29, 37, 36, 41)
        val text = Pictures.named(pictures).joinToString(" ") { it.emoji.replace("\uFE0F", "") }

        assertEquals(pictures, Pictures.find(text, 10).pictures)
        assertEquals(pictures, Pictures.find("Cloud heart UMBRELLA thumbs up light bulb cloud heart umbrella thumbs up light bulb", 10).pictures)
    }

    @Test
    fun namesAmongOtherWordsDoNotCount() {
        val names = Pictures.named(ten).joinToString(" ") { it.name }

        // "key", "book", "lock" here are words; "Keyboard" holds a name inside it.
        assertEquals(ten, Pictures.find("Here is the key to the book lock, Keyboard: $names. Thanks!", 10).pictures)
        val short = Pictures.find(Pictures.named(ten.take(7)).joinToString(" ") { it.name }, 10)
        assertNull(short.pictures)
        assertEquals(7, short.longest)
        assertNull(Pictures.find("$names Dog", 10).pictures, "eleven in a row are not ten")
        assertEquals(0, Pictures.find("nothing here", 10).longest)
    }

    @Test
    fun randomPicturesAreAllOfThe64() {
        val drawn = Pictures.random(10_000, java.security.SecureRandom())

        assertEquals(64, drawn.toSet().size)
        assertTrue(drawn.all { it in 0..63 })
    }
}
