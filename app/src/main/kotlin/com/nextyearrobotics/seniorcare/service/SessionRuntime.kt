package com.nextyearrobotics.seniorcare.service

import android.content.Context
import android.net.nsd.NsdManager
import android.os.PowerManager
import com.nextyearrobotics.seniorcare.BuildConfig
import com.nextyearrobotics.seniorcare.data.*
import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.location.*
import com.nextyearrobotics.seniorcare.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-local UI bridge. Only BridgeService starts/stops work; Activity recreation owns no jobs. */
class SessionRuntime(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = object : Clock {
        override fun monotonicMs() = android.os.SystemClock.elapsedRealtime()
        override fun epochMs() = System.currentTimeMillis()
    }
    val session = BridgeSession(scope, OkHttpTransportFactory(),
        NsdBridgeDiscovery(context.getSystemService(NsdManager::class.java), context.mainExecutor),
        DataStoreConnectionSettings(context), BuildConfig.VERSION_NAME, clock, capabilities = listOf(com.nextyearrobotics.seniorcare.protocol.HelloCapabilities.LOCATION_CACHE, com.nextyearrobotics.seniorcare.protocol.HelloCapabilities.LOCATION_FRESH))
    val trip = TripRepository(session, scope, clock)
    val source: LocationSource = FusedLocationSource(context)
    val cache = LocationCache(clock)
    val notifications = SessionNotifications(context)
    val permissionProblem = MutableStateFlow(false)
    val sos = SosCoordinator(scope, source, cache, UrgentSender(session::sendUrgent), notifications, WakeLease { timeout ->
        val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeniorCare:SOS")
        lock.acquire(timeout)
        AutoCloseable { if (lock.isHeld) lock.release() }
    })
    init { session.onSos = sos::handle }
}
