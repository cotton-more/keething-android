package dev.keething.kdbx.schema

import dev.keething.kdbx.tree.XElement
import dev.keething.kdbx.tree.XText
import java.util.Base64

/** Something in the document KeePass's schema does not allow ([KdbxSchema]). Never carries a value. */
public data class SchemaViolation(
    /**
     * Where: `Meta`, `Root`, `group <UUID>`, `entry <UUID>` (the versions in its history count as the entry, so one
     * copied into the history is not new), `UUID <UUID>`.
     */
    val place: String,
    /** What, by the path inside the place: `PreviousParentGroup: out of order`, `Times/Expires: not True or False`. */
    val problem: String,
) : Comparable<SchemaViolation> {
    override fun compareTo(other: SchemaViolation): Int = compareValuesBy(this, other, { it.place }, { it.problem })

    override fun toString(): String = "$place: $problem"

    /** As the editor says it, without the UUID: "an entry's Times/Expires: not True or False". */
    public fun describe(more: Int = 0): String {
        val whose = when {
            place.startsWith("entry ") -> "an entry's "
            place.startsWith("group ") -> "a group's "
            place.startsWith("UUID ") -> "a UUID "
            else -> "$place "
        }
        return whose + problem + if (more > 0) " (and $more more)" else ""
    }
}

/**
 * KeePass's XML schema, `KDBX_XML.xsd` (KDBX 4.1, keepass.info/help/download/KDBX_XML.xsd), written out in Kotlin
 * (SPEC §3.3; port of the Mac `KDBXSchema`): the elements each element may hold and in what order (`TEntry`,
 * `TGroup` are sequences, `TMeta`, `TTimes` sets), the attributes, the value formats (`True`/`False`, 16-byte base64
 * UUIDs, KeePass times, `#RRGGBB`, numbers in range), UUIDs used once (`KUuid`) and custom icons that exist
 * (`RCustomIcon`). The tests hold it to the schema itself: every file they write is validated by both, and the two
 * must agree. As strict as libxml2, as on the Mac, which is a little stricter than the JDK's validator (blanks around
 * numbers, a sign on an unsigned one, a blank inside base64 padding).
 *
 * A save refuses a violation the file did not already have ([added]), so a database that holds what the schema does
 * not know (a plugin's elements, an older app's) still saves.
 */
internal object KdbxSchema {
    /** Everything in the document the schema does not allow. */
    fun violations(document: XElement): Set<SchemaViolation> = Check().also { it.document(document) }.found

    /** What [after] has that [before] had not: what a change added. */
    fun added(before: Set<SchemaViolation>, after: Set<SchemaViolation>): List<SchemaViolation> = (after - before).sorted()

    // Placing

    /**
     * Puts [child] into [parent] (a `Group` or an `Entry`) where the schema's sequence has it: before the first
     * element that comes after it. Elements the schema does not know are passed over.
     */
    fun insert(child: XElement, parent: XElement) {
        val model = sequence(parent.name)
        val index = model?.index?.get(child.name)
        if (model == null || index == null) {
            parent.append(child)
            return
        }
        val follower = parent.elements.firstOrNull { (model.index[it.name] ?: -1) > index }
        parent.insertBefore(child, follower)
    }

    /**
     * Moves every `PreviousParentGroup` that is out of the schema's order to its place: Keething before 2026-10 put
     * an entry's after `Times`. Entries in histories too. Run on every save, so old files become valid.
     */
    fun placePreviousParentGroups(document: XElement) {
        fun visit(element: XElement) {
            for (child in element.elements) {
                if (child.name != "Group" && child.name != "Entry" && child.name != "History") continue
                if (child.name != "History") {
                    val previous = child.first("PreviousParentGroup")
                    if (previous != null && isOutOfOrder(previous, child)) {
                        child.remove(previous)
                        insert(previous, child)
                    }
                }
                visit(child)
            }
        }
        document.first("Root")?.let(::visit)
    }

    private fun isOutOfOrder(child: XElement, parent: XElement): Boolean {
        val model = sequence(parent.name) ?: return false
        val index = model.index[child.name] ?: return false
        var passed = false
        for (e in parent.elements) {
            if (e === child) {
                passed = true
                continue
            }
            val other = model.index[e.name] ?: continue
            if (if (passed) other < index else other > index) return true
        }
        return false
    }

    private fun sequence(name: String): Model? = when (name) {
        "Group" -> model(Complex.GROUP)
        "Entry" -> model(Complex.ENTRY)
        else -> null
    }

    // The schema

    enum class Simple(val problem: String) {
        STRING("not text"),
        BOOL("not True or False"),
        NULLABLE_BOOL("not True, False or Null"),
        COLOR("not #RRGGBB (upper case) or empty"),
        UUID("not a 16-byte base64 UUID"),
        DATE_TIME("not a KeePass time"),
        BASE64("not base64"),
        NON_NEGATIVE_INT("not a number of 0 or more"),
        UNSIGNED_INT("not a number of 0 or more"),
        UNSIGNED_LONG("not a number of 0 or more"),
        INT_FROM_MINUS_ONE("not a number of -1 or more"),
        LONG_FROM_MINUS_ONE("not a number of -1 or more"),
        OBFUSCATION("not 0 or 1"),
    }

    enum class Complex {
        FILE, META, MEMORY_PROTECTION, CUSTOM_ICONS, ICON, BINARIES, META_CUSTOM_DATA, META_ITEM, CUSTOM_DATA, ITEM,
        ROOT, DELETED_OBJECTS, DELETED_OBJECT, GROUP, ENTRY, TIMES, STRING, BINARY, AUTO_TYPE, ASSOCIATION, HISTORY,
    }

    sealed interface Kind {
        class Value(
            val type: Simple,
            val attributes: Map<String, Simple> = emptyMap(),
            val required: Set<String> = emptySet(),
        ) : Kind

        class Element(val type: Complex) : Kind
    }

    class Particle(val name: String, val kind: Kind, val required: Boolean = false, val many: Boolean = false)

    /** A sequence (in order) or a set (`xs:all`, any order). */
    class Model(val ordered: Boolean, val particles: List<Particle>) {
        /** Each particle's place by its name. */
        val index: Map<String, Int> = buildMap { particles.forEachIndexed { i, p -> putIfAbsent(p.name, i) } }
    }

    private fun p(name: String, type: Simple, required: Boolean = false) = Particle(name, Kind.Value(type), required)

    private fun p(name: String, type: Complex, required: Boolean = false, many: Boolean = false) =
        Particle(name, Kind.Element(type), required, many)

    private val models: Map<Complex, Model> = buildMap {
        put(Complex.FILE, Model(true, listOf(p("Meta", Complex.META, required = true), p("Root", Complex.ROOT, required = true))))
        put(
            Complex.META,
            Model(
                false,
                listOf(
                    p("Generator", Simple.STRING), p("HeaderHash", Simple.BASE64), p("SettingsChanged", Simple.DATE_TIME),
                    p("DatabaseName", Simple.STRING), p("DatabaseNameChanged", Simple.DATE_TIME),
                    p("DatabaseDescription", Simple.STRING), p("DatabaseDescriptionChanged", Simple.DATE_TIME),
                    p("DefaultUserName", Simple.STRING), p("DefaultUserNameChanged", Simple.DATE_TIME),
                    p("MaintenanceHistoryDays", Simple.UNSIGNED_INT), p("Color", Simple.COLOR),
                    p("MasterKeyChanged", Simple.DATE_TIME), p("MasterKeyChangeRec", Simple.LONG_FROM_MINUS_ONE),
                    p("MasterKeyChangeForce", Simple.LONG_FROM_MINUS_ONE), p("MasterKeyChangeForceOnce", Simple.BOOL),
                    p("MemoryProtection", Complex.MEMORY_PROTECTION), p("CustomIcons", Complex.CUSTOM_ICONS),
                    p("RecycleBinEnabled", Simple.BOOL), p("RecycleBinUUID", Simple.UUID),
                    p("RecycleBinChanged", Simple.DATE_TIME), p("EntryTemplatesGroup", Simple.UUID),
                    p("EntryTemplatesGroupChanged", Simple.DATE_TIME), p("HistoryMaxItems", Simple.INT_FROM_MINUS_ONE),
                    p("HistoryMaxSize", Simple.LONG_FROM_MINUS_ONE), p("LastSelectedGroup", Simple.UUID),
                    p("LastTopVisibleGroup", Simple.UUID), p("Binaries", Complex.BINARIES),
                    p("CustomData", Complex.META_CUSTOM_DATA),
                ),
            ),
        )
        put(
            Complex.MEMORY_PROTECTION,
            Model(false, listOf("ProtectTitle", "ProtectUserName", "ProtectPassword", "ProtectURL", "ProtectNotes").map { p(it, Simple.BOOL) }),
        )
        put(Complex.CUSTOM_ICONS, Model(true, listOf(p("Icon", Complex.ICON, many = true))))
        put(
            Complex.ICON,
            Model(
                false,
                listOf(
                    p("UUID", Simple.UUID, required = true), p("Data", Simple.BASE64, required = true),
                    p("Name", Simple.STRING), p("LastModificationTime", Simple.DATE_TIME),
                ),
            ),
        )
        put(
            Complex.BINARIES,
            Model(
                true,
                listOf(
                    Particle(
                        "Binary",
                        Kind.Value(
                            Simple.BASE64,
                            mapOf("ID" to Simple.NON_NEGATIVE_INT, "Compressed" to Simple.BOOL, "Protected" to Simple.BOOL),
                            setOf("ID"),
                        ),
                        many = true,
                    ),
                ),
            ),
        )
        put(Complex.META_CUSTOM_DATA, Model(true, listOf(p("Item", Complex.META_ITEM, many = true))))
        put(
            Complex.META_ITEM,
            Model(
                true,
                listOf(
                    p("Key", Simple.STRING, required = true), p("Value", Simple.STRING, required = true),
                    p("LastModificationTime", Simple.DATE_TIME),
                ),
            ),
        )
        put(Complex.CUSTOM_DATA, Model(true, listOf(p("Item", Complex.ITEM, many = true))))
        put(Complex.ITEM, Model(true, listOf(p("Key", Simple.STRING, required = true), p("Value", Simple.STRING, required = true))))
        put(Complex.ROOT, Model(true, listOf(p("Group", Complex.GROUP, required = true), p("DeletedObjects", Complex.DELETED_OBJECTS))))
        put(Complex.DELETED_OBJECTS, Model(true, listOf(p("DeletedObject", Complex.DELETED_OBJECT, many = true))))
        put(
            Complex.DELETED_OBJECT,
            Model(true, listOf(p("UUID", Simple.UUID, required = true), p("DeletionTime", Simple.DATE_TIME, required = true))),
        )
        put(
            Complex.GROUP,
            Model(
                true,
                listOf(
                    p("UUID", Simple.UUID, required = true), p("Name", Simple.STRING), p("Notes", Simple.STRING),
                    p("IconID", Simple.NON_NEGATIVE_INT), p("CustomIconUUID", Simple.UUID), p("Times", Complex.TIMES),
                    p("IsExpanded", Simple.BOOL), p("DefaultAutoTypeSequence", Simple.STRING),
                    p("EnableAutoType", Simple.NULLABLE_BOOL), p("EnableSearching", Simple.NULLABLE_BOOL),
                    p("LastTopVisibleEntry", Simple.UUID), p("PreviousParentGroup", Simple.UUID), p("Tags", Simple.STRING),
                    p("CustomData", Complex.CUSTOM_DATA), p("Entry", Complex.ENTRY, many = true),
                    p("Group", Complex.GROUP, many = true),
                ),
            ),
        )
        put(
            Complex.ENTRY,
            Model(
                true,
                listOf(
                    p("UUID", Simple.UUID, required = true), p("IconID", Simple.NON_NEGATIVE_INT),
                    p("CustomIconUUID", Simple.UUID), p("ForegroundColor", Simple.COLOR), p("BackgroundColor", Simple.COLOR),
                    p("OverrideURL", Simple.STRING), p("QualityCheck", Simple.BOOL), p("Tags", Simple.STRING),
                    p("PreviousParentGroup", Simple.UUID), p("Times", Complex.TIMES),
                    p("String", Complex.STRING, many = true), p("Binary", Complex.BINARY, many = true),
                    p("AutoType", Complex.AUTO_TYPE), p("CustomData", Complex.CUSTOM_DATA), p("History", Complex.HISTORY),
                ),
            ),
        )
        put(
            Complex.TIMES,
            Model(
                false,
                listOf(
                    p("CreationTime", Simple.DATE_TIME), p("LastModificationTime", Simple.DATE_TIME),
                    p("LastAccessTime", Simple.DATE_TIME), p("ExpiryTime", Simple.DATE_TIME), p("Expires", Simple.BOOL),
                    p("UsageCount", Simple.UNSIGNED_LONG), p("LocationChanged", Simple.DATE_TIME),
                ),
            ),
        )
        put(
            Complex.STRING,
            Model(
                true,
                listOf(
                    p("Key", Simple.STRING, required = true),
                    Particle(
                        "Value",
                        Kind.Value(Simple.STRING, mapOf("Protected" to Simple.BOOL, "ProtectInMemory" to Simple.BOOL)),
                        required = true,
                    ),
                ),
            ),
        )
        put(
            Complex.BINARY,
            Model(
                true,
                listOf(
                    p("Key", Simple.STRING, required = true),
                    Particle("Value", Kind.Value(Simple.BASE64, mapOf("Ref" to Simple.NON_NEGATIVE_INT)), required = true),
                ),
            ),
        )
        put(
            Complex.AUTO_TYPE,
            Model(
                true,
                listOf(
                    p("Enabled", Simple.BOOL), p("DataTransferObfuscation", Simple.OBFUSCATION),
                    p("DefaultSequence", Simple.STRING), p("Association", Complex.ASSOCIATION, many = true),
                ),
            ),
        )
        put(
            Complex.ASSOCIATION,
            Model(true, listOf(p("Window", Simple.STRING, required = true), p("KeystrokeSequence", Simple.STRING, required = true))),
        )
        put(Complex.HISTORY, Model(true, listOf(p("Entry", Complex.ENTRY, many = true))))
    }

    fun model(type: Complex): Model = models.getValue(type)

    // Checking

    private class Check {
        val found = mutableSetOf<SchemaViolation>()

        /** `KUuid`: the UUIDs of custom icons, groups and entries (not their histories), by their bytes. */
        private val uuids = mutableMapOf<String, Int>()

        /** `KCustomIcon` and the `CustomIconUUID`s that must refer to one (`RCustomIcon`), with where they are. */
        private val icons = mutableSetOf<String>()
        private val iconReferences = mutableListOf<Pair<String, SchemaViolation>>()

        fun document(root: XElement) {
            if (root.name != "KeePassFile") {
                found += SchemaViolation("Document", "${root.name}: not KeePassFile")
                return
            }
            complex(root, Complex.FILE, place = "Document", path = null)
            for ((uuid, count) in uuids) if (count > 1) found += SchemaViolation("UUID $uuid", "used more than once")
            for ((uuid, violation) in iconReferences) if (uuid !in icons) found += violation
        }

        /**
         * Where an element is inside its place, built into text only for a violation: a chain of names, null right
         * in the place.
         */
        class Path(val name: String, val parent: Path?) {
            override fun toString(): String = if (parent == null) name else "$parent/$name"
        }

        private fun named(path: Path?, child: String): String = if (path == null) child else "$path/$child"

        /**
         * [path]: where the element is inside its place (null right in it). An entry, a group, `Meta` and `Root`
         * are places of their own; a version in an entry's history ([version]) counts as the entry.
         */
        fun complex(element: XElement, type: Complex, place: String, path: Path?, version: Boolean = false) {
            var place = place
            val inside: Path? = when (type) {
                Complex.META -> null.also { place = "Meta" }
                Complex.ROOT -> null.also { place = "Root" }
                Complex.FILE -> null
                Complex.GROUP -> null.also { place = "group ${uuidText(element)}" }
                Complex.ENTRY -> null.also { if (!version) place = "entry ${uuidText(element)}" }
                else -> Path(element.name, path)
            }
            val here = inside?.toString() ?: element.name

            for ((attribute, _) in element.attributes) {
                if (!isNamespace(attribute)) found += SchemaViolation(place, "$here: attribute $attribute not allowed")
            }
            val model = model(type)
            val counts = IntArray(model.particles.size)
            var last = -1
            var text = false
            for (child in element.children) {
                if (child !is XElement) {
                    if (!text && child is XText && !isBlank(child.text)) {
                        found += SchemaViolation(place, "$here: text not allowed")
                        text = true
                    }
                    continue
                }
                val index = model.index[child.name]
                if (index == null) {
                    found += SchemaViolation(place, "${named(inside, child.name)}: not expected here")
                    continue
                }
                val particle = model.particles[index]
                counts[index]++
                if (counts[index] > 1 && !particle.many) {
                    found += SchemaViolation(place, "${named(inside, child.name)}: more than once")
                } else if (model.ordered && index < last) {
                    found += SchemaViolation(place, "${named(inside, child.name)}: out of order")
                }
                if (model.ordered && index > last) last = index

                when (val kind = particle.kind) {
                    is Kind.Element -> {
                        complex(child, kind.type, place, inside, version = type == Complex.HISTORY)
                        // KUuid: custom icons, groups, and entries in a group (not the versions in a history).
                        val counted = kind.type == Complex.ICON || kind.type == Complex.GROUP ||
                            (kind.type == Complex.ENTRY && type == Complex.GROUP)
                        if (counted) {
                            uuidKey(child.first("UUID"))?.let { uuid ->
                                uuids[uuid] = (uuids[uuid] ?: 0) + 1
                                if (kind.type == Complex.ICON) icons += uuid
                            }
                        }
                    }
                    is Kind.Value -> {
                        value(child, kind, place, inside)
                        if (child.name == "CustomIconUUID") {
                            uuidKey(child)?.let { iconReferences += it to SchemaViolation(place, "${named(inside, child.name)}: no such custom icon") }
                        }
                    }
                }
            }
            model.particles.forEachIndexed { index, particle ->
                if (particle.required && counts[index] == 0) found += SchemaViolation(place, "${named(inside, particle.name)}: missing")
            }
        }

        fun value(element: XElement, kind: Kind.Value, place: String, path: Path?) {
            fun name() = named(path, element.name)
            if (element.children.any { it is XElement }) found += SchemaViolation(place, "${name()}: elements not allowed")
            if (kind.type != Simple.STRING && !valid(element.text, kind.type)) found += SchemaViolation(place, "${name()}: ${kind.type.problem}")
            for ((attribute, text) in element.attributes) {
                if (isNamespace(attribute)) continue
                val type = kind.attributes[attribute]
                if (type == null) {
                    found += SchemaViolation(place, "${name()}: attribute $attribute not allowed")
                } else if (!valid(text, type)) {
                    found += SchemaViolation(place, "${name()}: attribute $attribute ${type.problem}")
                }
            }
            for (attribute in kind.required) {
                if (element.attribute(attribute) == null) found += SchemaViolation(place, "${name()}: attribute $attribute missing")
            }
        }

        fun uuidText(element: XElement): String = element.first("UUID")?.text?.trim() ?: "(none)"
    }

    private fun isNamespace(name: String): Boolean = name == "xmlns" || name.startsWith("xmlns:") || name.startsWith("xsi:")

    /** A 16-byte UUID's bytes in plain base64 (blanks dropped), to compare UUIDs written differently; null when it is none. */
    private fun uuidKey(element: XElement?): String? {
        val text = element?.text ?: return null
        if (base64Length(text) != 16) return null
        val bytes = Base64.getDecoder().decode(text.filterNot(::isBlank))
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun valid(text: String, type: Simple): Boolean = when (type) {
        Simple.STRING -> true
        Simple.BOOL -> text == "True" || text == "False"
        Simple.NULLABLE_BOOL -> text in setOf("Null", "null", "False", "false", "True", "true")
        Simple.COLOR -> text.isEmpty() || (text.length == 7 && text[0] == '#' && text.drop(1).all { it in '0'..'9' || it in 'A'..'F' })
        Simple.UUID -> base64Length(text) == 16
        Simple.BASE64 -> base64Length(text) != null
        Simple.DATE_TIME -> base64Length(text) == 8 || isDateTime(collapse(text))
        Simple.NON_NEGATIVE_INT -> integer(text)?.let { it in 0..Int.MAX_VALUE.toLong() } ?: false
        Simple.UNSIGNED_INT -> unsigned(text)?.let { it <= UInt.MAX_VALUE.toULong() } ?: false
        Simple.UNSIGNED_LONG -> unsigned(text) != null
        Simple.INT_FROM_MINUS_ONE -> integer(text)?.let { it in -1..Int.MAX_VALUE.toLong() } ?: false
        Simple.LONG_FROM_MINUS_ONE -> integer(text)?.let { it >= -1 } ?: false
        Simple.OBFUSCATION -> integer(text)?.let { it == 0L || it == 1L } ?: false
    }

    /**
     * `xs:long`'s lexical form, an optional sign and digits, in range. No whitespace around: libxml2 (2.9) does not
     * collapse it for numbers, so neither does this.
     */
    private fun integer(text: String): Long? {
        val digits = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return text.toLongOrNull()
    }

    /** `xs:unsignedLong` as libxml2 reads it: digits only, no sign, no whitespace. */
    private fun unsigned(text: String): ULong? {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        return text.toULongOrNull()
    }

    // Lexical forms

    /** XML Schema's `collapse`: no whitespace around, runs inside as one space. */
    fun collapse(text: String): String = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** Whether the text is only XML whitespace. */
    fun isBlank(text: String): Boolean = text.all(::isBlank)

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * The number of bytes an `xs:base64Binary` holds (XML Schema 1.0's lexical form: whitespace around and between
     * the characters, `=` padding only at the end, the unused bits of the last character zero); null when it is not one.
     */
    fun base64Length(text: String): Int? {
        var count = 0
        var padding = 0
        var last = 0
        // libxml2 takes no blank between the two `=` (XML Schema would): neither does this.
        var blankAfterPadding = false
        for (c in text) {
            when {
                isBlank(c) -> {
                    blankAfterPadding = padding > 0
                    continue
                }
                c == '=' -> {
                    if (blankAfterPadding) return null
                    padding++
                }
                else -> {
                    val value = BASE64_ALPHABET.indexOf(c)
                    if (value < 0 || padding > 0) return null
                    last = value
                }
            }
            count++
        }
        if (count % 4 != 0 || padding > 2) return null
        if (padding == 1 && last and 0x03 != 0) return null
        if (padding == 2 && last and 0x0F != 0) return null
        return count / 4 * 3 - padding
    }

    /** `xs:dateTime`: `-?YYYY-MM-DDThh:mm:ss(.s+)?` and an optional zone, `Z` or `±hh:mm`. */
    fun isDateTime(text: String): Boolean {
        var s = text
        if (s.startsWith('-')) s = s.substring(1)
        fun number(count: Int): Int? {
            if (s.length < count || !s.take(count).all { it in '0'..'9' }) return null
            return s.take(count).toInt().also { s = s.substring(count) }
        }
        fun take(c: Char): Boolean = s.startsWith(c).also { if (it) s = s.substring(1) }

        val yearDigits = s.takeWhile { it in '0'..'9' }
        if (yearDigits.length < 4 || (yearDigits.length > 4 && yearDigits[0] == '0')) return false
        val year = yearDigits.toLongOrNull() ?: return false
        if (year == 0L) return false
        s = s.substring(yearDigits.length)
        if (!take('-')) return false
        val month = number(2) ?: return false
        if (!take('-')) return false
        val day = number(2) ?: return false
        if (!take('T')) return false
        val hour = number(2) ?: return false
        if (!take(':')) return false
        val minute = number(2) ?: return false
        if (!take(':')) return false
        val second = number(2) ?: return false
        if (take('.')) {
            val fraction = s.takeWhile { it in '0'..'9' }
            if (fraction.isEmpty()) return false
            s = s.substring(fraction.length)
        }
        if (!take('Z') && (s.startsWith('+') || s.startsWith('-'))) {
            s = s.substring(1)
            val zoneHour = number(2) ?: return false
            if (!take(':')) return false
            val zoneMinute = number(2) ?: return false
            if (zoneMinute >= 60 || !(zoneHour < 14 || (zoneHour == 14 && zoneMinute == 0))) return false
        }
        if (s.isNotEmpty() || month !in 1..12 || minute >= 60 || second >= 60) return false
        val leap = year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        if (day !in 1..days) return false
        return hour < 24 || (hour == 24 && minute == 0 && second == 0)
    }
}
