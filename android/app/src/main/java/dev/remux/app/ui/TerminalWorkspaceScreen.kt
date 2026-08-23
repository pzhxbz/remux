@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package dev.remux.app.ui

import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.remux.app.network.ConnectionPhase
import dev.remux.app.network.TerminalPhase
import dev.remux.app.ui.terminal.ModifierMode
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
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::leaveTerminals) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back without detaching")
                        }
                    },
                    title = {
                        val active = tabs.getOrNull(activeIndex)
                        Column {
                            Text(active?.sessionName ?: "Terminal")
                            Text(
                                active?.machineName.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
                TerminalTabBar(
                    tabs = tabs,
                    activeId = state.activeTerminalId,
                    onActivate = viewModel::activateTerminal,
                    onClose = viewModel::closeTerminal,
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab.id == activeId
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.combinedClickable(onClick = { onActivate(tab.id) }),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when (tab.remoteStatus.phase) {
                            TerminalPhase.OPEN -> Color(0xFF45C98A)
                            TerminalPhase.CONNECTION_LOST -> Color(0xFFFFC857)
                            else -> Color(0xFFFF6B6B)
                        },
                        modifier = Modifier.size(8.dp),
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tab.sessionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Default.Close, "Detach ${tab.sessionName}", modifier = Modifier.size(18.dp))
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
    val clipboard = LocalClipboardManager.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val imeVisible = WindowInsets.isImeVisible
    val listener = remember(tab.id, tab.handle.streamId) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        if (!imeVisible) {
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
                FilledTonalButton(
                    onClick = { terminalView?.scrollToBottom() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null)
                    Text(
                        if (tab.viewport.unread > 0) "Live · ${tab.viewport.unread} new" else "Back to live",
                    )
                }
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
        ExtraKeyBar(
            tab = tab,
            onKey = { viewModel.sendKey(tab.id, it) },
            onControlC = { viewModel.sendControlC(tab.id) },
            onModifier = { ctrl, locked -> viewModel.setModifier(tab.id, ctrl, locked) },
            onPaste = {
                val value = clipboard.getText()?.text.orEmpty()
                when {
                    value.isEmpty() -> viewModel.reportError("Clipboard is empty")
                    '\u0000' in value -> viewModel.reportError("Paste rejected because it contains NUL")
                    value.contains('\n') || value.length > 200 -> pendingPaste = value
                    else -> viewModel.paste(tab.id, value)
                }
            },
            compact = imeVisible || landscape,
            tmuxPrefix = tmuxPrefix,
        )
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
private fun TerminalStateRow(
    tab: TerminalTabState,
    relayConnected: Boolean,
    terminalView: TerminalView?,
    viewModel: MainViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            modifier = Modifier.height(36.dp),
        ) {
            Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(17.dp))
            Text(if (tab.applicationPointer) "App gestures" else "History gestures")
        }
    }
}

@Composable
private fun StatePill(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) Color(0xFF174C3B) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun ExtraKeyBar(
    tab: TerminalTabState,
    onKey: (TerminalKey) -> Unit,
    onControlC: () -> Unit,
    onModifier: (ctrl: Boolean, locked: Boolean) -> Unit,
    onPaste: () -> Unit,
    compact: Boolean,
    tmuxPrefix: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExtraKeyButton(TerminalKey.ESC, onKey)
            ExtraKeyButton(TerminalKey.TAB, onKey)
            ExtraKeyButton(TerminalKey.TMUX_PREFIX, onKey, label = tmuxPrefix)
            ModifierButton("Ctrl", tab.ctrl, ctrl = true, onModifier)
            ModifierButton("Alt", tab.alt, ctrl = false, onModifier)
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .size(width = 58.dp, height = 48.dp)
                    .combinedClickable(onClick = onControlC)
                    .semantics { contentDescription = "Send Control C" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("^C", fontWeight = FontWeight.Bold)
                }
            }
            ExtraKeyButton(TerminalKey.UP, onKey)
            ExtraKeyButton(TerminalKey.DOWN, onKey)
            ExtraKeyButton(TerminalKey.LEFT, onKey)
            ExtraKeyButton(TerminalKey.RIGHT, onKey)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(width = 58.dp, height = 48.dp)
                    .combinedClickable(onClick = onPaste)
                    .semantics { contentDescription = "Paste clipboard" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (!compact) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    TerminalKey.HOME,
                    TerminalKey.END,
                    TerminalKey.PAGE_UP,
                    TerminalKey.PAGE_DOWN,
                    TerminalKey.INSERT,
                    TerminalKey.DELETE,
                    TerminalKey.F1,
                    TerminalKey.F2,
                    TerminalKey.F3,
                    TerminalKey.F4,
                    TerminalKey.F5,
                    TerminalKey.F6,
                    TerminalKey.F7,
                    TerminalKey.F8,
                    TerminalKey.F9,
                    TerminalKey.F10,
                    TerminalKey.F11,
                    TerminalKey.F12,
                    TerminalKey.PIPE,
                    TerminalKey.SLASH,
                    TerminalKey.BACKSLASH,
                    TerminalKey.DASH,
                    TerminalKey.UNDERSCORE,
                ).forEach { key ->
                    ExtraKeyButton(key = key, onKey = onKey)
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    key: TerminalKey,
    onKey: (TerminalKey) -> Unit,
    label: String = key.label,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(width = if (label.length > 4) 68.dp else 54.dp, height = 48.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKey(key)
                },
            )
            .semantics { contentDescription = key.contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ModifierButton(
    label: String,
    mode: ModifierMode,
    ctrl: Boolean,
    onModifier: (Boolean, Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when (mode) {
            ModifierMode.OFF -> MaterialTheme.colorScheme.surfaceVariant
            ModifierMode.ARMED -> MaterialTheme.colorScheme.secondaryContainer
            ModifierMode.LOCKED -> MaterialTheme.colorScheme.primaryContainer
        },
        modifier = Modifier
            .size(width = 62.dp, height = 48.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onModifier(ctrl, false)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onModifier(ctrl, true)
                },
            )
            .semantics {
                contentDescription = "$label modifier"
                stateDescription = mode.name.lowercase()
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                when (mode) {
                    ModifierMode.LOCKED -> "$label 🔒"
                    ModifierMode.ARMED -> "$label ·"
                    ModifierMode.OFF -> label
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
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
