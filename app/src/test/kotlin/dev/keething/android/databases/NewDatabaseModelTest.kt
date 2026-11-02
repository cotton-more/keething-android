package dev.keething.android.databases

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.keething.domain.encryption.DatabaseEncryption
import dev.keething.domain.encryption.DatabaseEncryption.Cipher
import dev.keething.domain.encryption.DatabaseEncryption.KeyDerivation
import dev.keething.domain.encryption.MeasuredEncryption
import dev.keething.domain.secret.Secret
import dev.keething.generator.PasswordGenerator
import dev.keething.generator.RandomSource
import dev.keething.generator.Strength
import dev.keething.domain.storage.NewFile
import dev.keething.domain.storage.NewFilePlace
import dev.keething.vault.creating.CreateFailure
import dev.keething.vault.creating.DatabaseCreator
import dev.keething.vault.creating.DerivedKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The Mac's `NewDatabaseWindowTests` model rules (keething-spec databases.new-database, databases.new-database-create). */
class NewDatabaseModelTest {
    /** Makes nothing on disk: what it was asked, what it answers. */
    private class FakeMaking : DatabaseMaking {
        data class Made(val path: String, val name: String, val password: String, val keyFile: Boolean, val units: Long)

        val measured = mutableListOf<DatabaseEncryption>()
        val made = mutableListOf<Made>()
        val keyFiles = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val notes = mutableListOf<String>()

        /** Measuring waits for it. */
        var measureGate: CompletableDeferred<Unit>? = null
        var measuring = false

        /** Writing waits for it. */
        var writeGate: CompletableDeferred<Unit>? = null
        var writing = false
        var createFailure: Exception? = null
        var keyFileFailure: Exception? = null
        var removeFailure: Exception? = null
        var measureFailure: Throwable? = null

        /** A run in progress cannot stop halfway: cancelled, it goes on until the gate opens. */
        var ignoresCancel = false

        /** How many measurements run at once, at most. */
        @Volatile var running = 0
        @Volatile var mostAtOnce = 0

        override fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit): MeasuredEncryption {
            measured += encryption
            measureFailure?.let { throw it }
            synchronized(this) {
                running++
                mostAtOnce = maxOf(mostAtOnce, running)
            }
            try {
                measureGate?.let { gate ->
                    measuring = true
                    while (!gate.isCompleted) {
                        if (!ignoresCancel) checkCancelled()
                        Thread.sleep(1)
                    }
                    measuring = false
                }
            } finally {
                synchronized(this) { running-- }
            }
            checkCancelled()
            return MeasuredEncryption(encryption, if (encryption.keyDerivation == KeyDerivation.AES_KDF) 2_000_000 else 42, encryption.seconds)
        }

        override fun begin(name: String) {
            notes += "begin $name"
        }

        override fun createKeyFile(path: String): Secret {
            keyFileFailure?.let { throw it }
            keyFiles += path
            return Secret.copyOf(ByteArray(32) { 0x5A })
        }

        override fun readKeyFile(path: String): Secret = Secret.copyOf(ByteArray(32) { 0x33 })

        override fun removeKeyFile(path: String) {
            removeFailure?.let { throw it }
            removed += path
        }

        override fun create(path: String, name: String, password: Secret, keyFile: Secret?, measured: MeasuredEncryption): DerivedKey {
            writeGate?.let { gate ->
                writing = true
                while (!gate.isCompleted) Thread.sleep(1)
                writing = false
            }
            createFailure?.let { throw it }
            made += Made(path, name, password.reveal(), keyFile != null, measured.units)
            // The real creator, cheaply: a key derived for the file it wrote, as Create hands it over.
            return DatabaseCreator(now = { Instant.EPOCH }).create(InMemoryFile(), name, password, keyFile, CHEAP)
        }

        override fun end(made: Boolean) {
            notes += if (made) "end, made" else "end"
        }
    }

    private class InMemoryFile : NewFile {
        override val fileName = "new.kdbx"

        override fun check() = NewFilePlace.FREE

        override fun create(bytes: ByteArray, readOnly: Boolean) = Unit

        override fun removeCreated() = Unit
    }

    private class FakeFiles(val there: MutableSet<String>, val synced: Set<String> = emptySet()) : NewDatabaseFiles {
        var lookedAgain = 0

        override fun exists(path: String): Boolean = path in there

        override fun isFolder(path: String): Boolean = path in there

        override fun syncs(folder: String): Boolean = synced.any { folder == it || folder.startsWith("$it/") }

        override fun lookAgain() {
            lookedAgain++
        }
    }

    /** SplitMix64, as the generator's tests. */
    private class SeededRandom(private var state: Long) : RandomSource {
        override fun nextBelow(bound: Int): Int {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return java.lang.Long.remainderUnsigned(z xor (z ushr 31), bound.toLong()).toInt()
        }
    }

    private class Fixture(
        scope: TestScope,
        folders: List<String> = listOf("$ROOT/Sync/KeePass"),
        existing: Set<String> = emptySet(),
        synced: Set<String> = emptySet(),
        offersFingerprint: Boolean = true,
        // A real thread for the fake's waits: the test dispatcher would run them on the test's own.
        io: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ) {
        val making = FakeMaking()
        val files = FakeFiles((existing + folders + listOf("$ROOT/Documents", "$ROOT/Keys")).toMutableSet(), synced)
        var locks = 0
        val model = NewDatabaseModel(
            making = making,
            folders = folders,
            root = ROOT,
            files = files,
            offersFingerprint = offersFingerprint,
            scope = scope,
            io = io,
            lockCount = { locks },
            random = SeededRandom(7),
            cores = 8,
            measureDelay = 1.milliseconds,
        )
        val created = mutableListOf<CreatedDatabase>()
        val creating = mutableListOf<Boolean>()

        init {
            model.onCreated = { created += it }
            model.onCreating = { creating += it }
        }

        /** Named and with a password typed twice: Create can. */
        fun fill(name: String = "Family Shared", password: String = "correct horse battery staple") {
            model.changeName(name)
            model.password.setTextAndPlaceCursorAtEnd(password)
            model.again.setTextAndPlaceCursorAtEnd(password)
        }
    }

    // The name and the place (the Mac's D9)

    @Test
    fun theNameMakesTheFileNameAndIsWrittenAsItWasTyped() = runTest {
        val m = Fixture(this).model
        m.changeName("  Family Shared ")
        assertEquals("Family Shared", m.databaseName)
        assertEquals("Family Shared.kdbx", m.fileName)
        assertEquals("$ROOT/Sync/KeePass/Family Shared.kdbx", m.path)
        assertEquals("Sync/KeePass/Family Shared.kdbx", m.pathText)
        m.changeName("Taxes/2026: Q1")
        assertEquals("Taxes-2026- Q1.kdbx", m.fileName, "no folder or colon in a file name")
        assertEquals("Taxes/2026: Q1", m.databaseName, "the name keeps them")
        m.changeName(".hidden")
        assertEquals("hidden.kdbx", m.fileName, "never a hidden file")
        m.changeName("Work.KDBX")
        assertEquals("Work", m.databaseName)
        assertEquals("Work.kdbx", m.fileName, "not Work.KDBX.kdbx")
        m.changeName("Line\nbreak")
        assertEquals("Linebreak", m.databaseName, "control characters dropped")
        m.changeName("   ")
        assertNull(m.fileName)
        assertNull(m.path)
        assertEquals("Sync/KeePass/…", m.pathText, "the folder alone")
        assertEquals("Name it.", m.blockers.first())
    }

    @Test
    fun saveInStartsAtTheCurrentDatabasesFolderTheGrantedOnesOfferedAnotherJoins() = runTest {
        val f = Fixture(this, folders = listOf("$ROOT/Sync/KeePass", "$ROOT/Work", "$ROOT/Sync/KeePass"))
        val m = f.model
        assertEquals("$ROOT/Sync/KeePass", m.folder)
        assertEquals(listOf("Sync/KeePass", "Work"), m.folders.map { it.title })
        assertEquals("this database’s", m.folders.first().note)
        m.chooseFolder("$ROOT/Taxes/")
        assertEquals("$ROOT/Taxes", m.folder)
        assertEquals("Taxes", m.folders.last().title)
        m.chooseFolder("$ROOT/Work")
        assertEquals(3, m.folders.size, "a folder already offered is not added again")
    }

    @Test
    fun withNoFolderGrantedYetOneIsChosenFirst() = runTest {
        // spec: databases.saf/AC11
        val f = Fixture(this, folders = emptyList())
        val first = f.model
        f.fill()
        assertNull(first.folder, "nothing granted: no folder guessed")
        assertEquals(emptyList(), first.folders)
        assertNull(first.path)
        assertEquals("Keething saves only in a folder you choose.", first.pathText)
        assertEquals("Choose the folder to save it in.", first.blockers.first())
        assertFalse(first.canCreate)

        first.chooseFolder("$ROOT/Documents")
        assertEquals("Documents/Family Shared.kdbx", first.pathText)
        assertNull(first.folders.single().note)
        assertTrue(first.canCreate)
    }

    @Test
    fun aFileAlreadyThereIsNeverReplacedTheNameIsRedTheLineSaysSoCreateWaits() = runTest {
        val f = Fixture(this, existing = setOf("$ROOT/Sync/KeePass/Family Shared.kdbx"))
        f.fill()
        assertTrue(f.model.nameIsTaken)
        assertEquals("There is already a Family Shared.kdbx in Sync/KeePass. A database is never replaced: choose another name or folder.", f.model.problem)
        assertFalse(f.model.canCreate)
        f.model.changeName("Family")
        assertTrue(!f.model.nameIsTaken && f.model.problem == null && f.model.canCreate)
    }

    @Test
    fun comingBackTheFilesAreLookedAtAgain() = runTest {
        val f = Fixture(this)
        f.fill()
        assertTrue(f.model.canCreate)
        f.files.there += "$ROOT/Sync/KeePass/Family Shared.kdbx"
        f.model.lookAgain()
        assertEquals(1, f.files.lookedAgain, "what the grants gave is read again")
        assertTrue(f.model.nameIsTaken)
    }

    // The key (the Mac's D9: required, a weak one allowed)

    @Test
    fun thePasswordIsRequiredAndTypedTwiceAWeakOneIsAllowedAndSaid() = runTest {
        val m = Fixture(this).model
        m.changeName("Family")
        assertEquals(listOf("Type a master password."), m.blockers)
        assertNull(m.strength)
        assertNull(m.match)
        m.password.setTextAndPlaceCursorAtEnd("abc")
        assertEquals(listOf("Type the password again."), m.blockers)
        m.again.setTextAndPlaceCursorAtEnd("abd")
        assertEquals(NewDatabaseModel.Match.DIFFERS, m.match)
        assertEquals(listOf("The two passwords differ."), m.blockers)
        m.again.setTextAndPlaceCursorAtEnd("abc")
        assertEquals(NewDatabaseModel.Match.MATCHES, m.match)
        assertTrue(m.canCreate, "a weak password is the owner's choice")
        val strength = assertNotNull(m.strength)
        assertEquals(Strength.WEAK, strength.verdict)
        assertEquals(1, strength.level)
        assertTrue(strength.text.startsWith("Weak: shorter than 8 characters"), strength.text)
        assertTrue(strength.text.endsWith("Anyone with the file can try passwords against it."), strength.text)
        m.password.setTextAndPlaceCursorAtEnd("Gx7#mQ2v!Lp9@Wz4")
        assertEquals(Strength.STRONG, m.strength?.verdict)
        m.password.setTextAndPlaceCursorAtEnd("family2026")
        assertEquals(Strength.WEAK, m.strength?.verdict, "the database's name counts as one character")
    }

    @Test
    fun suggestAPassphraseFourEffWordsTheCountChangeableUseThisFillsBothFields() = runTest {
        val m = Fixture(this).model
        m.suggest()
        val first = assertNotNull(m.suggestion)
        val words = first.split("-").map { part -> part.filter(Char::isLetter).lowercase() }
        assertEquals(4, words.size)
        assertTrue(words.all { it in PasswordGenerator.words }, first)
        assertTrue(first.any(Char::isUpperCase) && first.any(Char::isDigit), first)
        m.suggest()
        assertNotEquals(first, m.suggestion, "Another")
        val four = m.suggestion
        m.changeWords(8)
        assertNotEquals(four, m.suggestion, "another count, another suggestion")
        assertEquals(8, m.suggestion?.split("-")?.size)
        m.changeWords(40)
        assertEquals(10, m.passphraseWords, "brought into range")
        m.changeWords(1)
        assertEquals(3, m.passphraseWords, "brought into range")
        m.useSuggestion()
        assertEquals("Strong · 3 words, about 51 bits", m.strength?.text)
        assertEquals(3, m.strength?.level, "under 80 bits: three segments, as a typed one")
        m.changeWords(6)
        m.suggest()
        val six = m.suggestion
        m.useSuggestion()
        assertEquals(six, m.password.text.toString())
        assertEquals(six, m.again.text.toString())
        assertTrue(m.reveals, "shown in both fields, to be written down")
        assertEquals("Strong · 6 words, about 92 bits", m.strength?.text, "the generator's bits, not a guess from its letters")
        assertEquals(4, m.strength?.level)
        m.password.setTextAndPlaceCursorAtEnd(six + "x")
        assertEquals("Strong", m.strength?.text, "typed over: an estimate again")
    }

    @Test
    fun alsoRequireAKeyFileOneToMakeOrOneToUseBesideTheDatabaseOrSyncingAWarningOneThereAProblem() = runTest {
        val f = Fixture(this, existing = setOf("$ROOT/Keys/old.keyx", "$ROOT/Keys/Family Shared.keyx"), synced = setOf("$ROOT/Sync"))
        val m = f.model
        f.fill()
        m.requireKeyFile(true)
        assertEquals(listOf("Make a key file, or choose one you have."), m.blockers)
        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Documents"))
        assertTrue(m.canCreate)
        assertEquals("$ROOT/Documents/Family Shared.keyx", m.keyFilePath, "named as the database")
        assertEquals("Family Shared.keyx", m.keyFileName)
        assertEquals("Documents · made at Create", m.keyFileDetail)
        assertNull(m.keyFileWarning)
        m.changeName("Family")
        assertEquals("Family.keyx", m.keyFileName, "it follows the name")

        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Sync/KeePass"))
        assertEquals("Beside the database, whoever gets the folder gets both. Keep it apart: a folder that does not sync.", m.keyFileWarning)
        assertTrue(m.canCreate, "a warning, not a refusal")
        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Sync/Keys"))
        f.files.there += "$ROOT/Sync/Keys"
        m.lookAgain()
        assertEquals("In a folder that syncs, it goes wherever the database goes. Keep it apart: a folder that does not sync.", m.keyFileWarning)

        m.changeName("Family Shared")
        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Keys"))
        assertEquals("There is already a Family Shared.keyx in Keys. A key file is never replaced: choose another folder.", m.keyFileProblem)
        assertFalse(m.canCreate)
        assertNull(m.keyFileWarning)

        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.Existing("$ROOT/Keys/old.keyx"))
        assertNull(m.keyFileProblem)
        assertTrue(m.canCreate)
        assertEquals("old.keyx", m.keyFileName)
        assertEquals("Keys", m.keyFileDetail)
        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.Existing("$ROOT/Keys/gone.keyx"))
        assertEquals("gone.keyx is not there any more.", m.keyFileProblem)
        m.requireKeyFile(false)
        assertTrue(m.keyFileName == null && m.keyFilePath == null && m.canCreate, "turned off: no key file")
    }

    @Test
    fun aNewKeyFileWhoseFolderIsGoneOrANameTooLongWaitBeforeCreate() = runTest {
        val f = Fixture(this)
        f.fill("Ж".repeat(130))
        assertEquals(listOf("The name is too long for a file name: shorten it."), f.model.blockers, "260 bytes")
        f.fill("Ж".repeat(120))
        assertTrue(f.model.canCreate, "250 bytes with .kdbx")
        f.model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("/storage/1234-5678/Keys"))
        assertEquals("The key file's folder, /storage/1234-5678/Keys, is not there any more.", f.model.keyFileProblem)
        assertFalse(f.model.canCreate)
    }

    @Test
    fun theFingerprintIsOfferedCheckedOnlyWhileTheAppsSwitchIsOn() = runTest {
        assertTrue(Fixture(this, offersFingerprint = true).model.useFingerprint)
        val off = Fixture(this, offersFingerprint = false)
        assertFalse(off.model.useFingerprint)
        off.fill()
        off.model.useFingerprint = true
        off.model.create()
        advanceUntilIdle()
        assertFalse(off.created.single().useFingerprint, "never on when the app's switch is off: it only allows")
    }

    // Encryption

    @Test
    fun theEncryptionLineSaysTheRecommendedOneArgon2idSaysFromWhichKeePass() = runTest {
        val m = Fixture(this).model
        assertEquals(DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 64, 4, 1.0), m.encryption)
        assertTrue(m.isRecommended)
        assertEquals("KDBX 4 · AES-256 · Argon2d · opens in about 1 s on this phone · read by every current KeePass app", m.encryptionLine)
        m.changeEncryption(m.encryption.copy(keyDerivation = KeyDerivation.ARGON2ID, cipher = Cipher.CHACHA20))
        assertFalse(m.isRecommended)
        assertEquals(
            "KDBX 4 · ChaCha20 · Argon2id · opens in about 1 s on this phone · read by KeePass 2.47 and later and every current KeePass app",
            m.encryptionLine,
        )
        m.recommend()
        assertTrue(m.isRecommended)
        assertEquals("0.5 s", NewDatabaseModel.seconds(0.5))
        assertEquals("2 s", NewDatabaseModel.seconds(2.0))
        assertEquals("2.5 s", NewDatabaseModel.seconds(2.54))
    }

    @Test
    fun theEncryptionPageMeasuresTheTimeAloneScalesWhatWasMeasured() = runTest {
        val f = Fixture(this)
        val m = f.model
        assertEquals("Measuring on this phone…", m.measuredLine)
        m.showPage(NewDatabaseModel.Page.ENCRYPTION)
        advanceUntilIdle()
        assertEquals(1, f.making.measured.size)
        assertEquals("1.0 s on this phone: 42 iterations, measured. A computer usually opens it faster.", m.measuredLine)
        m.changeEncryption(m.encryption.copy(seconds = 2.0))
        assertEquals(1, f.making.measured.size, "the time alone: scaled")
        assertEquals(84, m.measured?.units)
        assertEquals("KDBX 4 · AES-256 · Argon2d · opens in about 2 s on this phone · read by every current KeePass app", m.encryptionLine)
        m.changeEncryption(m.encryption.copy(keyDerivation = KeyDerivation.AES_KDF))
        advanceUntilIdle()
        assertEquals(2, f.making.measured.size, "another kind: measured")
        assertTrue("2,000,000 rounds" in m.measuredLine, m.measuredLine)
    }

    @Test
    fun theEncryptionPageMeasuresOnceAStepperRestsOneAtATime() = runTest {
        val f = Fixture(this)
        val m = f.model
        m.showPage(NewDatabaseModel.Page.ENCRYPTION)
        m.changeEncryption(m.encryption.copy(memoryMiB = 128))
        m.changeEncryption(m.encryption.copy(memoryMiB = 256))
        m.changeEncryption(m.encryption.copy(lanes = 2))
        advanceUntilIdle()
        assertEquals(listOf(256), f.making.measured.map { it.memoryMiB }, "tapped through: only the last is measured")
        assertEquals(2, m.measured?.encryption?.lanes)
    }

    // Create

    @Test
    fun createMeasuresMakesTheNewKeyFileWritesTheFileAndHandsItOverWithItsKey() = runTest {
        val f = Fixture(this)
        val m = f.model
        f.fill(password = "pw-family")
        m.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Keys"))
        f.locks = 3
        m.create()
        assertEquals(NewDatabaseModel.Phase.Creating(writing = false), m.phase)
        assertEquals("Creating Family Shared…", m.creatingTitle)
        assertEquals("Measuring this phone’s speed for 1 s to open, then writing the file and reading it back.", m.creatingDetail)
        f.locks = 4
        advanceUntilIdle()
        assertEquals(NewDatabaseModel.Phase.Created, m.phase)
        assertEquals(listOf("$ROOT/Keys/Family Shared.keyx"), f.making.keyFiles)
        val made = f.making.made.single()
        assertEquals(FakeMaking.Made("$ROOT/Sync/KeePass/Family Shared.kdbx", "Family Shared", "pw-family", keyFile = true, units = 42), made)
        val created = f.created.single()
        assertEquals(made.path, created.path)
        assertEquals("$ROOT/Keys/Family Shared.keyx", created.keyFilePath)
        assertTrue(created.useFingerprint)
        assertEquals(3, created.lockCount, "the app's lock count when Create was tapped")
        assertEquals("Secret(wiped)", created.password.toString(), "wiped once handed over")
        assertTrue(m.password.text.isEmpty() && m.again.text.isEmpty(), "the fields are emptied")
        assertEquals(listOf("begin Family Shared", "end, made"), f.making.notes, "noted while written, the files the user's after")
        assertEquals(listOf(true, false), f.creating, "the app's process kept for it, then let go")
    }

    @Test
    fun whatTheEncryptionPageMeasuredIsWhatCreateWritesNotMeasuredAgain() = runTest {
        val f = Fixture(this)
        f.fill()
        f.model.showPage(NewDatabaseModel.Page.ENCRYPTION)
        advanceUntilIdle()
        f.model.showPage(NewDatabaseModel.Page.BASIC)
        f.model.create()
        advanceUntilIdle()
        assertEquals(1, f.making.measured.size)
        assertEquals(1, f.created.size)
    }

    @Test
    fun aFileThatAppearedMeanwhileNotCreatedSaidInRedTheNameRedItsNewKeyFileGone() = runTest {
        val f = Fixture(this)
        f.fill()
        f.model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Keys"))
        f.making.createFailure = CreateFailure.Exists("Family Shared.kdbx")
        f.model.create()
        advanceUntilIdle()
        assertEquals(NewDatabaseModel.Phase.Editing, f.model.phase)
        assertEquals(
            "Not created: there is already a Family Shared.kdbx in Sync/KeePass. A database is never replaced: choose another name or folder.",
            f.model.problem,
        )
        assertTrue(f.model.nameIsTaken)
        assertEquals(listOf("$ROOT/Keys/Family Shared.keyx"), f.making.removed, "never used: it goes (the Mac's D9)")
        assertTrue(f.created.isEmpty())
        assertFalse(f.model.password.text.isEmpty(), "what was typed stays, to try another name")
        assertEquals(listOf("begin Family Shared", "end"), f.making.notes)
        f.model.changeName("Family")
        assertTrue(f.model.problem == null && !f.model.nameIsTaken && f.model.canCreate)
    }

    @Test
    fun aKeyFileItCannotMakeOrACheckThatFailsSaidNothingHandedOverOneTheUserHasStays() = runTest {
        val f = Fixture(this)
        f.fill()
        f.model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Keys"))
        f.making.keyFileFailure = CreateFailure.Exists("Family Shared.keyx")
        f.model.create()
        advanceUntilIdle()
        assertEquals("Not created: there is already a Family Shared.keyx there. A key file is never replaced: choose another folder.", f.model.problem)
        assertTrue(f.making.made.isEmpty())
        assertTrue(f.making.removed.isEmpty(), "the file already there is not the one it made: never removed")

        val g = Fixture(this, existing = setOf("$ROOT/Keys/family.keyx"))
        g.fill()
        g.model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.Existing("$ROOT/Keys/family.keyx"))
        g.making.createFailure = CreateFailure.VerificationFailed("the new file does not open")
        g.model.create()
        advanceUntilIdle()
        assertEquals("Not created: the new file did not check out (the new file does not open), so it was not kept.", g.model.problem)
        assertTrue(g.making.removed.isEmpty(), "the user's own key file is never removed")

        val h = Fixture(this)
        h.fill()
        h.making.createFailure = CreateFailure.NotEnoughMemory()
        h.model.create()
        advanceUntilIdle()
        assertEquals("Not created: this phone has not enough memory for 64 MiB: choose less on the Encryption page.", h.model.problem)
        h.model.changeEncryption(h.model.encryption.copy(memoryMiB = 32))
        assertNull(h.model.problem, "less memory chosen: the failure about the memory goes")
    }

    @Test
    fun cancelWhileMeasuringGoesBackToTheFormNothingMadeTheWritingCannotBeCancelled() = runTest {
        val f = Fixture(this, io = kotlinx.coroutines.Dispatchers.IO)
        f.fill()
        f.making.measureGate = CompletableDeferred()
        f.model.create()
        while (!f.making.measuring) {
            runCurrent()
            Thread.sleep(1)
        }
        assertTrue(f.model.canCancel)
        f.model.cancelCreating()
        f.making.measureGate?.complete(Unit)
        waitFor { f.model.phase == NewDatabaseModel.Phase.Editing }
        assertTrue(f.making.made.isEmpty() && f.created.isEmpty())
        assertTrue(f.making.notes.isEmpty(), "nothing begun, nothing noted")
        assertTrue(f.model.canCreate, "Create again")

        f.making.measureGate = null
        f.making.writeGate = CompletableDeferred()
        f.model.create()
        waitFor { f.making.writing }
        assertTrue(f.model.isWriting && !f.model.canCancel, "the writing: no Cancel")
        f.model.cancelCreating()
        f.model.close()
        f.making.writeGate?.complete(Unit)
        waitFor { f.model.phase == NewDatabaseModel.Phase.Created }
        assertEquals(1, f.created.size, "Cancel and close during the writing changed nothing")
    }

    @Test
    fun aMeasurementThatFailsIsSaidInTheMacsWords() = runTest {
        val g = Fixture(this)
        g.fill()
        val failing = object : DatabaseMaking by g.making {
            override fun measure(encryption: DatabaseEncryption, checkCancelled: () -> Unit): MeasuredEncryption = throw IllegalStateException("clock")
        }
        val m = NewDatabaseModel(failing, listOf("$ROOT/Sync/KeePass"), ROOT, g.files, true, this, StandardTestDispatcher(testScheduler), { 0 })
        m.changeName("Family")
        m.password.setTextAndPlaceCursorAtEnd("pw")
        m.again.setTextAndPlaceCursorAtEnd("pw")
        m.create()
        advanceUntilIdle()
        assertEquals("Not created: this phone’s speed could not be measured. Try again, or choose AES-KDF.", m.problem)
    }

    @Test
    fun aMeasurementStillRunningIsWaitedForNeverTwoAtOnce() = runTest {
        val f = Fixture(this, io = kotlinx.coroutines.Dispatchers.IO)
        val m = f.model
        f.making.measureGate = CompletableDeferred()
        f.making.ignoresCancel = true
        m.showPage(NewDatabaseModel.Page.ENCRYPTION)
        waitFor { f.making.measuring }
        // The first stops only between runs: the next waits for it.
        m.changeEncryption(m.encryption.copy(memoryMiB = 128))
        repeat(50) {
            runCurrent()
            Thread.sleep(2)
        }
        assertEquals(1, f.making.measured.size, "the next has not started while the first runs")
        f.making.measureGate?.complete(Unit)
        waitFor { m.measured?.encryption?.memoryMiB == 128 }
        assertEquals(2, f.making.measured.size)
        assertEquals(1, f.making.mostAtOnce, "never two at once: each would measure a slower phone")
    }

    @Test
    fun aFailedMeasurementIsSaidOnTheEncryptionPage() = runTest {
        val f = Fixture(this)
        val m = f.model
        f.making.measureFailure = CreateFailure.NotEnoughMemory()
        m.showPage(NewDatabaseModel.Page.ENCRYPTION)
        m.changeEncryption(m.encryption.copy(memoryMiB = 2047))
        advanceUntilIdle()
        assertFalse(m.isMeasuring)
        assertEquals("This phone has not enough memory for 2047 MiB: choose less.", m.measuredLine)
        f.making.measureFailure = null
        m.changeEncryption(m.encryption.copy(memoryMiB = 1024))
        assertEquals("Measuring on this phone…", m.measuredLine, "a new measurement: the failure goes")
        advanceUntilIdle()
        assertTrue("measured" in m.measuredLine, m.measuredLine)
    }

    @Test
    fun aNewKeyFileThatCannotBeRemovedStaysNotedForTheNextStart() = runTest {
        val f = Fixture(this)
        f.fill()
        f.model.chooseKeyFile(NewDatabaseModel.KeyFileChoice.New("$ROOT/Keys"))
        f.making.createFailure = CreateFailure.WriteFailed("disk full")
        f.making.removeFailure = java.io.IOException("access revoked")
        f.model.create()
        advanceUntilIdle()
        assertEquals("Not created: the file could not be written: disk full.", f.model.problem)
        assertEquals(listOf("begin Family Shared", "end"), f.making.notes, "not made: the note keeps what was not removed (FileDatabaseMakingTest)")
        assertEquals(NewDatabaseModel.Phase.Editing, f.model.phase)
    }

    @Test
    fun anUnexpectedErrorIsSaidAndCrashesNothing() = runTest {
        val f = Fixture(this)
        f.fill()
        f.making.createFailure = IllegalStateException("surprise")
        f.model.create()
        advanceUntilIdle()
        assertEquals("Not created: something went wrong (surprise).", f.model.problem)
        assertEquals(listOf("begin Family Shared", "end"), f.making.notes)
    }

    @Test
    fun aLockTakesTheFormButNotWhileCreatingCloseWaitsForTheWriting() = runTest {
        val f = Fixture(this, io = kotlinx.coroutines.Dispatchers.IO)
        val forms = NewDatabases { f.model }
        forms.open()
        f.fill()
        forms.locked()
        assertNull(forms.form, "a lock: the form and what was typed go")
        assertTrue(f.model.password.text.isEmpty() && f.model.again.text.isEmpty())

        forms.open()
        f.fill()
        f.making.writeGate = CompletableDeferred()
        f.model.create()
        waitFor { f.making.writing }
        forms.locked()
        forms.close()
        assertEquals(f.model, forms.form, "while the file is written the screen stays")
        f.making.writeGate?.complete(Unit)
        waitFor { f.model.phase == NewDatabaseModel.Phase.Created }
        forms.created(f.model)
        assertNull(forms.form)
    }

    /** Real threads (the IO dispatcher) and the test's virtual time (the page's 1 ms pause) both move on. */
    private suspend fun TestScope.waitFor(condition: () -> Boolean) {
        repeat(5_000) {
            if (condition()) return
            advanceTimeBy(1)
            runCurrent()
            Thread.sleep(1)
        }
        throw AssertionError("not reached")
    }

    private companion object {
        const val ROOT = "/storage/emulated/0"

        /** Argon2d with 1 MiB and 2 iterations: what the fake writes, whatever was measured. */
        val CHEAP = MeasuredEncryption(DatabaseEncryption(Cipher.AES256, KeyDerivation.ARGON2D, 1, 1, 0.1), units = 2, seconds = 0.01)
    }
}
