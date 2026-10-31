package com.nextyearrobotics.seniorcare

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import com.nextyearrobotics.seniorcare.places.*
import com.nextyearrobotics.seniorcare.service.BridgeService

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as SeniorCareApp).runtime
    val mapSelection = MapSelection(KakaoPlaceSearchProvider(), viewModelScope)
    val mapPicker: MapPinPicker = KakaoMapPinPicker()
    val locating = MutableStateFlow(false)
    fun canLocate() = runtime.source.failure() == null
    private var locateJob: kotlinx.coroutines.Job? = null
    fun closeMap() { locateJob?.cancel(); locating.value = false; mapSelection.close() }
    fun locate() {
        if (locating.value) return
        locating.value = true
        locateJob = viewModelScope.launch {
            try { withTimeoutOrNull(30_000) { runtime.source.fresh() }?.fix?.let { mapSelection.current(MapPoint(it.lat, it.lon)) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Coordinates remain unchanged; manual selection remains available. */ }
            finally { locating.value = false }
        }
    }
    val places = runtime.places
    val trip = runtime.trip
    val connection = runtime.session.state
    val sos = runtime.sos
    val permissionProblem = runtime.permissionProblem
    val myLocation = runtime.cache.current
    /** 주행 화면을 열 때 한 번. 30 s 주기 갱신을 기다리지 않고 지도에 내 위치를 띄운다. */
    fun refreshMyLocation() {
        if (runtime.source.failure() != null) return
        viewModelScope.launch {
            try { withTimeoutOrNull(15_000) { runtime.source.fresh() }?.fix?.let(runtime.cache::update) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    fun raiseSos() = trip.raiseSos()
    fun cancelSos(id: String) = trip.cancelSos(id, runtime.sos::forget)
    fun connect(endpoint: String? = null) {
        try {
            ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), BridgeService::class.java).putExtra("endpoint", endpoint))
        } catch (_: IllegalStateException) { permissionProblem.value = true
        } catch (_: SecurityException) { permissionProblem.value = true }
    }
    fun disconnect() { getApplication<Application>().stopService(Intent(getApplication(), BridgeService::class.java)) }
}
