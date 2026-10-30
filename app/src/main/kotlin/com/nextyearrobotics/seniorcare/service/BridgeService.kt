package com.nextyearrobotics.seniorcare.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.nextyearrobotics.seniorcare.SeniorCareApp
import com.nextyearrobotics.seniorcare.domain.Connection
import com.nextyearrobotics.seniorcare.location.SessionPermissions
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BridgeService : Service() {
    private val runtime get() = (application as SeniorCareApp).runtime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoring: Job? = null
    private var starting: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!SessionPermissions.mayConnect(this)) {
            runtime.permissionProblem.value = true
            stopSelf(); return START_NOT_STICKY
        }
        try {
            startForeground(SessionNotifications.CONNECTION_ID, runtime.notifications.connection(null, Connection.CONNECTING), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            runtime.permissionProblem.value = true
            stopSelf(); return START_NOT_STICKY
        }
        runtime.permissionProblem.value = false
        starting?.cancel()
        starting = scope.launch {
            runtime.session.start(intent?.getStringExtra("endpoint"))
        }
        if (monitoring == null) {
            runtime.source.start(runtime.cache::update)
            monitoring = scope.launch {
                launch {
                    runtime.session.state.map { it.hello?.robotId to it.connection }.distinctUntilChanged().collect { (robot, connection) ->
                        runtime.notifications.updateConnection(robot, connection)
                    }
                }
                launch {
                    while (isActive) {
                        delay(60_000)
                        if (runtime.session.state.value.connection == Connection.READY && runtime.session.state.value.status?.missionState == RobotStatusMissionState.ACTIVE)
                            runtime.session.sendUrgent(runtime.cache.message(failure = runtime.source.failure()))
                    }
                }
                while (isActive) {
                    delay(1000)
                    val failure = runtime.source.failure()
                    if (failure != null || !SessionPermissions.mayConnect(this@BridgeService)) {
                        runtime.permissionProblem.value = true
                        // Give the socket a bounded chance to send none before termination.
                        val sent = CompletableDeferred<Unit>()
                        runtime.session.sendUrgent(runtime.cache.message(failure = failure ?: LocationUpdateFailureReason.PERMISSION_DENIED)) { sent.complete(Unit) }
                        withTimeoutOrNull(500) { sent.await() }
                        stopSelf(); break
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        runtime.source.stop()
        runtime.sos.close()
        runtime.scope.launch { runtime.session.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val STOP = "com.nextyearrobotics.seniorcare.STOP" }
}
