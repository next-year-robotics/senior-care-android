package com.nextyearrobotics.seniorcare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nextyearrobotics.seniorcare.R
import com.nextyearrobotics.seniorcare.network.Endpoint
import com.nextyearrobotics.seniorcare.network.SessionState

@Composable
fun ConnectionDialog(state: SessionState, onConnect: (String?) -> Unit, onDisconnect: () -> Unit, onDismiss: () -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.connection_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.foreground_session_notice))
            if (state.needsEndpoint) Text(stringResource(R.string.connection_manual_needed))
            if (state.error != null) Text(stringResource(R.string.connection_error))
            state.candidates.forEach { robot -> TextButton(onClick = { onConnect(robot.url); onDismiss() }) { Text(robot.robotId) } }
            OutlinedTextField(value = ip, onValueChange = { ip = it; invalid = false }, label = { Text(stringResource(R.string.connection_ip)) }, singleLine = true)
            OutlinedTextField(value = port, onValueChange = { port = it; invalid = false }, label = { Text(stringResource(R.string.connection_port)) }, singleLine = true)
            if (invalid) Text(stringResource(R.string.connection_invalid), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onConnect(null); onDismiss() }) { Text(stringResource(R.string.connection_discover)) }
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.connection_stop)) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val endpoint = runCatching { Endpoint.manual(ip, port) }.getOrNull()
            if (endpoint == null) invalid = true else { onConnect(endpoint); onDismiss() }
        }) { Text(stringResource(R.string.action_connect)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) } })
}
