package dev.keepassbar.android.navigation

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.keepassbar.android.AppContainer
import dev.keepassbar.android.editor.EntryEditorScreen
import dev.keepassbar.android.entry.EntryCardScreen
import dev.keepassbar.android.history.EntryHistoryScreen
import dev.keepassbar.android.entry.PasswordScreen
import dev.keepassbar.android.entry.PasswordView
import dev.keepassbar.android.groups.GroupsScreen
import dev.keepassbar.android.list.EntryListScreen
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.settings.SettingsScreen
import dev.keepassbar.android.sharing.AnswerScreen
import dev.keepassbar.android.sharing.AskScreen
import dev.keepassbar.android.sharing.ChooseEntriesScreen
import dev.keepassbar.android.sharing.MadeAnswer
import dev.keepassbar.android.sharing.MyRequestScreen
import dev.keepassbar.android.sharing.ReadCodeScreen
import dev.keepassbar.android.sharing.ReceivedScreen
import dev.keepassbar.android.sharing.ReviewScreen
import dev.keepassbar.android.sharing.SharingScreen
import dev.keepassbar.android.sharing.TheirRequestScreen
import dev.keepassbar.android.setup.SetupScreen
import dev.keepassbar.android.storageprobe.StorageProbeScreen
import dev.keepassbar.android.unlock.LockScreen
import dev.keepassbar.vault.session.SessionState

/** The screens of the unlocked vault. */
internal sealed interface Screen {
    data object Entries : Screen

    /** [origin]: the list section the card was opened from, so the row it grows from is the right one. */
    data class Card(val entryId: String, val origin: String) : Screen

    /** A password full screen; the entry's id only, the password is read from the vault. */
    data class Password(val entryId: String, val view: PasswordView) : Screen

    /** An entry's earlier versions. */
    data class History(val entryId: String) : Screen

    /** The editor of an entry, or of a new one ([entryId] null) in [groupId]. */
    data class Editor(val entryId: String?, val groupId: String? = null) : Screen

    /** The group tree and the tags, each with its entries' count. */
    data object Groups : Screen

    data object Settings : Screen

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

    data class ShareChoose(val id: String, val preselected: List<String> = emptyList()) : Screen

    data class ShareReview(val id: String, val entryIds: List<String>) : Screen

    /** An answer made; its request is gone already. */
    data class ShareAnswer(val answer: MadeAnswer) : Screen

    /** An answer to a request of mine, as text (it is sealed): its screen opens it. */
    data class ShareReceived(val answerText: String) : Screen
}

/** What survives a rotation: where the user is. Never a secret; reset by every lock. */
internal class NavigationState : ViewModel() {
    val backStack = mutableStateListOf<Screen>(Screen.Entries)

    /** The search, kept here so that the card's group and tag chips can set it. */
    val query = TextFieldState()

    var choosingDatabase by mutableStateOf(false)

    /** The "Codes" shortcut's list (SPEC §6.2). A lock keeps it: the shortcut opens the lock screen first. */
    var codesOnly by mutableStateOf(false)

    /** The "Codes" launcher shortcut: the list of entries with a code, from the top. */
    fun showCodes() {
        backStack.retainAll { it == Screen.Entries }
        query.clearText()
        codesOnly = true
    }

    fun open(screen: Screen) {
        backStack += screen
    }

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

    /** Out of a sharing flow that ended (an answer made): back to Sharing, or to the card it began on. */
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

/** Choosing the database, the lock screen, or the vault's screens (SPEC §6.2). */
@Composable
internal fun KeePassBarApp(container: AppContainer) {
    val navigation: NavigationState = viewModel()
    val database by container.sessions.current.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val current = database
        if (current == null || navigation.choosingDatabase) {
            SetupScreen(
                container = container,
                onChosen = { navigation.choosingDatabase = false },
                onBack = if (current != null) ({ navigation.choosingDatabase = false }) else null,
            )
            return@Surface
        }
        val state by current.session.state.collectAsStateWithLifecycle()
        val unlocked = state is SessionState.Unlocked
        LaunchedEffect(unlocked) { if (!unlocked) navigation.reset() }
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val grow = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = unlocked,
            transitionSpec = { (fadeIn(enter) + scaleIn(grow, initialScale = 0.94f)) togetherWith fadeOut(exit) },
            label = "lock",
        ) { showVault ->
            if (showVault) {
                VaultScreens(container, current, navigation)
            } else {
                LockScreen(container, current, onChooseDatabase = { navigation.choosingDatabase = true })
            }
        }
    }
}

@Composable
private fun VaultScreens(container: AppContainer, database: DatabaseSession, navigation: NavigationState) {
    // Codes another app handed over (SPEC §10) are read once the vault is open.
    val incoming by container.incomingCodes.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        incoming?.let { codes ->
            container.incomingCodes.value = null
            // One picture after another from a messenger: the screen reading them is replaced, not stacked.
            if (navigation.backStack.last() is Screen.ShareRead) navigation.replace(Screen.ShareRead(codes)) else navigation.open(Screen.ShareRead(codes))
        }
    }
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
                sharedTransitionScope = this,
                transitionSpec = { fade() },
                popTransitionSpec = { fade() },
                predictivePopTransitionSpec = { fadeIn(enter) togetherWith (fadeOut(enter) + scaleOut(targetScale = 0.9f)) },
                entryProvider = entryProvider {
                    entry<Screen.Entries> {
                        EntryListScreen(
                            container = container,
                            database = database,
                            query = navigation.query,
                            codesOnly = navigation.codesOnly,
                            onOpen = { id, origin -> navigation.open(Screen.Card(id, origin)) },
                            onSettings = { navigation.open(Screen.Settings) },
                            onGroups = { navigation.open(Screen.Groups) },
                            onLeaveCodes = { navigation.codesOnly = false },
                            onNewEntry = { navigation.open(Screen.Editor(entryId = null)) },
                            onSharing = { navigation.open(Screen.Sharing) },
                        )
                    }
                    entry<Screen.Card> { key ->
                        EntryCardScreen(
                            container = container,
                            database = database,
                            entryId = key.entryId,
                            origin = key.origin,
                            onBack = navigation::back,
                            onSearch = navigation::search,
                            onShowPassword = { view -> navigation.open(Screen.Password(key.entryId, view)) },
                            onEdit = { navigation.open(Screen.Editor(key.entryId)) },
                            onHistory = { navigation.open(Screen.History(key.entryId)) },
                            onSendTo = { their ->
                                val chosen = listOf(key.entryId)
                                navigation.open(if (their.checked) Screen.ShareReview(their.request.id, chosen) else Screen.ShareTheirs(their.request.id, chosen))
                            },
                            onReadRequest = { navigation.open(Screen.ShareRead()) },
                        )
                    }
                    entry<Screen.History> { key ->
                        EntryHistoryScreen(database, key.entryId, onBack = navigation::back)
                    }
                    entry<Screen.Editor> { key ->
                        EntryEditorScreen(
                            database = database,
                            entryId = key.entryId,
                            groupId = key.groupId,
                            generator = container::passwordGenerator,
                            onClose = navigation::back,
                            onSaved = { id -> if (key.entryId == null) navigation.replace(Screen.Card(id, origin = "new")) else navigation.back() },
                        )
                    }
                    entry<Screen.Password> { key ->
                        PasswordScreen(database, key.entryId, key.view, onBack = navigation::back)
                    }
                    entry<Screen.Groups> {
                        GroupsScreen(database, onBack = navigation::back, onSearch = navigation::search)
                    }
                    entry<Screen.Settings> {
                        SettingsScreen(
                            container = container,
                            database = database,
                            onBack = navigation::back,
                            onChooseDatabase = { navigation.choosingDatabase = true },
                            onStorageProbe = { navigation.open(Screen.StorageProbe) },
                        )
                    }
                    entry<Screen.StorageProbe> { StorageProbeScreen(container) }
                    entry<Screen.Sharing> {
                        SharingScreen(
                            container = container,
                            onBack = navigation::back,
                            onTheirs = { navigation.open(Screen.ShareTheirs(it)) },
                            onMine = { navigation.open(Screen.ShareMine(it)) },
                            onRead = { navigation.open(Screen.ShareRead()) },
                            onAsk = { navigation.open(Screen.ShareAsk) },
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
                            onAnswer = { navigation.replace(Screen.ShareReceived(it)) },
                        )
                    }
                    entry<Screen.ShareTheirs> { key ->
                        TheirRequestScreen(container, key.id, onBack = { navigation.close(key) }, onChoose = { navigation.open(Screen.ShareChoose(key.id, key.preselected)) })
                    }
                    entry<Screen.ShareChoose> { key ->
                        ChooseEntriesScreen(
                            container = container,
                            database = database,
                            id = key.id,
                            preselected = key.preselected,
                            onClose = { navigation.close(key) },
                            onNext = { navigation.open(Screen.ShareReview(key.id, it)) },
                        )
                    }
                    entry<Screen.ShareReview> { key ->
                        ReviewScreen(container, database, key.id, key.entryIds, onBack = { navigation.close(key) }, onMade = { navigation.replace(Screen.ShareAnswer(it)) })
                    }
                    entry<Screen.ShareAnswer> { key -> AnswerScreen(container, key.answer, onDone = navigation::leaveSharing) }
                    entry<Screen.ShareReceived> { key ->
                        ReceivedScreen(
                            container = container,
                            database = database,
                            answerText = key.answerText,
                            onClose = navigation::back,
                            onOpenEntry = { navigation.replace(Screen.Card(it, origin = "received")) },
                        )
                    }
                },
            )
        }
    }
}
