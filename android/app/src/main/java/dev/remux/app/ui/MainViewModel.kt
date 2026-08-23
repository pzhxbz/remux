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
import dev.remux.app.protocol.PairingBundle
import dev.remux.app.protocol.PairingToml
import dev.remux.app.protocol.PaneInfo
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
    val fontSize: Int = 14,
    val applicationPointer: Boolean = false,
    val ctrl: ModifierMode = ModifierMode.OFF,
    val alt: ModifierMode = ModifierMode.OFF,
    val rendererError: String? = null,
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
            val incompatible = current.pairings.any { it.relayUrl.trimEnd('/') != normalizedUrl }
            require(!incompatible) {
                "Imported pairings belong to another relay; remove or replace them before changing relay"
            }
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

    fun importPairing(text: String) {
        launchOperation {
            val pairing = PairingToml.parse(text)
            val current = mutableState.value.config
            val relay = current.relay ?: error("Configure a Relay profile before importing a pairing")
            require(pairing.relayUrl.trimEnd('/') == relay.relayUrl.trimEnd('/')) {
                "This pairing belongs to ${pairing.relayUrl}, not the active Relay"
            }
            val existing = current.pairings.firstOrNull { it.machineId == pairing.machineId }
            if (existing != null && existing != pairing) {
                detachMachineTerminals(pairing.machineId)
            }
            val updatedPairings = current.pairings
                .filterNot { it.machineId == pairing.machineId } + pairing
            val updated = current.copy(pairings = updatedPairings)
            store.save(updated)
            relayClient?.updatePairings(updatedPairings)
            mutableState.update {
                it.copy(config = updated, message = "Paired ${pairing.machineName}")
            }
        }
    }

    fun removePairing(machineId: String) {
        launchOperation {
            detachMachineTerminals(machineId)
            val current = mutableState.value.config
            val updated = current.copy(
                pairings = current.pairings.filterNot { it.machineId == machineId },
                favoriteMachineIds = current.favoriteMachineIds - machineId,
                recentMachineIds = current.recentMachineIds - machineId,
            )
            store.save(updated)
            relayClient?.updatePairings(updated.pairings)
            mutableState.update {
                it.copy(
                    config = updated,
                    selectedMachineId = if (it.selectedMachineId == machineId) null else it.selectedMachineId,
                    screen = if (it.selectedMachineId == machineId) AppScreen.MACHINES else it.screen,
                )
            }
        }
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
            val pairing = selectedPairing()
            val result = requireRelay().request(pairing, Command.ListSessions)
            require(result is CommandResult.Sessions) { "Agent returned an invalid sessions response" }
            mutableState.update { it.copy(sessions = result.sessions) }
        }
    }

    fun createSession(name: String, cwd: String?) {
        launchOperation {
            require(name.trim().isNotEmpty()) { "Session name cannot be empty" }
            val result = requireRelay().request(
                selectedPairing(),
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
            requireRelay().request(selectedPairing(), Command.RenameSession(sessionId, name.trim()))
            refreshSessionsDirect()
        }
    }

    fun killSession(sessionId: String) {
        launchOperation {
            requireRelay().request(selectedPairing(), Command.KillSession(sessionId))
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
            val result = requireRelay().request(selectedPairing(), Command.ListWindows(sessionId))
            require(result is CommandResult.Windows) { "Agent returned an invalid windows response" }
            mutableState.update {
                it.copy(windowsBySession = it.windowsBySession + (sessionId to result.windows))
            }
        }
    }

    fun createWindow(sessionId: String, name: String?, cwd: String?) {
        launchOperation {
            requireRelay().request(
                selectedPairing(),
                Command.CreateWindow(sessionId, name.cleanOptional(), cwd.cleanOptional()),
            )
            loadWindowsDirect(sessionId)
        }
    }

    fun renameWindow(sessionId: String, windowId: String, name: String) {
        launchOperation {
            require(name.trim().isNotEmpty()) { "Window name cannot be empty" }
            requireRelay().request(selectedPairing(), Command.RenameWindow(windowId, name.trim()))
            loadWindowsDirect(sessionId)
        }
    }

    fun killWindow(sessionId: String, windowId: String) {
        launchOperation {
            requireRelay().request(selectedPairing(), Command.KillWindow(windowId))
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
            val result = requireRelay().request(selectedPairing(), Command.ListPanes(windowId))
            require(result is CommandResult.Panes) { "Agent returned an invalid panes response" }
            mutableState.update { it.copy(panesByWindow = it.panesByWindow + (windowId to result.panes)) }
        }
    }

    fun attach(session: SessionInfo, sizePolicy: SizePolicy = SizePolicy.AUTO) {
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
            val pairing = selectedPairing()
            val handle = requireRelay().openTerminal(
                pairing = pairing,
                sessionId = session.id,
                cols = 80,
                rows = 24,
                sizePolicy = sizePolicy,
            )
            val tab = TerminalTabState(
                id = UUID.randomUUID().toString(),
                machineId = pairing.machineId,
                machineName = pairing.machineName,
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

    fun reattachTerminal(tabId: String, cols: Int = 80, rows: Int = 24) {
        val old = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
        launchOperation {
            val pairing = mutableState.value.config.pairings.first { it.machineId == old.machineId }
            runCatching { old.handle.detach() }
            val handle = requireRelay().openTerminal(pairing, old.sessionId, cols, rows)
            val replacement = old.copy(
                handle = handle,
                remoteStatus = TerminalStatus(),
                viewport = TerminalViewport(),
                modes = TerminalModes(),
                rendererError = null,
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
        }
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
        viewModelScope.launch { runCatching { tab.handle.resize(cols, rows) }.onFailure(::showError) }
    }

    fun terminalRendererReady(tabId: String, cols: Int, rows: Int) {
        val tab = mutableState.value.terminalTabs.firstOrNull { it.id == tabId } ?: return
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
            initialPairings = config.pairings,
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
        val result = requireRelay().request(selectedPairing(), Command.ListSessions)
        require(result is CommandResult.Sessions)
        mutableState.update { it.copy(sessions = result.sessions) }
    }

    private suspend fun loadWindowsDirect(sessionId: String) {
        val result = requireRelay().request(selectedPairing(), Command.ListWindows(sessionId))
        require(result is CommandResult.Windows)
        mutableState.update {
            it.copy(windowsBySession = it.windowsBySession + (sessionId to result.windows))
        }
    }

    private suspend fun detachMachineTerminals(machineId: String) {
        val tabs = mutableState.value.terminalTabs.filter { it.machineId == machineId }
        tabs.forEach { tab ->
            terminalCollectors.remove(tab.id)?.cancel()
            runCatching { tab.handle.detach() }
        }
        if (tabs.isEmpty()) return
        val removedIds = tabs.mapTo(mutableSetOf(), TerminalTabState::id)
        mutableState.update { current ->
            val remaining = current.terminalTabs.filterNot { it.id in removedIds }
            current.copy(
                terminalTabs = remaining,
                activeTerminalId = current.activeTerminalId
                    ?.takeIf { active -> remaining.any { it.id == active } }
                    ?: remaining.lastOrNull()?.id,
            )
        }
    }

    private fun selectedPairing(): PairingBundle {
        val machineId = mutableState.value.selectedMachineId
            ?: error("No machine is selected")
        return mutableState.value.config.pairings.firstOrNull { it.machineId == machineId }
            ?: error("Import this machine's pairing bundle before managing tmux")
    }

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

private fun String?.cleanOptional(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun Throwable.userMessage(): String = message ?: this::class.java.simpleName
