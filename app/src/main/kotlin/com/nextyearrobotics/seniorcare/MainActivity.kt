package com.nextyearrobotics.seniorcare

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: BridgeViewModel = viewModel()
            val ui by model.trip.state.collectAsStateWithLifecycle()
            val connection by model.connection.collectAsStateWithLifecycle()
            var settings by rememberSaveable { mutableStateOf(false) }
            var permissionInfo by rememberSaveable { mutableStateOf(false) }
            var pendingEndpoint by rememberSaveable { mutableStateOf<String?>(null) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (SessionPermissions.mayConnect(this)) model.connect(pendingEndpoint) else permissionInfo = true
            }
            val connect: (String?) -> Unit = { endpoint ->
                pendingEndpoint = endpoint
                if (SessionPermissions.mayConnect(this)) model.connect(endpoint)
                else permissionLauncher.launch(SessionPermissions.required())
            }
            LaunchedEffect(connection.needsEndpoint, connection.error) {
                if (connection.needsEndpoint || connection.error != null) settings = true
            }
            SeniorCareTheme {
                BackHandler(ui.screen != Screen.HOME) { model.trip.back() }
                when (ui.screen) {
                    Screen.HOME -> HomeScreen(ui.app, model.trip::select, { connect(null) }, { settings = true }, model.trip::chooseOrigin)
                    Screen.ORIGIN -> HomeScreen(ui.app, model.trip::setOrigin, { connect(null) }, { settings = true }, model.trip::back)
                    Screen.CONFIRM, Screen.TRIP -> TripScreen(ui, ui.screen == Screen.CONFIRM, model.trip::startTrip, model.trip::cancelTrip, model.trip::back)
                }
                if (settings) ConnectionDialog(connection, connect, model::disconnect) { settings = false }
                if (permissionInfo) AlertDialog(onDismissRequest = { permissionInfo = false }, title = { Text(stringResource(R.string.permissions_title)) }, text = { Text(stringResource(R.string.permissions_explanation)) },
                    confirmButton = { TextButton(onClick = { permissionInfo = false; startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text(stringResource(R.string.permissions_settings)) } },
                    dismissButton = { TextButton(onClick = { permissionInfo = false }) { Text(stringResource(R.string.action_back)) } })
                if (ui.notice != TripNotice.NONE) AlertDialog(onDismissRequest = model.trip::dismissNotice,
                    text = { Text(stringResource(when (ui.notice) {
                        TripNotice.SAME_PLACE -> R.string.trip_same_place
                        TripNotice.SELECTION_CHANGED -> R.string.trip_selection_changed
                        TripNotice.RESULT_UNKNOWN -> R.string.trip_result_unknown
                        TripNotice.CATALOG_CHANGED -> R.string.trip_catalog_changed
                        else -> R.string.trip_rejected
                    })) }, confirmButton = { TextButton(onClick = model.trip::dismissNotice) { Text(stringResource(R.string.action_acknowledge)) } })
            }
        }
    }
}
