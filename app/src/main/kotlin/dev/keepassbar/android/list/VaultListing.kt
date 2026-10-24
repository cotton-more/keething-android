package dev.keepassbar.android.list

import dev.keepassbar.card.Deadline
import dev.keepassbar.card.DeadlineList
import dev.keepassbar.card.EntryAvatar
import dev.keepassbar.card.EntryFace
import dev.keepassbar.card.ExpiryHint
import dev.keepassbar.domain.totp.TotpConfig
import dev.keepassbar.domain.vault.Entry
import dev.keepassbar.domain.vault.Vault
import dev.keepassbar.search.EntrySearch
import dev.keepassbar.search.SearchFacets
import java.text.Collator
import java.time.Instant

/** One row of the list (SPEC §6.2): title, username and site, the live code when there is one. */
internal data class EntryRow(
    val id: String,
    val title: String,
    /** `username · host`, either part left out when empty. */
    val subtitle: String,
    /** The group's path, `Finanzen › Banken` (inside the recycle bin for an entry in it); empty for the root group. */
    val group: String,
    val avatar: EntryAvatar,
    val customIconId: String?,
    val hasAttachments: Boolean,
    /** What a swipe can copy (SPEC §6.2). */
    val hasUsername: Boolean,
    val hasPassword: Boolean,
    val totp: TotpConfig?,
    val deadline: RowDeadline?,
    /** A payment card's or a Wi-Fi network's small face, drawn instead of the icon (SPEC §6.2). */
    val face: EntryFace? = null,
)

/** The most pressing date an entry says something ends on, when it ended or ends within 90 days. */
internal data class RowDeadline(val text: String, val status: ExpiryHint.Status)

internal data class ListSection(val kind: Kind, val rows: List<EntryRow>) {
    /**
     * [SUGGESTED]: the entries autofill matched to the app or page (SPEC §7). [CODES]: every entry with a
     * one-time code, the "Codes" shortcut's list (SPEC §6.2).
     */
    enum class Kind { SUGGESTED, RECENT, ALL, RESULTS, RECYCLE_BIN, CODES }
}

/** The sections without the rows of [ids]; the recycle bin keeps them, a section left empty goes. */
internal fun List<ListSection>.without(ids: Set<String>): List<ListSection> {
    if (ids.isEmpty()) return this
    return mapNotNull { section ->
        if (section.kind == ListSection.Kind.RECYCLE_BIN) return@mapNotNull section
        val rows = section.rows.filterNot { it.id in ids }
        when {
            rows.size == section.rows.size -> section
            rows.isEmpty() -> null
            else -> section.copy(rows = rows)
        }
    }
}

/**
 * A vault prepared for the list: the search indexes and each entry's deadline are computed once per vault
 * (it takes a moment for ~2000 entries: build it off the main thread), then [sections] answers every query.
 */
internal class VaultListing(val vault: Vault, now: Instant, private val collator: Collator = Collator.getInstance()) {
    private val index = EntrySearch.index(vault.entries, collator)
    private val binIndex = EntrySearch.index(vault.recycleBin, collator)
    private val byId: Map<String, Entry> = vault.entries.associateBy { it.id }
    private val deadlines: Map<String, RowDeadline> = DeadlineList.build(vault, now)
        .filter { it.hint.status != ExpiryHint.Status.VALID }
        .groupBy { it.entryId }
        .mapValues { (_, list) -> rowDeadline(list.minWith(compareBy<Deadline> { it.hint.status }.thenBy { it.date })) }

    /** Only the entries with typed fields can have one: few, and found without reading the others' fields. */
    private val faces: Map<String, EntryFace> = (vault.entries + vault.recycleBin)
        .filter { it.fieldTypes.isNotEmpty() }
        .mapNotNull { entry -> EntryFace.of(entry)?.let { entry.id to it } }
        .toMap()

    val ids: Set<String> get() = byId.keys

    /** The groups, tags and file names for the chips over the keyboard (SPEC §6.2); only the list asks for them. */
    val facets: SearchFacets by lazy { SearchFacets.of(vault.entries, collator) }

    /** The same inside the recycle bin, the bin's own group left out of the paths (as its rows show them). */
    val binFacets: SearchFacets by lazy { SearchFacets.of(vault.recycleBin.map { it.copy(groupPath = it.groupPath.drop(1)) }, collator) }

    /**
     * Nothing typed: the recently used entries, then all by title. Typed: the matches, best first. A query
     * starting with `-` lists the recycle bin (the latest deleted first) or searches it. [codesOnly]: only
     * the entries with a one-time code; nothing typed lists them all by title.
     */
    fun sections(query: String, recentIds: List<String>, codesOnly: Boolean = false): List<ListSection> {
        if (codesOnly) {
            val entries = EntrySearch.filter(index, query)
            val kind = if (query.isBlank()) ListSection.Kind.CODES else ListSection.Kind.RESULTS
            return listOfNotNull(section(kind, entries.filter { it.totp != null }))
        }
        EntrySearch.recycleBinQuery(query)?.let { binQuery ->
            val entries = if (binQuery.isBlank()) vault.recycleBin.asReversed() else EntrySearch.filter(binIndex, binQuery)
            return listOfNotNull(section(ListSection.Kind.RECYCLE_BIN, entries))
        }
        if (query.isNotBlank()) return listOfNotNull(section(ListSection.Kind.RESULTS, EntrySearch.filter(index, query)))
        return listOfNotNull(
            section(ListSection.Kind.RECENT, recentIds.mapNotNull(byId::get)),
            section(ListSection.Kind.ALL, EntrySearch.filter(index, "")),
        )
    }

    fun entry(id: String): Entry? = byId[id] ?: vault.recycleBin.firstOrNull { it.id == id }

    fun section(kind: ListSection.Kind, entries: List<Entry>): ListSection? =
        if (entries.isEmpty()) null else ListSection(kind, entries.map(::row))

    private fun row(entry: Entry) = EntryRow(
        id = entry.id,
        title = entry.displayTitle,
        // A card or a network says which it is ("Visa •• 4242 · 12/26"); other entries their username and site.
        subtitle = faces[entry.id]?.subtitle ?: listOf(entry.username, entry.urlHost.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
        group = (if (entry.id in byId) entry.groupPath else entry.groupPath.drop(1)).joinToString(" › "),
        avatar = EntryAvatar.of(entry.displayTitle),
        customIconId = entry.customIconId?.takeIf { it in vault.customIcons },
        hasAttachments = entry.attachments.isNotEmpty(),
        hasUsername = entry.username.isNotEmpty(),
        hasPassword = !entry.password.isEmpty(),
        totp = entry.totp,
        deadline = deadlines[entry.id],
        face = faces[entry.id],
    )

    private fun rowDeadline(deadline: Deadline): RowDeadline {
        val hint = deadline.hint
        val text = when {
            deadline.source != Deadline.Source.EntryExpiry -> "${deadline.label.substringAfterLast(" › ")} · ${hint.text}"
            hint.status == ExpiryHint.Status.EXPIRED -> "Expired ${hint.text}"
            else -> "Expires ${hint.text}"
        }
        return RowDeadline(text, hint.status)
    }
}
