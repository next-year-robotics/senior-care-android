package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.domain.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(state: AppState, onSelect: (String) -> Unit, onConnect: () -> Unit, onSettings: () -> Unit, onOrigin: () -> Unit,
               sosBusy: Boolean = false, onSos: (() -> Unit)? = null) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val hint = stringResource(R.string.settings_hint)
    val unavailable = stringResource(R.string.place_disabled)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (onSos != null) Surface(tonalElevation = 3.dp) { SosButton(sosBusy, onSos, Modifier.navigationBarsPadding().padding(16.dp)) }
    }) { insets ->
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(2) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.connection == Connection.READY) Icons.Default.CheckCircle else Icons.Default.Info, null)
                    Text(stringResource(when (state.connection) {
                        Connection.READY -> R.string.status_connected
                        Connection.STOPPED -> R.string.status_disconnected
                        else -> R.string.status_searching
                    }), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    if (state.connection == Connection.READY) BatteryChip(state.batteryPercent)
                    SettingsEntry(onSettings, { scope.launch { snackbar.showSnackbar(hint) } })
                }
            }
            item(span = { GridItemSpan(2) }) { Text(stringResource(R.string.home_prompt), style = MaterialTheme.typography.displaySmall) }
            if (state.connection == Connection.STOPPED || state.connection == Connection.RECONNECTING) {
                item(span = { GridItemSpan(2) }) {
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.action_connect)) }
                }
            }
            if (state.catalog.isEmpty()) {
                item(span = { GridItemSpan(2) }) { Text(stringResource(R.string.catalog_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.catalog, key = { it.id }) { place ->
                ElevatedCard(onClick = { if (place.enabled) onSelect(place.id) else scope.launch { snackbar.showSnackbar(unavailable) } },
                    enabled = state.canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).alpha(if (place.enabled) 1f else .5f)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(placeIcon(place.kind), null, Modifier.size(56.dp))
                        Text(place.label, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text(stringResource(R.string.origin_label, state.catalog.find { it.id == state.originId }?.label ?: stringResource(R.string.origin_none)), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onOrigin, enabled = state.canEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_change)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(onOpen: () -> Unit, onHint: () -> Unit) {
    val configuration = LocalViewConfiguration.current
    val longPress = remember(configuration) { object : ViewConfiguration by configuration { override val longPressTimeoutMillis = 1_500L } }
    CompositionLocalProvider(LocalViewConfiguration provides longPress) {
        Box(Modifier.size(56.dp).combinedClickable(role = Role.Button, onClick = onHint,
            onLongClickLabel = stringResource(R.string.action_settings), onLongClick = onOpen), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Settings, stringResource(R.string.action_settings))
        }
    }
}
