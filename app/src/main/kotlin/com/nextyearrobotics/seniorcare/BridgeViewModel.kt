package com.nextyearrobotics.seniorcare

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import com.nextyearrobotics.seniorcare.service.BridgeService

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as SeniorCareApp).runtime
    val trip = runtime.trip
    val connection = runtime.session.state
    val sos = runtime.sos
    val permissionProblem = runtime.permissionProblem
    fun connect(endpoint: String? = null) {
        try {
            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), BridgeService::class.java).putExtra("endpoint", endpoint))
        } catch (_: IllegalStateException) { permissionProblem.value = true
        } catch (_: SecurityException) { permissionProblem.value = true }
    }
    fun disconnect() { getApplication<Application>().stopService(Intent(getApplication(), BridgeService::class.java)) }
}
