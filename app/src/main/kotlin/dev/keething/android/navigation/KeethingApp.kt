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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
import dev.keething.android.editor.EntryEditorScreen
import dev.keething.android.entry.EntryCardScreen
import dev.keething.android.history.EntryHistoryScreen
import dev.keething.android.entry.PasswordScreen
import dev.keething.android.entry.PasswordView
import dev.keething.android.groups.GroupsScreen
import dev.keething.android.list.EntryListScreen
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.settings.SettingsScreen
import dev.keething.android.databases.DatabasePageScreen
import dev.keething.android.databases.NewDatabaseScreen
import dev.keething.android.databases.WelcomeScreen
import dev.keething.vault.databases.DatabaseId
import dev.keething.vault.databases.EntryRef
import dev.keething.android.sharing.AnswerScreen
import dev.keething.android.storage.CreationNote
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
    data class Editor(val entryId: String?, val groupId: String? = null, val database: String? = null) : Screen {
        override val ref: EntryRef? get() = entryId?.let(EntryRef::of)
    }

    /** The group tree and the tags, each with its entries' count. */
    data object Groups : Screen

    data object Settings : Screen

    /** A database's page in Settings › Databases, by its id. */
    data class Database(val id: String) : Screen

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

/** What survives a rotation: where the user is. Never a secret; reset by every lock. */
internal class NavigationState : ViewModel() {
    val backStack = mutableStateListOf<Screen>(Screen.Entries)

    /** The search, kept here so that the card's group and tag chips can set it. */
    val query = TextFieldState()

    /** The database shown before another was chosen on the lock screen: its Back shows it again. */
    var lockBack by mutableStateOf<DatabaseId?>(null)

    /** The "Codes" shortcut's list (SPEC §6.2). A lock keeps it: the shortcut opens the lock screen first. */
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
    }

    /**
     * The current database, its vault shown with session [number], is open no more. Locked alone (its password changed
     * elsewhere) while [open] stay open, the vault goes on with them: the current database's own screen (Groups & tags)
     * goes, the search and the others' screens stay (a new entry and a received one follow their own database, plan
     * AD11), and the result is the open database to make current, the window's first. Null when it was not shown or
     * nothing else is open: its lock screen shows.
     */
    fun lockedAlone(number: Int, open: Map<DatabaseId, Int>): DatabaseId? {
        if (number != shownSession || open.isEmpty()) {
            shownSession = 0
            return null
        }
        val (next, nextNumber) = open.entries.first()
        backStack.removeAll { it is Screen.Groups }
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

    /** The "Codes" launcher shortcut: the list of entries with a code, from the top. */
    fun showCodes() {
        backStack.retainAll { it == Screen.Entries }
        query.clearText()
        codesOnly = true
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
        // New database, over whatever was shown (the welcome, a lock screen, Settings): its Create outlives the screen.
        container.newDatabases.form?.let { form ->
            NewDatabaseScreen(form, onClose = container.newDatabases::close)
            return@Surface
        }
        val current = database
        if (current == null) {
            WelcomeScreen(container)
            return@Surface
        }
        val state by container.sessions.state.collectAsStateWithLifecycle()
        val openIds = (state as? SessionsState.Open)?.vaults?.map { it.id }.orEmpty()
        // The app's state, not one session's: nothing open clears the screens, also while another database still opens
        // (keething-spec databases.several-open AC10). The vault's screens show while the current database is open; its
        // lock screen shows otherwise, the others staying open behind it.
        val unlocked = current.id in openIds
        val openSessions = openIds.mapNotNull { id -> container.sessions.session(id)?.let { id to it.number } }.toMap()
        // The current database locked alone while its vault showed: the vault stays, on the open ones (below).
        val stays = !unlocked && navigation.shownSession == current.number && openSessions.isNotEmpty()
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
                else -> navigation.lockedAlone(current.number, openSessions)?.let(container.sessions.databases::choose) ?: navigation.reset()
            }
        }
        // One database locked alone while others stay open: its entries' screens go.
        LaunchedEffect(openSessions) { navigation.keepOpen(openSessions) }
        // Back to the database shown before, while it is in the list and another is shown; else to the open ones.
        val previous = navigation.lockBack?.takeIf { it != current.id && list.record(it) != null } ?: openIds.firstOrNull { it != current.id }
        val back = previous?.let {
            {
                navigation.lockBack = null
                // The screens are this one's already (reset when its lock screen showed): no frame of the lock screen again.
                container.sessions.session(it)?.let { session -> navigation.sessionNumber = session.number }
                container.sessions.databases.choose(it)
            }
        }
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val grow = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            // Not before the screens are reset for this session (above): never a frame of another database's.
            targetState = (unlocked || stays) && navigation.sessionNumber == current.number,
            transitionSpec = { (fadeIn(enter) + scaleIn(grow, initialScale = 0.94f)) togetherWith fadeOut(exit) },
            label = "lock",
        ) { showVault ->
            if (showVault) {
                VaultScreens(container, current, navigation)
            } else {
                LockScreen(container, current, onBack = back, onChose = { shown -> if (navigation.lockBack == null) navigation.lockBack = shown })
            }
        }
    }
}

/** What a process that ended while creating left was removed at this start (plan AD7). */
@Composable
private fun NotCreatedDialog(left: CreationNote.Leftover, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (left.name.isEmpty()) "The new database was not created" else "“${left.name}” was not created") },
        text = {
            Text(
                if (left.kept.isEmpty()) {
                    "Keething was closed while writing it, so nothing of it was kept. Create it again."
                } else {
                    "Keething was closed while writing it. This may be left of it, written in part: " +
                        left.kept.joinToString(", ") + ". Delete it if it is not yours, then create the database again."
                },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * The open databases' screens: one list over them; an entry's screens on its own database ([Screen.ref]); a new entry
 * and a received one in the open database chosen for them (plan AD11); Groups & tags and Settings with the [current]
 * database.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun VaultScreens(container: AppContainer, current: DatabaseSession, navigation: NavigationState) {
    val sessions = container.sessions
    val open by sessions.open.collectAsStateWithLifecycle()
    // An entry's database by its ref; null once it is not open (its screens go: NavigationState.keepOpen).
    val databaseOf = { ref: EntryRef -> sessions.session(ref.database) }
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
                                onEdit = { id -> navigation.open(Screen.Editor(id)) },
                                onSettings = { navigation.open(Screen.Settings) },
                                onGroups = { navigation.open(Screen.Groups) },
                                onLeaveCodes = { navigation.codesOnly = false },
                                onNewEntry = {
                                    // The database the search names, else the card beside the list's, else the last new entry's.
                                    val start = NewEntryDatabases.start(
                                        sessions.newEntryDatabases(open),
                                        navigation.query.text.toString(),
                                        navigation.cardBesideList?.let(EntryRef::of),
                                        container.settings.lastNewEntryDatabase,
                                    )
                                    navigation.open(Screen.Editor(entryId = null, database = (start ?: current.id).value))
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
                                onEdit = { navigation.open(Screen.Editor(key.entryId)) },
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
                        EntryHistoryScreen(database, key.ref.entry, onBack = { navigation.close(key) })
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
                            onSaved = { ref ->
                                container.entrySaved()
                                if (key.entryId == null) navigation.replace(Screen.Card(ref.text, origin = "new")) else navigation.back()
                            },
                            places = if (key.entryId == null) remember { container.newEntryPlaces() } else null,
                        )
                    }
                    entry<Screen.Password> { key ->
                        val database = databaseOf(key.ref) ?: return@entry
                        PasswordScreen(database, key.ref.entry, key.view, key.rowId, container.clipboard, onBack = { navigation.close(key) })
                    }
                    entry<Screen.Groups> {
                        // The current database's: with two or more open its label says which.
                        val label = open.takeIf { it.size >= 2 }?.firstOrNull { it.database.id == current.id }?.mark?.label?.full
                        GroupsScreen(current, label, onBack = navigation::back, onSearch = navigation::search)
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
