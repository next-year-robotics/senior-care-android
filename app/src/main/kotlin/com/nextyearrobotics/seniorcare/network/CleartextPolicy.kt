package com.nextyearrobotics.seniorcare.network

import java.net.InetAddress

/** No DNS lookup for ws: NSD must supply a literal address. */
object CleartextPolicy {
    fun allows(host: String, debug: Boolean): Boolean {
        val value = host.removePrefix("[").removeSuffix("]")
        if (debug && value == "localhost") return true
        val parts = value.split('.')
        if (parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }) {
            val p = parts.map(String::toInt)
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) || (debug && p[0] == 127)
        }
        if (!value.contains(':') || !value.matches(Regex("[0-9a-fA-F:]+"))) return false
        val bytes = runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return false
        if (bytes.size != 16) return false
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        return (a and 254) == 252 || (a == 254 && (b and 192) == 128) ||
            (debug && bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte())
    }
}
