package dev.keething.domain.text

/**
 * A text the user sees, made in the core (keething-spec plans T13b): which of the app's texts it is and what goes into
 * it, never the words themselves. The core is plain JVM and has no resources; `app/` turns a [UserText] into its string
 * resource, so a hint or a problem follows the phone's language. An argument is a number, words that are not the app's
 * own ([Plain]'s, as a String), or another [UserText].
 */
public sealed interface UserText {
    /** The text [key] names, its placeholders filled with [args] in order. */
    public data class Of(val key: TextKey, val args: List<Any> = emptyList()) : UserText

    /** The counted text [key] names (a plurals resource), chosen by [count]; [args] fill it (by default the count). */
    public data class Counted(val key: TextKey, val count: Int, val args: List<Any> = listOf(count)) : UserText

    /** Words that are not the app's own and stay as they are: a field's name, a network's name, a message from elsewhere. */
    public data class Plain(val text: String) : UserText

    /** "A", "A and B", "A, B and C" in the phone's language. */
    public data class Listed(val items: List<UserText>) : UserText

    /** [items] set one after another with [separator] between, a mark that is no word (" · ", "\n"). */
    public data class Joined(val items: List<UserText>, val separator: String) : UserText

    public companion object {
        public fun of(key: TextKey, vararg args: Any): UserText = Of(key, args.toList())

        public fun counted(key: TextKey, count: Int, vararg args: Any): UserText =
            Counted(key, count, if (args.isEmpty()) listOf(count) else args.toList())

        public fun plain(text: String): UserText = Plain(text)
    }
}

/**
 * One of the app's texts, named by the core: [resource] is the name of its string (or plurals) resource in `app/`.
 * Each core module keeps its texts in one enum (`CardText`, `VaultText` …), and `app/` maps every entry to its
 * resource; a test there checks that the names agree.
 */
public interface TextKey {
    public val resource: String
}
