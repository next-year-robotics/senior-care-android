package com.nextyearrobotics.seniorcare

import android.app.Application
import android.net.nsd.NsdManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextyearrobotics.seniorcare.data.DataStoreConnectionSettings
import com.nextyearrobotics.seniorcare.data.TripRepository
import com.nextyearrobotics.seniorcare.network.*
import kotlinx.coroutines.launch

/** Foreground development session. M1-7 will move ownership into the location FGS. */
class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = DataStoreConnectionSettings(application)
    private val session = BridgeSession(viewModelScope, OkHttpTransportFactory(),
        NsdBridgeDiscovery(application.getSystemService(NsdManager::class.java), application.mainExecutor), settings, BuildConfig.VERSION_NAME)
    val trip = TripRepository(session, viewModelScope)
    val connection = session.state
    fun connect(endpoint: String? = null) { viewModelScope.launch { session.start(endpoint) } }
    fun disconnect() { viewModelScope.launch { session.stop() } }
}
