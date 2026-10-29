package dev.keething.kdbx.writer

import dev.keething.kdbx.schema.SchemaViolation

/**
 * For the tests only (SPEC §3.3; keething-spec kdbx.keepass-schema AC3; Mac `KDBXWriter.willWrite`): told of every
 * document the writer is about to write, as XML with the protected values left empty, and the schema's violations
 * in it. The tests validate it against `KDBX_XML.xsd` and hold the two to each other. Null in the app.
 */
internal object WrittenDocuments {
    @Volatile
    var observer: ((xml: ByteArray, violations: Set<SchemaViolation>) -> Unit)? = null
}
