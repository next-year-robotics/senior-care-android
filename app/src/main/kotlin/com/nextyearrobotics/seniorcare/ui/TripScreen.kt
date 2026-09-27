package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.data.TripUiState
import com.nextyearrobotics.seniorcare.domain.Connection
import com.nextyearrobotics.seniorcare.domain.TripPhase

@Composable
fun TripScreen(ui: TripUiState, confirm: Boolean, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    val app = ui.app
    val from = app.catalog.find { it.id == app.originId }?.label ?: stringResource(R.string.origin_none)
    val to = app.catalog.find { it.id == app.destinationId }?.label ?: stringResource(R.string.destination_unknown)
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(if (confirm) stringResource(R.string.trip_confirm, from, to) else when (app.phase) {
                TripPhase.ACTIVE -> stringResource(R.string.trip_active, to)
                TripPhase.CANCELLING -> stringResource(R.string.trip_stopping)
                TripPhase.ARRIVED -> stringResource(R.string.trip_arrived)
                TripPhase.START_PENDING -> stringResource(R.string.trip_starting)
                else -> stringResource(R.string.trip_unknown)
            }, style = MaterialTheme.typography.headlineMedium)
            if (app.stale) Text(stringResource(R.string.trip_stale), style = MaterialTheme.typography.bodyLarge)
            if (app.resultUnknown) Text(stringResource(R.string.trip_result_unknown), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = if (confirm) onStart else onStop, enabled = !ui.busy &&
                (if (confirm) app.canStart else app.connection == Connection.READY && app.tripId != null && app.phase in listOf(TripPhase.ACTIVE, TripPhase.START_PENDING)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                Text(stringResource(if (confirm) R.string.action_depart else R.string.action_stop))
            }
            if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.trip_waiting), style = MaterialTheme.typography.bodyLarge) }
            if (ui.canUndo && !confirm) OutlinedButton(onClick = onStop, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.action_undo)) }
            if (confirm || app.phase == TripPhase.ARRIVED) OutlinedButton(onClick = onBack, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.action_back)) }
        }
    }
}
