package com.nextyearrobotics.seniorcare.domain

import com.nextyearrobotics.seniorcare.location.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class LocationDelivery { CHECKING, SENT, LOW_CONFIDENCE, UNAVAILABLE, NOT_DELIVERED }
data class SosState(val request: SosRequest, val dismissed: Boolean = false,
                    val delivery: LocationDelivery = LocationDelivery.CHECKING, val approximate: Boolean = false)
interface SosAlerts { fun show(state: SosState, first: Boolean); fun clear(id: String) }
fun interface WakeLease { fun acquire(timeoutMs: Long): AutoCloseable }
/** Enqueue without waiting for the server ACK. Completion means bridge receipt, not caregiver receipt. */
fun interface UrgentSender { fun send(payload: Payload, replyTo: String?, delivered: (Boolean) -> Unit) }

class SosCoordinator(private val scope: CoroutineScope, private val source: LocationSource,
    private val cache: LocationCache, private val sender: UrgentSender, private val alerts: SosAlerts,
    private val wake: WakeLease) {
    private val mutable = MutableStateFlow<Map<String, SosState>>(emptyMap())
    val state = mutable.asStateFlow()
    private val freshJobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private var generation = 0L
    fun handle(message: Message) {
        when (val payload = message.payload) {
            is SosRequest -> receive(payload, message.envelope.id)
            is SosClear -> clear(payload.sosId)
            else -> Unit
        }
    }
    private fun receive(request: SosRequest, envelopeId: String) {
        val id = request.sosId
        val first = id !in state.value
        if (first) {
            mutable.value = state.value + (id to SosState(request, approximate = !source.precise()))
            generations[id] = ++generation
        }
        val version = generations.getValue(id)
        // These two enqueues never wait for notification APIs, GPS, or another request's ACK.
        sender.send(SosAck(id, true), envelopeId) { }
        sendLocation(cache.message(id, failure = source.failure()), version)
        if (!first) return
        alerts.show(state.value.getValue(id), true)
        freshJobs[id] = scope.launch {
            val lease = wake.acquire(35_000)
            try {
                val result = try { withTimeoutOrNull(30_000) { source.fresh() }
                    ?: FixResult(failure = LocationUpdateFailureReason.TIMEOUT)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { FixResult(failure = source.failure() ?: LocationUpdateFailureReason.UNAVAILABLE) }
                if (generations[id] != version) return@launch
                result.fix?.takeIf { it.valid() }?.let(cache::update)
                val failure = source.failure() ?: if (result.fix?.valid() == true) null else result.failure
                sendLocation(cache.message(id, LocationUpdateSource.FRESH, failure), version)
            } finally { lease.close() }
        }
    }
    private fun sendLocation(location: LocationUpdate, version: Long) {
        sender.send(location, null) { delivered ->
            val id = location.sosId ?: return@send
            val old = state.value[id] ?: return@send
            if (generations[id] != version) return@send
            val delivery = when {
                !delivered -> LocationDelivery.NOT_DELIVERED
                !location.hasFix -> LocationDelivery.UNAVAILABLE
                (location.ageMs ?: 0) > 300_000 || (location.horizontalAccuracyM ?: 0.0) > 50 -> LocationDelivery.LOW_CONFIDENCE
                else -> LocationDelivery.SENT
            }
            // Keep an already acknowledged useful cache visible when fresh fails.
            val next = if (old.delivery in listOf(LocationDelivery.SENT, LocationDelivery.LOW_CONFIDENCE) &&
                delivery in listOf(LocationDelivery.UNAVAILABLE, LocationDelivery.NOT_DELIVERED)) old else old.copy(delivery = delivery)
            mutable.value = state.value + (id to next)
            alerts.show(next, false)
        }
    }
    fun dismiss(id: String) { state.value[id]?.let { mutable.value = state.value + (id to it.copy(dismissed = true)) } }
    fun reopen(id: String? = null) { mutable.value = state.value.mapValues { (key, value) -> if (id == null || key == id) value.copy(dismissed = false) else value } }
    private fun clear(id: String) {
        freshJobs.remove(id)?.cancel()
        generations.remove(id)
        mutable.value = state.value - id
        alerts.clear(id)
    }
    fun close() { state.value.keys.toList().forEach(::clear); cache.clear() }
}
