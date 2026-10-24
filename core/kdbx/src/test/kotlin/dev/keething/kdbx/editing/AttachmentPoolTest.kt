package dev.keething.kdbx.editing

import dev.keething.domain.secret.Secret
import dev.keething.kdbx.tree.XElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The file's attachments and the entries' references to them (the Mac `LifecycleTests`, `AttachmentEditingTests`). */
class AttachmentPoolTest {
    /** One entry per ref, each with a history version that refers to the same attachment. */
    private fun tree(vararg refs: String): XElement {
        val root = XElement("KeePassFile")
        val group = XElement("Group")
        root.append(XElement("Root").apply { append(group) })
        for (ref in refs) {
            val binary = XElement("Binary").apply {
                append(XElement("Key", "f"))
                append(XElement("Value").apply { setAttribute("Ref", ref) })
            }
            val history = XElement("History").apply { append(XElement("Entry").apply { append(binary.copy()) }) }
            group.append(XElement("Entry").apply { append(binary); append(history) })
        }
        return root
    }

    private fun refs(root: XElement) = AttachmentPool.references(root).map { it.attribute("Ref") }

    private fun bytes(vararg values: Int) = values.map { Secret.adopt(byteArrayOf(it.toByte())) }

    @Test
    fun unreferencedAttachmentsGoAndTheOthersAreRenumberedInOrder() {
        val root = tree("3", "1")
        val pool = AttachmentPool(bytes(0, 1, 2, 3), listOf(0, 1, 0, 1))

        pool.compact(root)

        assertEquals(bytes(1, 3), pool.binaries)
        assertEquals(listOf<Byte>(1, 1), pool.flags)
        assertEquals(listOf("1", "1", "0", "0"), refs(root))
    }

    @Test
    fun aReferenceThatIsNoIndexLeavesEverythingAsItIs() {
        val bad = tree("0", "9")
        val pool = AttachmentPool(bytes(0, 1, 2, 3), listOf(0, 0, 0, 0))

        pool.compact(bad)

        assertEquals(4, pool.binaries.size)
        assertEquals(listOf("0", "0", "9", "9"), refs(bad))
        assertNull(pool.digests(bad)[2])
    }

    @Test
    fun identicalContentsAreStoredOnce() {
        val root = tree("0", "2", "1")
        val pool = AttachmentPool(bytes(7, 8, 7), listOf(1, 1, 1))
        val before = pool.digests(root)

        pool.compact(root)

        assertEquals(bytes(7, 8), pool.binaries)
        assertEquals(listOf("0", "0", "0", "0", "1", "1"), refs(root))
        assertEquals(before, pool.digests(root), "every reference means the same bytes")
    }

    @Test
    fun nothingChangesWhenEveryAttachmentIsUsedOnce() {
        val root = tree("1", "0")
        val pool = AttachmentPool(bytes(5, 6), listOf(1, 0))

        pool.compact(root)

        assertEquals(bytes(5, 6), pool.binaries)
        assertEquals(listOf<Byte>(1, 0), pool.flags)
        assertEquals(listOf("1", "1", "0", "0"), refs(root))
    }
}
