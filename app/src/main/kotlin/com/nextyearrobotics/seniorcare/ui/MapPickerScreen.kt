package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.places.*

@Composable
fun MapPickerScreen(selection: MapSelection, state: MapSelectionState, picker: MapPinPicker,
    canLocate: Boolean, locating: Boolean, onLocate: () -> Unit, onApply: () -> Unit, onBack: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.place_map), style = MaterialTheme.typography.headlineMedium)
            if (!state.manual) {
                OutlinedTextField(state.query, selection::query, label = { Text(stringResource(R.string.map_search_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = selection::search, enabled = !state.searching && state.query.isNotBlank()) { Text(stringResource(R.string.map_search)) }
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.searchFailed) Text(stringResource(R.string.map_search_failed))
                state.results.forEach { place ->
                    OutlinedButton(onClick = { selection.choose(place) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Column { Text(place.name); Text(place.address) } }
                }
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    picker.Content(state.cameraTarget, state.radius, Modifier.fillMaxSize(), selection::moved, selection::manual)
                    Icon(Icons.Default.Add, stringResource(R.string.map_center_pin), Modifier.align(Alignment.Center).size(40.dp))
                }
                OutlinedButton(onClick = onLocate, enabled = canLocate && !locating) { Text(stringResource(if (locating) R.string.sos_checking else R.string.map_current)) }
                if (!canLocate) Text(stringResource(R.string.map_location_unavailable))
                TextButton(onClick = selection::manual) { Text(stringResource(R.string.map_manual)) }
            } else {
                Text(stringResource(R.string.map_unavailable))
                OutlinedTextField(state.latitude, { selection.coordinates(it, state.longitude) }, label = { Text(stringResource(R.string.place_latitude)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.longitude, { selection.coordinates(state.latitude, it) }, label = { Text(stringResource(R.string.place_longitude)) }, modifier = Modifier.fillMaxWidth())
            }
            Text(state.address?.takeIf { it.isNotBlank() } ?: state.selected?.let { stringResource(R.string.map_selected_coordinates, it.lat, it.lon) } ?: stringResource(R.string.map_choose),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.place_radius, state.radius.toInt()))
            Slider(state.radius, selection::radius, valueRange = 5f..500f)
            Button(onClick = onApply, enabled = state.selected?.valid() == true, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.map_apply)) }
        }
    }
}
