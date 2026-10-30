package com.nextyearrobotics.seniorcare.places

import com.nextyearrobotics.seniorcare.protocol.PlaceSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MapSelectionState(val query: String = "", val results: List<SearchPlace> = emptyList(),
    val selected: MapPoint? = null, val cameraTarget: MapPoint? = null, val address: String? = null,
    val name: String? = null, val source: PlaceSource = PlaceSource.MAP_PIN, val radius: Float = 30f,
    val searching: Boolean = false, val searchFailed: Boolean = false, val manual: Boolean = false,
    val latitude: String = "", val longitude: String = "")
class MapSelection(private val provider: PlaceSearchProvider, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(MapSelectionState())
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var addressJob: Job? = null
    fun open(draft: PlaceDraft) {
        close()
        val point = runCatching { MapPoint(draft.lat.toDouble(), draft.lon.toDouble()).takeIf { it.valid() } }.getOrNull()
        mutable.value = MapSelectionState(selected = point, cameraTarget = point, address = draft.address, radius = draft.radius,
            latitude = draft.lat, longitude = draft.lon)
    }
    fun close() { searchJob?.cancel(); addressJob?.cancel(); mutable.value = MapSelectionState() }
    fun query(value: String) { searchJob?.cancel(); mutable.value = state.value.copy(query = value, searching = false, results = emptyList(), searchFailed = false) }
    fun search() {
        searchJob?.cancel()
        val query = state.value.query
        mutable.value = state.value.copy(searching = true, searchFailed = false)
        searchJob = scope.launch {
            try { val found = provider.search(query); mutable.value = state.value.copy(results = found, searching = false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(searching = false, searchFailed = true, results = emptyList()) }
        }
    }
    fun choose(place: SearchPlace) {
        addressJob?.cancel()
        mutable.value = state.value.copy(selected = place.point, cameraTarget = place.point, name = place.name, address = place.address,
            source = PlaceSource.MAP_SEARCH, results = emptyList(), latitude = place.point.lat.toString(), longitude = place.point.lon.toString())
    }
    fun moved(point: MapPoint) {
        if (!point.valid() || state.value.selected?.let { kotlin.math.abs(it.lat - point.lat) < 0.000001 && kotlin.math.abs(it.lon - point.lon) < 0.000001 } == true) return
        addressJob?.cancel()
        mutable.value = state.value.copy(selected = point, address = null, source = PlaceSource.MAP_PIN,
            latitude = point.lat.toString(), longitude = point.lon.toString())
        addressJob = scope.launch {
            delay(400)
            val address = try { provider.address(point) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            mutable.value = state.value.copy(address = address)
        }
    }
    fun current(point: MapPoint) {
        moved(point)
        mutable.value = state.value.copy(cameraTarget = point, source = PlaceSource.CURRENT_LOCATION)
    }
    fun radius(value: Float) { mutable.value = state.value.copy(radius = value.coerceIn(5f, 500f)) }
    fun manual() { addressJob?.cancel(); mutable.value = state.value.copy(manual = true, source = PlaceSource.MANUAL_COORDS, address = null) }
    fun coordinates(lat: String, lon: String) {
        val point = runCatching { MapPoint(lat.toDouble(), lon.toDouble()).takeIf { it.valid() } }.getOrNull()
        mutable.value = state.value.copy(latitude = lat, longitude = lon, selected = point, source = PlaceSource.MANUAL_COORDS, address = null)
    }
    fun apply(draft: PlaceDraft): PlaceDraft? {
        val s = state.value
        val point = s.selected?.takeIf { it.valid() } ?: return null
        return draft.copy(lat = point.lat.toString(), lon = point.lon.toString(), radius = s.radius, address = s.address,
            label = draft.label.ifBlank { s.name?.take(20).orEmpty() }, source = s.source)
    }
}
