package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.LocalSearch
import dev.firetvlite.iptv.data.SearchResults
import dev.firetvlite.iptv.data.SearchSuggestions
import dev.firetvlite.iptv.data.SearchText
import dev.firetvlite.iptv.data.SearchWindow
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.SearchProgramme
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.ui.components.remoteHomeKey
import dev.firetvlite.iptv.ui.components.ArchiveIcon
import dev.firetvlite.iptv.ui.components.DeadChannelIcon
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private data class SearchHit(val channel: ChannelEntity, val programme: SearchProgramme? = null) {
    val key: String get() = if (programme == null) "channel:${channel.id}" else "programme:${channel.id}:${programme.programmeId}"
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun SearchDialog(sourceId: Long?, viewModel: PlayerViewModel) {
    val app = LocalContext.current.applicationContext as FireLiteApplication
    val search = remember(app) { LocalSearch(app.container.database.searchDao(), app.container.database.channelUsageDao()::all) }
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val suggestions = remember(search, scope) { SearchSuggestions(scope, search::search) }
    val suggestion by suggestions.state.collectAsState()
    val current = suggestion.query == query && suggestion.sourceId == sourceId
    val results = if (current) suggestion.results else SearchResults()
    val loading = current && suggestion.loading
    val failed = current && suggestion.failed
    val resultTime = suggestion.at
    var keyboard by remember { mutableStateOf<SoftwareKeyboardController?>(null) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val hits = remember(results) {
        results.channels.map { SearchHit(it) } + results.programmes.map { SearchHit(it.channel, it) }
    }
    LaunchedEffect(sourceId) { suggestions.request(sourceId, query) }
    LaunchedEffect(query, sourceId) { scroll.scrollToItem(0) }
    LaunchedEffect(hits) {
        if (focusedKey != null && hits.none { it.key == focusedKey }) {
            focusedKey = null
            fieldFocus.requestFocusWhenReady()
        }
    }
    fun enterResults() {
        if (hits.isNotEmpty()) {
            keyboard?.hide()
            scope.launch {
                scroll.scrollToItem(0)
                firstFocus.requestFocusWhenReady()
            }
        }
    }
    Dialog(onDismissRequest = viewModel::closeSearch, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogKeyboard = LocalSoftwareKeyboardController.current
        SideEffect { keyboard = dialogKeyboard }
        val colors = FireLiteTheme.colors
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val inputLabel = stringResource(R.string.search_input)
        LaunchedEffect(Unit) { fieldFocus.requestFocusWhenReady() }
        Column(Modifier.fillMaxSize().remoteHomeKey().background(colors.background).padding(horizontal = 48.dp, vertical = 27.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                FLText(stringResource(R.string.search), style = FireLiteTheme.typography.headline)
                SecondaryButton(stringResource(R.string.diagnostics_close), viewModel::closeSearch,
                    Modifier.focusProperties { down = fieldFocus })
            }
            BasicTextField(query, {
                query = it.take(256)
                suggestions.request(sourceId, query)
            }, singleLine = true,
                textStyle = FireLiteTheme.typography.title.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); enterResults() }),
                decorationBox = { inner ->
                    if (query.isEmpty()) FLText(inputLabel, style = FireLiteTheme.typography.title, color = colors.textSecondary)
                    inner()
                },
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused; if (it.isFocused) focusedKey = null }
                    .semantics { contentDescription = inputLabel }
                    .onPreInterceptKeyBeforeSoftKeyboard { event ->
                        if (event.key == Key.Back && keyboardVisible) {
                            if (event.type == KeyEventType.KeyUp) keyboard?.hide()
                            true
                        } else false
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionDown) {
                            if (event.type == KeyEventType.KeyDown) enterResults()
                            true
                        } else false
                    }
                    .background(colors.surfaceHigh, FireLiteTheme.shapes.medium)
                    .border(2.dp, if (fieldFocused) colors.focusRing else colors.surfaceHigh,
                        FireLiteTheme.shapes.medium).padding(14.dp))
            val status = when {
                loading -> R.string.search_loading
                failed -> R.string.search_failed
                SearchText.normalize(query).isEmpty() -> R.string.search_empty_query
                hits.isEmpty() -> R.string.search_no_results
                else -> null
            }
            if (status != null) FLText(stringResource(status), style = FireLiteTheme.typography.body, maxLines = 1,
                color = if (status == R.string.search_empty_query) colors.textSecondary else colors.textPrimary)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(hits, key = { _, hit -> hit.key }) { index, hit ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index == 0 || index == results.channels.size) {
                            val group = if (hit.programme == null) R.string.search_channels else R.string.search_programmes
                            val count = if (hit.programme == null) results.channels.size else results.programmes.size
                            FLText(stringResource(group, count), style = FireLiteTheme.typography.title)
                        }
                        SearchResultRow(hit, resultTime, {
                            val programme = hit.programme
                            viewModel.playSearchResult(hit.channel,
                                if (programme != null && programme.stop <= System.currentTimeMillis()) programme.start else null)
                        }, onFocused = { focusedKey = hit.key },
                            modifier = (if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusProperties { if (index == hits.lastIndex) down = FocusRequester.Cancel }
                                .onPreviewKeyEvent { event ->
                                    if (index == 0 && event.key == Key.DirectionUp) {
                                        if (event.type == KeyEventType.KeyDown) fieldFocus.requestFocus()
                                        true
                                    } else false
                                })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: SearchHit, now: Long, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier) {
    val programme = hit.programme
    val archived = programme != null && SearchWindow.archiveStart(programme, now) != null
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.forLanguageTag("pl-PL")).withZone(ZoneId.systemDefault()) }
    val time = programme?.let { "${formatter.format(Instant.ofEpochMilli(it.start))} – ${formatter.format(Instant.ofEpochMilli(it.stop))}" }
    FocusSurface(onClick, modifier.fillMaxWidth(), focusedScale = 1f, onFocusChanged = { if (it) onFocused() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FLText(programme?.title ?: hit.channel.name, style = FireLiteTheme.typography.title, maxLines = 1)
                if (programme != null) {
                    FLText("${hit.channel.name} · $time", style = FireLiteTheme.typography.body, maxLines = 1)
                    val action = if (archived) R.string.search_archive else if (programme.start > now) R.string.search_upcoming else R.string.search_live
                    FLText(stringResource(action), style = FireLiteTheme.typography.body, color = FireLiteTheme.colors.textSecondary)
                }
            }
            if (CatchupUrlBuilder.isSupported(hit.channel) && (programme == null ||
                    (programme.start <= now && SearchWindow.includes(programme, now)))) ArchiveIcon()
            DeadChannelIcon(hit.channel.streamUrl)
        }
    }
}
