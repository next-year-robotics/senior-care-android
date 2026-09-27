package com.nextyearrobotics.seniorcare.protocol

import com.nextyearrobotics.seniorcare.domain.Clock

/** Per client lifetime, across reconnects. Never uses sent_at_ms for expiry. */
class MessageHistory(private val clock: Clock, private val capacity: Int = 4096) {
    private data class Entry(val at: Long, val response: String?)
    private val entries = linkedMapOf<String, Entry>()
    init { require(capacity > 0) }
    @Synchronized fun contains(id: String): Boolean { prune(); return id in entries }
    @Synchronized fun response(id: String): String? { prune(); return entries[id]?.response }
    @Synchronized fun remember(id: String, response: String? = null) {
        prune()
        if (id in entries) return // Retransmission does not extend retention indefinitely.
        if (entries.size >= capacity) throw ProtocolException("INVALID_MESSAGE", "message history capacity exceeded")
        entries[id] = Entry(clock.monotonicMs(), response)
    }
    @Synchronized fun recordResponse(id: String, response: String) {
        entries[id]?.let { entries[id] = it.copy(response = response) }
    }
    private fun prune() { val now = clock.monotonicMs(); entries.entries.removeAll { now - it.value.at >= 600_000 } }
}
