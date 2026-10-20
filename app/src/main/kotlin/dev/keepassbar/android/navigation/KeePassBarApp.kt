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
import dev.keepassbar.android.entry.EntryCardScreen
import dev.keepassbar.android.entry.PasswordScreen
import dev.keepassbar.android.entry.PasswordView
import dev.keepassbar.android.list.EntryListScreen
import dev.keepassbar.android.session.DatabaseSession
import dev.keepassbar.android.settings.SettingsScreen
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

    data object Settings : Screen

    data object StorageProbe : Screen
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

    /** The card's group or tag chip: back to the list, searching for [filter]. */
    fun search(filter: String) {
        backStack.retainAll { it == Screen.Entries }
        codesOnly = false
        query.edit { replace(0, length, filter) }
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
                            onLeaveCodes = { navigation.codesOnly = false },
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
                        )
                    }
                    entry<Screen.Password> { key ->
                        PasswordScreen(database, key.entryId, key.view, onBack = navigation::back)
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
                },
            )
        }
    }
}
