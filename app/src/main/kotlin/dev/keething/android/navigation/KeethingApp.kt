package dev.keething.android.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.keething.android.ui.resolve
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.keething.android.AppContainer
import dev.keething.android.editor.NewNote
import dev.keething.android.editor.NoteFromClipboard
import dev.keething.android.list.NewChoice
import dev.keething.android.editor.EntryEditorScreen
import dev.keething.android.entry.EntryCardScreen
import dev.keething.android.history.EntryHistoryScreen
import dev.keething.android.entry.PasswordScreen
import dev.keething.android.entry.PasswordView
import dev.keething.android.groups.GroupsScreen
import dev.keething.android.list.EntryListScreen
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.settings.SettingsScreen
import dev.keething.android.databases.ChangeMasterKeyScreen
import dev.keething.android.databases.DatabasePageScreen
import dev.keething.android.databases.NewDatabaseScreen
import dev.keething.android.databases.WelcomeScreen
import dev.keething.android.about.AboutScreen
import dev.keething.android.about.WithAboutCorner
import dev.keething.vault.databases.DatabaseId
import android.os.Environment
import dev.keething.android.databases.DatabaseText
import dev.keething.vault.creating.CreationNote
import dev.keething.vault.databases.EntryRef
import dev.keething.android.sharing.AnswerScreen
import dev.keething.android.sharing.AskScreen
import dev.keething.android.sharing.ChooseEntriesScreen
import dev.keething.android.sharing.MadeAnswer
import dev.keething.android.sharing.MyRequestScreen
import dev.keething.android.sharing.ReadCodeScreen
import dev.keething.android.sharing.ReceivedScreen
import dev.keething.android.sharing.ReviewScreen
import dev.keething.android.sharing.SentScreen
import dev.keething.android.sharing.SharingScreen
import dev.keething.android.sharing.TheirRequestScreen
import dev.keething.android.storageprobe.StorageProbeScreen
import dev.keething.android.unlock.LockScreen
import dev.keething.vault.databases.SessionsState
import dev.keething.android.editor.NewEntryDatabases
import dev.keething.android.databases.WindowDatabasesModel
import dev.keething.android.databases.WindowDatabasesScreen
import dev.keething.android.ui.Notice
import dev.keething.android.ui.uiPlain
import dev.keething.android.ui.uiText
import dev.keething.android.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.keething.android.session.isReadOnly
import dev.keething.android.list.ReadOnlyDialog
import dev.keething.android.unlock.Kdbx3QuestionDialog
import dev.keething.search.FilterCompletion
import dev.keething.search.FilterKind
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.keething.android.databases.ChooserScreen
import dev.keething.android.databases.rememberFileActions
import dev.keething.android.databases.Reach
import dev.keething.vault.session.SessionState

/**
 * The screens of the open databases. An entry's screens name it by its ref ([EntryRef.text]: the same UUID in two
 * databases is two entries) and act on it through its own database's session (keething-spec databases.one-list AC2).
 */
internal sealed interface Screen {
    /** The entry this screen is about, by ref; null for the others. */
    val ref: EntryRef? get() = null

    data object Entries : Screen

    /** [origin]: the list section the card was opened from, so the row it grows from is the right one. */
    data class Card(val entryId: String, val origin: String) : Screen {
        override val ref: EntryRef get() = EntryRef.of(entryId)
    }

    /** A password full screen; the entry's ref only (and a field's row id), the password is read from the vault. */
    data class Password(val entryId: String, val view: PasswordView, val rowId: String? = null) : Screen {
        override val ref: EntryRef get() = EntryRef.of(entryId)
    }

    /** An entry's earlier versions. */
    data class History(val entryId: String) : Screen {
        override val ref: EntryRef get() = EntryRef.of(entryId)
    }

    /**
     * The editor of an entry, or of a new one ([entryId] null) in [groupId], starting in the open [database] (its id;
     * keething-spec databases.one-list AC4): its editor may move it to another (Save into) and closes when its own locks.
     */
    data class Editor(val entryId: String?, val groupId: String? = null, val database: String? = null, val note: NewNote? = null) : Screen {
        override val ref: EntryRef? get() = entryId?.let(EntryRef::of)
    }

    /** The group tree and the tags, each with its entries' count. */
    data object Groups : Screen

    data object Settings : Screen

    /** A database's page in Settings › Databases, by its id. */
    data class Database(val id: String) : Screen

    /** ⋮ › Databases: the open ones, those not open, Recent (plan AD12). */
    data object WindowDatabases : Screen

    data object StorageProbe : Screen

    /** Sharing (SPEC §10): the requests both ways. */
    data object Sharing : Screen

    data object ShareAsk : Screen

    /** A request of mine, by its id. */
    data class ShareMine(val id: String) : Screen

    /** Reading a code; [incoming]: codes another app handed over. */
    data class ShareRead(val incoming: List<String> = emptyList()) : Screen

    /** A request someone sent; [preselected]: the entry whose card it was picked from. */
    data class ShareTheirs(val id: String, val preselected: List<String> = emptyList()) : Screen

    /** Choosing entries for their request [id], or to send without one (null). */
    data class ShareChoose(val id: String?, val preselected: List<String> = emptyList()) : Screen

    data class ShareReview(val id: String?, val entryIds: List<String>) : Screen

    /** An envelope sent from here, by its id: its code and pictures are in the book. */
    data class ShareSent(val id: String) : Screen

    /** An answer made; its request is gone already. */
    data class ShareAnswer(val answer: MadeAnswer) : Screen

    /** An answer to a request of mine, or an envelope, as text (it is sealed): its screen opens it. */
    data class ShareReceived(val codeText: String) : Screen
}

/** What the app shows over its databases (plan AD13; the Mac's `WindowScreen`); the welcome and New database aside. */
internal enum class Shown {
    /** Some database open: the list (with the chips of those not open) and the screens over it. */
    VAULT,

    /** The current database's lock screen. */
    LOCK_SCREEN,

    /** Nothing open and several in the window: which to open (keething-spec databases.chooser). */
    CHOOSER,

    /** The chooser's run: one checked database after another. */
    RUN,
}

/** Where a lock screen's Back goes: to the open databases, or to the chooser (nothing open, several in the window). */
internal enum class LockBack { VAULT, CHOOSER }

/** What survives a rotation: where the user is. Never a secret; reset by every lock. */
internal class NavigationState : ViewModel() {
    val backStack = mutableStateListOf<Screen>(Screen.Entries)

    /** The search, kept here so that the card's group and tag chips can set it. */
    val query = TextFieldState()

    /** The database shown before another was chosen on the lock screen: its Back shows it again. */
    var lockBack by mutableStateOf<DatabaseId?>(null)

    /**
     * A lock screen asked for while nothing is open (the lock screen's sheet, Open other… there or in the chooser): it
     * shows in place of the chooser while its database is current, with Back to the chooser (plan AD13). A lock forgets it.
     */
    var lockAsked by mutableStateOf<DatabaseId?>(null)

    /** What showed last ([showing]): after the chooser or the run, the vault shows on the first database that opened. */
    var lastShown = Shown.LOCK_SCREEN
        private set

    fun showing(shown: Shown) {
        lastShown = shown
        // Its database opened: locked alone later with nothing else open, the chooser shows, not its lock screen.
        if (shown == Shown.VAULT) lockAsked = null
    }

    /**
     * The vault may show for the current database's session [currentNumber]: the screens are its own already, or the
     * chooser or the run showed before (the lock that brought them reset everything): no frame of a lock screen between.
     */
    fun vaultReady(currentNumber: Int): Boolean = sessionNumber == currentNumber || lastShown == Shown.CHOOSER || lastShown == Shown.RUN

    /** A lock (the button, the timeout, a stay away past it): the plain chooser or lock screen comes, not one asked before. */
    fun locked() {
        lockAsked = null
    }

    /** The app's lock count as last seen: a screen made again (a rotation) is no lock. */
    private var seenLocks = -1

    /** The app's lock [count] now: [locked] when it changed. */
    fun followLocks(count: Int) {
        if (count == seenLocks) return
        seenLocks = count
        locked()
    }

    /** Back from a lock screen asked for with nothing open. */
    fun backToChooser() {
        lockAsked = null
    }

    /**
     * What shows (plan AD13; the Mac's `WindowScreenModel.workOut`): the run while it goes; the vault while [current] is
     * open, or while it locked alone with its vault shown and others open; with others open after the chooser or the run
     * the chooser stays until the first open one is current ([openedToShow]), else [current]'s lock screen; with nothing
     * open and several in the window ([windowSize]) the chooser, unless [current]'s lock screen was asked for or it is
     * opening by itself (a new database made: "Opening…"; not while the fingerprint opens several,
     * [fingerprintRunning]); else its lock screen. [open]: the open databases in the window's order.
     */
    fun screenFor(
        current: DatabaseId,
        currentNumber: Int,
        open: List<DatabaseId>,
        windowSize: Int,
        running: Boolean = false,
        currentOpening: Boolean = false,
        fingerprintRunning: Boolean = false,
    ): Shown = when {
        running -> Shown.RUN
        current in open -> Shown.VAULT
        open.isNotEmpty() -> when {
            shownSession == currentNumber -> Shown.VAULT
            lastShown == Shown.CHOOSER || lastShown == Shown.RUN -> Shown.CHOOSER
            else -> Shown.LOCK_SCREEN
        }
        windowSize >= 2 && lockAsked != current && !(currentOpening && !fingerprintRunning) -> Shown.CHOOSER
        else -> Shown.LOCK_SCREEN
    }

    /** The open database to make current after the chooser or the run, so the vault shows on it; null: none to. */
    fun openedToShow(current: DatabaseId, open: List<DatabaseId>): DatabaseId? =
        open.firstOrNull()?.takeIf { current !in open && (lastShown == Shown.CHOOSER || lastShown == Shown.RUN) }

    /**
     * Where [current]'s lock screen goes Back to; null: nowhere (the window's only database with nothing open, or one
     * opening by itself, a new database made: it opens in a moment).
     */
    fun lockScreenBack(current: DatabaseId, open: List<DatabaseId>, windowSize: Int): LockBack? = when {
        open.any { it != current } -> LockBack.VAULT
        windowSize >= 2 && lockAsked == current -> LockBack.CHOOSER
        else -> null
    }

    /** The Codes view (keething-spec app.codes-view, SPEC §6.2). A lock keeps it: the shortcut opens the lock screen first. */
    var codesOnly by mutableStateOf(false)

    /** The database session the screens were last for ([DatabaseSession.number]): another one starts them afresh. */
    var sessionNumber by mutableIntStateOf(0)

    /** The open databases' sessions ([DatabaseSession.number]) as the screens last saw them. */
    private var openSessions: Map<DatabaseId, Int> = emptyMap()

    /** The current database's session while its vault is shown; 0 while its lock screen is. */
    var shownSession by mutableIntStateOf(0)
        private set

    /** The current database's session [number] is open, its vault shown. */
    fun shown(number: Int) {
        shownSession = number
        lockBack = null
        asked = 0
    }

    /** The session whose lock screen was asked for from the vault (a chip, ⋮ › Databases); 0: none. */
    private var asked = 0

    /**
     * A database's lock screen asked for from the vault while others stay open (keething-spec databases.not-open AC3):
     * the screens are its session's ([number]) already, the list under its lock screen (and Groups & tags, every open
     * database's, when asked from there), the search kept for when it opens (its entries join the list).
     */
    fun ask(number: Int) {
        asked = number
        sessionNumber = number
        backStack.retainAll { it == Screen.Entries || it == Screen.Groups }
    }

    /** The current database's lock screen shows, session [number]: the screens reset, unless it was asked for and some stay open. */
    fun lockScreen(number: Int, anyOpen: Boolean) {
        if (number == asked && anyOpen) return
        asked = 0
        reset()
    }

    /**
     * The current database, its vault shown with session [number], is open no more. Locked alone (its password changed
     * elsewhere) while [open] stay open, the vault goes on with them: the search and the screens stay (Groups & tags drops
     * its block) (a new entry and a received one follow their own database, plan
     * AD11), and the result is the open database to make current, the window's first. Null when it was not shown or
     * nothing else is open: its lock screen shows.
     */
    fun lockedAlone(number: Int, open: Map<DatabaseId, Int>): DatabaseId? {
        if (number != shownSession || open.isEmpty()) {
            shownSession = 0
            return null
        }
        val (next, nextNumber) = open.entries.first()
        sessionNumber = nextNumber
        shownSession = nextNumber
        return next
    }

    /**
     * [open]: the open databases' sessions now. A database that locked alone (its password changed elsewhere), or whose
     * session is another one now, takes its entries' screens with it; the others' stay.
     */
    fun keepOpen(open: Map<DatabaseId, Int>) {
        val gone = openSessions.filter { (id, number) -> open[id] != number }.keys
        openSessions = open
        if (gone.isNotEmpty()) backStack.removeAll { it.ref?.database in gone }
    }

    /** The Codes view, from the launcher shortcut or the list's header: from the top, nothing typed. */
    fun showCodes() {
        backStack.retainAll { it == Screen.Entries }
        query.clearText()
        codesOnly = true
    }

    /** Back or the arrow in the Codes view: the whole list, nothing typed. */
    fun leaveCodes() {
        query.clearText()
        codesOnly = false
    }

    fun open(screen: Screen) {
        backStack += screen
    }

    /**
     * A card from the list: it takes the place of the card open beside the list (two panes), so that Back closes
     * it rather than going through every card looked at.
     */
    fun openCard(entryId: String, origin: String) {
        backStack.retainAll { it == Screen.Entries }
        backStack += Screen.Card(entryId, origin)
    }

    /** The card open beside the list, whose row the list marks. */
    val cardBesideList: String?
        get() = (backStack.getOrNull(1) as? Screen.Card)?.takeIf { backStack.size == 2 }?.entryId

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** The screen on top gives way to [screen] (a new entry saved: its card instead of the editor). */
    fun replace(screen: Screen) {
        if (backStack.size > 1) backStack[backStack.lastIndex] = screen else backStack += screen
    }

    /** The card's group or tag chip, a row of Groups & tags: back to the list, searching for [filter]. */
    fun search(filter: String) {
        backStack.retainAll { it == Screen.Entries }
        codesOnly = false
        query.edit { replace(0, length, filter) }
    }

    /**
     * [screen] leaves the stack, wherever it is: a screen whose request went (answered, deleted, expired) closes
     * itself, also while it is still drawn on its way out under the next one, which a plain [back] would close.
     */
    fun close(screen: Screen) {
        if (backStack.size > 1) backStack.remove(screen)
    }

    /** Out of a sharing flow that ended (an answer made, entries sent): back to Sharing, or to the card it began on. */
    fun leaveSharing() {
        while (backStack.size > 1 && backStack.last() !is Screen.Sharing && backStack.last() !is Screen.Card) backStack.removeAt(backStack.lastIndex)
    }

    /** A lock: nothing opened or typed lingers. */
    fun reset() {
        backStack.retainAll { it == Screen.Entries }
        query.clearText()
    }
}

internal val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The welcome (no database yet), the lock screen, or the vault's screens (SPEC §6.2). */
@Composable
internal fun KeethingApp(container: AppContainer) {
    val navigation: NavigationState = viewModel()
    val database by container.sessions.current.collectAsStateWithLifecycle()
    val list by container.sessions.list.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        container.newDatabases.notCreated?.let { left -> NotCreatedDialog(left) { container.newDatabases.notCreated = null } }
        if (container.unreadableSaves > 0) UnreadableSavesDialog(container.unreadableSaves, container::unreadableSavesSaid)
        // New database, over whatever was shown (the welcome, a lock screen, Settings): its Create outlives the screen.
        container.newDatabases.form?.let { form ->
            NewDatabaseScreen(form, onClose = container.newDatabases::close)
            return@Surface
        }
        // Change master key…, over its database's page: its change outlives the screen.
        container.masterKeys.form?.let { form ->
            ChangeMasterKeyScreen(form, onClose = container.masterKeys::close)
            return@Surface
        }
        // About over whatever was shown (Settings, a lock screen, the welcome), back to it.
        if (container.about.shown) {
            AboutScreen(onClose = { container.about.shown = false })
            return@Surface
        }
        val openAbout = { container.about.shown = true }
        val current = database
        if (current == null) {
            WithAboutCorner(openAbout) { WelcomeScreen(container) }
            return@Surface
        }
        val state by container.sessions.state.collectAsStateWithLifecycle()
        val openIds = (state as? SessionsState.Open)?.vaults?.map { it.id }.orEmpty()
        val run by container.chooser.run.collectAsStateWithLifecycle()
        val asked by container.chooser.asked.collectAsStateWithLifecycle()
        val visit by container.sessions.visit.collectAsStateWithLifecycle()
        val fingerprintRunning by container.sessions.windowFingerprint.running.collectAsStateWithLifecycle()
        val currentState by current.session.state.collectAsStateWithLifecycle()
        val lockCount by container.sessions.locks.counted.collectAsStateWithLifecycle()
        // A lock forgets the lock screen asked: the plain chooser or lock screen comes.
        LaunchedEffect(lockCount) { navigation.followLocks(lockCount) }
        // The app's state, not one session's: nothing open clears the screens, also while another database still opens
        // (keething-spec databases.several-open AC10). The vault's screens show while the current database is open; its
        // lock screen shows otherwise, the others staying open behind it; with nothing open and several in the window,
        // the chooser, and its run while it goes (plan AD13).
        val unlocked = current.id in openIds
        val openSessions = openIds.mapNotNull { id -> container.sessions.session(id)?.let { id to it.number } }.toMap()
        val shown = navigation.screenFor(
            current = current.id,
            currentNumber = current.number,
            open = openIds,
            windowSize = list.window.size,
            running = run != null,
            currentOpening = currentState == SessionState.Unlocking,
            fingerprintRunning = fingerprintRunning,
        )
        LaunchedEffect(shown) { navigation.showing(shown) }
        // A visit that begins on the run uses its one fingerprint offer: Lock all or the run's end brings no prompt.
        LaunchedEffect(visit, shown == Shown.RUN) { if (shown == Shown.RUN) container.chooser.showingRun(visit) }
        // The chooser's fingerprint, once in a visit that began locked, while the chooser is what shows: decided here,
        // not by the chooser's screen, which stays a moment while it animates away (back from Open other…'s picker,
        // a new visit, its file shows its lock screen in the chooser's place: found on the phone).
        LaunchedEffect(visit, shown == Shown.CHOOSER) {
            if (shown == Shown.CHOOSER) container.chooser.offer(visit) else container.chooser.left()
        }
        // After the chooser or the run: the vault on the first database that opened.
        LaunchedEffect(shown, openIds) { navigation.openedToShow(current.id, openIds)?.let(container.sessions.databases::choose) }
        // Another database's session (a new database made from Settings opens at once, its lock never shown here):
        // nothing of the screens of before goes over to it.
        LaunchedEffect(current.number) {
            if (navigation.sessionNumber != current.number) {
                navigation.sessionNumber = current.number
                navigation.reset()
            }
        }
        LaunchedEffect(unlocked, openSessions, current.number) {
            when {
                unlocked -> navigation.shown(current.number)
                else -> navigation.lockedAlone(current.number, openSessions)?.let(container.sessions.databases::choose)
                    ?: navigation.lockScreen(current.number, anyOpen = openSessions.isNotEmpty())
            }
        }
        // One database locked alone while others stay open: its entries' screens go.
        LaunchedEffect(openSessions) { navigation.keepOpen(openSessions) }
        // Back to the open databases (the one shown before while it is open), or to the chooser.
        val back = when (navigation.lockScreenBack(current.id, openIds, list.window.size)) {
            LockBack.VAULT -> {
                val previous = navigation.lockBack?.takeIf { it != current.id && it in openIds } ?: openIds.first { it != current.id }
                val going = {
                    navigation.lockBack = null
                    // The screens are this one's already (reset when its lock screen showed): no frame of the lock screen again.
                    container.sessions.session(previous)?.let { session -> navigation.sessionNumber = session.number }
                    container.sessions.databases.choose(previous)
                }
                going
            }
            LockBack.CHOOSER -> navigation::backToChooser
            null -> null
        }
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val grow = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            // Not before the screens are reset for this session (above): never a frame of another database's.
            targetState = if (shown == Shown.VAULT && !navigation.vaultReady(current.number)) Shown.LOCK_SCREEN else shown,
            transitionSpec = { (fadeIn(enter) + scaleIn(grow, initialScale = 0.94f)) togetherWith fadeOut(exit) },
            label = "lock",
        ) { showing ->
            when (showing) {
                Shown.VAULT -> VaultScreens(container, current, navigation)
                Shown.LOCK_SCREEN -> WithAboutCorner(openAbout) {
                    LockScreen(
                        container = container,
                        database = current,
                        onBack = back,
                        onChose = { shown ->
                            // Nothing open: its lock screen in place of the chooser, Back to it; else Back to the one before.
                            if (openIds.isEmpty()) {
                                navigation.lockAsked = container.sessions.list.currentId
                            } else if (navigation.lockBack == null) {
                                navigation.lockBack = shown
                            }
                        },
                    )
                }
                Shown.CHOOSER -> WithAboutCorner(openAbout) { Chooser(container, navigation) }
                // The database the run asks, followed as it moves on (a Skip), with that run; each its own screen.
                Shown.RUN -> asked?.let { (askedRun, id) ->
                    container.sessions.session(id)?.let { database ->
                        key(database.number) { LockScreen(container, database, onBack = null, onChose = {}, run = askedRun) }
                    }
                }
            }
        }
    }
}

/** The chooser with its sheets: Open other… and Locate… (a folder, then its files), New database (plan AD13). */
@Composable
private fun Chooser(container: AppContainer, navigation: NavigationState) {
    val sessions = container.sessions
    val chooser = container.chooser
    val windowModel = viewModel(key = "window") { WindowDatabasesModel(sessions.list, sessions.window, chooser.skipped, sessions.tried, container.databaseFiles) }
    val rows by windowModel.chooser.collectAsStateWithLifecycle()
    val note by chooser.note.collectAsStateWithLifecycle()
    val busy by chooser.busy.collectAsStateWithLifecycle()
    val refreshes = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        windowModel.refresh()
        refreshes.launch { sessions.refreshWindowFingerprints() }
        onPauseOrDispose {}
    }
    val context = LocalContext.current
    val resources = LocalResources.current
    // Open other…: its lock screen in place of the chooser, Back to it. What Locate… refused is said.
    val files = rememberFileActions(
        container,
        onOpened = { navigation.lockAsked = it },
        onProblem = { problem -> problem?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } },
    )
    ChooserScreen(
        rows = rows,
        note = note,
        busy = busy,
        onToggle = chooser::toggle,
        onLocate = { id -> files.locate(id, rows?.firstOrNull { it.id == id }?.line?.reach == Reach.NO_ACCESS) },
        onUnlock = chooser::start,
        onFingerprint = chooser::unlockWithFingerprint,
        onOpenOther = files.open,
        onNewDatabase = container.newDatabases::open,
        onRemove = { id ->
            sessions.remove(id) { Toast.makeText(context.applicationContext, resources.getString(R.string.navigation_stored_password_not_deleted, it), Toast.LENGTH_LONG).show() }
        },
    )
}

/** What a process that ended while creating left was removed at this start (plan AD7). */
@Composable
private fun NotCreatedDialog(left: CreationNote.Leftover, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (left.name.isEmpty()) stringResource(R.string.navigation_not_created_title_unnamed)
                else stringResource(R.string.navigation_not_created_title, left.name),
            )
        },
        text = {
            Text(
                if (left.kept.isEmpty()) {
                    stringResource(R.string.navigation_not_created_nothing_kept)
                } else {
                    val separator = stringResource(R.string.list_separator)
                    stringResource(
                        R.string.navigation_not_created_left,
                        left.kept.joinToString(separator) { DatabaseText.pathOf(it, Environment.getExternalStorageDirectory().path) },
                    )
                },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.navigation_ok)) } },
    )
}

/**
 * Stopped saves whose notes this version could not read (keething-spec app.upgrade-safety AC3): they may have been cut
 * short, and nothing says which database they were of.
 */
@Composable
private fun UnreadableSavesDialog(count: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.navigation_unreadable_saves_title, count, count)) },
        text = { Text(pluralStringResource(R.plurals.navigation_unreadable_saves_text, count, count)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.navigation_ok)) } },
    )
}

/**
 * The open databases' screens: one list over them; an entry's screens on its own database ([Screen.ref]); a new entry
 * and a received one in the open database chosen for them (plan AD11); Groups & tags over every open one; Settings with
 * the [current] database.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun VaultScreens(container: AppContainer, current: DatabaseSession, navigation: NavigationState) {
    val sessions = container.sessions
    val open by sessions.open.collectAsStateWithLifecycle()
    // The window's databases not open and ⋮ › Databases (plan AD12).
    val windowModel = viewModel(key = "window") { WindowDatabasesModel(sessions.list, sessions.window, container.chooser.skipped, sessions.tried, container.databaseFiles) }
    val overview by windowModel.overview.collectAsStateWithLifecycle()
    val refreshes = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        windowModel.refresh()
        refreshes.launch { sessions.refreshWindowFingerprints() }
        onPauseOrDispose {}
    }
    val databases = sessions.databases
    // A database's lock screen with Back, the search kept for when it opens (keething-spec databases.not-open AC3).
    val showLockScreen = { id: DatabaseId ->
        sessions.session(id)?.let { session ->
            navigation.lockBack = current.id
            navigation.ask(session.number)
            databases.choose(id)
        }
    }
    // Close (keething-spec databases.not-open AC4, several-open AC4): its Undo puts it back, locked. The current one
    // closed, the next open one is shown without resetting the screens.
    val closeDatabase = { id: DatabaseId ->
        val label = overview?.let { o -> (o.open.firstOrNull { it.id == id } ?: o.notOpen.firstOrNull { it.id == id }?.line)?.label?.full }
        if (databases.close(id)) {
            sessions.current.value?.takeIf { id == current.id }?.let { navigation.sessionNumber = it.number }
            container.windowNotices.trySend(
                Notice(
                    label?.let { uiText(R.string.navigation_closed, uiPlain(it)) } ?: uiText(R.string.navigation_closed_unnamed),
                    undo = { databases.putBack(id) },
                ),
            )
        }
    }
    // An entry's database by its ref; null once it is not open (its screens go: NavigationState.keepOpen).
    val databaseOf = { ref: EntryRef -> sessions.session(ref.database) }
    // An entry of a database read-only is never edited: KDBX 3.1 asks its question again (keething-spec kdbx.kdbx3 AC6),
    // one too large to save says so (app.large-database AC3).
    val edit: (String) -> Unit = { id ->
        val database = databaseOf(EntryRef.of(id))
        if (database?.isReadOnly == true) {
            sessions.readOnlyRefusal(database)
        } else {
            navigation.open(Screen.Editor(id))
        }
    }
    // A KDBX 3.1 database's question over the list (AC5, AC6): opened without its lock screen's, or a change tried.
    val kdbx3Asking by sessions.kdbx3.asking.collectAsStateWithLifecycle()
    kdbx3Asking?.let { asked ->
        val name = sessions.list.record(asked.id)?.displayName ?: asked.location.fileName
        Kdbx3QuestionDialog(name) { upgrade -> sessions.kdbx3.answer(asked, upgrade) }
    }
    // A change tried on a database too large to save, from any screen (keething-spec app.large-database AC3).
    val readOnlySaid by sessions.readOnlySaid.collectAsStateWithLifecycle()
    readOnlySaid?.let { text -> ReadOnlyDialog(text.resolve(), onOk = sessions::readOnlySaidOk) }
    // Codes another app handed over (SPEC §10) are read once the vault is open.
    val incoming by container.incomingCodes.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        incoming?.let { codes ->
            container.incomingCodes.value = null
            // One picture after another from a messenger: the screen reading them is replaced, not stacked.
            if (navigation.backStack.last() is Screen.ShareRead) navigation.replace(Screen.ShareRead(codes)) else navigation.open(Screen.ShareRead(codes))
        }
    }
    // Two panes from 840 dp (a phone in landscape too): the list beside the card; everything else over the whole window.
    // Back closes the card first, then leaves the list.
    val panes = rememberListDetailSceneStrategy<Screen>(
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
        directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()).copy(horizontalPartitionSpacerSize = 8.dp),
    )
    val listWidth = if (LocalConfiguration.current.screenWidthDp >= 1000) 400.dp else 360.dp
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val fade: () -> ContentTransform = { fadeIn(enter) togetherWith fadeOut(exit) }
            NavDisplay(
                backStack = navigation.backStack,
                onBack = navigation::back,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                sceneStrategies = listOf(panes),
                sharedTransitionScope = this,
                transitionSpec = { fade() },
                popTransitionSpec = { fade() },
                predictivePopTransitionSpec = { fadeIn(enter) togetherWith (fadeOut(enter) + scaleOut(targetScale = 0.9f)) },
                entryProvider = entryProvider {
                    entry<Screen.Entries>(
                        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoEntryChosen() }) +
                            ListDetailSceneStrategy.preferredPaneSize(width = listWidth),
                    ) {
                        ListPane {
                            EntryListScreen(
                                container = container,
                                query = navigation.query,
                                codesOnly = navigation.codesOnly,
                                selectedId = navigation.cardBesideList,
                                onOpen = navigation::openCard,
                                onEdit = edit,
                                onSettings = { navigation.open(Screen.Settings) },
                                onGroups = { navigation.open(Screen.Groups) },
                                onCodes = navigation::showCodes,
                                onLeaveCodes = navigation::leaveCodes,
                                notOpen = overview?.attention.orEmpty(),
                                readOnly = overview?.readOnly.orEmpty(),
                                openCount = open.size,
                                canClose = overview?.canClose == true,
                                onLockScreen = { showLockScreen(it) },
                                onCloseDatabase = { closeDatabase(it) },
                                onDatabases = { navigation.open(Screen.WindowDatabases) },
                                onNew = onNew@{ choice ->
                                    val places = sessions.newEntryDatabases(open)
                                    // Only databases read-only are open: a new entry has nowhere to go.
                                    if (places.isEmpty() && open.isNotEmpty()) {
                                        sessions.readOnlyRefusal(open.first().database)
                                        return@onNew
                                    }
                                    // The database the search names, else the card beside the list's, else the last new entry's.
                                    val start = NewEntryDatabases.start(
                                        places,
                                        navigation.query.text.toString(),
                                        navigation.cardBesideList?.let(EntryRef::of),
                                        container.settings.lastNewEntryDatabase,
                                    )
                                    val database = (start ?: current.id).value
                                    when (choice) {
                                        NewChoice.ENTRY -> navigation.open(Screen.Editor(entryId = null, database = database))
                                        NewChoice.NOTE -> navigation.open(Screen.Editor(entryId = null, database = database, note = NewNote.EMPTY))
                                        // Read once, on the tap (the app in front); no text opens nothing (app.quick-note AC6, AC7).
                                        NewChoice.NOTE_FROM_CLIPBOARD -> when (val text = NoteFromClipboard.read(container.systemClipboard)) {
                                            null -> container.windowNotices.trySend(Notice(NoteFromClipboard.NO_TEXT))
                                            else -> navigation.open(Screen.Editor(entryId = null, database = database, note = NewNote(text)))
                                        }
                                    }
                                },
                                onSharing = { navigation.open(Screen.Sharing) },
                            )
                        }
                    }
                    // A card from Sharing ("received") is not beside the list: the list is not under it.
                    entry<Screen.Card>(metadata = { key -> if (key.origin == "received") emptyMap() else ListDetailSceneStrategy.detailPane() }) { key ->
                        val database = databaseOf(key.ref) ?: return@entry
                        // Its database's chip while two or more are open (keething-spec databases.name-colour AC6).
                        val mark = open.takeIf { it.size >= 2 }?.firstOrNull { it.database.id == key.ref.database }?.mark
                        CardPane {
                            EntryCardScreen(
                                container = container,
                                database = database,
                                ref = key.ref,
                                mark = mark,
                                origin = key.origin,
                                onBack = { navigation.close(key) },
                                onSearch = navigation::search,
                                onShowPassword = { view -> navigation.open(Screen.Password(key.entryId, view)) },
                                onShowAddress = { rowId -> navigation.open(Screen.Password(key.entryId, PasswordView.ADDRESS_QR, rowId)) },
                                onEdit = { edit(key.entryId) },
                                onHistory = { navigation.open(Screen.History(key.entryId)) },
                                onSendTo = { their ->
                                    val chosen = listOf(key.entryId)
                                    navigation.open(if (their.checked) Screen.ShareReview(their.request.id, chosen) else Screen.ShareTheirs(their.request.id, chosen))
                                },
                                onReadRequest = { navigation.open(Screen.ShareRead()) },
                                onSendWithout = { navigation.open(Screen.ShareReview(null, listOf(key.entryId))) },
                            )
                        }
                    }
                    entry<Screen.History> { key ->
                        val database = databaseOf(key.ref) ?: return@entry
                        EntryHistoryScreen(
                            database,
                            key.ref.entry,
                            onBack = { navigation.close(key) },
                            // Back on the card, which shows the version now, saying so (edit.history-restore; the canvas).
                            onRestored = { notice ->
                                container.versionRestored(notice)
                                navigation.close(key)
                            },
                        )
                    }
                    entry<Screen.Editor> { key ->
                        val database = key.ref?.let { databaseOf(it) ?: return@entry }
                            ?: key.database?.let { sessions.session(DatabaseId(it)) }
                            ?: current
                        EntryEditorScreen(
                            database = database,
                            entryId = key.ref?.entry,
                            groupId = key.groupId,
                            generator = container::passwordGenerator,
                            onClose = { navigation.close(key) },
                            onSaved = { saved ->
                                container.entrySaved(saved.deletedGroups, saved.mayNotSync)
                                if (key.entryId == null) navigation.replace(Screen.Card(saved.ref.text, origin = "new")) else navigation.back()
                            },
                            places = if (key.entryId == null) remember { container.newEntryPlaces() } else null,
                            note = key.note,
                            copySecret = { container.clipboard.copy(it, sensitive = true) },
                        )
                    }
                    entry<Screen.Password> { key ->
                        val database = databaseOf(key.ref) ?: return@entry
                        PasswordScreen(database, key.ref.entry, key.view, key.rowId, container.clipboard, onBack = { navigation.close(key) })
                    }
                    entry<Screen.Groups> {
                        GroupsScreen(
                            open = sessions.open,
                            notOpen = overview?.notOpen.orEmpty(),
                            folded = container.foldedGroupBlocks,
                            onBack = navigation::back,
                            onSearch = navigation::search,
                            onLockScreen = { showLockScreen(it) },
                            databases = sessions::newEntryDatabases,
                            onReadOnly = sessions::readOnlyRefusal,
                        )
                    }
                    entry<Screen.Settings> {
                        SettingsScreen(
                            container = container,
                            database = current,
                            onBack = navigation::back,
                            onDatabase = { id -> navigation.open(Screen.Database(id.value)) },
                            onStorageProbe = { navigation.open(Screen.StorageProbe) },
                        )
                    }
                    entry<Screen.WindowDatabases> {
                        WindowDatabasesScreen(
                            container = container,
                            overview = overview,
                            onBack = navigation::back,
                            onLockScreen = { showLockScreen(it) },
                            onClose = { closeDatabase(it) },
                            onShowOnly = { label -> navigation.search(FilterCompletion.token(FilterKind.DATABASE, label) + " ") },
                            onOpened = { id ->
                                sessions.session(id)?.let {
                                    navigation.lockBack = current.id
                                    navigation.ask(it.number)
                                }
                            },
                        )
                    }
                    entry<Screen.Database> { key ->
                        DatabasePageScreen(container, DatabaseId(key.id), onBack = { navigation.close(key) })
                    }
                    entry<Screen.StorageProbe> { StorageProbeScreen(container) }
                    entry<Screen.Sharing> {
                        SharingScreen(
                            container = container,
                            onBack = navigation::back,
                            onTheirs = { navigation.open(Screen.ShareTheirs(it)) },
                            onMine = { navigation.open(Screen.ShareMine(it)) },
                            onSent = { navigation.open(Screen.ShareSent(it)) },
                            onRead = { navigation.open(Screen.ShareRead()) },
                            onAsk = { navigation.open(Screen.ShareAsk) },
                            onSend = { navigation.open(Screen.ShareChoose(null)) },
                        )
                    }
                    entry<Screen.ShareAsk> {
                        AskScreen(container, onClose = navigation::back, onCreated = { navigation.replace(Screen.ShareMine(it)) })
                    }
                    entry<Screen.ShareMine> { key -> MyRequestScreen(container, key.id, onClose = { navigation.close(key) }) }
                    entry<Screen.ShareRead> { key ->
                        ReadCodeScreen(
                            container = container,
                            incoming = key.incoming,
                            onClose = navigation::back,
                            onTheirs = { navigation.replace(Screen.ShareTheirs(it)) },
                            onSealed = { navigation.replace(Screen.ShareReceived(it)) },
                        )
                    }
                    entry<Screen.ShareTheirs> { key ->
                        TheirRequestScreen(container, key.id, onBack = { navigation.close(key) }, onChoose = { navigation.open(Screen.ShareChoose(key.id, key.preselected)) })
                    }
                    entry<Screen.ShareChoose> { key ->
                        ChooseEntriesScreen(
                            container = container,
                            id = key.id,
                            preselected = key.preselected,
                            onClose = { navigation.close(key) },
                            onNext = { navigation.open(Screen.ShareReview(key.id, it)) },
                        )
                    }
                    entry<Screen.ShareReview> { key ->
                        ReviewScreen(
                            container,
                            key.id,
                            key.entryIds,
                            onBack = { navigation.close(key) },
                            onMade = { navigation.replace(Screen.ShareAnswer(it)) },
                            onSent = { navigation.replace(Screen.ShareSent(it)) },
                        )
                    }
                    entry<Screen.ShareSent> { key -> SentScreen(container, key.id, onDone = navigation::leaveSharing) }
                    entry<Screen.ShareAnswer> { key -> AnswerScreen(container, key.answer, onDone = navigation::leaveSharing) }
                    entry<Screen.ShareReceived> { key ->
                        // The entries go into the database chosen there (keething-spec sharing.receiving AC8).
                        ReceivedScreen(
                            container = container,
                            codeText = key.codeText,
                            onClose = navigation::back,
                            onOpenEntry = { ref -> navigation.replace(Screen.Card(ref.text, origin = "received")) },
                        )
                    }
                },
            )
        }
    }
}
