package dev.keething.android.storageprobe

import android.content.ContentResolver
import android.net.Uri
import dev.keething.android.storage.PersistedGrants
import dev.keething.android.storage.ProviderFolder
import dev.keething.domain.storage.GrantedFolder

/**
 * What the phone's storage provider does with names (keething-spec features/databases/saf/plan.md stage S3): names
 * differing only in case, and each call of the port for a file or a folder that is not there, in a folder granted in
 * the system's picker. Debug builds only.
 */
internal class GrantProbe(
    private val resolver: ContentResolver,
    private val grants: PersistedGrants,
    private val log: (String) -> Unit,
) {
    @Volatile var folder: ProviderFolder? = null
        private set

    /** The folder the picker gave: its grant kept (given back at the next start, as any the list does not use). Off the main thread. */
    fun granted(tree: Uri) {
        grants.take(tree, write = true)
        val granted = ProviderFolder(resolver, tree)
        folder = granted
        log("granted ${granted.place}: ${granted.list("").orEmpty().size} item(s)")
    }

    /** What `createDocument` and `renameDocument` give for a name taken in another case (S2's handover). Off the main thread. */
    fun caseTest() {
        val granted = folder ?: return log("no folder granted")
        val made = mutableListOf<String>()
        fun GrantedFolder.make(name: String) = create("", name).also { made += it; log("create $name -> $it") }
        try {
            granted.make("$PREFIX-case.tmp")
            granted.make("$PREFIX-CASE.tmp")
            val other = granted.make("$PREFIX-other.tmp")
            val renamed = granted.rename(other, "$PREFIX-Case.tmp").also { made -= other; made += it }
            log("rename $other to $PREFIX-Case.tmp -> $renamed")
            val self = granted.make("$PREFIX-self.tmp")
            val itself = granted.rename(self, "$PREFIX-SELF.tmp").also { made -= self; made += it }
            log("rename $self to its own name in capitals -> $itself")
            log("version by another case: ${granted.version("$PREFIX-CASE.TMP")}")
            log("list: " + granted.list("").orEmpty().map { it.name }.filter { it.startsWith(PREFIX, ignoreCase = true) })
        } catch (failure: Exception) {
            log("case test failed: $failure")
        } finally {
            for (name in made.distinct()) log("delete $name: ${runCatching { granted.delete(name) }.getOrElse { it.toString() }}")
        }
    }

    /** What each call of the port gives for a file or a folder that is not there (the review of stage S3). Off the main thread. */
    fun missingTest() {
        val granted = folder ?: return log("no folder granted")
        fun attempt(what: String, call: () -> Any?) = log("$what -> " + runCatching { call() }.fold({ "$it" }, { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }))
        attempt("version of a file not there") { granted.version("$PREFIX-nope.kdbx") }
        attempt("read of a file not there") { granted.read("$PREFIX-nope.kdbx")?.bytes?.size }
        attempt("delete of a file not there") { granted.delete("$PREFIX-nope.kdbx") }
        attempt("rename of a file not there") { granted.rename("$PREFIX-nope.kdbx", "$PREFIX-x.tmp") }
        attempt("list of a folder not there") { granted.list("$PREFIX-NoDir") }
        attempt("version in a folder not there") { granted.version("$PREFIX-NoDir/a.kdbx") }
        attempt("create in a folder not there") { granted.create("$PREFIX-NoDir", "$PREFIX-x.tmp") }
        attempt("watch of a folder not there") { granted.watch("$PREFIX-NoDir") {}.also { it.close() } }
    }

    private companion object {
        /** Names Syncthing never sends. */
        const val PREFIX = ".syncthing.keething-probe"
    }
}
