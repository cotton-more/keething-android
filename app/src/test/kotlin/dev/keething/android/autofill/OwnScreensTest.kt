package dev.keething.android.autofill

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keething's own screens give no autofill service anything to fill or save (keething-spec autofill.own-screens). */
class OwnScreensTest {
    private val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()

    /** Each call of a text field, with the lines up to its modifier (or the next 30). */
    private fun textFields(): List<Pair<String, String>> = sources.flatMap { file ->
        val lines = file.readLines()
        lines.indices.filter { FIELD.containsMatchIn(lines[it]) && !lines[it].contains("fun ") }.map { start ->
            val call = lines.subList(start, minOf(lines.size, start + 30))
            "${file.name}:${start + 1}" to (call.firstOrNull { it.contains("modifier = ") } ?: "")
        }
    }

    @Test
    fun everyTextFieldIsKeptOutOfAutofill() {
        val fields = textFields()
        assertTrue(fields.size >= 10, "the fields are found: ${fields.size}")
        assertEquals(emptyList(), fields.filterNot { it.second.contains(".keepOutOfAutofill()") }.map { it.first })
    }

    @Test
    fun everyActivityWithAScreenKeepsItsWindowOutOfAutofill() {
        val withScreens = sources.filter { it.readText().contains("setContent {") }.map { it.name }
        assertTrue(withScreens.isNotEmpty())
        assertEquals(emptyList(), withScreens.filterNot { name -> sources.first { it.name == name }.readText().contains("secureWindow()") })
    }

    private companion object {
        val FIELD = Regex("""\b(TextField|OutlinedTextField|BasicTextField|SecureTextField|OutlinedSecureTextField)\(""")
    }
}
