package com.nextyearrobotics.seniorcare.network

import java.net.URI
import com.nextyearrobotics.seniorcare.BuildConfig

object Endpoint {
    fun validate(url: String, debug: Boolean = BuildConfig.DEBUG): String {
        val uri = URI(url)
        require(uri.scheme in listOf("ws", "wss") && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.port in 1..65535 && uri.path == "/bridge/v1")
        require(uri.scheme != "ws" || CleartextPolicy.allows(uri.host, debug))
        return uri.toASCIIString()
    }
    fun manual(ipv4: String, port: String): String {
        val parts = ipv4.trim().split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
        val number = port.toIntOrNull() ?: throw IllegalArgumentException("Invalid port")
        return validate("ws://${parts.joinToString(".") { it.toInt().toString() }}:$number/bridge/v1")
    }
}
data class RobotEndpoint(val robotId: String, val url: String)
fun interface BridgeDiscovery { suspend fun discover(): List<RobotEndpoint> }
interface ConnectionSettings {
    suspend fun instanceId(): String
    suspend fun lastEndpoint(): String?
    suspend fun saveEndpoint(endpoint: String)
}
class ReconnectBackoff(private val random: () -> Double = { kotlin.random.Random.nextDouble() }) {
    private var attempt = 0
    fun nextMs(): Long {
        val base = longArrayOf(1000, 2000, 4000, 8000, 10000)[attempt.coerceAtMost(4)]
        attempt = (attempt + 1).coerceAtMost(4)
        return (base * (0.8 + 0.4 * random().coerceIn(0.0, 1.0))).toLong()
    }
    fun reset() { attempt = 0 }
}
