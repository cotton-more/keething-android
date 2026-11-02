package dev.keething.vault.databases

import dev.keething.domain.storage.StoragePlace
import dev.keething.vault.biometric.MapSettings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's `DatabaseListTests` (SPEC §3.1; no migration from the one database of before, plan AD4). */
class DatabaseListTest {
    private fun list(store: MapSettings, now: Instant = Instant.ofEpochSecond(1_000_000)): DatabaseList {
        var ids = 0
        return DatabaseList(store, makeId = { DatabaseId("db-${++ids}") }, now = { now })
    }

    // No default path, no migration

    @Test
    fun aFirstStartIsEmptyAndTheOneDatabaseOfBeforeIsNotTaken() {
        val store = MapSettings()
        store.setString("databasePath", "/storage/emulated/0/Sync/1Password.kdbx")
        store.setString("biometricUnlock", "on")

        val list = list(store)

        assertTrue(list.records.isEmpty())
        assertNull(list.current)
        assertNotNull(store.string(DatabaseList.STORE_KEY), "the empty list is saved")
        assertEquals("/storage/emulated/0/Sync/1Password.kdbx", store.string("databasePath"), "the old key is left as it was")
    }

    @Test
    fun theListIsReadBackAsSaved() {
        val store = MapSettings()
        val list = list(store)
        list.add("/a/Personal.kdbx")
        val work = list.add("/b/Work.kdbx")
        list.update(work.id) { it.copy(keyFile = FileLocation.ByPath("/keys/work.keyx"), lastOpened = Instant.ofEpochMilli(1_800_000_000_123)) }
        list.setCurrent(work.id)

        val again = list(store)

        assertEquals(list.records, again.records)
        assertEquals(work.id, again.currentId)
    }

    @Test
    fun theStoredListIsTheMacsJson() {
        val store = MapSettings()
        val list = list(store)
        val work = list.add("/b/Work \"2\".kdbx")
        list.update(work.id) { it.copy(keyFile = FileLocation.ByPath("/k/w.keyx"), lastOpened = Instant.ofEpochSecond(1_800_000_000)) }

        assertEquals(
            "{\"current\":\"db-1\",\"databases\":[{\"checkedInChooser\":true,\"id\":\"db-1\",\"inWindow\":false," +
                "\"keyFilePath\":\"/k/w.keyx\",\"lastOpened\":1800000000,\"opensWithoutPassword\":false," +
                "\"path\":\"/b/Work \\\"2\\\".kdbx\",\"useBiometric\":false}],\"version\":1}",
            store.string(DatabaseList.STORE_KEY),
        )
    }

    @Test
    fun anUnreadableStoredListIsKeptAsideNeverOverwrittenAndTheListStartsEmpty() {
        val store = MapSettings()
        store.setString(DatabaseList.STORE_KEY, "{\"version\":1,\"databases\":[{\"broken")

        val list = list(store, now = Instant.ofEpochSecond(1_234_567))

        assertTrue(list.records.isEmpty())
        assertEquals("{\"version\":1,\"databases\":[{\"broken", store.string("databases.unreadable.1234567"))
        list.add("/a/Personal.kdbx")
        assertEquals("{\"version\":1,\"databases\":[{\"broken", store.string("databases.unreadable.1234567"), "still there")
    }

    @Test
    fun aRecordWithoutIdOrPathOrAFieldOfAnotherTypeMakesTheListUnreadable() {
        for (stored in listOf(
            "{\"databases\":[{\"path\":\"/a.kdbx\"}]}",
            "{\"databases\":[{\"id\":\"x\"}]}",
            "{\"databases\":[{\"id\":\"x\",\"path\":\"/a.kdbx\",\"useBiometric\":\"yes\"}]}",
            "{\"databases\":{}}",
            "{\"version\":\"1\",\"databases\":[]}",
            "[]",
            "",
        )) {
            val store = MapSettings()
            store.setString(DatabaseList.STORE_KEY, stored)
            assertTrue(list(store).records.isEmpty(), stored)
            assertEquals(stored, store.string("databases.unreadable.1000000"), stored)
        }
    }

    @Test
    fun aRecordWithFieldsMissingOrFromALaterVersionReadsWithDefaults() {
        val store = MapSettings()
        store.setString(
            DatabaseList.STORE_KEY,
            """
            {"version":2,"current":"x","future":true,"databases":[{"id":"x","path":"/a/X.kdbx","colour":"teal","name":null},
            {"id":"y","path":"/b/Y.kdbx","keyFilePath":"/k/y.keyx","useBiometric":true,"opensWithoutPassword":true,"name":"Why",
            "lastOpened":1800000000.5,"inWindow":true,"checkedInChooser":false}]}
            """.trimIndent(),
        )

        val list = list(store)

        assertEquals(listOf("x", "y"), list.records.map { it.id.value })
        val x = assertNotNull(list.record(DatabaseId("x")))
        assertEquals(DatabaseRecord(DatabaseId("x"), FileLocation.ByPath("/a/X.kdbx")), x)
        assertTrue(x.checkedInChooser)
        val y = assertNotNull(list.record(DatabaseId("y")))
        assertTrue(y.useBiometric && y.opensWithoutPassword && y.isPasswordless && y.inWindow && !y.checkedInChooser)
        assertEquals("Why", y.displayName)
        assertEquals(Instant.ofEpochMilli(1_800_000_000_500), y.lastOpened)
        assertEquals(DatabaseId("x"), list.currentId)
    }

    // Changing the list

    @Test
    fun aFileAddedGetsAnIdAndTheSameFileAgainIsTheSameDatabase() {
        val list = list(MapSettings())

        val personal = list.add("/a/Personal.kdbx", useBiometric = true)

        assertEquals(DatabaseId("db-1"), personal.id)
        assertTrue(personal.useBiometric)
        assertEquals(personal, list.add("/a/./sub/../Personal.kdbx"))
        assertEquals(personal, list.add("/a//Personal.kdbx"))
        val work = list.add("/b/Work.kdbx/")
        assertEquals("/b/Work.kdbx", work.path)
        assertEquals(listOf(personal.id, work.id), list.records.map { it.id })
        assertEquals(personal, list.current, "the first one added becomes current")
    }

    @Test
    fun removingTheCurrentOneMakesTheFirstLeftCurrentAndTheLastLeavesNone() {
        val list = list(MapSettings())
        val a = list.add("/a.kdbx")
        val b = list.add("/b.kdbx")
        val c = list.add("/c.kdbx")
        list.setCurrent(b.id)

        list.remove(a.id)
        assertEquals(b.id, list.currentId, "another one removed: the current stays")
        list.remove(b.id)
        assertEquals(c.id, list.currentId)
        list.remove(c.id)

        assertTrue(list.records.isEmpty())
        assertNull(list.current)
    }

    @Test
    fun removingTheCurrentOneMakesTheWindowsFirstCurrentOneNotInTheWindowStaysOut() {
        // keething-spec databases.list AC6: a database the user closed stays closed.
        val list = list(MapSettings())
        val a = list.add("/a.kdbx")
        val b = list.add("/b.kdbx")
        val c = list.add("/c.kdbx")
        list.setInWindow(c.id, true)
        list.setInWindow(b.id, true)
        list.setCurrent(b.id)

        list.remove(b.id)

        assertEquals(c.id, list.currentId, "the window's first, not the list's (a is not in the window)")
        assertEquals(a.id, list.records.first().id)
    }

    @Test
    fun locatingAndTheKeyFileChangeTheRecordNotItsIdAndAreSavedAtOnce() {
        val store = MapSettings()
        val list = list(store)
        val a = list.add("/storage/1234-5678/Crypto.kdbx")

        list.update(a.id) { it.copy(id = DatabaseId("other"), file = FileLocation.ByPath("/storage/emulated/0/Crypto.kdbx"), keyFile = FileLocation.ByPath("/k/c.keyx")) }

        val read = list(store)
        assertEquals("/storage/emulated/0/Crypto.kdbx", read.record(a.id)?.path)
        assertEquals("/k/c.keyx", read.record(a.id)?.keyFilePath)
        assertNull(read.record(DatabaseId("other")), "the id is not changed")
    }

    @Test
    fun anUnknownCurrentIdReadsAsTheFirstDatabaseAndSettingOneNotInTheListDoesNothing() {
        val store = MapSettings()
        store.setString(DatabaseList.STORE_KEY, "{\"version\":1,\"current\":\"gone\",\"databases\":[{\"id\":\"x\",\"path\":\"/x.kdbx\"}]}")
        val list = list(store)
        assertEquals(DatabaseId("x"), list.currentId)

        list.setCurrent(DatabaseId("nope"))

        assertEquals(DatabaseId("x"), list.currentId)
    }

    @Test
    fun itsNameIsTheFilesElseTheFileNameWithoutKdbxAndTheStateFollowsEveryChange() {
        val list = list(MapSettings())
        val seen = mutableListOf<Databases>()
        val a = list.add("/a/Family Shared.kdbx")
        seen += list.state.value
        assertEquals("Family Shared", a.displayName)
        assertEquals("Family Shared.kdbx", a.fileName)

        list.update(a.id) { it.copy(name = "Family") }
        assertEquals("Family", list.record(a.id)?.displayName)
        val before = list.state.value
        list.setCurrent(a.id) // current already (the first added): no change
        list.update(a.id) { it } // nothing changed
        assertTrue(before === list.state.value)

        assertEquals("Keys", DatabaseRecord(DatabaseId("k"), FileLocation.ByPath("/k/Keys"), keyFile = FileLocation.ByPath("/k.keyx")).displayName)
        assertEquals("x", DatabaseRecord(DatabaseId("k"), FileLocation.ByPath("/k/x.KDBX"), name = "  ").displayName)
        assertFalse(
            DatabaseRecord(DatabaseId("k"), FileLocation.ByPath("/k.kdbx"), opensWithoutPassword = true).isPasswordless,
            "without a key file nothing opens it without a password",
        )
    }

    // Ids

    @Test
    fun anIdIsAFileNameOfItsOwnAndOneThatIsNotPlainNeverLeavesItsFolder() {
        assertEquals("6F1C2A0E-5B7D-4C2E-9A31-0D8E7F6A5B4C", DatabaseId("6F1C2A0E-5B7D-4C2E-9A31-0D8E7F6A5B4C").fileName)
        assertEquals("id-2e2e2f2e2e2f657463", DatabaseId("../../etc").fileName)
        assertEquals("id-", DatabaseId("").fileName)
        assertEquals("id-c3a9", DatabaseId("é").fileName)
        val made = DatabaseId.make()
        assertEquals(made.value, made.fileName)
        assertEquals(36, made.value.length)
    }

    // The JSON itself

    @Test
    fun theJsonReaderTakesWhatTheWriterWritesAndRefusesWhatIsNotJson() {
        val value = mapOf("b" to listOf(1, 2.5, -3e2, true, null), "a" to "tab\t \"q\" \\ \u0001 ü 🔑")
        val text = Json.write(value)
        assertEquals("{\"a\":\"tab\\t \\\"q\\\" \\\\ \\u0001 ü 🔑\",\"b\":[1,2.5,-300,true,null]}", text)
        assertEquals(mapOf("a" to "tab\t \"q\" \\ \u0001 ü 🔑", "b" to listOf(1.0, 2.5, -300.0, true, null)), Json.parse(text))
        assertEquals("é/\n", Json.parse(" \"\\u00e9\\/\\n\" "))
        for (bad in listOf("{", "{\"a\" 1}", "[1,]", "01", "\"a", "\"\n\"", "tru", "{} x", "\"\\x\"", "[".repeat(40) + "]".repeat(40))) {
            assertFailsWith<JsonException>(bad) { Json.parse(bad) }
        }
    }

    @Test
    fun whatTheFileSaysOfItselfANameButKeePassXcsDefaultAndARrGgBbColour() {
        // The Mac's "database record: what the file says of itself".
        for (name in listOf("Passwords", "passwords", " Passwords ", "Passwörter", "PASSWÖRTER", "Пароли", "", "  ")) {
            assertNull(DatabaseRecord.nameFromFile(name), name)
        }
        assertEquals("Personal", DatabaseRecord.nameFromFile(" Personal "))
        assertEquals("My Passwords", DatabaseRecord.nameFromFile("My Passwords"))
        assertEquals("#FF8000", DatabaseRecord.colorFromFile("#ff8000"))
        assertEquals("#00A0FF", DatabaseRecord.colorFromFile(" 00A0FF "), "the # is left out by some")
        for (odd in listOf(null, "", "#F80", "red", "#GG0000", "#FF800000", "##FF8000")) {
            assertNull(DatabaseRecord.colorFromFile(odd), odd ?: "null")
        }
    }

    @Test
    fun aNameAndColourAreKeptInTheList() {
        val store = MapSettings()
        val first = list(store)
        val record = first.add("/a/Personal.kdbx")
        first.update(record.id) { it.copy(name = "Family Shared", color = "#FF8000") }
        val again = list(store).record(record.id)
        assertEquals("Family Shared", again?.name)
        assertEquals("#FF8000", again?.color)
    }

    // The window (several open, plan AD8)

    @Test
    fun theWindowIsItsDatabasesInTheListsOrderAndIsKeptWithTheList() {
        val store = MapSettings()
        val list = list(store)
        val personal = list.add("/a/Personal.kdbx")
        val work = list.add("/b/Work.kdbx")
        list.add("/c/Old.kdbx")
        assertTrue(list.window.isEmpty(), "a database joins the list out of the window")

        list.setInWindow(work.id, true)
        list.setInWindow(personal.id, true)
        assertEquals(listOf(personal.id, work.id), list.window.map { it.id })

        list.setInWindow(personal.id, false)
        list.setInWindow(DatabaseId("not in the list"), true)
        assertEquals(listOf(work.id), list(store).window.map { it.id })
    }

    @Test
    fun anObserverIsToldAfterEveryChangeWithTheListAlreadyChanged() {
        val list = list(MapSettings())
        val seen = mutableListOf<List<String?>>()
        list.observe { seen += list.records.map { it.path } }

        val personal = list.add("/a/Personal.kdbx")
        list.add("/a/Personal.kdbx")
        list.update(personal.id) { it }
        list.setCurrent(personal.id)
        list.setInWindow(personal.id, true)
        list.remove(personal.id)

        assertEquals(listOf(listOf("/a/Personal.kdbx"), listOf("/a/Personal.kdbx"), emptyList()), seen, "only what changed it is told")
    }

    // Folders the user granted (keething-spec databases.saf AC2, AC9, AC13, AC14)

    private val sync = StoragePlace(StoragePlace.PRIMARY, "Sync")
    private fun inSync(path: String, grant: String = "grant-sync") = FileLocation.InFolder(grant, sync, path)

    @Test
    fun aDatabaseInAGrantedFolderIsKeptByTheGrantAndThePathInsideAndReadBack() {
        val store = MapSettings()
        val list = list(store)
        val personal = list.add(inSync("KeePass/Personal.kdbx"))
        list.update(personal.id) { it.copy(keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "Download/p.keyx"))) }
        val work = list.add(inSync("Work.kdbx"))
        list.update(work.id) { it.copy(keyFile = inSync("Keys/work.keyx")) }

        assertEquals(
            "{\"current\":\"db-1\",\"databases\":[{\"checkedInChooser\":true," +
                "\"file\":{\"folder\":\"Sync\",\"grant\":\"grant-sync\",\"path\":\"KeePass/Personal.kdbx\",\"volume\":\"primary\"}," +
                "\"id\":\"db-1\",\"inWindow\":false," +
                "\"keyFile\":{\"grant\":\"grant-key\",\"place\":\"Download/p.keyx\",\"volume\":\"primary\"}," +
                "\"opensWithoutPassword\":false,\"useBiometric\":false},{\"checkedInChooser\":true," +
                "\"file\":{\"folder\":\"Sync\",\"grant\":\"grant-sync\",\"path\":\"Work.kdbx\",\"volume\":\"primary\"}," +
                "\"id\":\"db-2\",\"inWindow\":false," +
                "\"keyFile\":{\"folder\":\"Sync\",\"grant\":\"grant-sync\",\"path\":\"Keys/work.keyx\",\"volume\":\"primary\"}," +
                "\"opensWithoutPassword\":false,\"useBiometric\":false}],\"version\":1}",
            store.string(DatabaseList.STORE_KEY),
        )
        assertEquals(list.records, list(store).records)
        val read = assertNotNull(list(store).record(personal.id))
        assertEquals("Personal.kdbx", read.fileName)
        assertEquals("Personal", read.displayName)
        assertNull(read.path, "reached through the grant, not by a path")
        assertTrue(read.copy(opensWithoutPassword = true).isPasswordless, "a key file through a grant is a key file")
    }

    @Test
    fun aRecordOfBeforeReadsByItsPathsAsItWas() {
        val store = MapSettings()
        store.setString(
            DatabaseList.STORE_KEY,
            "{\"version\":1,\"databases\":[{\"id\":\"x\",\"path\":\"/storage/emulated/0/Sync/X.kdbx\",\"keyFilePath\":\"/storage/emulated/0/k.keyx\"," +
                "\"useBiometric\":true,\"name\":\"Ex\",\"color\":\"#112233\"}]}",
        )

        val x = assertNotNull(list(store).record(DatabaseId("x")))

        assertEquals(FileLocation.ByPath("/storage/emulated/0/Sync/X.kdbx"), x.file)
        assertEquals(FileLocation.ByPath("/storage/emulated/0/k.keyx"), x.keyFile)
        assertEquals(StoragePlace("primary", "Sync/X.kdbx"), x.file.place, "where the folder picker opens (AC13)")
        assertTrue(x.useBiometric && x.name == "Ex" && x.color == "#112233")
    }

    @Test
    fun aListOfBeforeIsWrittenBackByteForByteAsTheAppOfBeforeWroteIt() {
        val before = "{\"current\":\"B\",\"databases\":[{\"checkedInChooser\":true,\"color\":\"#112233\",\"id\":\"A\",\"inWindow\":true," +
            "\"keyFilePath\":\"/storage/emulated/0/Documents/Keys/keyx.keyx\",\"lastOpened\":1800000000,\"name\":\"Keyed\"," +
            "\"opensWithoutPassword\":true,\"path\":\"/storage/emulated/0/Sync/Keyed.kdbx\",\"useBiometric\":false}," +
            "{\"checkedInChooser\":false,\"id\":\"B\",\"inWindow\":false,\"opensWithoutPassword\":false," +
            "\"path\":\"/storage/1234-5678/Crypto.kdbx\",\"useBiometric\":true}],\"version\":1}"
        val store = MapSettings()
        store.setString(DatabaseList.STORE_KEY, before)
        val list = list(store)

        list.setInWindow(DatabaseId("B"), true)
        list.setInWindow(DatabaseId("B"), false)

        assertEquals(before, store.string(DatabaseList.STORE_KEY))
    }

    @Test
    fun aFileOrKeyFileOfAGrantWithoutItsPartsMakesTheListUnreadable() {
        for (stored in listOf(
            "{\"databases\":[{\"id\":\"x\",\"file\":{\"grant\":\"g\",\"volume\":\"primary\",\"folder\":\"Sync\"}}]}",
            "{\"databases\":[{\"id\":\"x\",\"file\":{\"volume\":\"primary\",\"folder\":\"Sync\",\"path\":\"x.kdbx\"}}]}",
            "{\"databases\":[{\"id\":\"x\",\"file\":{\"grant\":\"g\",\"folder\":\"Sync\",\"path\":\"x.kdbx\"}}]}",
            "{\"databases\":[{\"id\":\"x\",\"file\":{\"grant\":\"g\",\"volume\":\"primary\",\"folder\":\"Sync\",\"path\":\"\"}}]}",
            "{\"databases\":[{\"id\":\"x\",\"path\":\"/a.kdbx\",\"keyFile\":{\"grant\":\"g\",\"volume\":\"primary\"}}]}",
            "{\"databases\":[{\"id\":\"x\",\"file\":\"/a.kdbx\"}]}",
        )) {
            val store = MapSettings()
            store.setString(DatabaseList.STORE_KEY, stored)
            assertTrue(list(store).records.isEmpty(), stored)
        }
    }

    @Test
    fun theSameFileHoweverItIsReachedIsTheSameDatabase() {
        val list = list(MapSettings())
        val personal = list.add(inSync("KeePass/Personal.kdbx"))

        assertEquals(personal, list.add(inSync("keepass/PERSONAL.kdbx")), "the storage takes names regardless of case")
        assertEquals(personal, list.add(FileLocation.InFolder("grant-top", StoragePlace("primary", ""), "Sync/KeePass/Personal.kdbx")), "a folder that holds the other")
        assertEquals(personal, list.add("/storage/emulated/0/Sync/KeePass/Personal.kdbx"), "the path of before")
        assertEquals(1, list.records.size)
        assertEquals(personal, list.recordOf(FileLocation.ByPath("/sdcard/Sync/KeePass/Personal.kdbx")))

        assertTrue(list.add(FileLocation.InFolder("grant-card", StoragePlace("1234-5678", "Sync"), "KeePass/Personal.kdbx")) != personal, "on a card: another file")
        assertTrue(list.add("/a/Personal.kdbx") != list.add("/a/personal.kdbx"), "a path off the storage: as it is written")
    }

    @Test
    fun theGrantsInUseAndAPlaceInAFolderTheListAlreadyReaches() {
        val list = list(MapSettings())
        val personal = list.add(inSync("KeePass/Personal.kdbx"))
        list.update(personal.id) { it.copy(keyFile = FileLocation.Granted("grant-key", StoragePlace("primary", "Download/p.keyx"))) }
        list.add("/storage/emulated/0/Old.kdbx")
        val keePass = StoragePlace("primary", "Sync/KeePass")
        val inner = FileLocation.InFolder("grant-keepass", keePass, "Work.kdbx")
        list.add(inner)

        assertEquals(setOf("grant-sync", "grant-key", "grant-keepass"), list.grantsInUse)
        assertEquals(inSync("Keys/k.keyx"), list.inGrantedFolder(StoragePlace("primary", "sync/Keys/k.keyx")), "case aside, in its own spelling")
        assertEquals(inSync("KeePass/k.keyx"), list.inGrantedFolder(keePass.resolve("k.keyx")), "the list's first that holds it")
        assertEquals(
            FileLocation.InFolder("grant-keepass", keePass, "k.keyx"),
            list.inGrantedFolder(keePass.resolve("k.keyx"), prefer = inner),
            "the database's own folder first",
        )
        assertNull(list.inGrantedFolder(StoragePlace("primary", "Download/p.keyx")), "a file granted alone holds nothing")
        assertNull(list.inGrantedFolder(sync), "a folder is not a file in it")
    }
}
