package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.data.TripUiState
import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.places.MapPoint
import com.nextyearrobotics.seniorcare.places.TripMap
import com.nextyearrobotics.seniorcare.ui.theme.BrandError
import com.nextyearrobotics.seniorcare.ui.theme.BrandSuccess
import com.nextyearrobotics.seniorcare.ui.theme.DeepOcean

/** 목적지를 고른 뒤(확인)와 주행 중 화면. 지도는 내 위치와 목적지 핀만 보인다(길 안내 없음). */
@Composable
fun TripScreen(ui: TripUiState, confirm: Boolean, map: TripMap, me: MapPoint?, onStart: () -> Unit, onStop: () -> Unit,
               onBack: () -> Unit, onSos: (() -> Unit)?) {
    val app = ui.app
    val origin = app.catalog.find { it.id == app.originId }
    val dest = app.catalog.find { it.id == app.destinationId }
    val to = dest?.label ?: stringResource(R.string.destination_unknown)
    var mapFailed by remember { mutableStateOf(false) }
    val moving = !confirm && app.phase in listOf(TripPhase.ACTIVE, TripPhase.START_PENDING)
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (confirm || app.phase == TripPhase.ARRIVED)
                    TextButton(onClick = onBack, enabled = !ui.busy, modifier = Modifier.heightIn(min = 56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(stringResource(R.string.action_back), Modifier.padding(start = 4.dp))
                    }
                Spacer(Modifier.weight(1f))
                BatteryChip(app.batteryPercent)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(placeIcon(dest?.kind ?: "other"), null, Modifier.size(44.dp))
                }
                Column {
                    Text(to, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    origin?.let { Text(stringResource(R.string.trip_from, it.label), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            ElevatedCard(Modifier.fillMaxWidth().height(300.dp)) {
                Box(Modifier.fillMaxSize()) {
                    if (!mapFailed) map.Content(me, dest?.lat?.let { lat -> dest.lon?.let { MapPoint(lat, it) } }, to, Modifier.fillMaxSize()) { mapFailed = true }
                    else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Map, null, Modifier.size(56.dp)); Text(stringResource(R.string.map_off), style = MaterialTheme.typography.bodyLarge)
                    }
                    Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp))
                            Text(stringResource(if (me != null) R.string.map_my_location else R.string.map_waiting_location),
                                Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            StatusLine(ui, confirm)
            if (app.batteryPercent != null && app.batteryPercent <= BATTERY_LOW_PERCENT)
                StatusRow(Icons.Default.BatteryAlert, stringResource(R.string.battery_low), BrandError)
            if (confirm) BigAction(stringResource(R.string.action_depart), stringResource(R.string.trip_depart_sub), Icons.Default.PlayArrow,
                BrandSuccess, enabled = !ui.busy && app.canStart, onClick = onStart)
            else if (app.phase != TripPhase.ARRIVED) BigAction(stringResource(R.string.action_stop), stringResource(R.string.trip_stop_sub), Icons.Default.Stop,
                DeepOcean, enabled = !ui.busy && app.connection == Connection.READY && app.tripId != null && moving, onClick = onStop)
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.canUndo && !confirm) OutlinedButton(onClick = onStop, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.action_undo)) }
            if (onSos != null) SosButton(ui.sosBusy, onSos)
        }
    }
}

@Composable
private fun StatusLine(ui: TripUiState, confirm: Boolean) {
    val app = ui.app
    val (icon, text) = when {
        ui.busy -> Icons.Default.HourglassTop to stringResource(R.string.trip_waiting)
        confirm -> Icons.Default.DirectionsWalk to stringResource(R.string.trip_ready_hint)
        app.resultUnknown -> Icons.Default.Warning to stringResource(R.string.trip_result_unknown)
        app.stale -> Icons.Default.HourglassTop to stringResource(R.string.trip_stale)
        app.phase == TripPhase.CANCELLING -> Icons.Default.Stop to stringResource(R.string.trip_stopping)
        app.phase == TripPhase.ARRIVED -> Icons.Default.CheckCircle to stringResource(R.string.trip_arrived)
        app.phase == TripPhase.START_PENDING -> Icons.Default.HourglassTop to stringResource(R.string.trip_starting)
        app.phase == TripPhase.ACTIVE -> when (app.tracking) {
            Tracking.FOLLOWING -> Icons.Default.CheckCircle to stringResource(R.string.tracking_following)
            Tracking.SEARCHING -> Icons.Default.Search to stringResource(R.string.tracking_searching)
            Tracking.LOST -> Icons.Default.Warning to stringResource(R.string.tracking_lost)
            Tracking.UNAVAILABLE, Tracking.ERROR -> Icons.Default.Warning to stringResource(R.string.tracking_unavailable)
            else -> Icons.Default.DirectionsWalk to stringResource(R.string.trip_active, app.catalog.find { it.id == app.destinationId }?.label ?: "")
        }
        else -> Icons.Default.Info to stringResource(R.string.trip_unknown)
    }
    StatusRow(icon, text, if (app.tracking == Tracking.LOST && !confirm) BrandError else LocalContentColor.current)
}

@Composable
private fun StatusRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(36.dp), tint = tint)
        Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun BigAction(label: String, sub: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(icon, null, Modifier.size(48.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.headlineMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
