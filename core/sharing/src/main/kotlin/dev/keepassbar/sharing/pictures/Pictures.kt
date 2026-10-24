package dev.keepassbar.sharing.pictures

import dev.keepassbar.kdbx.crypto.Argon2
import dev.keepassbar.sharing.message.ShareRequest
import java.security.SecureRandom

/** One of the 64 pictures, with the name to say it by. */
public data class Picture(val emoji: String, val name: String)

/**
 * The six pictures of a request (SPEC §10): both phones show them, and the two people compare them by a call or
 * in person, so a request swapped on the way (another key, another reason) is caught. They come from the whole
 * request, key, name and reason included.
 *
 * The request's digest is stretched with Argon2id (64 MiB, 3 passes) before the 36 bits are taken: whoever
 * swaps a request would have to try about 2^35 keys to find one with the same pictures, and each try costs that
 * much memory and time. About 0.3 s on a phone, once per request.
 *
 * An envelope's ten pictures ([ENVELOPE_COUNT]) are drawn at random instead: they are its key, not a check.
 */
public object Pictures {
    public const val COUNT: Int = 6

    /** An envelope's pictures: 60 bits, as each of the 64 is six. */
    public const val ENVELOPE_COUNT: Int = 10

    /** Matrix's emoji for checking a key (the Matrix specification's `sas-emoji.json`, Apache 2.0), in its order. */
    public val ALL: List<Picture> = listOf(
        Picture("🐶", "Dog"), Picture("🐱", "Cat"), Picture("🦁", "Lion"), Picture("🐎", "Horse"),
        Picture("🦄", "Unicorn"), Picture("🐷", "Pig"), Picture("🐘", "Elephant"), Picture("🐰", "Rabbit"),
        Picture("🐼", "Panda"), Picture("🐓", "Rooster"), Picture("🐧", "Penguin"), Picture("🐢", "Turtle"),
        Picture("🐟", "Fish"), Picture("🐙", "Octopus"), Picture("🦋", "Butterfly"), Picture("🌷", "Flower"),
        Picture("🌳", "Tree"), Picture("🌵", "Cactus"), Picture("🍄", "Mushroom"), Picture("🌏", "Globe"),
        Picture("🌙", "Moon"), Picture("☁️", "Cloud"), Picture("🔥", "Fire"), Picture("🍌", "Banana"),
        Picture("🍎", "Apple"), Picture("🍓", "Strawberry"), Picture("🌽", "Corn"), Picture("🍕", "Pizza"),
        Picture("🎂", "Cake"), Picture("❤️", "Heart"), Picture("😀", "Smiley"), Picture("🤖", "Robot"),
        Picture("🎩", "Hat"), Picture("👓", "Glasses"), Picture("🔧", "Spanner"), Picture("🎅", "Santa"),
        Picture("👍", "Thumbs Up"), Picture("☂️", "Umbrella"), Picture("⌛", "Hourglass"), Picture("⏰", "Clock"),
        Picture("🎁", "Gift"), Picture("💡", "Light Bulb"), Picture("📕", "Book"), Picture("✏️", "Pencil"),
        Picture("📎", "Paperclip"), Picture("✂️", "Scissors"), Picture("🔒", "Lock"), Picture("🔑", "Key"),
        Picture("🔨", "Hammer"), Picture("☎️", "Telephone"), Picture("🏁", "Flag"), Picture("🚂", "Train"),
        Picture("🚲", "Bicycle"), Picture("✈️", "Aeroplane"), Picture("🚀", "Rocket"), Picture("🏆", "Trophy"),
        Picture("⚽", "Ball"), Picture("🎸", "Guitar"), Picture("🎺", "Trumpet"), Picture("🔔", "Bell"),
        Picture("⚓", "Anchor"), Picture("🎧", "Headphones"), Picture("📁", "Folder"), Picture("📌", "Pin"),
    )

    internal val STRETCH = Argon2.Parameters(Argon2.Variant.ID, iterations = 3, memoryKiB = 64 * 1024, parallelism = 2)
    private val SALT = "Keething pictures".encodeToByteArray()

    /** Slow on purpose (above): call it off the main thread. */
    public fun of(request: ShareRequest): List<Int> = indices(Argon2.hash(request.digest, SALT, STRETCH))

    /** The first 36 bits of [stretched], six bits a picture, the highest bit first. */
    internal fun indices(stretched: ByteArray): List<Int> {
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (stretched[i].toLong() and 0xFF)
        return (0 until COUNT).map { ((bits ushr (40 - 6 * (it + 1))) and 0x3F).toInt() }
    }

    public fun named(indices: List<Int>): List<Picture> = indices.map(ALL::get)

    /** [count] pictures drawn at random, each of the 64 as likely. */
    public fun random(count: Int, random: SecureRandom): List<Int> = List(count) { random.nextInt(ALL.size) }

    /** What [find] found: the pictures, or none and how many the longest run had. */
    public class Found(public val pictures: List<Int>?, public val longest: Int)

    /**
     * The pictures in a text pasted from a message (SPEC §10): their emoji or their names in any case, in order; an
     * emoji with its own name beside it ("🐶 Dog") counts once. Only a run of exactly [count] is taken, with nothing
     * but spaces, punctuation and numbers between them, so names among the words around it ("the key to the flat")
     * do not count.
     */
    public fun find(text: String, count: Int): Found {
        val plain = text.replace(VARIATION, "")
        val runs = mutableListOf<List<Int>>()
        var run = mutableListOf<Int>()
        fun endRun() {
            if (run.isNotEmpty()) runs += run
            run = mutableListOf()
        }
        var at = 0
        while (at < plain.length) {
            val match = emojiAt(plain, at)?.let { (index, end) -> index to ownNameAfter(plain, end, index) }
                ?: nameAt(plain, at)?.let { (index, end) -> index to ownEmojiAfter(plain, end, index) }
            if (match != null) {
                run += match.first
                at = match.second
                continue
            }
            val point = plain.codePointAt(at)
            if (isSeparator(point)) {
                at += Character.charCount(point)
                continue
            }
            endRun()
            // A word goes whole: a name inside it ("Keyboard") is no picture.
            at += Character.charCount(point)
            if (Character.isLetter(point)) {
                while (at < plain.length && Character.isLetter(plain.codePointAt(at))) at += Character.charCount(plain.codePointAt(at))
            }
        }
        endRun()
        return Found(runs.firstOrNull { it.size == count }, runs.maxOfOrNull { it.size } ?: 0)
    }

    /** Emoji are compared without U+FE0F, which messengers add or drop ("☁️" and "☁"). */
    private const val VARIATION = "\uFE0F"
    private val PLAIN_EMOJI = ALL.map { it.emoji.replace(VARIATION, "") }

    private fun emojiAt(text: String, at: Int): Pair<Int, Int>? =
        PLAIN_EMOJI.indices.filter { text.startsWith(PLAIN_EMOJI[it], at) }.maxByOrNull { PLAIN_EMOJI[it].length }
            ?.let { it to at + PLAIN_EMOJI[it].length }

    /** A name as a word of its own: no letter right before or after it. */
    private fun nameAt(text: String, at: Int): Pair<Int, Int>? {
        if (at > 0 && Character.isLetter(text.codePointBefore(at))) return null
        return ALL.indices
            .filter { index ->
                val name = ALL[index].name
                text.regionMatches(at, name, 0, name.length, ignoreCase = true) &&
                    (at + name.length == text.length || !Character.isLetter(text.codePointAt(at + name.length)))
            }
            .maxByOrNull { ALL[it].name.length }
            ?.let { it to at + ALL[it].name.length }
    }

    private fun ownNameAfter(text: String, end: Int, index: Int): Int {
        val next = skipSpaces(text, end)
        return nameAt(text, next)?.takeIf { it.first == index }?.second ?: end
    }

    private fun ownEmojiAfter(text: String, end: Int, index: Int): Int {
        val next = skipSpaces(text, end)
        return emojiAt(text, next)?.takeIf { it.first == index }?.second ?: end
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var at = from
        while (at < text.length && Character.isWhitespace(text[at])) at++
        return at
    }

    private fun isSeparator(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isDigit(point) ||
        point == 0x200D || point in 0x1F3FB..0x1F3FF ||
        Character.getType(point).toByte() in PUNCTUATION

    private val PUNCTUATION = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    )
}
