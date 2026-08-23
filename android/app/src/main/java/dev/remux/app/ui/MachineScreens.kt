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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.remux.app.protocol.PairingBundle

private data class MachineRow(
    val id: String,
    val name: String,
    val online: MachineInfo?,
    val pairing: PairingBundle?,
    val favorite: Boolean,
    val recentIndex: Int,
)

@Composable
fun RelaySetupScreen(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onSave: (String, String, String, String) -> Unit,
) {
    RelayForm(
        title = "Connect your machines",
        subtitle = "The app connects outbound to your Relay. Machine secrets remain encrypted on this device.",
        initialName = state.config.relay?.name.orEmpty(),
        initialUrl = state.config.relay?.relayUrl.orEmpty(),
        initialToken = state.config.relay?.clientToken.orEmpty(),
        initialClientName = state.config.clientName,
        busy = state.busy,
        modifier = modifier,
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
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName.ifBlank { "Personal Relay" }) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var clientName by remember(initialClientName) { mutableStateOf(initialClientName) }

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
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save and connect")
                    }
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
    onImportPairing: () -> Unit,
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImportPairing,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Import pairing") },
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
                EmptyMachines(onImportPairing)
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
            .clickable(enabled = row.pairing != null) { onMachine(row.id) },
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
                    color = if (row.online != null) Color(0xFF45C98A) else Color(0xFF78868F),
                    modifier = Modifier.size(13.dp),
                ) {}
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        row.pairing == null -> "Online · pairing required"
                        row.online != null -> "${row.online.os}/${row.online.arch} · agent ${row.online.agentVersion}"
                        else -> "Offline · paired"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.pairing != null) {
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
}

@Composable
private fun EmptyMachines(onImportPairing: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Computer, null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("No machines yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Import the pairing.toml generated by remux config. Offline paired machines remain visible here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onImportPairing) { Text("Import pairing.toml") }
    }
}

private fun machineRows(state: AppUiState): List<MachineRow> {
    val pairings = state.config.pairings.associateBy(PairingBundle::machineId)
    val ids = pairings.keys + state.onlineMachines.keys
    val query = state.search.trim().lowercase()
    return ids.map { id ->
        val online = state.onlineMachines[id]
        val pairing = pairings[id]
        MachineRow(
            id = id,
            name = online?.name ?: pairing?.machineName ?: id,
            online = online,
            pairing = pairing,
            favorite = id in state.config.favoriteMachineIds,
            recentIndex = state.config.recentMachineIds.indexOf(id).let {
                if (it < 0) Int.MAX_VALUE else it
            },
        )
    }.filter { row ->
        (!state.onlineOnly || row.online != null) &&
            (query.isEmpty() || row.name.lowercase().contains(query) || row.id.contains(query))
    }.sortedWith(
        compareByDescending<MachineRow> { it.favorite }
            .thenByDescending { it.online != null }
            .thenBy(MachineRow::recentIndex)
            .thenBy { it.name.lowercase() },
    )
}
