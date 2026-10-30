package com.nextyearrobotics.seniorcare.location

import com.nextyearrobotics.seniorcare.domain.Clock
import com.nextyearrobotics.seniorcare.protocol.*

/** Ephemeral data only: never persist or log this object. */
class LocationFix(val lat: Double, val lon: Double, val accuracy: Double, val capturedAtMs: Long, val elapsedMs: Long) {
    fun valid() = lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
        accuracy.isFinite() && accuracy >= 0 && capturedAtMs >= 0 && elapsedMs >= 0
}
data class FixResult(val fix: LocationFix? = null, val failure: LocationUpdateFailureReason = LocationUpdateFailureReason.UNAVAILABLE)
interface LocationSource {
    fun failure(): LocationUpdateFailureReason?
    fun precise(): Boolean
    fun start(onFix: (LocationFix) -> Unit)
    fun stop()
    suspend fun fresh(): FixResult
}
class LocationCache(private val clock: Clock) {
    private var latest: LocationFix? = null
    fun update(fix: LocationFix) { if (fix.valid() && (latest?.elapsedMs ?: -1) <= fix.elapsedMs) latest = fix }
    fun clear() { latest = null }
    fun message(sosId: String? = null, source: LocationUpdateSource = LocationUpdateSource.CACHE,
                failure: LocationUpdateFailureReason? = null): LocationUpdate {
        val fix = if (failure == null) latest else null
        return LocationUpdate(if (sosId == null) LocationUpdateReason.PERIODIC else LocationUpdateReason.SOS, sosId,
            fix != null, fix?.lat, fix?.lon, fix?.accuracy, fix?.capturedAtMs,
            fix?.let { (clock.monotonicMs() - it.elapsedMs).coerceAtLeast(0) },
            if (fix == null) LocationUpdateSource.NONE else source,
            if (fix == null) failure ?: LocationUpdateFailureReason.UNAVAILABLE else null)
    }
}
