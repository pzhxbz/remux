@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.remux.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.remux.app.network.ConnectionPhase
import dev.remux.app.protocol.MachineInfo

private data class MachineRow(
    val id: String,
    val name: String,
    val online: MachineInfo,
    val favorite: Boolean,
    val recentIndex: Int,
)

@Composable
fun RelaySetupScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onQuickConnect: (String) -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    RelayForm(
        title = "Connect your machines",
        subtitle = "The app connects outbound to your Relay. Anyone holding the Relay client token can manage every online machine.",
        initialName = state.config.relay?.name.orEmpty(),
        initialUrl = state.config.relay?.relayUrl.orEmpty(),
        initialToken = state.config.relay?.clientToken.orEmpty(),
        initialClientName = state.config.clientName,
        busy = state.busy,
        modifier = modifier,
        onQuickConnect = onQuickConnect,
        onSave = onSave,
    )
}

@Composable
internal fun RelayForm(
    title: String,
    subtitle: String,
    initialName: String,
    initialUrl: String,
    initialToken: String,
    initialClientName: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onQuickConnect: (String) -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName.ifBlank { "Personal Relay" }) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var clientName by remember(initialClientName) { mutableStateOf(initialClientName) }
    var quickConnect by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = quickConnect,
                    onValueChange = { quickConnect = it },
                    label = { Text("Quick connect") },
                    placeholder = { Text("server:port/secret") },
                    supportingText = { Text("Paste the REMUX_APP_CONFIG line printed by the Relay") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onQuickConnect(quickConnect) },
                    enabled = !busy && quickConnect.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect")
                    }
                }
                TextButton(
                    onClick = { showManual = !showManual },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(if (showManual) "Hide manual setup" else "Manual setup") }
                if (showManual) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Relay URL") },
                        placeholder = { Text("wss://relay.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Client token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientName,
                        onValueChange = { clientName = it },
                        label = { Text("This device") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onSave(name, url, token, clientName) },
                        enabled = !busy && url.isNotBlank() && token.length >= 16,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Save manual setup") }
                }
            }
        }
    }
}

@Composable
fun MachineListScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit,
    onOnlineOnly: (Boolean) -> Unit,
    onMachine: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onSettings: () -> Unit,
    onTerminals: () -> Unit,
) {
    val rows = machineRows(state)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.config.relay?.name ?: "RemoteMux")
                        RelayStatusText(state)
                    }
                },
                actions = {
                    if (state.terminalTabs.isNotEmpty()) {
                        IconButton(onClick = onTerminals) {
                            Icon(Icons.Default.Terminal, "Open terminal tabs")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearch,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                label = { Text("Search machines") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.onlineOnly,
                    onClick = { onOnlineOnly(!state.onlineOnly) },
                    label = { Text("Online only") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${state.onlineMachines.size} online") },
                )
            }
            if (rows.isEmpty()) {
                EmptyMachines()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = MachineRow::id) { row ->
                        MachineCard(row, onMachine, onFavorite)
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RelayStatusText(state: AppUiState) {
    val status = when (state.relayStatus.phase) {
        ConnectionPhase.CONNECTED -> "Connected · ${state.onlineMachines.size} online"
        ConnectionPhase.CONNECTING -> "Connecting…"
        ConnectionPhase.RECONNECTING -> "Reconnecting in ${state.relayStatus.retryInSeconds ?: 0}s"
        ConnectionPhase.ERROR -> "Connection error"
        ConnectionPhase.STOPPED -> "Offline"
    }
    Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = if (state.relayStatus.phase == ConnectionPhase.CONNECTED) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun MachineCard(
    row: MachineRow,
    onMachine: (String) -> Unit,
    onFavorite: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onMachine(row.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Computer, null)
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF45C98A),
                    modifier = Modifier.size(13.dp),
                ) {}
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${row.online.os}/${row.online.arch} · agent ${row.online.agentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onFavorite(row.id) }) {
                Icon(
                    if (row.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (row.favorite) "Remove favorite" else "Add favorite",
                    tint = if (row.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyMachines() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Computer, null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No machines online", style = MaterialTheme.typography.titleLarge)
        Text(
            "Start the Agent on a machine with remux run and it will appear here as soon as it connects to the Relay.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

private fun machineRows(state: AppUiState): List<MachineRow> {
    val query = state.search.trim().lowercase()
    return state.onlineMachines.values.map { machine ->
        MachineRow(
            id = machine.id,
            name = machine.name,
            online = machine,
            favorite = machine.id in state.config.favoriteMachineIds,
            recentIndex = state.config.recentMachineIds.indexOf(machine.id).let {
                if (it < 0) Int.MAX_VALUE else it
            },
        )
    }.filter { row ->
        query.isEmpty() || row.name.lowercase().contains(query) || row.id.contains(query)
    }.sortedWith(
        compareByDescending<MachineRow> { it.favorite }
            .thenBy(MachineRow::recentIndex)
            .thenBy { it.name.lowercase() },
    )
}
