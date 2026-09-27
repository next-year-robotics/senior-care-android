package com.nextyearrobotics.seniorcare.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** API 36-compatible discovery + API 34 service updates. Caller owns local-network permission. */
class NsdBridgeDiscovery(private val manager: NsdManager, private val executor: Executor) : BridgeDiscovery {
    override suspend fun discover(): List<RobotEndpoint> {
        val found = ConcurrentHashMap<String, RobotEndpoint>()
        val callbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        val lock = Any()
        var active = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) { found.remove(service.serviceName) }
            override fun onServiceFound(service: NsdServiceInfo) = synchronized(lock) {
                if (!active || callbacks.containsKey(service.serviceName)) return@synchronized
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { callbacks.remove(service.serviceName) }
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val api = info.attributes["api"]?.toString(Charsets.UTF_8)
                        val robot = info.attributes["robot_id"]?.toString(Charsets.UTF_8)
                        val host = info.hostAddresses.firstOrNull()?.hostAddress
                        if (api == "1" && !robot.isNullOrBlank() && host != null) {
                            val literal = if (':' in host) "[$host]" else host
                            runCatching { Endpoint.validate("ws://$literal:${info.port}/bridge/v1") }.getOrNull()?.let {
                                found[service.serviceName] = RobotEndpoint(robot, it)
                            }
                        }
                    }
                    override fun onServiceLost() { found.remove(service.serviceName) }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                callbacks[service.serviceName] = callback
                runCatching { manager.registerServiceInfoCallback(service, executor, callback) }.onFailure { callbacks.remove(service.serviceName) }
            }
        }
        try {
            manager.discoverServices("_mr-phone-bridge._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(10_000)
            return found.values.distinctBy { it.url }.sortedBy { it.robotId }
        } finally {
            synchronized(lock) {
                active = false
                runCatching { manager.stopServiceDiscovery(listener) }
                callbacks.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
                callbacks.clear()
            }
        }
    }
}
