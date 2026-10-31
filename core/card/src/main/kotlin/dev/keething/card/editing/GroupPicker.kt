package dev.keething.card.editing

import dev.keething.domain.text.TextFolding
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultGroup
import dev.keething.card.editing.NameFolding.fold
import java.text.Collator

/**
 * Where an entry goes, as the group picker chose it (keething-spec groups.new-groups, plans/android-groups.md G2): an
 * existing group by its id, and the names of new groups made in it with the save, each inside the one before.
 */
public data class GroupChoice(val groupId: String, val newNames: List<String> = emptyList())

/**
 * The groups of a database outside the recycle bin as the group picker and the editor's group row show them: each
 * in its own parent (found by the file's order, so two groups of one name in one parent are each themselves), the
 * tree in Groups & tags' order, and how many entries each holds.
 */
public class GroupTree(groups: List<VaultGroup>, entries: List<Entry> = emptyList(), collator: Collator = Collator.getInstance()) {
    /** A group in tree order: [depth] 0 for the root group; [total] its entries and those below it. */
    public data class Node(
        val group: VaultGroup,
        val depth: Int,
        val total: Int,
        /** Its parent has another group of its name, case and accents aside (made by another app). */
        val hasNamesake: Boolean,
        /** How many groups are right inside it. */
        val subgroups: Int,
    )

    /** One level of a path as the editor's group row shows it; [isNew]: made with the save. */
    public data class Level(val name: String, val isNew: Boolean)

    public val root: VaultGroup? = groups.firstOrNull { it.isRoot } ?: groups.firstOrNull()

    private val byId = groups.associateBy { it.id }

    /** Each group's parent by the file's order: a group's groups follow it, deeper than it. */
    private val parents: Map<String, String?> = HashMap<String, String?>().also { parents ->
        val open = ArrayList<VaultGroup>()
        for (group in groups) {
            while (open.isNotEmpty() && open.last().path.size >= group.path.size) open.removeAt(open.lastIndex)
            parents[group.id] = open.lastOrNull()?.id
            open += group
        }
    }

    private val inside: Map<String?, List<VaultGroup>> = groups.groupBy { parents[it.id] }

    public val nodes: List<Node> = run {
        val sorter = (collator.clone() as Collator).apply { strength = Collator.SECONDARY }
        val byName = Comparator<VaultGroup> { a, b -> sorter.compare(a.name, b.name).takeIf { it != 0 } ?: a.name.compareTo(b.name) }
        val here = entries.groupingBy { it.groupId }.eachCount()
        val totals = HashMap<String, Int>()
        // Each name folded once: a parent of many groups would fold them all again for every one.
        val folded = groups.associate { it.id to fold(it.name) }
        val namesakes = groups.groupingBy { parents[it.id] to folded[it.id] }.eachCount()
        fun total(group: VaultGroup): Int = totals.getOrPut(group.id) { (here[group.id] ?: 0) + children(group.id).sumOf(::total) }
        val result = ArrayList<Node>()
        fun walk(group: VaultGroup, depth: Int) {
            val namesake = (namesakes[parents[group.id] to folded[group.id]] ?: 0) > 1 && depth > 0
            result += Node(group, depth, total(group), namesake, children(group.id).size)
            children(group.id).sortedWith(byName).forEach { walk(it, depth + 1) }
        }
        root?.let { walk(it, 0) }
        result
    }

    public fun group(id: String): VaultGroup? = byId[id]

    public fun node(id: String): Node? = nodes.firstOrNull { it.group.id == id }

    public fun parentOf(id: String): VaultGroup? = parents[id]?.let(byId::get)

    public fun children(id: String): List<VaultGroup> = inside[id].orEmpty()

    /** Whether [id] is somewhere below [ancestorId]. */
    public fun isBelow(id: String, ancestorId: String): Boolean {
        var parent = parents[id]
        while (parent != null) {
            if (parent == ancestorId) return true
            parent = parents[parent]
        }
        return false
    }

    public fun resolve(names: List<String>, from: VaultGroup? = root): GroupChoice? {
        var existing = from ?: return null
        val folded = names.map(::fold)
        var depth = 0
        while (depth < names.size) {
            val (child, parts) = children(existing.id).asSequence()
                .map { it to it.name.split('/').map(::fold) }
                .firstOrNull { (_, parts) -> depth + parts.size <= folded.size && folded.subList(depth, depth + parts.size) == parts }
                ?: break
            existing = child
            depth += parts.size
        }
        return GroupChoice(existing.id, names.drop(depth))
    }

    public fun levels(choice: GroupChoice): List<Level>? {
        val group = group(choice.groupId) ?: return null
        return group.path.map { Level(it, isNew = false) } + choice.newNames.map { Level(it, isNew = true) }
    }

    public companion object {
        public fun of(vault: Vault, collator: Collator = Collator.getInstance()): GroupTree = GroupTree(vault.groups, vault.entries, collator)
    }
}

/**
 * The group picker's rows for what is typed (keething-spec groups.new-groups AC1, AC2 on the phone; plans/android-groups.md
 * G2, G8, and the owner's change of 2026-10-31: one level at a time): nothing typed, the top level, the root group
 * first, then its groups; a row's › goes into a group, listed the same way (the group first, then its own groups), and
 * [up] goes back. Something typed, the groups below the level whose name holds it, at any depth, each by its whole
 * path, those directly inside first; a path typed with `/` searches below the group its levels before the last name.
 */
public class GroupPicker(private val tree: GroupTree) {
    /**
     * What is in the search field, and the group a row's › went into ([entered], with the text it put there): while
     * the text still starts with it, the levels after it are below that group, even when another of its name comes
     * first.
     */
    public data class Query(val text: String = "", val entered: Entered? = null)

    public data class Entered(val groupId: String, val text: String)

    /** A row: [ancestors] the names above it (for a search), [match] where the typed part is in its name. */
    public data class Row(val node: GroupTree.Node, val ancestors: List<String>, val match: IntRange?)

    public data class View(
        /** Nothing typed at the last level: [level] listed, it first, then the groups right inside it. */
        val isLevel: Boolean,
        val rows: List<Row>,
        /**
         * The group whose groups are listed or searched: the one the levels before the last name, the root group with
         * none; null when one of them is not a group yet.
         */
        val level: VaultGroup?,
        /** The levels before the last that are not groups yet. */
        val missing: List<String>,
        /** The last level as typed, trimmed. */
        val last: String,
        /** "+ New group …": the levels typed that are not groups yet, made in an existing one; null when all are. */
        val offer: GroupChoice?,
        /** What is typed, taken as it is (the keyboard's Done): the group it names, or new groups; null with nothing typed. */
        val typed: GroupChoice?,
    )

    public fun view(query: Query): View {
        val text = query.text
        // Below the group a › went into, or the deepest of its parents, while the text still starts with its path (spaces
        // around `/` aside), so a namesake met on the way back is still the one gone into; else from the top.
        val typedPath = tight(text)
        val entered = query.entered?.let { tree.group(it.groupId) }
            ?.let { group -> generateSequence(group) { tree.parentOf(it.id)?.takeIf { parent -> !parent.isRoot } } }
            ?.firstOrNull { typedPath.startsWith(tight(pathText(it))) }
        val base = entered ?: tree.root ?: return View(isLevel = false, emptyList(), null, emptyList(), text.trim(), null, null)
        val parts = typedPath.removePrefix(entered?.let { tight(pathText(it)) }.orEmpty()).split('/')
        val last = parts.last().trim()
        val before = parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
        val parent = tree.resolve(before, base)!!
        val typed = tree.resolve(before + listOfNotNull(last.takeIf { it.isNotEmpty() }), base)!!
        val level = tree.group(parent.groupId)?.takeIf { parent.newNames.isEmpty() }
        val found = if (level == null) {
            emptyList()
        } else if (last.isEmpty()) {
            val inside = tree.nodes.filter { tree.parentOf(it.group.id)?.id == level.id }
            (listOfNotNull(tree.node(level.id)) + inside).map { Row(it, emptyList(), null) }
        } else {
            val matching = tree.nodes.filter { tree.isBelow(it.group.id, parent.groupId) }
                .mapNotNull { node -> NameFolding.match(node.group.name, last)?.let { Row(node, node.group.path.dropLast(1), it) } }
            val (direct, deeper) = matching.partition { tree.parentOf(it.node.group.id)?.id == parent.groupId }
            direct + deeper
        }
        // The group the whole path names is one of them, also when its name spans levels ("A/B").
        val named = tree.nodes.firstOrNull { it.group.id == typed.groupId }
            ?.takeIf { last.isNotEmpty() && typed.newNames.isEmpty() && found.none { row -> row.node.group.id == typed.groupId } }
            ?.let { Row(it, it.group.path.dropLast(1), null) }
        val rows = listOfNotNull(named) + found
        val isLevel = level != null && last.isEmpty()
        return View(isLevel, rows, level, parent.newNames, last, typed.takeIf { it.newNames.isNotEmpty() }, typed.takeIf { !text.isBlank() })
    }

    public fun enter(groupId: String): Query {
        val group = tree.group(groupId) ?: return Query()
        val text = pathText(group)
        return Query(text, Entered(groupId, text))
    }

    /** A group's path as › puts it in the field: `Travel / Rail / `. */
    private fun pathText(group: VaultGroup): String = group.path.joinToString("") { it + SEPARATOR }

    /** [text] without the spaces around its `/`: what is typed is compared so. */
    private fun tight(text: String): String = text.split('/').joinToString("/") { it.trim() }

    /** Back from [view]'s level to the one above it (the top level from a top-level group or a path not made yet). */
    public fun up(view: View): Query {
        val parent = view.level?.let { tree.parentOf(it.id) }
        return if (parent == null || parent.isRoot) Query() else enter(parent.id)
    }

    public companion object {
        /** How a path's levels are written in the field. */
        public const val SEPARATOR: String = " / "
    }
}

/** Names as the picker matches them. */
internal object NameFolding {
    /** A name as levels are matched: trimmed, case and accents aside, each character on its own (see [match]). */
    fun fold(name: String): String = folded(name.trim()).first

    /** [text] folded one character at a time, and where in [text] each folded character came from. */
    fun folded(text: String): Pair<String, List<IntRange>> {
        val folded = StringBuilder()
        val from = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val next = text.offsetByCodePoints(i, 1)
            for (c in TextFolding.fold(text.substring(i, next))) {
                folded.append(c)
                from += i until next
            }
            i = next
        }
        return folded.toString() to from
    }

    /** Where [typed] is in [name], case and accents aside, as a range of [name]; null when it is not. */
    fun match(name: String, typed: String): IntRange? {
        val wanted = folded(typed).first.takeIf { it.isNotEmpty() } ?: return null
        val (folded, from) = folded(name)
        val at = folded.indexOf(wanted).takeIf { it >= 0 } ?: return null
        return from[at].first..from[at + wanted.length - 1].last
    }
}
