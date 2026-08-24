package dev.remux.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.remux.app.BuildConfig
import dev.remux.app.data.AppConfig
import dev.remux.app.data.RelayProfile
import dev.remux.app.data.SecureConfigStore
import dev.remux.app.network.ConnectionPhase
import dev.remux.app.network.RelayClient
import dev.remux.app.network.RelayStatus
import dev.remux.app.network.TerminalHandle
import dev.remux.app.network.TerminalPhase
import dev.remux.app.network.TerminalStatus
import dev.remux.app.protocol.Command
import dev.remux.app.protocol.CommandResult
import dev.remux.app.protocol.MachineInfo
import dev.remux.app.protocol.PaneInfo
import dev.remux.app.protocol.RelayQuickConnectParser
import dev.remux.app.protocol.SessionInfo
import dev.remux.app.protocol.SizePolicy
import dev.remux.app.protocol.WindowInfo
import dev.remux.app.ui.terminal.ModifierMode
import dev.remux.app.ui.terminal.TerminalKey
import dev.remux.app.ui.terminal.TerminalKeyEncoder
import dev.remux.app.ui.terminal.TerminalModes
import dev.remux.app.ui.terminal.TerminalViewport
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppScreen {
    SETUP,
    MACHINES,
    MACHINE_DETAIL,
    TERMINALS,
    SETTINGS,
}

data class TerminalTabState(
    val id: String,
    val machineId: String,
    val machineName: String,
    val sessionId: String,
    val sessionName: String,
    val handle: TerminalHandle,
    val remoteStatus: TerminalStatus = TerminalStatus(),
    val viewport: TerminalViewport = TerminalViewport(),
    val modes: TerminalModes = TerminalModes(),
    val title: String? = null,
    val fontSize: Int = 12,
    val cols: Int = DEFAULT_TERMINAL_COLS,
    val rows: Int = DEFAULT_TERMINAL_ROWS,
    val applicationPointer: Boolean = false,
    val ctrl: ModifierMode = ModifierMode.OFF,
    val alt: ModifierMode = ModifierMode.OFF,
    val rendererError: String? = null,
    val windows: List<WindowInfo> = emptyList(),
    val windowBusy: Boolean = false,
)

data class AppUiState(
    val loadingConfig: Boolean = true,
    val screen: AppScreen = AppScreen.SETUP,
    val config: AppConfig = AppConfig.fresh(),
    val relayStatus: RelayStatus = RelayStatus(),
    val onlineMachines: Map<String, MachineInfo> = emptyMap(),
    val search: String = "",
    val onlineOnly: Boolean = false,
    val selectedMachineId: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val expandedSessionId: String? = null,
    val windowsBySession: Map<String, List<WindowInfo>> = emptyMap(),
    val expandedWindowId: String? = null,
    val panesByWindow: Map<String, List<PaneInfo>> = emptyMap(),
    val terminalTabs: List<TerminalTabState> = emptyList(),
    val activeTerminalId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureConfigStore(application)
    private val mutableState = MutableStateFlow(AppUiState())
    private val relayCollectors = mutableListOf<Job>()
    private val terminalCollectors = mutableMapOf<String, Job>()
    private var relayClient: RelayClient? = null

    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { store.load() }
                .onSuccess { config ->
                    mutableState.update {
                        it.copy(
                            loadingConfig = false,
                            config = config,
                            screen = if (config.relay == null) AppScreen.SETUP else AppScreen.MACHINES,
                        )
                    }
                    replaceRelayClient(config)
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            loadingConfig = false,
                            screen = AppScreen.SETUP,
                            message = "Cannot decrypt local configuration: ${error.userMessage()}",
                        )
                    }
                }
        }
    }

    fun configureRelay(name: String, relayUrl: String, token: String, clientName: String) {
        launchOperation {
            val normalizedUrl = validateRelayProfile(relayUrl, token)
            require(clientName.trim().isNotEmpty()) { "Client name cannot be empty" }
            val current = mutableState.value.config
            val updated = current.copy(
                clientName = clientName.trim(),
                relay = RelayProfile(
                    name = name.trim().ifEmpty { "Relay" },
                    relayUrl = normalizedUrl,
                    clientToken = token,
                ),
            )
            store.save(updated)
            mutableState.update { it.copy(config = updated, screen = AppScreen.MACHINES) }
            replaceRelayClient(updated)
        }
    }

    fun configureQuickConnect(value: String) {
        runCatching {
            RelayQuickConnectParser.parse(
                value,
                defaultScheme = if (BuildConfig.DEBUG) "ws" else "wss",
            )
        }.onSuccess { quick ->
            configureRelay(
                name = "Quick Connect",
                relayUrl = quick.relayUrl,
                token = quick.clientToken,
                clientName = mutableState.value.config.clientName,
            )
        }.onFailure(::showError)
    }

    fun toggleFavorite(machineId: String) {
        viewModelScope.launch {
            runCatching {
                val current = mutableState.value.config
                val favorites = if (machineId in current.favoriteMachineIds) {
                    current.favoriteMachineIds - machineId
                } else {
                    current.favoriteMachineIds + machineId
                }
                val updated = current.copy(favoriteMachineIds = favorites)
                store.save(updated)
                mutableState.update { it.copy(config = updated) }
            }
                .onFailure(::showError)
        }
    }

    fun updateTmuxPrefix(value: String) {
        launchOperation {
            val normalized = value.trim()
            TerminalKeyEncoder.encodeTmuxPrefix(normalized)
            val updated = mutableState.value.config.copy(tmuxPrefix = normalized)
            store.save(updated)
            mutableState.update { it.copy(config = updated, message = "tmux shortcut saved") }
        }
    }

    fun setSearch(value: String) = mutableState.update { it.copy(search = value) }

    fun setOnlineOnly(value: Boolean) = mutableState.update { it.copy(onlineOnly = value) }

    fun showSettings() = mutableState.update { it.copy(screen = AppScreen.SETTINGS) }

    fun showMachines() = mutableState.update { it.copy(screen = AppScreen.MACHINES) }

    fun openMachine(machineId: String) {
        launchOperation {
            val currentConfig = mutableState.value.config
            val updated = currentConfig.copy(
                recentMachineIds = (listOf(machineId) + currentConfig.recentMachineIds)
                    .distinct()
                    .take(20),
            )
            store.save(updated)
            mutableState.update {
                it.copy(
                    config = updated,
                    selectedMachineId = machineId,
                    screen = AppScreen.MACHINE_DETAIL,
                    sessions = emptyList(),
                    windowsBySession = emptyMap(),
                    panesByWindow = emptyMap(),
                )
            }
            refreshSessionsDirect()
        }
    }

    fun refreshSessions() {
        launchOperation {
            val result = requireRelay().request(selectedMachineId(), Command.ListSessions)
            require(result is CommandResult.Sessions) { "Agent returned an invalid sessions response" }
            mutableState.update { it.copy(sessions = result.sessions) }
        }
    }

    fun createSession(name: String, cwd: String?) {
        launchOperation {
            require(name.trim().isNotEmpty()) { "Session name cannot be empty" }
            val result = requireRelay().request(
                selectedMachineId(),
                Command.CreateSession(name.trim(), cwd.cleanOptional()),
            )
            require(result is CommandResult.SessionCreated) { "Agent returned an invalid create response" }
            refreshSessionsDirect()
            mutableState.update { it.copy(message = "Created ${result.session.name}") }
        }
    }

    fun renameSession(sessionId: String, name: String) {
        launchOperation {
            require(name.trim().isNotEmpty()) { "Session name cannot be empty" }
            requireRelay().request(selectedMachineId(), Command.RenameSession(sessionId, name.trim()))
            refreshSessionsDirect()
        }
    }

    fun killSession(sessionId: String) {
        launchOperation {
            requireRelay().request(selectedMachineId(), Command.KillSession(sessionId))
            refreshSessionsDirect()
            mutableState.update { it.copy(message = "Session terminated") }
        }
    }

    fun toggleSessionDetails(sessionId: String) {
        if (mutableState.value.expandedSessionId == sessionId) {
            mutableState.update { it.copy(expandedSessionId = null, expandedWindowId = null) }
            return
        }
        mutableState.update { it.copy(expandedSessionId = sessionId, expandedWindowId = null) }
        loadWindows(sessionId)
    }

    fun loadWindows(sessionId: String) {
        launchOperation {
            val result = requireRelay().request(selectedMachineId(), Command.ListWindows(sessionId))
            require(result is CommandResult.Windows) { "Agent returned an invalid windows response" }
            mutableState.update {
                it.copy(windowsBySession = it.windowsBySession + (sessionId to result.windows))
            }
        }
    }

    fun createWindow(sessionId: String, name: String?, cwd: String?) {
        launchOperation {
            requireRelay().request(
                selectedMachineId(),
                Command.CreateWindow(sessionId, name.cleanOptional(), cwd.cleanOptional()),
            )
            loadWindowsDirect(sessionId)
        }
    }

    fun renameWindow(sessionId: String, windowId: String, name: String) {
        launchOperation {
            require(name.trim().isNotEmpty()) { "Window name cannot be empty" }
            requireRelay().request(selectedMachineId(), Command.RenameWindow(windowId, name.trim()))
            loadWindowsDirect(sessionId)
        }
    }

    fun killWindow(sessionId: String, windowId: String) {
        launchOperation {
            requireRelay().request(selectedMachineId(), Command.KillWindow(windowId))
            loadWindowsDirect(sessionId)
            mutableState.update { it.copy(message = "Window terminated") }
        }
    }

    fun toggleWindowDetails(windowId: String) {
        if (mutableState.value.expandedWindowId == windowId) {
            mutableState.update { it.copy(expandedWindowId = null) }
            return
        }
        mutableState.update { it.copy(expandedWindowId = windowId) }
        loadPanes(windowId)
    }

    fun loadPanes(windowId: String) {
        launchOperation {
            val result = requireRelay().request(selectedMachineId(), Command.ListPanes(windowId))
            require(result is CommandResult.Panes) { "Agent returned an invalid panes response" }
            mutableState.update { it.copy(panesByWindow = it.panesByWindow + (windowId to result.panes)) }
        }
    }

    fun attach(session: SessionInfo, sizePolicy: SizePolicy = SizePolicy.TAKE_CONTROL) {
        val current = mutableState.value
        val machineId = current.selectedMachineId ?: return
        current.terminalTabs.firstOrNull {
            it.machineId == machineId && it.sessionId == session.id &&
                it.remoteStatus.phase == TerminalPhase.OPEN
        }?.let { existing ->
            mutableState.update {
                it.copy(screen = AppScreen.TERMINALS, activeTerminalId = existing.id)
            }
            return
        }
        if (current.terminalTabs.size >= MAX_TERMINAL_TABS) {
            mutableState.update {
                it.copy(message = "Close a terminal tab before opening another (limit $MAX_TERMINAL_TABS)")
            }
            return
        }
        launchOperation {
            val machine = mutableState.value.onlineMachines[machineId]
                ?: error("Machine is offline")
            val handle = requireRelay().openTerminal(
                machineId = machineId,
                sessionId = session.id,
                cols = DEFAULT_TERMINAL_COLS,
                rows = DEFAULT_TERMINAL_ROWS,
                sizePolicy = sizePolicy,
            )
            val tab = TerminalTabState(
                id = UUID.randomUUID().toString(),
                machineId = machineId,
                machineName = machine.name,
                sessionId = session.id,
                sessionName = session.name,
                handle = handle,
            )
            mutableState.update {
                it.copy(
                    terminalTabs = it.terminalTabs + tab,
                    activeTerminalId = tab.id,
                    screen = AppScreen.TERMINALS,
                )
            }
            observeTerminal(tab)
            loadTerminalWindowsDirect(tab.id)
        }
    }

    fun showTerminals(tabId: String? = mutableState.value.activeTerminalId) {
        if (tabId == null) return
        mutableState.update { it.copy(screen = AppScreen.TERMINALS, activeTerminalId = tabId) }
    }

    fun leaveTerminals() = mutableState.update {
        it.copy(
            screen = if (it.selectedMachineId == null) AppScreen.MACHINES else AppScreen.MACHINE_DETAIL,
        )
    }

    fun activateTerminal(tabId: String) {
        mutableState.update { it.copy(activeTerminalId = tabId) }
    }

    fun closeTerminal(tabId: String) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        terminalCollectors.remove(tabId)?.cancel()
        viewModelScope.launch { runCatching { tab.handle.detach() } }
        mutableState.update { current ->
            val remaining = current.terminalTabs.filterNot { it.id == tabId }
            val nextActive = when {
                current.activeTerminalId != tabId -> current.activeTerminalId
                remaining.isNotEmpty() -> remaining.last().id
                else -> null
            }
            current.copy(
                terminalTabs = remaining,
                activeTerminalId = nextActive,
                screen = if (remaining.isEmpty()) AppScreen.MACHINE_DETAIL else current.screen,
            )
        }
    }

    fun reattachTerminal(tabId: String) {
        val old = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        launchOperation {
            runCatching { old.handle.detach() }
            val handle = requireRelay().openTerminal(
                machineId = old.machineId,
                sessionId = old.sessionId,
                cols = old.cols,
                rows = old.rows,
                sizePolicy = SizePolicy.TAKE_CONTROL,
            )
            val replacement = old.copy(
                handle = handle,
                remoteStatus = TerminalStatus(),
                viewport = TerminalViewport(),
                modes = TerminalModes(),
                rendererError = null,
                windowBusy = false,
            )
            terminalCollectors.remove(tabId)?.cancel()
            mutableState.update { current ->
                current.copy(
                    terminalTabs = current.terminalTabs.map {
                        if (it.id == tabId) replacement else it
                    },
                )
            }
            observeTerminal(replacement)
            loadTerminalWindowsDirect(tabId)
        }
    }

    fun createTerminalWindow(tabId: String) {
        runTerminalWindowOperation(tabId) { tab, machineId ->
            val created = requireRelay().request(
                machineId,
                Command.CreateWindow(tab.sessionId, name = null, cwd = null),
            )
            require(created is CommandResult.WindowCreated) {
                "Agent returned an invalid create window response"
            }
            tab.handle.selectWindow(created.window.id)
            loadTerminalWindowsDirect(tabId)
        }
    }

    fun selectTerminalWindow(tabId: String, windowId: String) {
        runTerminalWindowOperation(tabId) { tab, _ ->
            require(tab.windows.any { it.id == windowId }) {
                "Window is no longer available; refresh the list"
            }
            tab.handle.selectWindow(windowId)
            loadTerminalWindowsDirect(tabId)
        }
    }

    fun refreshTerminalWindows(tabId: String) {
        runTerminalWindowOperation(tabId) { _, _ -> loadTerminalWindowsDirect(tabId) }
    }

    fun sendTerminalInput(tabId: String, input: ByteArray, applyModifiers: Boolean = true) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        if (tab.remoteStatus.phase != TerminalPhase.OPEN) {
            mutableState.update { it.copy(message = "Terminal is not connected; reattach before typing") }
            return
        }
        val bytes = if (applyModifiers) {
            TerminalKeyEncoder.applyTextModifiers(
                input,
                ctrl = tab.ctrl != ModifierMode.OFF,
                alt = tab.alt != ModifierMode.OFF,
            )
        } else {
            input
        }
        consumeArmedModifiers(tabId)
        viewModelScope.launch {
            runCatching {
                bytes.asList().chunked(32 * 1024).forEach { chunk ->
                    tab.handle.send(chunk.toByteArray())
                }
            }.onFailure(::showError)
        }
    }

    fun sendKey(tabId: String, key: TerminalKey) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        val bytes = TerminalKeyEncoder.encode(
            key,
            ctrl = tab.ctrl != ModifierMode.OFF,
            alt = tab.alt != ModifierMode.OFF,
            tmuxPrefix = mutableState.value.config.tmuxPrefix,
        )
        sendTerminalInput(tabId, bytes, applyModifiers = false)
    }

    fun sendControlC(tabId: String) = sendTerminalInput(
        tabId,
        byteArrayOf(0x03),
        applyModifiers = false,
    )

    fun paste(tabId: String, text: String) {
        if ('\u0000' in text) {
            reportError("Paste rejected because it contains NUL")
            return
        }
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        val bytes = text.encodeToByteArray()
        val payload = if (tab.modes.bracketedPaste) {
            "\u001b[200~".encodeToByteArray() + bytes + "\u001b[201~".encodeToByteArray()
        } else {
            bytes
        }
        sendTerminalInput(tabId, payload, applyModifiers = false)
    }

    fun resizeTerminal(tabId: String, cols: Int, rows: Int) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        if (!isValidTerminalSize(cols, rows)) return
        if (tab.cols == cols && tab.rows == rows) return
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        viewModelScope.launch {
            runCatching { tab.handle.resize(cols, rows) }.onFailure(::showError)
        }
    }

    fun terminalRendererReady(tabId: String, cols: Int, rows: Int) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        if (!isValidTerminalSize(cols, rows)) {
            terminalRendererError(tabId, "Terminal viewport is too small (${cols}x$rows)")
            return
        }
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        viewModelScope.launch {
            runCatching {
                tab.handle.resize(cols, rows)
                tab.handle.refresh()
            }.onFailure(::showError)
        }
    }

    fun setModifier(tabId: String, ctrl: Boolean, locked: Boolean) {
        updateTab(tabId) { tab ->
            val current = if (ctrl) tab.ctrl else tab.alt
            val next = when {
                locked && current == ModifierMode.LOCKED -> ModifierMode.OFF
                locked -> ModifierMode.LOCKED
                current == ModifierMode.OFF -> ModifierMode.ARMED
                else -> ModifierMode.OFF
            }
            if (ctrl) tab.copy(ctrl = next) else tab.copy(alt = next)
        }
    }

    fun updateViewport(tabId: String, viewport: TerminalViewport) =
        updateTab(tabId) { it.copy(viewport = viewport) }

    fun updateModes(tabId: String, modes: TerminalModes) =
        updateTab(tabId) { it.copy(modes = modes) }

    fun updateTerminalTitle(tabId: String, title: String) =
        updateTab(tabId) { it.copy(title = title.take(80)) }

    fun updateFontSize(tabId: String, value: Int) =
        updateTab(tabId) { it.copy(fontSize = value.coerceIn(8, 28)) }

    fun setApplicationPointer(tabId: String, enabled: Boolean) =
        updateTab(tabId) { it.copy(applicationPointer = enabled) }

    fun terminalRendererError(tabId: String, message: String) =
        updateTab(tabId) { it.copy(rendererError = message) }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun reportError(message: String) = mutableState.update { it.copy(message = message) }

    private fun replaceRelayClient(config: AppConfig) {
        relayCollectors.forEach(Job::cancel)
        relayCollectors.clear()
        relayClient?.close()
        val profile = config.relay ?: run {
            relayClient = null
            return
        }
        val client = RelayClient(
            profile = profile,
            clientId = config.clientId,
            clientName = config.clientName,
            scope = viewModelScope,
        )
        relayClient = client
        relayCollectors += viewModelScope.launch {
            client.status.collect { status -> mutableState.update { it.copy(relayStatus = status) } }
        }
        relayCollectors += viewModelScope.launch {
            client.machines.collect { machines ->
                mutableState.update { it.copy(onlineMachines = machines) }
            }
        }
        client.start()
    }

    private fun observeTerminal(tab: TerminalTabState) {
        terminalCollectors[tab.id] = viewModelScope.launch {
            tab.handle.status.collect { status ->
                updateTab(tab.id) { current -> current.copy(remoteStatus = status) }
            }
        }
    }

    private fun updateTab(tabId: String, transform: (TerminalTabState) -> TerminalTabState) {
        mutableState.update { current ->
            current.copy(
                terminalTabs = current.terminalTabs.map {
                    if (it.id == tabId) transform(it) else it
                },
            )
        }
    }

    private fun consumeArmedModifiers(tabId: String) = updateTab(tabId) { tab ->
        tab.copy(
            ctrl = if (tab.ctrl == ModifierMode.ARMED) ModifierMode.OFF else tab.ctrl,
            alt = if (tab.alt == ModifierMode.ARMED) ModifierMode.OFF else tab.alt,
        )
    }

    private suspend fun refreshSessionsDirect() {
        val result = requireRelay().request(selectedMachineId(), Command.ListSessions)
        require(result is CommandResult.Sessions)
        mutableState.update { it.copy(sessions = result.sessions) }
    }

    private suspend fun loadWindowsDirect(sessionId: String) {
        val result = requireRelay().request(selectedMachineId(), Command.ListWindows(sessionId))
        require(result is CommandResult.Windows)
        mutableState.update {
            it.copy(windowsBySession = it.windowsBySession + (sessionId to result.windows))
        }
    }

    private suspend fun loadTerminalWindowsDirect(tabId: String) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        val result = requireRelay().request(
            tab.machineId,
            Command.ListWindows(tab.sessionId),
        )
        require(result is CommandResult.Windows) { "Agent returned an invalid windows response" }
        updateTab(tabId) { it.copy(windows = result.windows) }
    }

    private fun runTerminalWindowOperation(
        tabId: String,
        block: suspend (TerminalTabState, String) -> Unit,
    ) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        if (tab.windowBusy || tab.remoteStatus.phase != TerminalPhase.OPEN) return
        updateTab(tabId) { it.copy(windowBusy = true) }
        viewModelScope.launch {
            runCatching { block(tab, tab.machineId) }.onFailure(::showError)
            updateTab(tabId) { it.copy(windowBusy = false) }
        }
    }

    private fun selectedMachineId(): String =
        mutableState.value.selectedMachineId ?: error("No machine is selected")

    private fun requireRelay(): RelayClient = relayClient ?: error("Relay is not configured")

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            runCatching { block() }.onFailure(::showError)
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun showError(error: Throwable) {
        mutableState.update { it.copy(message = error.userMessage()) }
    }

    private fun validateRelayProfile(url: String, token: String): String {
        val normalized = url.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrElse { throw IllegalArgumentException("Invalid Relay URL") }
        require(uri.scheme == "ws" || uri.scheme == "wss") { "Relay URL must start with ws:// or wss://" }
        require(BuildConfig.DEBUG || uri.scheme == "wss") { "Release builds require a wss:// Relay URL" }
        require(!uri.host.isNullOrBlank()) { "Relay URL must include a host" }
        require(token.length >= 16) { "Relay client token must be at least 16 characters" }
        return normalized
    }

    override fun onCleared() {
        terminalCollectors.values.forEach(Job::cancel)
        relayCollectors.forEach(Job::cancel)
        relayClient?.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_TERMINAL_TABS = 6
    }
}

private const val DEFAULT_TERMINAL_COLS = 80
private const val DEFAULT_TERMINAL_ROWS = 24
private const val MIN_TERMINAL_COLS = 20
private const val MAX_TERMINAL_COLS = 1000
private const val MIN_TERMINAL_ROWS = 5
private const val MAX_TERMINAL_ROWS = 500

private fun isValidTerminalSize(cols: Int, rows: Int): Boolean =
    cols in MIN_TERMINAL_COLS..MAX_TERMINAL_COLS &&
        rows in MIN_TERMINAL_ROWS..MAX_TERMINAL_ROWS

private fun String?.cleanOptional(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun Throwable.userMessage(): String = message ?: this::class.java.simpleName
