package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.data.TripUiState
import com.nextyearrobotics.seniorcare.data.TripScreen as TripPage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextyearrobotics.seniorcare.BuildConfig
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.network.SessionState
import com.nextyearrobotics.seniorcare.places.*
import com.nextyearrobotics.seniorcare.protocol.PlaceKind

@Composable
fun SettingsScreen(connection: SessionState, editor: PlaceEditor, edit: PlaceEditorState, permissions: String,
    onConnect: () -> Unit, onPermissions: () -> Unit, onExport: () -> Unit, onTestAlert: () -> Unit,
    onMap: () -> Unit, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("home") }
    var deleteConfirm by remember { mutableStateOf(false) }
    if (page == "preview" && edit.draft == null) {
        var doubleFont by remember { mutableStateOf(false) }
        var previewPage by remember { mutableStateOf(TripPage.HOME) }
        var destination by remember { mutableStateOf<String?>(null) }
        val density = LocalDensity.current
        val catalog = connection.catalog
        val app = AppState(connection = Connection.READY, phase = if (previewPage == TripPage.TRIP) TripPhase.ACTIVE else TripPhase.READY,
            catalog = catalog?.places?.map { Destination(it.id, it.kind.wire, it.label, it.enabled, it.sortOrder) }.orEmpty(),
            catalogReceived = true, originId = catalog?.defaultOriginPlaceId, destinationId = destination,
            statusAtMs = 0, tripId = "preview")
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { page = "home" }) { Text(stringResource(R.string.action_back)) }
            SettingToggle(stringResource(R.string.settings_preview_double), doubleFont, true) { doubleFont = it }
            Text(stringResource(R.string.settings_preview_local), Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (doubleFont) 2f else density.fontScale)) {
                    if (previewPage == TripPage.HOME) HomeScreen(app, { destination = it; previewPage = TripPage.CONFIRM }, {}, {}, {})
                    else TripScreen(TripUiState(app = app, screen = previewPage), previewPage == TripPage.CONFIRM,
                        { previewPage = TripPage.TRIP }, { previewPage = TripPage.HOME }, { previewPage = TripPage.HOME })
                }
            }
        }
        return
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { if (edit.draft != null) editor.close() else if (page != "home") page = "home" else onBack() }, enabled = !edit.busy) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(if (edit.draft != null) R.string.place_edit else R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            val draft = edit.draft
            if (draft != null) {
                fun update(next: PlaceDraft) = editor.update(next)
                OutlinedTextField(draft.label, { update(draft.copy(label = it)) }, label = { Text(stringResource(R.string.place_label)) }, modifier = Modifier.fillMaxWidth(), enabled = !edit.busy)
                var kinds by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { kinds = true }, enabled = !edit.busy) { Text(stringResource(kindLabel(draft.kind))) }
                    DropdownMenu(kinds, { kinds = false }) { PlaceKind.entries.filter { it != PlaceKind.UNKNOWN }.forEach { kind ->
                        DropdownMenuItem(text = { Text(stringResource(kindLabel(kind))) }, onClick = { update(draft.copy(kind = kind)); kinds = false })
                    } }
                }
                OutlinedButton(onClick = onMap, enabled = !edit.busy) { Text(stringResource(R.string.place_map)) }
                OutlinedTextField(draft.lat, { update(draft.copy(lat = it, source = com.nextyearrobotics.seniorcare.protocol.PlaceSource.MANUAL_COORDS, address = null)) }, label = { Text(stringResource(R.string.place_latitude)) }, enabled = !edit.busy)
                OutlinedTextField(draft.lon, { update(draft.copy(lon = it, source = com.nextyearrobotics.seniorcare.protocol.PlaceSource.MANUAL_COORDS, address = null)) }, label = { Text(stringResource(R.string.place_longitude)) }, enabled = !edit.busy)
                draft.address?.let { Text(it) }
                Text(stringResource(R.string.place_radius, draft.radius.toInt()))
                Slider(draft.radius, { update(draft.copy(radius = it)) }, valueRange = 5f..500f, enabled = !edit.busy)
                OutlinedTextField(draft.order, { update(draft.copy(order = it)) }, label = { Text(stringResource(R.string.place_order)) }, enabled = !edit.busy)
                SettingToggle(stringResource(R.string.place_enabled), draft.enabled, !edit.busy) { update(draft.copy(enabled = it)) }
                SettingToggle(stringResource(R.string.place_default_origin), draft.defaultOrigin, !edit.busy) { update(draft.copy(defaultOrigin = it)) }
                if (!editor.canEdit()) Text(stringResource(R.string.place_edit_disabled))
                Button(onClick = editor::save, enabled = !edit.busy && editor.canEdit(), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.action_save)) }
                if (draft.id != null) OutlinedButton(onClick = { deleteConfirm = true }, enabled = !edit.busy && editor.canEdit()) { Text(stringResource(R.string.action_delete)) }
            } else when (page) {
                "home" -> {
                    SettingsButton(R.string.place_list) { page = "places" }
                    SettingsButton(R.string.connection_title, onConnect)
                    SettingsButton(R.string.permissions_title) { page = "permissions" }
                    SettingsButton(R.string.settings_alert_test, onTestAlert)
                    SettingsButton(R.string.settings_preview) { page = "preview" }
                    SettingsButton(R.string.settings_diagnostics) { page = "info" }
                }
                "places" -> {
                    if (!editor.canEdit()) Text(stringResource(R.string.place_edit_disabled))
                    Button(onClick = { editor.open() }, enabled = editor.canEdit()) { Text(stringResource(R.string.place_add)) }
                    connection.catalog?.places?.sortedBy { it.sortOrder }?.forEach { p ->
                        OutlinedButton(onClick = { editor.open(p.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text(p.label + if (p.isDefaultOrigin) stringResource(R.string.place_origin_suffix) else "")
                        }
                    }
                }
                "permissions" -> { Text(permissions); SettingsButton(R.string.permissions_app_settings, onPermissions) }
                "preview" -> { Text(stringResource(R.string.settings_preview_notice)); Text(stringResource(R.string.home_prompt), style = MaterialTheme.typography.displaySmall); ElevatedCard(Modifier.fillMaxWidth().heightIn(min = 140.dp)) { Text(stringResource(R.string.kind_home), Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge) } }
                "info" -> {
                    Text(stringResource(R.string.diagnostics_summary, BuildConfig.VERSION_NAME, "1", connection.hello?.serverVersion ?: "—", connection.catalog?.revision ?: "—"))
                    SettingsButton(R.string.diagnostics_export, onExport)
                }
            }
            if (edit.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (edit.notice != EditNotice.NONE) {
                Text(stringResource(editNoticeLabel(edit.notice)))
                if (edit.notice == EditNotice.CONFLICT) SettingsButton(R.string.place_reload, editor::reload)
            }
        }
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text(stringResource(R.string.place_delete_confirm)) },
        confirmButton = { TextButton(onClick = { deleteConfirm = false; editor.delete() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.action_back)) } })
}
@Composable private fun SettingsButton(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(label)) }
}
@Composable private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, null, enabled = enabled) }
}
fun kindLabel(kind: PlaceKind): Int = when (kind) {
    PlaceKind.HOME -> R.string.kind_home; PlaceKind.MARKET -> R.string.kind_market; PlaceKind.MART -> R.string.kind_mart
    PlaceKind.SENIOR_CENTER -> R.string.kind_senior_center; PlaceKind.CHURCH -> R.string.kind_church; PlaceKind.TEMPLE -> R.string.kind_temple
    PlaceKind.HOSPITAL -> R.string.kind_hospital; PlaceKind.PHARMACY -> R.string.kind_pharmacy; else -> R.string.kind_other
}
private fun editNoticeLabel(notice: EditNotice) = when (notice) {
    EditNotice.SAVED -> R.string.place_saved; EditNotice.INVALID -> R.string.place_invalid; EditNotice.CONFLICT -> R.string.place_conflict
    EditNotice.LIMIT -> R.string.place_limit; EditNotice.IN_USE -> R.string.place_in_use; EditNotice.DEFAULT_ORIGIN -> R.string.place_default_delete
    EditNotice.DISABLED -> R.string.place_edit_disabled; EditNotice.UNKNOWN -> R.string.trip_result_unknown; else -> R.string.trip_rejected
}
