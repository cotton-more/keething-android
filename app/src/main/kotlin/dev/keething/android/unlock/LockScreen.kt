package dev.keething.android.unlock

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.ui.res.stringResource
import dev.keething.android.R
import dev.keething.android.session.Visit
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import dev.keething.android.databases.DatabaseLine
import dev.keething.android.databases.DatabaseLinesModel
import dev.keething.android.databases.DatabaseLabel
import dev.keething.android.databases.DatabaseLabelText
import dev.keething.android.databases.DatabaseTile
import dev.keething.android.databases.DatabasesSheet
import dev.keething.android.databases.KeyFileLine
import dev.keething.android.databases.RemoveFromListDialog
import dev.keething.android.databases.rememberFileActions
import dev.keething.android.databases.Reach
import dev.keething.android.ui.AppLogo
import dev.keething.android.ui.ButtonLabel
import androidx.compose.foundation.text.input.clearText
import dev.keething.android.ui.UiText
import dev.keething.android.ui.keepOutOfAutofill
import dev.keething.android.ui.resolve
import dev.keething.android.ui.secretToAccessibility
import dev.keething.android.ui.uiPlain
import dev.keething.vault.databases.DatabaseId
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.keething.android.AppContainer
import dev.keething.vault.databases.DatabaseSession
import dev.keething.android.theme.MonospaceFamily
import dev.keething.android.ui.SecretTextMenu
import dev.keething.android.ui.dotsAtLineEnd
import dev.keething.domain.biometric.BiometricAvailability
import dev.keething.vault.session.LockReason
import dev.keething.vault.session.SessionState
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.keething.android.databases.lockScreen
import dev.keething.android.theme.extraColors
import dev.keething.vault.databases.UnlockRun
import dev.keething.domain.secret.Secret

/**
 * The lock screen (SPEC §6.2): the database (a tap: the list's databases), why it is locked, and the password field at
 * the bottom in thumb reach, its key file chosen in it and said under it, and one button beside it: Unlock, or the
 * fingerprint while the field is empty and the fingerprint can open the database or may be offered (SPEC §5;
 * keething-spec unlock.lock-screen-keyboard, unlock.biometric AC4). A file not found has Locate… in place of the field; a database that opens with its
 * key file alone has Unlock. [onBack]: Back from it (to the open databases, to the chooser, or to the one shown before);
 * [onChose]: this one gave way to another chosen here. [run]: the chooser's run asks this database (plan AD13;
 * keething-spec databases.chooser AC5, AC6): "Unlocking 2 of 3" over it with Stop, no fingerprint, the typed password
 * the run's, a wrong one said over the field, and Skip. [purpose]: what the unlock is for, under the database ("Unlock to
 * write the quick note", SPEC §6.4): a lock screen of its own over another app, for this database alone (no other is
 * chosen on it), which asks for the fingerprint by itself once, when it shows. [showsList]: the app's list follows the
 * unlock, so a database whose fingerprint was never chosen is asked over it (unlock.biometric AC4); not autofill's,
 * nor a quick note's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LockScreen(
    container: AppContainer,
    database: DatabaseSession,
    onBack: (() -> Unit)?,
    onChose: (DatabaseId) -> Unit,
    run: UnlockRun? = null,
    purpose: String? = null,
    showsList: Boolean = purpose == null,
) {
    val databases = container.sessions.databases
    // Per session, not per file: a model of an earlier session of the same file would unlock that one.
    val model = viewModel(key = "lock:${database.number}") {
        val offers: (Visit) -> Boolean = if (purpose == null) {
            container.sessions.fingerprintOffers::offer
        } else {
            var offered = false
            { _ -> !offered.also { offered = true } }
        }
        LockViewModel(database, databases, container.sessions.list, offers, container.sessions.filesThere::isThere, container.lockScreenFields)
    }
    val linesModel = viewModel(key = "database-lines") { DatabaseLinesModel(container.sessions.list, container.databaseFiles) }
    val screens by linesModel.screens.collectAsStateWithLifecycle()
    // Its own, current or not (the run's database is not made current).
    val lock = screens?.lines?.firstOrNull { it.id == database.id }?.let { line -> container.sessions.list.record(database.id)?.lockScreen(line) }
    // Its name as last read from the file, before the lines (the files looked at) are there.
    val name = lock?.line?.name ?: container.sessions.list.record(database.id)?.displayName ?: database.location.fileName
    val session by model.session.collectAsStateWithLifecycle()
    val biometric by model.biometric.collectAsStateWithLifecycle()
    val fingerprintNotice by model.fingerprintNotice.collectAsStateWithLifecycle()
    val wrongInARow by model.wrongInARow.collectAsStateWithLifecycle()
    val timeout by container.settings.lockTimeout.collectAsStateWithLifecycle()
    val visit by container.sessions.visit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.refresh()
        linesModel.refresh()
    }
    // A KDBX 3.1 file asks its question before the key, told from the file (keething-spec kdbx.kdbx3 AC5): null until
    // the file was looked at, again after every lock (an answer lasts one opening).
    val kdbx3 = container.sessions.kdbx3
    var asksKdbx3 by remember(database.number) { mutableStateOf<Boolean?>(null) }
    val locked = session is SessionState.Locked
    LaunchedEffect(database.number, locked) {
        if (locked) asksKdbx3 = !kdbx3.answered(database) && kdbx3.isKdbx3(database)
    }
    // Not in the run: the chooser asked the fingerprint for every database that could. Not before the question.
    LaunchedEffect(visit, run, asksKdbx3) { if (run == null && asksKdbx3 == false) model.offerFingerprint(visit) }
    val runState = run?.state?.collectAsStateWithLifecycle()?.value
    // A failed unlock may have found the file gone (or back): the line looks again.
    LaunchedEffect(session) { if (session is SessionState.Locked) linesModel.refresh() }
    // Another database shown: what was typed here was meant for this one (its model stays until the activity goes).
    val leave = { model.password.clearText() }
    // In the run Back is Stop: it ends where it is.
    val back = run?.let { { leave(); container.chooser.stop() } } ?: onBack?.let { { leave(); it() } }
    BackHandler(enabled = back != null) { back?.invoke() }

    val context = LocalContext.current

    val resources = LocalResources.current
    // Per session: nothing of this database's sheet, dialog or Locate… stays on the next one's lock screen.
    var sheet by remember(database.number) { mutableStateOf(false) }
    var removing by remember(database.number) { mutableStateOf(false) }
    // What the last Locate… or folder chosen could not do ("That file is already in the list as …").
    var locateProblem by remember(database.number) { mutableStateOf<String?>(null) }
    // Open other…: another database shown here (this one's own file chosen again changes nothing).
    val files = rememberFileActions(
        container,
        onOpened = { id ->
            if (id != database.id) {
                leave()
                onChose(database.id)
            }
        },
        onProblem = { locateProblem = it },
    )

    if (sheet) {
        DatabasesSheet(
            lines = screens?.lines.orEmpty(),
            onChoose = { id ->
                sheet = false
                if (id != database.id) {
                    leave()
                    databases.choose(id)
                    onChose(database.id)
                }
            },
            onOpenOther = {
                sheet = false
                files.open()
            },
            onNew = {
                sheet = false
                leave()
                container.newDatabases.open()
            },
            onRemove = {
                sheet = false
                removing = true
            },
            onDismiss = { sheet = false },
        )
    }
    if (removing) {
        RemoveFromListDialog(
            id = database.id,
            name = lock?.line?.label?.full ?: database.location.fileName,
            notFound = lock?.line?.notFound,
            countBackups = container.sessions::backupCount,
            onRemove = {
                removing = false
                leave()
                container.sessions.remove(database.id) {
                    Toast.makeText(context.applicationContext, resources.getString(R.string.unlock_stored_password_not_deleted, it), Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { removing = false },
        )
    }

    val reason = (session as? SessionState.Locked)?.reason
    // From the accepted fingerprint on: opening the stored password in StrongBox takes a moment before the
    // vault's own unlock starts.
    val unlocking = session is SessionState.Unlocking || biometric.opening

    val short = LocalConfiguration.current.screenHeightDp < 480
    // A wrong typed key shakes the field; a stored password the database rejected is not the user's typo.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(reason) {
        if ((reason as? LockReason.UnlockFailed)?.failure?.isWrongKey == true && !model.lastAttemptByFingerprint) {
            for (target in listOf(-18f, 14f, -8f, 4f, 0f)) shake.animateTo(target, spring(stiffness = 4_000f))
        }
    }

    // The database's identity, then the way in: one above the other; side by side on a screen less than 480 dp high
    // (a phone in landscape), where the keyboard leaves room for the field only. At most 480 dp wide on a tablet.
    val identity = @Composable {
        // With the keyboard up on a low screen one row is left: the database's, beside the field; in the run the
        // keyboard leaves room for the field and Skip only under the run's bar.
        // A lock screen for a purpose is under its own title ("Keething note").
        // The keyboard up, the logo gives way and the line keeps to one line, so that the field and what is said over it
        // stay above the keyboard (keething-spec unlock.lock-screen-keyboard AC1). The app's name is not said: the user
        // knows which app this is, and the database's line says where.
        val keyboard = WindowInsets.isImeVisible
        if (purpose == null && !((short || run != null) && keyboard)) {
            val logo by animateDpAsState(if (keyboard) 64.dp else if (short) 72.dp else 104.dp, label = "logo")
            AppLogo(Modifier.size(logo))
            Spacer(Modifier.height(if (short || keyboard) 12.dp else 24.dp))
        }
        DatabaseIdentity(lock?.line, name, compact = keyboard, onClick = if (run == null && purpose == null) ({ sheet = true }) else null)
        purpose?.let {
            Spacer(Modifier.height(16.dp))
            Purpose(it)
        }
        // The file's problems first (its key file not there; a file not found says it in place of the field); then
        // why the fingerprint is not offered or its stored password went (also when one fingerprint for several said
        // it, plan AD13), what was typed here aside; then why it is locked. In the run a wrong password is said over the
        // field instead.
        val reasonNotice = reason?.notice(timeout, lock?.line?.path ?: database.location.fileName, wrongInARow)
        val setUpAgain = biometric.setUpAgain?.takeIf { biometric.enabled && !biometric.stored }?.notice()
        // On, nothing stored yet (its page, a stored password gone): what is typed now sets it up.
        val typeToSetUp = setUpByTypingNotice.takeIf {
            biometric.enabled && biometric.availability == BiometricAvailability.Available && !biometric.stored && !biometric.passwordless
        }
        val notice = locateProblem?.let(::problemNotice)
            ?: when {
                lock?.line?.isMissing == true -> null
                lock?.problem != null -> problemNotice(lock.problem)
                run != null -> setUpAgain ?: reasonNotice?.takeIf { reason == LockReason.PasswordChanged } ?: typeToSetUp
                else -> fingerprintNotice ?: reasonNotice?.takeIf { it.isProblem && model.typedHere } ?: setUpAgain
                    ?: reasonNotice?.takeIf { it.isProblem } ?: typeToSetUp ?: reasonNotice
            }
        notice?.let {
            Spacer(Modifier.height(12.dp))
            ReasonChip(it)
        }
    }
    val wayInContent = @Composable {
        AnimatedContent(
            targetState = unlocking,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "unlocking",
        ) { busy ->
            when {
                busy -> Column(Modifier.fillMaxWidth().height(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.unlock_opening, name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Not known yet (the files are being looked at): nothing rather than a field that goes again.
                lock == null -> Spacer(Modifier.height(56.dp))
                lock.showsLocate -> NotFound(lock.missingText?.resolve().orEmpty(), lock.locateLabel.resolve(), othersAbove = run == null && purpose == null, onLocate = {
                    files.locate(database.id, lock.line.reach == Reach.NO_ACCESS)
                })
                asksKdbx3 == true -> Kdbx3QuestionCard { upgrade ->
                    kdbx3.answer(database, upgrade)
                    asksKdbx3 = false
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The run's: "Wrong password … Type it again, or skip it."; from the second in a row, with the
                    // hardware key (keething-spec app.first-start AC5): the run's tries go through this session too.
                    runState?.failure?.let { RunFailure(stringResource(R.string.unlock_run_failure, it.describe(lock.line.path, wrongInARow).resolve())) }
                    // The run's: bound to this database, never tried on the next one asked.
                    val unlock = { model.unlock(run?.let { { secret: Secret -> container.chooser.type(database.id, secret) } }, askFingerprint = showsList) }
                    // Not in the run: the chooser asked the fingerprint for every database that could. Nor where no list
                    // follows to ask whether to use it over.
                    val offered = lock.offersFingerprint && run == null && (showsList || biometric.canUnlock)
                    val beside = besideField(biometric, offered, typed = model.password.text.isNotEmpty())
                    val fingerprint = BesideField(
                        fingerprint = beside != Beside.UNLOCK,
                        // Never chosen: it unlocks as the arrow does, and an empty field opens nothing without a key file.
                        enabled = !biometric.prompting &&
                            (beside != Beside.UNLOCK_THEN_ASK || !lock.needsPassword || model.password.text.isNotEmpty()),
                        label = stringResource(if (beside == Beside.FINGERPRINT) R.string.unlock_with_fingerprint else R.string.unlock_then_set_up_fingerprint),
                        onFingerprint = if (beside == Beside.FINGERPRINT) model::fingerprintTapped else unlock,
                    )
                    val keyFile = lock.line.keyFile
                    // The key in the field chooses one, and takes the chosen one off again.
                    val toggleKeyFile = { if (keyFile == null) files.chooseKeyFile(database.id) else databases.setKeyFile(database.id, null) }
                    if (lock.showsPasswordField) {
                        PasswordRow(
                            model = model,
                            placeholder = lock.passwordPlaceholder.resolve(),
                            needsPassword = lock.needsPassword,
                            // Not while the fingerprint can open it (the user may touch), nor before it is known.
                            focusAtOnce = biometric.availability != null && !(offered && biometric.canUnlock),
                            keyFile = keyFile,
                            onKeyFile = toggleKeyFile.takeIf { keyFile != null || lock.offersKeyFile },
                            beside = fingerprint,
                            onUnlock = unlock,
                            modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                        )
                    } else {
                        // Its Unlock asks the question once it opened: the fingerprint beside it only opens.
                        KeyFileAlone(fingerprint.copy(fingerprint = beside == Beside.FINGERPRINT), onUnlock = unlock)
                    }
                    KeyFileRow(keyFile)
                    // No field, so no key in it: the only way to another key file with nothing open (the audit's A2).
                    if (lock.offersChooseKeyFile) {
                        TextButton(onClick = { files.chooseKeyFile(database.id) }) {
                            ButtonLabel(stringResource(R.string.databases_choose_key_file), Icons.Rounded.Key, iconSize = 18.dp)
                        }
                    }
                }
            }
        }
    }
    val wayIn = @Composable {
        wayInContent()
        // Skip whatever shows (also Locate… for a file gone since the run reached it), not while it opens.
        if (run != null && !unlocking) {
            OutlinedButton(onClick = { leave(); container.chooser.skip(database.id) }, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(52.dp)) {
                ButtonLabel(stringResource(R.string.unlock_run_skip, name), Icons.Rounded.SkipNext, iconSize = 18.dp)
            }
        }
    }
    val edges = Modifier
        .fillMaxSize()
        .then(if (run == null) Modifier.statusBarsPadding() else Modifier)
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        .displayCutoutPadding()
        .padding(horizontal = 24.dp, vertical = if (short) 8.dp else 16.dp)
    Column(Modifier.fillMaxSize()) {
        // The run's bar over it: Stop, "Unlocking 2 of 3", what opened, a segment per database (the agreed mock-up).
        if (run != null && runState != null) {
            val labels = screens?.lines.orEmpty().associate { it.id to it.label.full }
            val separator = stringResource(R.string.list_separator)
            RunBar(
                step = run.step.resolve(),
                opened = run.opened.takeIf { it.isNotEmpty() }?.joinToString(separator) { labels[it].orEmpty() }
                    ?.let { stringResource(R.string.unlock_run_opened, it) },
                segments = run.segments,
                onStop = { back?.invoke() },
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (short) {
                Row(edges, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { identity() }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { wayIn() }
                }
            } else {
                Column(edges.wrapContentWidth().widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    identity()
                    Spacer(Modifier.weight(1f))
                    wayIn()
                }
            }
            if (back != null && run == null) {
                IconButton(onClick = back, modifier = Modifier.statusBarsPadding().displayCutoutPadding().padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.unlock_back))
                }
            }
        }
    }
}

/** Over the run's lock screen (the agreed mock-up): Stop, "Unlocking 2 of 3", what opened, and a segment per database. */
@Composable
private fun RunBar(step: String, opened: String?, segments: List<UnlockRun.Segment>, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().displayCutoutPadding().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onStop) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.unlock_run_stop)) }
            Text(step, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            opened?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.extraColors.good,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 16.dp).widthIn(max = 200.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.forEach { segment ->
                val color = when (segment) {
                    UnlockRun.Segment.OPENED -> MaterialTheme.extraColors.good
                    UnlockRun.Segment.SKIPPED -> MaterialTheme.colorScheme.outlineVariant
                    UnlockRun.Segment.CURRENT -> MaterialTheme.colorScheme.primary
                    UnlockRun.Segment.WAITING -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
    }
}

/** Over the run's field: why the password typed did not open it ("… Type it again, or skip it."). */
@Composable
private fun RunFailure(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * A database that opens with its key file alone (keething-spec unlock.key-file AC5): nothing to type; the fingerprint
 * beside Unlock as beside the field.
 */
@Composable
private fun KeyFileAlone(beside: BesideField, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.unlock_key_file_alone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUnlock, modifier = Modifier.weight(1f).height(56.dp)) {
                ButtonLabel(stringResource(R.string.unlock_unlock), Icons.Rounded.LockOpen, iconSize = 20.dp)
            }
            if (beside.fingerprint) FingerprintBeside(beside)
        }
    }
}

/**
 * The database's key file under the field, on one line: its name, then its folder or that it is not there, cut when long;
 * shown only; the key in the field changes it. Its place is kept while there is none, so that choosing one moves nothing
 * (keething-spec unlock.lock-screen-keyboard AC2).
 */
@Composable
private fun KeyFileRow(keyFile: KeyFileLine?) {
    val place = Modifier.fillMaxWidth().padding(top = 4.dp).height(40.dp)
    if (keyFile == null) {
        Spacer(place)
        return
    }
    val tint = if (keyFile.isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        place.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Key, contentDescription = stringResource(R.string.unlock_key_file), Modifier.size(18.dp), tint = tint)
        // One text, cut in the middle: a long name and a long folder share the line.
        Text(
            buildAnnotatedString {
                append(keyFile.name)
                withStyle(SpanStyle(color = tint)) { append(" · ${keyFile.detail.resolve()}") }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/**
 * A file not found (keething-spec databases.list AC5), not reached any more or kept by its path (databases.saf AC10,
 * AC13): said in place of the field, with Locate… or Choose its folder… ([button]); [othersAbove]: another database can
 * be chosen above.
 */
@Composable
private fun NotFound(text: String, button: String, othersAbove: Boolean, onLocate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.FindInPage, contentDescription = null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onLocate) { ButtonLabel(button, Icons.Rounded.Search, iconSize = 20.dp) }
        if (othersAbove) {
            Text(stringResource(R.string.unlock_choose_another_above), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the unlock is for, under the database (the agreed mock-up): "Unlock to write the quick note". */
@Composable
private fun Purpose(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Rounded.StickyNote2, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
}

/** A problem another part of the app said, in its words: as a String until that part says it as a [UiText]. */
private fun problemNotice(text: String) = LockNotice(uiPlain(text), isProblem = true)

private fun problemNotice(text: UiText) = LockNotice(text, isProblem = true)

/** The one button beside the field: the fingerprint ([fingerprint]: its [label] said to TalkBack), else Unlock. */
private data class BesideField(val fingerprint: Boolean, val enabled: Boolean, val label: String, val onFingerprint: () -> Unit)

@Composable
private fun FingerprintBeside(beside: BesideField) {
    FilledIconButton(
        onClick = beside.onFingerprint,
        enabled = beside.enabled,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(20.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(Icons.Rounded.Fingerprint, contentDescription = beside.label, Modifier.size(28.dp))
    }
}

/**
 * Asked once over the list of a database whose fingerprint was never chosen, after a typed unlock opened it
 * (keething-spec unlock.biometric AC4): Use fingerprint, or No (kept: not asked again). Put away, it answers nothing.
 */
@Composable
internal fun FingerprintQuestion(name: String, passwordless: Boolean, onAnswer: (Boolean?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        icon = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
        title = { Text(stringResource(R.string.unlock_fingerprint_question_title, name), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (passwordless) R.string.unlock_fingerprint_question_key_file else R.string.unlock_fingerprint_question_password,
                    ),
                )
                Text(stringResource(R.string.unlock_fingerprint_question_once))
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.unlock_fingerprint_question_use)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.unlock_fingerprint_question_no)) } },
    )
}

/**
 * The database's line (SPEC §6.2): its tile, name, folder and when it changed; a tap opens the list's databases. Null
 * [onClick]: it is only said (the chooser's run asks this one). [compact]: the keyboard is up, the folder and when it
 * changed keep to one line, the folder cut in the middle.
 */
@Composable
private fun DatabaseIdentity(line: DatabaseLine?, name: String, compact: Boolean, onClick: (() -> Unit)?) {
    val content = @Composable {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Its colour needs the whole list's: neutral until the lines are there.
            DatabaseTile(line?.initial ?: name.trim().firstOrNull()?.uppercase() ?: "?", line?.colorIndex)
            // Weighted, so that with a large font the icon keeps its place beside the lines.
            Column(Modifier.weight(1f, fill = false)) {
                DatabaseLabelText(line?.label ?: DatabaseLabel(name), MaterialTheme.typography.titleMedium)
                val color = if (line?.isMissing == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                if (compact && line != null) {
                    // The folder gives way, cut in the middle; when it changed is said whole.
                    Row {
                        Text(
                            line.folder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        line.status?.let { Text(" · ${it.resolve()}", style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, softWrap = false) }
                    }
                } else {
                    Text(line?.detail?.resolve().orEmpty().dotsAtLineEnd(), style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2)
                }
            }
            if (onClick != null) {
                Icon(Icons.Rounded.UnfoldMore, contentDescription = stringResource(R.string.unlock_choose_another), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
    }
}

@Composable
private fun ReasonChip(notice: LockNotice) {
    val container = if (notice.isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (notice.isProblem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(container).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val icon = when {
            notice.isProblem -> Icons.Rounded.ErrorOutline
            notice.fingerprint -> Icons.Rounded.Fingerprint
            else -> Icons.Rounded.Schedule
        }
        Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = content)
        Text(notice.text.resolve(), style = MaterialTheme.typography.bodyMedium, color = content, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PasswordRow(
    model: LockViewModel,
    placeholder: String,
    needsPassword: Boolean,
    focusAtOnce: Boolean,
    keyFile: KeyFileLine?,
    /** Key file… in the field, or the chosen one taken off; null: none is offered (a file not found locates first). */
    onKeyFile: (() -> Unit)?,
    beside: BesideField,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealed = model.typed.revealed
    val focus = remember { FocusRequester() }
    val passwordWanted by model.passwordWanted.collectAsStateWithLifecycle()
    val wantedBefore = remember { model.passwordWanted.value }
    LaunchedEffect(focusAtOnce) { if (focusAtOnce) focus.requestFocus() }
    // "Use password" in the fingerprint prompt, since this screen appeared.
    LaunchedEffect(passwordWanted) { if (passwordWanted > wantedBefore) focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Copying is off in a secure field; other apps' items in its menu ("Ask Gemini") go too.
        SecretTextMenu(copyable = false, modifier = Modifier.weight(1f)) {
            SecureTextField(
                state = model.password,
                modifier = Modifier.keepOutOfAutofill().secretToAccessibility(model.password.text.length, shown = revealed).fillMaxWidth().focusRequester(focus),
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                textStyle = if (revealed) MaterialTheme.typography.bodyLarge.copy(fontFamily = MonospaceFamily) else MaterialTheme.typography.bodyLarge,
                textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
                trailingIcon = {
                    Row {
                        IconButton(onClick = { model.typed.revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(if (revealed) R.string.unlock_hide_password else R.string.unlock_show_password),
                            )
                        }
                        onKeyFile?.let {
                            IconButton(onClick = it) {
                                Icon(
                                    Icons.Rounded.Key,
                                    contentDescription = keyFile?.let { file -> stringResource(R.string.unlock_key_file_remove, file.name) } ?: stringResource(R.string.unlock_key_file_choose),
                                    tint = when {
                                        keyFile == null -> LocalContentColor.current
                                        keyFile.isMissing -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        }
                    }
                },
                // All of it, not only the action: a password field keeps the keyboard from capitalising, correcting
                // and learning what is typed.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { onUnlock() },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        }
        // Typing turns the fingerprint into Unlock, emptying the field turns it back (not while it was never chosen: it unlocks).
        AnimatedContent(targetState = beside.fingerprint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "beside") { fingerprint ->
            if (fingerprint) {
                FingerprintBeside(beside)
            } else {
                FilledIconButton(
                    onClick = onUnlock,
                    // Without a key file an empty field opens nothing (keething-spec unlock.lock-screen AC2).
                    enabled = !needsPassword || model.password.text.isNotEmpty(),
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.unlock_unlock))
                }
            }
        }
    }
}
