@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.remux.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remux.app.protocol.PaneInfo
import dev.remux.app.protocol.SessionInfo
import dev.remux.app.protocol.WindowInfo

private sealed interface EditTarget {
    data object NewSession : EditTarget
    data class RenameSession(val session: SessionInfo) : EditTarget
    data class NewWindow(val sessionId: String) : EditTarget
    data class RenameWindow(val sessionId: String, val window: WindowInfo) : EditTarget
}

private sealed interface DeleteTarget {
    data class Session(val session: SessionInfo) : DeleteTarget
    data class Window(val sessionId: String, val window: WindowInfo) : DeleteTarget
}

@Composable
fun MachineDetailScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val machineId = state.selectedMachineId
    val pairing = state.config.pairings.firstOrNull { it.machineId == machineId }
    val online = state.onlineMachines[machineId]
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::showMachines) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to machines")
                    }
                },
                title = {
                    Column {
                        Text(pairing?.machineName ?: online?.name ?: "Machine")
                        Text(
                            if (online == null) "Offline" else "Online · ${online.os}/${online.arch}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (online == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                },
                actions = {
                    if (state.terminalTabs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showTerminals() }) {
                            Icon(Icons.Default.Terminal, "Terminal tabs")
                        }
                    }
                    IconButton(onClick = viewModel::refreshSessions, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, "Refresh sessions")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editTarget = EditTarget.NewSession },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New session") },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (online == null) {
                Text(
                    "Agent is offline. Existing terminal tabs keep their last screen; no input is queued.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.sessions.isEmpty() && !state.busy) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(52.dp))
                    Text("No tmux sessions", style = MaterialTheme.typography.titleLarge)
                    Text("Create one to start the machine's default shell.")
                    Button(
                        onClick = { editTarget = EditTarget.NewSession },
                        modifier = Modifier.padding(top = 16.dp),
                    ) { Text("Create session") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.sessions, key = SessionInfo::id) { session ->
                        SessionCard(
                            session = session,
                            expanded = state.expandedSessionId == session.id,
                            windows = state.windowsBySession[session.id].orEmpty(),
                            expandedWindowId = state.expandedWindowId,
                            panesByWindow = state.panesByWindow,
                            onToggle = { viewModel.toggleSessionDetails(session.id) },
                            onAttach = { viewModel.attach(session) },
                            onRename = { editTarget = EditTarget.RenameSession(session) },
                            onDelete = { deleteTarget = DeleteTarget.Session(session) },
                            onNewWindow = { editTarget = EditTarget.NewWindow(session.id) },
                            onWindowToggle = viewModel::toggleWindowDetails,
                            onWindowRename = { window ->
                                editTarget = EditTarget.RenameWindow(session.id, window)
                            },
                            onWindowDelete = { window ->
                                deleteTarget = DeleteTarget.Window(session.id, window)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    editTarget?.let { target ->
        SessionWindowEditor(
            target = target,
            onDismiss = { editTarget = null },
            onConfirm = { name, cwd ->
                when (target) {
                    EditTarget.NewSession -> viewModel.createSession(name, cwd)
                    is EditTarget.RenameSession -> viewModel.renameSession(target.session.id, name)
                    is EditTarget.NewWindow -> viewModel.createWindow(target.sessionId, name, cwd)
                    is EditTarget.RenameWindow -> {
                        viewModel.renameWindow(target.sessionId, target.window.id, name)
                    }
                }
                editTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        val targetName = when (target) {
            is DeleteTarget.Session -> "session '${target.session.name}'"
            is DeleteTarget.Window -> "window '${target.window.name}'"
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Permanently terminate $targetName?") },
            text = {
                Text("All programs inside it, including Codex or Claude Code, will be terminated. Closing a terminal tab does not do this.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (target) {
                            is DeleteTarget.Session -> viewModel.killSession(target.session.id)
                            is DeleteTarget.Window -> viewModel.killWindow(target.sessionId, target.window.id)
                        }
                        deleteTarget = null
                    },
                ) { Text("Terminate permanently") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    expanded: Boolean,
    windows: List<WindowInfo>,
    expandedWindowId: String?,
    panesByWindow: Map<String, List<PaneInfo>>,
    onToggle: () -> Unit,
    onAttach: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewWindow: () -> Unit,
    onWindowToggle: (String) -> Unit,
    onWindowRename: (WindowInfo) -> Unit,
    onWindowDelete: (WindowInfo) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${session.windows} windows · ${session.attachedClients} attached · ${session.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onAttach) { Text("Attach") }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "Session actions")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Terminate") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            if (expanded) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Windows", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onNewWindow) {
                        Icon(Icons.Default.Add, null)
                        Text("New window")
                    }
                }
                if (windows.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    windows.forEach { window ->
                        WindowRow(
                            window = window,
                            expanded = expandedWindowId == window.id,
                            panes = panesByWindow[window.id].orEmpty(),
                            onToggle = { onWindowToggle(window.id) },
                            onRename = { onWindowRename(window) },
                            onDelete = { onWindowDelete(window) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowRow(
    window: WindowInfo,
    expanded: Boolean,
    panes: List<PaneInfo>,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${window.index}:${window.name}${if (window.active) " · active" else ""}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${window.panes} panes", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, "Window actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Terminate") },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
        if (expanded) {
            if (panes.isEmpty()) {
                Text("Loading panes…", modifier = Modifier.padding(start = 40.dp, bottom = 10.dp))
            } else {
                panes.forEach { pane ->
                    Text(
                        "${pane.id}  ${pane.command}  ${pane.currentPath}  ${pane.width}×${pane.height}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 40.dp, end = 16.dp, bottom = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun SessionWindowEditor(
    target: EditTarget,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    val isRename = target is EditTarget.RenameSession || target is EditTarget.RenameWindow
    val isSession = target is EditTarget.NewSession || target is EditTarget.RenameSession
    val initialName = when (target) {
        EditTarget.NewSession, is EditTarget.NewWindow -> ""
        is EditTarget.RenameSession -> target.session.name
        is EditTarget.RenameWindow -> target.window.name
    }
    var name by remember(target) { mutableStateOf(initialName) }
    var cwd by remember(target) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    isRename && isSession -> "Rename session"
                    isRename -> "Rename window"
                    isSession -> "Create tmux session"
                    else -> "Create tmux window"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isRename) "New name" else "Name${if (isSession) "" else " (optional)"}") },
                    singleLine = true,
                )
                if (!isRename) {
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        label = { Text("Working directory (optional)") },
                        singleLine = true,
                    )
                    Text("The Agent validates cwd and starts the machine's default shell. No arbitrary management command is sent.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, cwd.takeIf(String::isNotBlank)) },
                enabled = !isSession || name.isNotBlank(),
            ) { Text(if (isRename) "Rename" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onQuickConnect: (String) -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onTmuxPrefix: (String) -> Unit,
    onRemovePairing: (String) -> Unit,
    onImportPairing: () -> Unit,
) {
    val relay = state.config.relay
    var name by remember(relay?.name) { mutableStateOf(relay?.name.orEmpty()) }
    var url by remember(relay?.relayUrl) { mutableStateOf(relay?.relayUrl.orEmpty()) }
    var token by remember(relay?.clientToken) { mutableStateOf(relay?.clientToken.orEmpty()) }
    var clientName by remember(state.config.clientName) { mutableStateOf(state.config.clientName) }
    var tmuxPrefix by remember(state.config.tmuxPrefix) { mutableStateOf(state.config.tmuxPrefix) }
    var quickConnect by remember { mutableStateOf("") }
    var removeId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Quick connect", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = quickConnect,
                    onValueChange = { quickConnect = it },
                    label = { Text("server:port/secret") },
                    supportingText = { Text("Paste REMUX_APP_CONFIG from Relay startup") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { onQuickConnect(quickConnect) },
                    enabled = !state.busy && quickConnect.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply quick connect") }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                Text("Manual Relay profile", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("Relay URL") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Client token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(clientName, { clientName = it }, label = { Text("This device") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(
                    onClick = { onSave(name, url, token, clientName) },
                    enabled = !state.busy && token.length >= 16,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save Relay profile") }
            }
            item {
                Text(
                    "Release builds require wss://. Credentials and pairings are encrypted with an Android Keystore key and excluded from backups.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { Text("Terminal shortcuts", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    tmuxPrefix,
                    { tmuxPrefix = it },
                    label = { Text("tmux prefix") },
                    supportingText = { Text("Examples: C-b, C-a, M-a, or 0x02") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { onTmuxPrefix(tmuxPrefix) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save terminal shortcut") }
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paired machines", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onImportPairing) { Text("Import") }
                }
            }
            items(state.config.pairings, key = { it.machineId }) { pairing ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pairing.machineName, fontWeight = FontWeight.Medium)
                            Text(
                                pairing.machineId,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { removeId = pairing.machineId }) {
                            Icon(Icons.Default.Delete, "Remove pairing")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    removeId?.let { machineId ->
        val machine = state.config.pairings.firstOrNull { it.machineId == machineId }
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text("Remove pairing?") },
            text = { Text("This removes the local key for ${machine?.machineName ?: machineId}. It does not stop the Agent or any tmux session.") },
            confirmButton = {
                Button(onClick = { onRemovePairing(machineId); removeId = null }) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } },
        )
    }
}
