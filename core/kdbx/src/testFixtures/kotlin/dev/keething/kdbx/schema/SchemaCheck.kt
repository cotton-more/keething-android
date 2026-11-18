package dev.keething.kdbx.schema

import dev.keething.kdbx.writer.WrittenDocuments
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * SPEC §3.3 (keething-spec kdbx.keepass-schema AC3, AC4; Mac `SchemaCheck`): every document any test writes is
 * validated against KeePass's own `KDBX_XML.xsd` ([XsdValidator]) and checked by the app's [KdbxSchema]. A written
 * file the schema refuses fails the test that wrote it, unless that test said it writes one on purpose
 * ([allowViolations]); and the two must agree in every case, so the app's check misses nothing the schema catches
 * (and refuses nothing it allows). Found by JUnit through its service file in every module that has these fixtures.
 */
public class SchemaCheck : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        synchronized(lock) {
            problems.clear()
            allowed = false
            entryTwice = false
        }
        WrittenDocuments.observer = ::check
    }

    override fun afterEach(context: ExtensionContext) {
        WrittenDocuments.observer = null
        val found = synchronized(lock) { problems.toList() }
        if (found.isNotEmpty() && context.executionException.isEmpty) {
            throw AssertionError(found.joinToString("\n"))
        }
    }

    public companion object {
        private val lock = Any()
        private val problems = mutableListOf<String>()
        private var allowed = false
        private var entryTwice = false

        /** How many documents were checked, over all tests. */
        @Volatile
        public var checked: Int = 0
            private set

        /** The running test writes documents the schema refuses on purpose (a file that already had violations). */
        public fun allowViolations() {
            synchronized(lock) { allowed = true }
        }

        /**
         * The running test writes a file with two entries of one UUID on purpose: the JDK's validator misses that part of
         * `KUuid` (KdbxSchemaTest), so the two disagree on it alone and that is not a failure.
         */
        public fun allowEntryUuidTwice() {
            synchronized(lock) {
                allowed = true
                entryTwice = true
            }
        }

        /** The validator's errors and the app's violations for one document. */
        public fun validate(xml: ByteArray, violations: Set<SchemaViolation>): Pair<List<String>, List<SchemaViolation>> =
            XsdValidator.errors(xml) to violations.sorted()

        private fun check(xml: ByteArray, violations: Set<SchemaViolation>) {
            val (xsd, own) = validate(xml, violations)
            synchronized(lock) {
                checked++
                val knownMiss = entryTwice && xsd.isEmpty() && own.all { it.problem == "used more than once" }
                if (xsd.isEmpty() != own.isEmpty() && !knownMiss) {
                    problems += "KdbxSchema and KDBX_XML.xsd disagree on a written file — XSD: " +
                        (if (xsd.isEmpty()) "valid" else xsd.take(3).joinToString(" | ")) + "; KdbxSchema: " +
                        (if (own.isEmpty()) "valid" else own.take(3).joinToString(" | "))
                } else if (xsd.isNotEmpty() && !allowed) {
                    problems += "a written file is not valid by KDBX_XML.xsd: ${xsd.take(3).joinToString(" | ")}; " +
                        "KdbxSchema: ${own.take(3).joinToString(" | ")}"
                }
            }
        }
    }
}
