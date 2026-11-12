package dev.firetvlite.iptv.ui.screens

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.film.FilmReminderCard
import dev.firetvlite.iptv.data.film.FilmReminders
import dev.firetvlite.iptv.data.film.ReminderShowing
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay

@Composable
internal fun FilmReminderOverlay(library: LibraryViewModel, eligible: Boolean, watchingLiveChannelId: Long?) {
    val source by library.source.collectAsState()
    val sourceId = source?.id
    val preferences = library.reminderPreferences
    val acknowledgements by preferences.state.collectAsState()
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var showings by remember(sourceId) { mutableStateOf(emptyList<ReminderShowing>()) }
    var checkedAt by remember(sourceId) { mutableStateOf(0L) }
    var card by remember(sourceId) { mutableStateOf<FilmReminderCard?>(null) }
    LaunchedEffect(sourceId, foreground) {
        if (sourceId != null && foreground) while (true) {
            val now = System.currentTimeMillis()
            preferences.prune(now)
            showings = try {
                library.reminderShowings(sourceId, now)
            } catch (_: SQLiteException) {
                emptyList()
            }
            checkedAt = now
            delay(60_000L - System.currentTimeMillis().mod(60_000L))
        }
    }
    LaunchedEffect(eligible, foreground, showings, checkedAt, acknowledgements, watchingLiveChannelId, card) {
        if (!eligible || !foreground) {
            card = null
        } else if (card == null) {
            val now = System.currentTimeMillis()
            card = FilmReminders.next(showings, now, acknowledgements.date, acknowledgements.shown, watchingLiveChannelId)
            (card as? FilmReminderCard.Soon)?.let { preferences.markShown(it.showing, now) }
        }
    }
    if (eligible && foreground) card?.let { current ->
        ReminderCard(current) {
            if (current is FilmReminderCard.Daily) preferences.acknowledge(current.date)
            card = null
        }
    }
}

@Composable
private fun ReminderCard(card: FilmReminderCard, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    var dismissKey by remember(card) { mutableStateOf<Key?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        LaunchedEffect(card) { focus.requestFocusWhenReady() }
        Box(Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back)) {
                if (event.type == KeyEventType.KeyDown) dismissKey = event.key
                else if (event.type == KeyEventType.KeyUp && dismissKey == event.key) {
                    dismissKey = null
                    onDismiss()
                }
            }
            true
        }.focusable(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp).padding(24.dp)
                .background(FireLiteTheme.colors.surface, RoundedCornerShape(20.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FLText(stringResource(if (card is FilmReminderCard.Daily) R.string.reminder_today else R.string.reminder_soon),
                    style = FireLiteTheme.typography.headline)
                val showings = when (card) {
                    is FilmReminderCard.Daily -> card.showings
                    is FilmReminderCard.Soon -> listOf(card.showing)
                }
                showings.take(4).forEach { showing ->
                    val text = if (card is FilmReminderCard.Daily)
                        stringResource(R.string.reminder_daily_row, clockTime(showing.start), showing.channelName, showing.title)
                    else stringResource(R.string.reminder_soon_row, showing.title, clockTime(showing.start), showing.channelName)
                    FLText(text, style = FireLiteTheme.typography.title, maxLines = 2)
                }
                if (showings.size > 4) FLText(stringResource(R.string.reminder_more, showings.size - 4), style = FireLiteTheme.typography.label)
                FLText(stringResource(R.string.reminder_dismiss), style = FireLiteTheme.typography.body, color = FireLiteTheme.colors.accent)
            }
        }
    }
}
