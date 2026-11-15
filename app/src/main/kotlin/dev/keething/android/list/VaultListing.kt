package dev.keething.android.list

import dev.keething.android.databases.DatabaseMark
import dev.keething.android.session.OpenDatabase
import dev.keething.card.Deadline
import dev.keething.card.DeadlineList
import dev.keething.card.EntryAvatar
import dev.keething.card.EntryFace
import dev.keething.card.ExpiryHint
import dev.keething.card.ImportedFieldName
import dev.keething.card.OneTimeCodes
import dev.keething.domain.totp.TotpConfig
import dev.keething.domain.vault.CustomField
import dev.keething.domain.vault.CustomIcon
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.Vault
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.search.EntrySearch
import dev.keething.search.Narrowing
import dev.keething.search.SearchFacets
import dev.keething.search.narrowing
import java.text.Collator
import java.time.Instant

/** One row of the list (SPEC §6.2): title, username and site, the live code when there is one. */
internal data class EntryRow(
    /** The entry's ref ([EntryRef.text]): the same UUID in two databases is two rows. */
    val id: String,
    val title: String,
    /** `username · host`, either part left out when empty. */
    val subtitle: String,
    /** The group's path, `Finanzen`, `Banken` (inside the recycle bin for an entry in it); empty for the root group. */
    val group: List<String>,
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
    /** Its database, while two or more are open: the group line says it ("● Work › Engineering"). */
    val database: DatabaseMark? = null,
)

/**
 * One one-time code of the Codes view (keething-spec app.codes-view AC1, AC2): an entry's own code, or a further one (a
 * custom field holding an `otpauth://` link).
 */
internal data class CodeTile(
    /** Unique in the view: the entry's ref for its own code, the ref and the field's name for a further one. */
    val key: String,
    /** The entry's ref ([EntryRef.text]). */
    val entryId: String,
    val title: String,
    /** The username under the entry's own code, the field's name under a further one. */
    val subtitle: String,
    /** The custom field a further code is kept in; null for the entry's own. */
    val fieldName: String?,
    val totp: TotpConfig,
    val avatar: EntryAvatar,
    val customIconId: String?,
    /** The entry's group's path, as its row shows it; empty for the root group. */
    val group: List<String> = emptyList(),
    /** Its database, while two or more are open: the group line says it, as a row's does. */
    val database: DatabaseMark? = null,
) {
    val further: Boolean get() = fieldName != null
}

/** The most pressing date an entry says something ends on, when it ended or ends within 90 days. */
internal data class RowDeadline(val text: String, val status: ExpiryHint.Status)

internal data class ListSection(val kind: Kind, val rows: List<EntryRow>) {
    /** [SUGGESTED]: the entries autofill matched to the app or page (SPEC §7). */
    enum class Kind { SUGGESTED, RECENT, ALL, RESULTS, RECYCLE_BIN }
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

/** The sections with the rows of the databases [open] only (the others' locked meanwhile); a section left empty goes. */
internal fun List<ListSection>.onlyOf(open: Set<DatabaseId>): List<ListSection> = mapNotNull { section ->
    val rows = section.rows.filter { EntryRef.of(it.id).database in open }
    when {
        rows.size == section.rows.size -> section
        rows.isEmpty() -> null
        else -> section.copy(rows = rows)
    }
}

/**
 * An open database as the list lists it: [mark] while two or more are open (keething-spec databases.name-colour AC6);
 * [label] for the search's `!` filter, also with one open (search.database-filter AC1), null for a vault on its own.
 */
internal class ListedDatabase(val id: DatabaseId, val vault: Vault, val mark: DatabaseMark? = null, val label: String? = mark?.label?.full)

/** The open databases listed as one, labelled while two or more are open. */
internal fun listingOf(open: List<OpenDatabase>, now: Instant): VaultListing {
    val labelled = open.size >= 2
    return VaultListing(open.map { ListedDatabase(it.database.id, it.unlocked.vault, it.mark.takeIf { labelled }, it.mark.label.full) }, now)
}

/** Whether [a] and [b] list the same: the same sessions, the same vaults read, the same marks. */
internal fun sameListing(a: List<OpenDatabase>, b: List<OpenDatabase>): Boolean =
    a.size == b.size && a.indices.all { a[it].database === b[it].database && a[it].unlocked.vault === b[it].unlocked.vault && a[it].mark == b[it].mark }

/**
 * The open databases prepared for the list, as one (plan AD9; keething-spec databases.one-list AC1): each entry is a
 * copy whose id is its ref, so one index ranks them all and the same UUID in two databases is two rows; the search
 * indexes and each entry's deadline are computed once per vault read (it takes a moment for ~2000 entries: build it
 * off the main thread), then [sections] answers every query.
 */
internal class VaultListing(databases: List<ListedDatabase>, now: Instant, private val collator: Collator = Collator.getInstance()) {
    /** One vault on its own, its entries' refs their own ids. */
    constructor(vault: Vault, now: Instant, collator: Collator = Collator.getInstance()) :
        this(listOf(ListedDatabase(DatabaseId(""), vault)), now, collator)

    /**
     * The open databases' entries as one vault, ids and custom icons by ref; its recycle bin is each database's in the
     * window's order, each in its file's order (a file keeps no time of the move).
     */
    val vault: Vault

    /** The bins' entries, latest deleted first in each database (keething-spec search.recycle-bin AC5). */
    private val latestDeleted: List<Entry>

    private val marks: Map<String, DatabaseMark>

    /** Each entry's and each binned entry's database label, for the `!` filter; null for a vault on its own. */
    private val labels: List<String>?
    private val binLabels: List<String>?

    /** Every listed database's label, one without entries (an empty bin) too: a whole label keeps that database only. */
    private val allLabels: List<String> = databases.mapNotNull { it.label }

    /** The databases by label while two or more are open: the `!` suggestions and the counts per database. */
    val databaseMarks: Map<String, DatabaseMark> = databases.mapNotNull { it.mark }.associateBy { it.label.full }

    init {
        val entries = ArrayList<Entry>()
        val bin = ArrayList<Entry>()
        val deleted = ArrayList<Entry>()
        val icons = HashMap<String, CustomIcon>()
        val marked = HashMap<String, DatabaseMark>()
        val labelled = databases.all { it.label != null }
        val entryLabels = ArrayList<String>()
        val binnedLabels = ArrayList<String>()
        for (database in databases) {
            fun ref(id: String) = EntryRef(database.id, id).text
            fun copy(entry: Entry) = entry.copy(id = ref(entry.id), customIconId = entry.customIconId?.let(::ref))
                .also { copied -> database.mark?.let { marked[copied.id] = it } }
            database.vault.entries.mapTo(entries, ::copy)
            database.label?.let { label -> repeat(database.vault.entries.size) { entryLabels += label } }
            val binned = database.vault.recycleBin.map(::copy)
            database.label?.let { label -> repeat(binned.size) { binnedLabels += label } }
            bin += binned
            deleted += binned.asReversed()
            for ((id, icon) in database.vault.customIcons) icons[ref(id)] = icon
        }
        vault = Vault(entries = entries, recycleBin = bin, customIcons = icons)
        latestDeleted = deleted
        marks = marked
        labels = entryLabels.takeIf { labelled }
        binLabels = binnedLabels.takeIf { labelled }
    }

    private val index = EntrySearch.index(vault.entries, collator, labels, allLabels)
    private val binIndex = EntrySearch.index(vault.recycleBin, collator, binLabels, allLabels)

    /**
     * The open databases' one-time codes, one tile each (keething-spec app.codes-view AC1): each entry's own, then its
     * further ones in field order; a code that cannot be read is not one. Built when the Codes view first asks.
     */
    private val codeTiles: Map<String, CodeTile> by lazy { codeEntries.first.associateBy { it.key } }

    /**
     * The tiles as entries to search (AC3): the title, the username and a further code's field name, nothing else of
     * the entry but what the filters read (its groups, tags, attachments and database); in the tiles' order.
     */
    private val codeEntries: Pair<List<CodeTile>, List<Entry>> by lazy {
        val tiles = ArrayList<CodeTile>()
        val searched = ArrayList<Entry>()
        for (entry in vault.entries) {
            val title = entry.displayTitle
            val avatar = EntryAvatar.of(title)
            val icon = entry.customIconId?.takeIf { it in vault.customIcons }
            fun add(tile: CodeTile, fields: List<CustomField>) {
                tiles += tile
                searched += Entry(
                    id = tile.key, title = title, username = entry.username, groupPath = entry.groupPath, tags = entry.tags,
                    customFields = fields, attachments = entry.attachments,
                )
            }
            val mark = marks[entry.id]
            entry.totp?.let { add(CodeTile(entry.id, entry.id, title, entry.username, null, it, avatar, icon, entry.groupPath, mark), emptyList()) }
            for (field in entry.customFields) {
                val config = OneTimeCodes.config(field) ?: continue
                val name = codeName(field.name)
                val tile = CodeTile("${entry.id}\n${field.name}", entry.id, title, name, field.name, config, avatar, icon, entry.groupPath, mark)
                add(tile, listOf(CustomField.plain(name, "")))
            }
        }
        tiles to searched
    }

    private val codeIndex by lazy {
        val labelOf = labels?.let { list -> vault.entries.indices.associate { vault.entries[it].id to list[it] } }
        val (tiles, searched) = codeEntries
        EntrySearch.index(searched, collator, labelOf?.let { map -> tiles.map { map.getValue(it.entryId) } }, allLabels)
    }

    /**
     * The Codes view's tiles (keething-spec app.codes-view AC1, AC3): nothing typed, all by title, an entry's own code
     * before its further ones; typed, the tiles matching, best first; bare signs alone nothing.
     */
    fun codes(query: String): List<CodeTile> {
        if (query.isNotBlank() && EntrySearch.isEmpty(query)) return emptyList()
        return EntrySearch.filter(codeIndex, query).map { codeTiles.getValue(it.id) }
    }

    /** The bins' entries with the bin's own group left out of their paths (as their rows show them), for the suggestions. */
    private val binFacetIndex by lazy { EntrySearch.index(vault.recycleBin.map { it.copy(groupPath = it.groupPath.drop(1)) }, collator, binLabels, allLabels) }
    private val byId: Map<String, Entry> = vault.entries.associateBy { it.id }
    private val binById: Map<String, Entry> = vault.recycleBin.associateBy { it.id }
    private val deadlines: Map<String, RowDeadline> = DeadlineList.build(vault, now)
        .filter { it.hint.status != ExpiryHint.Status.VALID }
        .groupBy { it.entryId }
        .mapValues { (_, list) -> rowDeadline(list.minWith(compareBy<Deadline> { it.hint.status }.thenBy { it.date })) }

    /** Only notes and the entries with typed fields can have one: few, and found without reading the others' fields. */
    private val faces: Map<String, EntryFace> = (vault.entries + vault.recycleBin)
        .filter { it.fieldTypes.isNotEmpty() || it.isNote }
        .mapNotNull { entry -> EntryFace.of(entry)?.let { entry.id to it } }
        .toMap()

    val ids: Set<String> get() = byId.keys

    private class Facets(val filters: String, val inBin: Boolean, val facets: SearchFacets)

    /** The facets the last suggestions were made from: typing after a sign changes only the filter being typed. */
    @Volatile private var lastFacets: Facets? = null

    /**
     * The groups, tags, file names and databases of the entries (or the bins' entries) passing [filters]' filters, for
     * the chips over the keyboard (SPEC §6.2; keething-spec search.suggestions AC6); kept until the filters change.
     */
    fun facets(filters: String, inBin: Boolean): SearchFacets {
        lastFacets?.takeIf { it.filters == filters && it.inBin == inBin }?.let { return it.facets }
        return EntrySearch.facets(if (inBin) binFacetIndex else index, filters).also { lastFacets = Facets(filters, inBin, it) }
    }

    /** The names the query's last word may narrow it to (keething-spec search.narrowing). */
    fun narrowing(query: String): List<Narrowing> = EntrySearch.narrowing(index, query)

    /**
     * Nothing typed: the recently used entries (at most [RecentEntries.LIMIT], of the databases listed), then all by
     * title. Typed: the matches, best first; bare signs alone (`@`, `!`) nothing. A query starting with `-` lists the
     * recycle bins (each database's latest deleted first) or searches them. The Codes view lists [codes] instead.
     */
    fun sections(query: String, recentIds: List<String>): List<ListSection> {
        // Bare signs filter nothing: nothing to list, never the whole vault (keething-spec search.database-filter AC2).
        val bareSigns = query.isNotBlank() && EntrySearch.isEmpty(query)
        EntrySearch.recycleBinQuery(query)?.let { binQuery ->
            val entries = if (binQuery.isBlank()) latestDeleted else EntrySearch.filter(binIndex, binQuery)
            return listOfNotNull(section(ListSection.Kind.RECYCLE_BIN, entries))
        }
        if (bareSigns) return emptyList()
        if (query.isNotBlank()) return listOfNotNull(section(ListSection.Kind.RESULTS, EntrySearch.filter(index, query)))
        return listOfNotNull(
            section(ListSection.Kind.RECENT, recentIds.mapNotNull(byId::get).take(RecentEntries.LIMIT)),
            section(ListSection.Kind.ALL, EntrySearch.filter(index, "")),
        )
    }

    /** The entry of [id] (a ref), a copy whose id is the ref; null when it is in none of the databases listed. */
    fun entry(id: String): Entry? = byId[id] ?: binById[id]

    /** Whether [id] is in a recycle bin. */
    fun inRecycleBin(id: String): Boolean = id in binById

    /** A further code's field name as the card shows it; an empty or generated one is "One-time code". */
    private fun codeName(name: String): String =
        if (name.isBlank() || ImportedFieldName.isGeneratedId(name)) "One-time code" else ImportedFieldName.displayName(name)

    fun section(kind: ListSection.Kind, entries: List<Entry>): ListSection? =
        if (entries.isEmpty()) null else ListSection(kind, entries.map(::row))

    private fun row(entry: Entry) = EntryRow(
        id = entry.id,
        title = entry.displayTitle,
        // A card or a network says which it is ("Visa •• 4242 · 12/26"), a note its line; other entries their username and site.
        subtitle = faces[entry.id]?.subtitle ?: entry.username.ifEmpty { entry.webHost.orEmpty() },
        group = if (entry.id in byId) entry.groupPath else entry.groupPath.drop(1),
        avatar = EntryAvatar.of(entry.displayTitle),
        customIconId = entry.customIconId?.takeIf { it in vault.customIcons },
        hasAttachments = entry.attachments.isNotEmpty(),
        hasUsername = entry.username.isNotEmpty(),
        hasPassword = !entry.password.isEmpty(),
        totp = entry.totp,
        deadline = deadlines[entry.id],
        face = faces[entry.id],
        database = marks[entry.id],
    )

    private fun rowDeadline(deadline: Deadline): RowDeadline {
        val hint = deadline.hint
        val text = if (deadline.source != Deadline.Source.EntryExpiry) "${deadline.label.substringAfterLast(" › ")} · ${hint.text}" else hint.text
        return RowDeadline(text, hint.status)
    }
}
