package com.nextyearrobotics.seniorcare

import android.os.Bundle
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nextyearrobotics.seniorcare.data.TripNotice
import com.nextyearrobotics.seniorcare.data.TripScreen as Screen
import com.nextyearrobotics.seniorcare.location.SessionPermissions
import com.nextyearrobotics.seniorcare.ui.*
import com.nextyearrobotics.seniorcare.ui.theme.SeniorCareTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("show_sos", false)) (application as SeniorCareApp).runtime.sos.reopen()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: BridgeViewModel = viewModel()
            val mapState by model.mapSelection.state.collectAsStateWithLifecycle()
            val locating by model.locating.collectAsStateWithLifecycle()
            val edit by model.places.state.collectAsStateWithLifecycle()
            val ui by model.trip.state.collectAsStateWithLifecycle()
            val sos by model.sos.state.collectAsStateWithLifecycle()
            val permissionProblem by model.permissionProblem.collectAsStateWithLifecycle()
            val connection by model.connection.collectAsStateWithLifecycle()
            var connectionDialog by rememberSaveable { mutableStateOf(false) }
            var mapInfo by remember { mutableStateOf(false) }
            var settings by rememberSaveable { mutableStateOf(false) }
            var permissionInfo by rememberSaveable { mutableStateOf(false) }
            var pendingEndpoint by rememberSaveable { mutableStateOf<String?>(null) }
            var sosConfirm by rememberSaveable { mutableStateOf(false) }
            val me by model.myLocation.collectAsStateWithLifecycle()
            val tripMap = remember { com.nextyearrobotics.seniorcare.places.KakaoTripMap() }
            // 열린 SOS가 있으면 위 배너가 대신한다
            val onSos: (() -> Unit)? = if (sos.isEmpty()) ({ sosConfirm = true }) else null
            LaunchedEffect(ui.screen) { if (ui.screen == Screen.CONFIRM || ui.screen == Screen.TRIP) model.refreshMyLocation() }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (SessionPermissions.mayConnect(this)) model.connect(pendingEndpoint) else permissionInfo = true
            }
            val connect: (String?) -> Unit = { endpoint ->
                pendingEndpoint = endpoint
                if (SessionPermissions.mayConnect(this)) model.connect(endpoint)
                else permissionLauncher.launch(SessionPermissions.required())
            }
            LaunchedEffect(connection.needsEndpoint, connection.error) {
                if (connection.needsEndpoint || connection.error != null) { settings = true; connectionDialog = true }
            }
            LaunchedEffect(permissionProblem) { if (permissionProblem) permissionInfo = true }
            LaunchedEffect(Unit) { if (intent.getBooleanExtra("show_sos", false)) model.sos.reopen() }
            SeniorCareTheme {
                BackHandler(settings || ui.screen != Screen.HOME) { if (mapInfo) { mapInfo = false; model.closeMap() } else if (settings) { if (edit.draft != null) model.places.close() else settings = false } else model.trip.back() }
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (sos.isNotEmpty()) SosBanner { model.sos.reopen() }
                Box(Modifier.weight(1f)) {
                if (mapInfo) MapPickerScreen(model.mapSelection, mapState, model.mapPicker, model.canLocate(), locating, model::locate,
                    { edit.draft?.let { model.mapSelection.apply(it)?.let(model.places::update) }; mapInfo = false; model.closeMap() },
                    { mapInfo = false; model.closeMap() })
                else if (settings) SettingsScreen(connection, model.places, edit, permissionSummary(),
                    { connectionDialog = true },
                    { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    { val summary = getString(R.string.diagnostics_summary, BuildConfig.VERSION_NAME, "1", connection.hello?.serverVersion ?: "—", connection.catalog?.revision ?: "—")
                      startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary), getString(R.string.diagnostics_export))) },
                    { (application as SeniorCareApp).runtime.notifications.testAlert() },
                    { edit.draft?.let { model.mapSelection.open(it); mapInfo = true } }, { settings = false })
                else when (ui.screen) {
                    Screen.HOME -> HomeScreen(ui.app, model.trip::select, { connect(null) }, { settings = true }, model.trip::chooseOrigin, ui.sosBusy, onSos)
                    Screen.ORIGIN -> HomeScreen(ui.app, model.trip::setOrigin, { connect(null) }, { settings = true }, model.trip::back)
                    Screen.CONFIRM, Screen.TRIP -> TripScreen(ui, ui.screen == Screen.CONFIRM, tripMap,
                        me?.let { com.nextyearrobotics.seniorcare.places.MapPoint(it.lat, it.lon) },
                        model.trip::startTrip, model.trip::cancelTrip, model.trip::back, onSos)
                }
                }
                }
                if (sosConfirm) SosConfirmDialog({ sosConfirm = false; model.raiseSos() }, { sosConfirm = false })
                if (connectionDialog) ConnectionDialog(connection, connect, model::disconnect) { connectionDialog = false }
                if (permissionInfo) AlertDialog(onDismissRequest = { permissionInfo = false }, title = { Text(stringResource(R.string.permissions_title)) }, text = { Column { Text(stringResource(R.string.permissions_explanation)); TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text(stringResource(R.string.permissions_app_settings)) }; TextButton(onClick = { permissionInfo = false; permissionLauncher.launch(SessionPermissions.required()) }) { Text(stringResource(R.string.permissions_retry)) } } },
                    confirmButton = { TextButton(onClick = { permissionInfo = false; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text(stringResource(R.string.permissions_settings)) } },
                    dismissButton = { TextButton(onClick = { permissionInfo = false }) { Text(stringResource(R.string.action_back)) } })
                if (ui.notice != TripNotice.NONE) AlertDialog(onDismissRequest = model.trip::dismissNotice,
                    text = { Text(stringResource(when (ui.notice) {
                        TripNotice.SAME_PLACE -> R.string.trip_same_place
                        TripNotice.SELECTION_CHANGED -> R.string.trip_selection_changed
                        TripNotice.RESULT_UNKNOWN -> R.string.trip_result_unknown
                        TripNotice.CATALOG_CHANGED -> R.string.trip_catalog_changed
                        TripNotice.SOS_OFFLINE -> R.string.sos_offline
                        TripNotice.SOS_FAILED -> R.string.sos_failed
                        TripNotice.SOS_UNSUPPORTED -> R.string.sos_unsupported
                        else -> R.string.trip_rejected
                    })) }, confirmButton = { TextButton(onClick = model.trip::dismissNotice) { Text(stringResource(R.string.action_acknowledge)) } })
                sos.values.firstOrNull { !it.dismissed }?.let { active ->
                    SosOverlay(active, { model.sos.dismiss(active.request.sosId) }, { model.sos.dismiss(active.request.sosId); settings = true },
                        { model.sos.dismiss(active.request.sosId); model.cancelSos(active.request.sosId) })
                }
            }
        }
    }
    private fun permissionSummary(): String {
        fun label(value: Boolean) = getString(if (value) R.string.permission_allowed else R.string.permission_denied)
        fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        return getString(R.string.permission_status, label(granted(Manifest.permission.ACCESS_COARSE_LOCATION)),
            label(granted(Manifest.permission.ACCESS_FINE_LOCATION)), label(granted(Manifest.permission.POST_NOTIFICATIONS)),
            label(getSystemService(LocationManager::class.java).isLocationEnabled))
    }

}
