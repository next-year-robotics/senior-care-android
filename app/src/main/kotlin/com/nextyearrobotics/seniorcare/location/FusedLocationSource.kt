package com.nextyearrobotics.seniorcare.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.nextyearrobotics.seniorcare.protocol.LocationUpdateFailureReason
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FusedLocationSource(private val context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    override fun failure(): LocationUpdateFailureReason? = when {
        !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationUpdateFailureReason.PERMISSION_DENIED
        !context.getSystemService(LocationManager::class.java).isLocationEnabled -> LocationUpdateFailureReason.PROVIDER_DISABLED
        else -> null
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun precise() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun Location.fix() = LocationFix(latitude, longitude, accuracy.toDouble(), time, elapsedRealtimeNanos / 1_000_000)

    @SuppressLint("MissingPermission") // Rechecked immediately before API; revocation still caught.
    override fun start(onFix: (LocationFix) -> Unit) {
        stop()
        if (failure() != null) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.takeIf { it.hasAccuracy() }?.let { onFix(it.fix()) } }
        }
        callback = cb
        try {
            client.lastLocation.addOnSuccessListener { location ->
                if (callback === cb && failure() == null) location?.takeIf { it.hasAccuracy() }?.let { onFix(it.fix()) }
            }
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000)
                .setMinUpdateIntervalMillis(30_000).build(), cb, Looper.getMainLooper())
                .addOnFailureListener { /* Fresh requests report unavailable; service checks permissions separately. */ }
        } catch (_: SecurityException) { stop() }
    }
    override fun stop() { callback?.let { client.removeLocationUpdates(it) }; callback = null }

    @SuppressLint("MissingPermission")
    override suspend fun fresh(): FixResult {
        failure()?.let { return FixResult(failure = it) }
        return suspendCancellableCoroutine { continuation ->
            val token = CancellationTokenSource()
            continuation.invokeOnCancellation { token.cancel() }
            try {
                client.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0).setDurationMillis(30_000).build(), token.token)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(if (location != null && location.hasAccuracy()) FixResult(location.fix())
                            else FixResult(failure = failure() ?: LocationUpdateFailureReason.TIMEOUT))
                    }.addOnFailureListener {
                        if (continuation.isActive) continuation.resume(FixResult(failure = failure() ?: LocationUpdateFailureReason.UNAVAILABLE))
                    }.addOnCanceledListener {
                        if (continuation.isActive) continuation.resume(FixResult(failure = LocationUpdateFailureReason.UNAVAILABLE))
                    }
            } catch (_: SecurityException) { continuation.resume(FixResult(failure = LocationUpdateFailureReason.PERMISSION_DENIED)) }
        }
    }
}
