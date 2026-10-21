package dev.keepassbar.domain.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Mac `FieldTypeTests` "Format" expectations: a JSON object of names and types, keys sorted. */
class EntryFieldTypesTest {
    @Test
    fun encodedWithKeysSortedAndSlashesAsTheyAre() {
        assertEquals("""{"a/x":"until","b":"date"}""", EntryFieldTypes.encode(mapOf("b" to "date", "a/x" to "until")))
        assertEquals("{}", EntryFieldTypes.encode(emptyMap()))
    }

    @Test
    fun quotesBackslashesAndControlCharactersAreEscapedOtherTextStaysAsItIs() {
        val types = mapOf("Gültig \"bis\"\\" to "until", "Line\nTwo\t\u0001" to "срок")
        val text = EntryFieldTypes.encode(types)
        assertEquals("""{"Gültig \"bis\"\\":"until","Line\nTwo\t\u0001":"срок"}""", text)
        assertEquals(types, EntryFieldTypes.decode(text))
    }

    @Test
    fun decodedFromAnyJsonObjectOfStrings() {
        assertEquals(mapOf("Gültig bis" to "until"), EntryFieldTypes.decode("""{"Gültig bis":"until"}"""))
        assertEquals(
            mapOf("é/" to "date", "😀" to "url", "" to ""),
            EntryFieldTypes.decode(""" { "\u00e9\/" : "date" ,"\ud83d\ude00":"url", "":"" } """),
        )
        assertEquals(emptyMap(), EntryFieldTypes.decode("{}"))
    }

    @Test
    fun anythingElseIsNone() {
        for (text in listOf("[]", """{"a":1}""", """{"a":null}""", """{"a":{"b":"c"}}""", "x = date", "", """{"a":"b"""", """{"a":"b"} x""", """{"a" "b"}""", """{"a":"\x"}""")) {
            assertNull(EntryFieldTypes.decode(text), text)
        }
    }
}
