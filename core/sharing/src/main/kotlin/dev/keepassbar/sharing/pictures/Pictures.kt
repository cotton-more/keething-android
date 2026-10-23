package dev.keepassbar.sharing.pictures

import dev.keepassbar.kdbx.crypto.Argon2
import dev.keepassbar.sharing.message.ShareRequest

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
 */
public object Pictures {
    public const val COUNT: Int = 6

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
}
