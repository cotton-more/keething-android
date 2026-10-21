package dev.keepassbar.kdbx.tree

import kotlin.test.assertEquals

/**
 * Two trees with the same elements, attributes, text and secrets, in the same order; the whitespace between
 * elements aside.
 */
internal fun assertSameTree(expected: XElement, actual: XElement, where: String = "") {
    val here = "$where/${expected.name}"
    assertEquals(expected.name, actual.name, where)
    assertEquals(expected.attributes, actual.attributes, "$here attributes")
    assertEquals(expected.secret, actual.secret, "$here secret")
    if (expected.elements.isEmpty()) {
        assertEquals(expected.text, actual.text, "$here text")
    } else {
        assertEquals(expected.text.trim(), actual.text.trim(), "$here text between elements")
    }
    assertEquals(expected.elements.map { it.name }, actual.elements.map { it.name }, "$here children")
    expected.elements.zip(actual.elements).forEach { (e, a) -> assertSameTree(e, a, here) }
}

/** Every element below [this], in document order. */
internal fun XElement.descendants(): List<XElement> = elements.flatMap { listOf(it) + it.descendants() }
