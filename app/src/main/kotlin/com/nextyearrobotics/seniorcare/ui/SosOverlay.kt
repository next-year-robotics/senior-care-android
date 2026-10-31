package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.protocol.SosRequestReason

@Composable
fun SosOverlay(state: SosState, onDismiss: () -> Unit, onSettings: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(Icons.Default.Warning, null, Modifier.size(56.dp))
                Text(stringResource(R.string.notification_sos), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(when (state.request.reason) {
                    SosRequestReason.MANUAL -> R.string.sos_manual
                    SosRequestReason.TRACKING_LOST -> R.string.sos_tracking_lost
                    SosRequestReason.STUCK -> R.string.sos_stuck
                    SosRequestReason.LOW_BATTERY -> R.string.sos_battery
                    else -> R.string.sos_generic
                }), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(when (state.delivery) {
                    LocationDelivery.CHECKING -> R.string.sos_checking
                    LocationDelivery.SENT -> R.string.sos_sent
                    LocationDelivery.LOW_CONFIDENCE -> R.string.sos_low_confidence
                    LocationDelivery.UNAVAILABLE -> R.string.sos_unavailable
                    LocationDelivery.NOT_DELIVERED -> R.string.sos_not_delivered
                }), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.approximate) Text(stringResource(R.string.sos_approximate))
                Text(stringResource(R.string.sos_receipt_notice))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.action_acknowledge)) }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.sos_cancel)) }
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.action_settings)) }
            }
        }
    }
}

@Composable
fun SosBanner(onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Icon(Icons.Default.Warning, null)
            Text(stringResource(R.string.sos_banner), Modifier.padding(8.dp))
        }
    }
}
