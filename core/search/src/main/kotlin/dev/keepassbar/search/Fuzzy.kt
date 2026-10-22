package dev.keepassbar.search

/**
 * A field prepared for fuzzy matching (Mac `FuzzyText`): folded characters, and which of them start a word or
 * a camelCase hump, since matching there is what people mean by initials ("gh" → GitHub).
 */
internal class FuzzyText(text: String) {
    val characters: CharArray
    val boundaries: BooleanArray

    init {
        val original = text.take(MAXIMUM_LENGTH)
        val folded = SearchNormalizer.normalize(original)
        characters = folded.toCharArray()
        // Boundaries come from the original text (camelCase needs the case). If folding changed the length
        // (ß → ss) the positions no longer line up, so the folded text is used instead.
        val basis = if (folded.length == original.length) original else folded
        boundaries = BooleanArray(basis.length) { i ->
            val current = basis[i]
            when {
                !current.isLetterOrDigit() -> false // separators are not word starts
                i == 0 -> true
                !basis[i - 1].isLetterOrDigit() -> true
                else -> basis[i - 1].isLowerCase() && current.isUpperCase()
            }
        }
    }

    private companion object {
        /** Long fields are cut: fuzzy matching is for names, not for essays. */
        const val MAXIMUM_LENGTH = 80
    }
}

/**
 * Subsequence matching with a score, in the spirit of fzf (Mac `FuzzyMatcher`): the letters of the term must
 * appear in order and close together; word starts and consecutive letters score higher, gaps cost.
 */
internal object FuzzyMatcher {
    /** Null when [term] (already folded) does not match [text]. */
    fun score(term: String, text: FuzzyText): Int? {
        val letters = term.toCharArray()
        val n = letters.size
        val m = text.characters.size
        if (n < 2 || n > m) return null

        var best: Int? = null
        var start = 0
        while (start <= m - n) {
            val first = indexOf(text.characters, letters[0], start)
            if (first < 0) break
            start = first + 1
            // Two alignments per start: the earliest letters, and the earliest letters that start a word (the
            // second is what makes initials like "ic" find "ILAY - Continentale").
            for (wordStartsOnly in listOf(false, true)) {
                val positions = align(letters, first, text, wordStartsOnly) ?: continue
                if (!isCloseEnough(positions, text)) continue
                val total = quality(positions, text)
                if (best == null || total > best) best = total
            }
        }
        return best
    }

    private fun indexOf(characters: CharArray, c: Char, from: Int): Int {
        for (i in from until characters.size) if (characters[i] == c) return i
        return -1
    }

    /** Greedy: each next letter at its earliest (optionally: word-start) position after the previous one. */
    private fun align(letters: CharArray, first: Int, text: FuzzyText, wordStartsOnly: Boolean): IntArray? {
        if (wordStartsOnly && !text.boundaries[first]) return null
        val positions = IntArray(letters.size)
        positions[0] = first
        var from = first + 1
        for (k in 1 until letters.size) {
            var found = -1
            for (i in from until text.characters.size) {
                if (text.characters[i] == letters[k] && (!wordStartsOnly || text.boundaries[i])) {
                    found = i
                    break
                }
            }
            if (found < 0) return null
            positions[k] = found
            from = found + 1
        }
        return positions
    }

    /**
     * Letters must sit close together; initials (every letter at a word start) may be spread wider. Two
     * letters in order say too little, so a two-letter term only counts as initials.
     */
    private fun isCloseEnough(positions: IntArray, text: FuzzyText): Boolean {
        val n = positions.size
        val span = positions[n - 1] - positions[0] + 1
        val initials = positions.all { text.boundaries[it] }
        if (n == 2 && !initials) return false
        return span <= if (initials) 6 * n + 8 else 2 * n + 3
    }

    private fun quality(positions: IntArray, text: FuzzyText): Int {
        var total = 0
        var previous = -1
        for ((index, position) in positions.withIndex()) {
            total += 16
            if (text.boundaries[position]) total += 10
            if (index == 0) {
                if (position == 0) total += 8
            } else if (position == previous + 1) {
                total += 12
            } else {
                total -= 4 + (position - previous - 1)
            }
            previous = position
        }
        return total
    }
}

/** Names offered for what is typed (Mac `FuzzyNames`): the tags the editor completes. */
public object FuzzyNames {
    /** Those starting with [typed] first, in their order, then those it matches fuzzily, best first; nothing typed, none. */
    public fun matching(typed: String, names: List<String>): List<String> {
        val term = SearchNormalizer.normalize(typed.trim())
        if (term.isEmpty()) return emptyList()
        val prefixed = mutableListOf<String>()
        val fuzzy = mutableListOf<Pair<String, Int>>()
        for (name in names) {
            if (SearchNormalizer.normalize(name).startsWith(term)) {
                prefixed += name
            } else {
                FuzzyMatcher.score(term, FuzzyText(name))?.let { fuzzy += name to it }
            }
        }
        // A stable sort: equal scores keep the names' order.
        return prefixed + fuzzy.sortedByDescending { it.second }.map { it.first }
    }
}
