@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.remux.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.remux.app.network.ConnectionPhase
import dev.remux.app.network.TerminalPhase
import dev.remux.app.protocol.WindowInfo
import dev.remux.app.ui.terminal.TerminalKey
import dev.remux.app.ui.terminal.TerminalModes
import dev.remux.app.ui.terminal.TerminalView
import dev.remux.app.ui.terminal.TerminalViewListener
import dev.remux.app.ui.terminal.TerminalViewport
import kotlin.math.max
import kotlinx.coroutines.flow.collect

@Composable
fun TerminalWorkspaceScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val tabs = state.terminalTabs
    val imeVisible = WindowInsets.isImeVisible
    val keyboardFocusMode = imeVisible
    if (tabs.isEmpty()) {
        LaunchedEffect(Unit) { viewModel.leaveTerminals() }
        return
    }
    val activeIndex = max(0, tabs.indexOfFirst { it.id == state.activeTerminalId })
    val pagerState = rememberPagerState(initialPage = activeIndex) { tabs.size }

    LaunchedEffect(state.activeTerminalId, tabs.map(TerminalTabState::id)) {
        val target = tabs.indexOfFirst { it.id == state.activeTerminalId }
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }
    LaunchedEffect(pagerState.currentPage, tabs.map(TerminalTabState::id)) {
        tabs.getOrNull(pagerState.currentPage)?.let { viewModel.activateTerminal(it.id) }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFF0B1014),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (keyboardFocusMode) {
                // The IME leaves limited vertical space. Navigation remains
                // available through Android Back, which hides the IME before leaving this screen.
            } else {
                TopAppBar(
                    modifier = Modifier.height(56.dp),
                    windowInsets = WindowInsets(0),
                    navigationIcon = {
                        IconButton(onClick = viewModel::leaveTerminals) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back without detaching")
                        }
                    },
                    title = {
                        TerminalTabBar(
                            tabs = tabs,
                            activeId = state.activeTerminalId,
                            onActivate = viewModel::activateTerminal,
                            onClose = viewModel::closeTerminal,
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            beyondViewportPageCount = (tabs.size - 1).coerceAtLeast(0),
            userScrollEnabled = false,
            key = { index -> tabs[index].id },
        ) { page ->
            tabs.getOrNull(page)?.let { tab ->
                key(tab.handle.streamId) {
                    TerminalPage(
                        tab = tab,
                        relayConnected = state.relayStatus.phase == ConnectionPhase.CONNECTED,
                        viewModel = viewModel,
                        active = page == pagerState.currentPage,
                        tmuxPrefix = state.config.tmuxPrefix,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalTabBar(
    tabs: List<TerminalTabState>,
    activeId: String?,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab.id == activeId
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.combinedClickable(onClick = { onActivate(tab.id) }),
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when (tab.remoteStatus.phase) {
                            TerminalPhase.OPEN -> Color(0xFF45C98A)
                            TerminalPhase.CONNECTION_LOST -> Color(0xFFFFC857)
                            else -> Color(0xFFFF6B6B)
                        },
                        modifier = Modifier.size(6.dp),
                    ) {}
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.sessionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Detach ${tab.sessionName}", modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalPage(
    tab: TerminalTabState,
    relayConnected: Boolean,
    viewModel: MainViewModel,
    active: Boolean,
    tmuxPrefix: String,
) {
    var terminalView by remember(tab.handle.streamId) { mutableStateOf<TerminalView?>(null) }
    var pendingPaste by remember(tab.id) { mutableStateOf<String?>(null) }
    var inputMode by remember(tab.id) { mutableStateOf(TerminalInputMode.HIDDEN) }
    var systemImeSeen by remember(tab.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val listener = remember(tab.id, tab.handle.streamId, inputMode, imeVisible) {
        object : TerminalViewListener {
            override fun onReady(cols: Int, rows: Int) {
                viewModel.terminalRendererReady(tab.id, cols, rows)
            }

            override fun onInput(bytes: ByteArray) {
                viewModel.sendTerminalInput(tab.id, bytes)
            }

            override fun onResize(cols: Int, rows: Int) {
                viewModel.resizeTerminal(tab.id, cols, rows)
            }

            override fun onViewportChanged(viewport: TerminalViewport) {
                viewModel.updateViewport(tab.id, viewport)
            }

            override fun onModesChanged(modes: TerminalModes) {
                viewModel.updateModes(tab.id, modes)
            }

            override fun onTitleChanged(title: String) {
                viewModel.updateTerminalTitle(tab.id, title)
            }

            override fun onFontSizeChanged(value: Int) {
                viewModel.updateFontSize(tab.id, value)
            }

            override fun onFocusRequested() {
                if (inputMode != TerminalInputMode.SYSTEM || !imeVisible) {
                    terminalView?.setSystemKeyboardEnabled(false)
                    softwareKeyboard?.hide()
                    inputMode = TerminalInputMode.CUSTOM
                }
            }

            override fun onError(message: String) {
                viewModel.terminalRendererError(tab.id, message)
            }
        }
    }

    LaunchedEffect(terminalView, tab.handle) {
        val view = terminalView ?: return@LaunchedEffect
        tab.handle.output.collect(view::write)
    }
    LaunchedEffect(active, terminalView) {
        if (active) terminalView?.focusTerminal()
    }
    LaunchedEffect(tab.applicationPointer, terminalView) {
        terminalView?.setPointerMode(tab.applicationPointer)
    }
    LaunchedEffect(tab.fontSize, terminalView) {
        terminalView?.setFontSize(tab.fontSize)
    }
    LaunchedEffect(inputMode, terminalView, imeVisible) {
        val view = terminalView ?: return@LaunchedEffect
        when (inputMode) {
            TerminalInputMode.HIDDEN -> {
                view.setSystemKeyboardEnabled(false)
                softwareKeyboard?.hide()
            }
            TerminalInputMode.CUSTOM -> {
                view.setSystemKeyboardEnabled(false)
                softwareKeyboard?.hide()
            }
            TerminalInputMode.SYSTEM -> view.showSystemKeyboard()
        }
    }
    LaunchedEffect(inputMode, imeVisible) {
        if (inputMode == TerminalInputMode.SYSTEM && imeVisible) systemImeSeen = true
        if (inputMode == TerminalInputMode.SYSTEM && systemImeSeen && !imeVisible) {
            inputMode = TerminalInputMode.HIDDEN
            systemImeSeen = false
        }
    }

    BackHandler(enabled = inputMode == TerminalInputMode.CUSTOM) {
        inputMode = TerminalInputMode.HIDDEN
    }

    val requestPaste = {
        val value = clipboard.getText()?.text.orEmpty()
        when {
            value.isEmpty() -> viewModel.reportError("Clipboard is empty")
            '\u0000' in value -> viewModel.reportError("Paste rejected because it contains NUL")
            value.contains('\n') || value.length > 200 -> pendingPaste = value
            else -> viewModel.paste(tab.id, value)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        if (!imeVisible && inputMode != TerminalInputMode.CUSTOM) {
            TerminalStateRow(tab, relayConnected, terminalView, viewModel)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    TerminalView(context).also {
                        it.listener = listener
                        terminalView = it
                    }
                },
                update = { it.listener = listener },
                modifier = Modifier.fillMaxSize(),
            )
            if (!tab.viewport.atBottom) {
                HistoryControls(
                    viewport = tab.viewport,
                    onPageUp = { terminalView?.scrollPages(-1) },
                    onPageDown = { terminalView?.scrollPages(1) },
                    onLive = { terminalView?.scrollToBottom() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }
            if (tab.remoteStatus.phase != TerminalPhase.OPEN || tab.rendererError != null) {
                Surface(
                    color = Color(0xE611181D),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            tab.rendererError ?: tab.remoteStatus.message ?: "Terminal disconnected",
                            color = Color.White,
                        )
                        Button(
                            onClick = { viewModel.reattachTerminal(tab.id) },
                            enabled = relayConnected,
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Text("Reattach")
                        }
                    }
                }
            }
        }
        if (inputMode == TerminalInputMode.CUSTOM && !imeVisible) {
            TerminalKeyboard(
                tab = tab,
                tmuxPrefix = tmuxPrefix,
                onText = { viewModel.sendTerminalInput(tab.id, it.encodeToByteArray()) },
                onKey = { viewModel.sendKey(tab.id, it) },
                onControlC = { viewModel.sendControlC(tab.id) },
                onModifier = { ctrl, locked -> viewModel.setModifier(tab.id, ctrl, locked) },
                onPaste = requestPaste,
                onSystemIme = {
                    systemImeSeen = false
                    inputMode = TerminalInputMode.SYSTEM
                },
                onHide = { inputMode = TerminalInputMode.HIDDEN },
            )
        }
    }

    DisposableEffect(tab.handle.streamId) {
        onDispose {
            terminalView?.destroy()
            terminalView = null
        }
    }

    pendingPaste?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text("Paste ${value.length} characters?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (tab.modes.bracketedPaste) {
                            "Remote application requested bracketed paste."
                        } else {
                            "Bracketed paste is not active; each newline may execute a command."
                        },
                    )
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            visibleControls(value).take(1200),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.paste(tab.id, value); pendingPaste = null }) {
                    Text("Paste")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPaste = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HistoryControls(
    viewport: TerminalViewport,
    onPageUp: () -> Unit,
    onPageDown: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            color = Color(0xE6172229),
            contentColor = Color(0xFFDCE7EE),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPageUp,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "One page older")
                }
                IconButton(
                    onClick = onPageDown,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "One page newer")
                }
                TextButton(
                    onClick = onLive,
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        if (viewport.unread > 0) "Live · ${viewport.unread}" else "Live",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalStateRow(
    tab: TerminalTabState,
    relayConnected: Boolean,
    terminalView: TerminalView?,
    viewModel: MainViewModel,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        FilledTonalButton(
            onClick = { viewModel.createTerminalWindow(tab.id) },
            enabled = !tab.windowBusy && tab.remoteStatus.phase == TerminalPhase.OPEN,
            modifier = Modifier.height(34.dp),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(15.dp))
            Text("Window", style = MaterialTheme.typography.labelSmall)
        }
        tab.windows.sortedBy(WindowInfo::index).forEach { window ->
            FilterChip(
                selected = window.active,
                onClick = { viewModel.selectTerminalWindow(tab.id, window.id) },
                enabled = !tab.windowBusy && tab.remoteStatus.phase == TerminalPhase.OPEN,
                label = {
                    Text(
                        "${window.index}:${window.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
        IconButton(
            onClick = { viewModel.refreshTerminalWindows(tab.id) },
            enabled = !tab.windowBusy,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(Icons.Default.Refresh, "Refresh windows", modifier = Modifier.size(18.dp))
        }
        StatePill(
            text = when {
                !relayConnected -> "Reconnecting"
                tab.remoteStatus.phase == TerminalPhase.OPEN -> "Live"
                else -> tab.remoteStatus.phase.name.lowercase().replace('_', ' ')
            },
            active = relayConnected && tab.remoteStatus.phase == TerminalPhase.OPEN,
        )
        if (!tab.viewport.atBottom) {
            StatePill("History · ${tab.viewport.distance} lines", active = false)
        }
        if (tab.modes.alternateScreen) StatePill("Alt screen", active = false)
        FilledTonalButton(
            onClick = {
                val next = !tab.applicationPointer
                viewModel.setApplicationPointer(tab.id, next)
                terminalView?.setPointerMode(next)
            },
            modifier = Modifier.height(34.dp),
        ) {
            Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(15.dp))
            Text(
                if (tab.applicationPointer) "App gestures" else "History gestures",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        }
    }
}

@Composable
private fun StatePill(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) Color(0xFF174C3B) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun visibleControls(value: String): String = buildString {
    value.forEach { character ->
        append(
            when (character) {
                '\n' -> "↵\n"
                '\r' -> "␍"
                '\t' -> "⇥"
                else -> if (character.code < 0x20 || character.code == 0x7f) {
                    "<0x${character.code.toString(16).padStart(2, '0')}>"
                } else {
                    character
                }
            },
        )
    }
}
