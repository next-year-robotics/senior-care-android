package com.nextyearrobotics.seniorcare.domain

enum class Connection { STOPPED, DISCOVERING, CONNECTING, SYNCING, READY, RECONNECTING }
enum class Mission { UNKNOWN, IDLE, STARTING, ACTIVE, CANCELLING, CANCELLED, ARRIVED, ERROR }
enum class TripPhase { READY, START_PENDING, ACTIVE, CANCELLING, ARRIVED, UNKNOWN }
enum class Tracking { UNKNOWN, UNAVAILABLE, IDLE, SEARCHING, FOLLOWING, LOST, ERROR }
/** lat/lon은 주행 화면 지도에 목적지 핀을 찍는 데만 쓴다(명세: 홈 화면에는 좌표를 보이지 않는다). */
data class Destination(val id: String, val kind: String, val label: String, val enabled: Boolean, val sortOrder: Long,
                       val lat: Double? = null, val lon: Double? = null)
data class Sos(val id: String, val reason: String, val dismissed: Boolean = false)

data class AppState(
    val connection: Connection = Connection.STOPPED,
    val phase: TripPhase = TripPhase.UNKNOWN,
    val mission: Mission = Mission.UNKNOWN,
    val tripId: String? = null,
    val catalog: List<Destination> = emptyList(),
    val originId: String? = null,
    val destinationId: String? = null,
    val catalogReceived: Boolean = false,
    val statusAtMs: Long? = null,
    val nowMs: Long = 0,
    val resultUnknown: Boolean = false,
    val sos: Map<String, Sos> = emptyMap(),
    val batteryPercent: Int? = null,
    val tracking: Tracking = Tracking.UNKNOWN,
) {
    val stale: Boolean get() = connection != Connection.READY || statusAtMs?.let { nowMs - it >= 5_000 } != false
    val canEdit: Boolean get() = connection == Connection.READY && phase == TripPhase.READY && !stale
    val canStart: Boolean get() = canEdit && originId != destinationId &&
        catalog.any { it.id == originId && it.enabled } && catalog.any { it.id == destinationId && it.enabled }
    val visibleSos: Sos? get() = sos.values.firstOrNull { !it.dismissed }
}

sealed interface AppEvent {
    data object Start : AppEvent
    data object EndpointFound : AppEvent
    data object SocketOpened : AppEvent
    data object Disconnected : AppEvent
    data object Stop : AppEvent
    data class Catalog(val places: List<Destination>, val defaultOrigin: String?) : AppEvent
    data class Status(val mission: Mission, val tripId: String?, val atMs: Long,
                      val batteryPercent: Int? = null, val tracking: Tracking = Tracking.UNKNOWN) : AppEvent
    data class Select(val id: String?) : AppEvent
    data class Origin(val id: String) : AppEvent
    data object RequestStart : AppEvent
    data object RequestCancel : AppEvent
    data object CommandUncertain : AppEvent
    data object CommandRejected : AppEvent
    data class Tick(val atMs: Long) : AppEvent
    data class SosRequested(val id: String, val reason: String) : AppEvent
    data class SosDismissed(val id: String) : AppEvent
    data class SosCleared(val id: String) : AppEvent
}

/** Pure reducer. ACK is intentionally not a mission transition: only robot.status is authoritative. */
fun reduce(state: AppState, event: AppEvent): AppState {
    val next = when (event) {
        AppEvent.Start -> if (state.connection == Connection.STOPPED) state.copy(connection = Connection.DISCOVERING) else state
        AppEvent.EndpointFound -> state.copy(connection = Connection.CONNECTING, catalogReceived = false, statusAtMs = null)
        AppEvent.SocketOpened -> state.copy(connection = Connection.SYNCING)
        AppEvent.Disconnected -> state.copy(connection = Connection.RECONNECTING, catalogReceived = false,
            resultUnknown = state.resultUnknown || state.phase in listOf(TripPhase.START_PENDING, TripPhase.CANCELLING))
        AppEvent.Stop -> state.copy(connection = Connection.STOPPED, catalogReceived = false, statusAtMs = null)
        is AppEvent.Catalog -> state.copy(catalog = event.places.sortedBy { it.sortOrder }, catalogReceived = true,
            originId = state.originId?.takeIf { id -> event.places.any { it.id == id && it.enabled } } ?: event.defaultOrigin,
            destinationId = state.destinationId?.takeIf { id -> event.places.any { it.id == id && it.enabled } })
        is AppEvent.Status -> state.copy(mission = event.mission, tripId = event.tripId, statusAtMs = event.atMs, nowMs = event.atMs,
            batteryPercent = event.batteryPercent, tracking = event.tracking,
            resultUnknown = false, phase = when (event.mission) {
                Mission.IDLE, Mission.CANCELLED -> TripPhase.READY
                Mission.STARTING -> TripPhase.START_PENDING
                Mission.ACTIVE -> if (state.phase == TripPhase.CANCELLING) TripPhase.CANCELLING else TripPhase.ACTIVE
                Mission.CANCELLING -> TripPhase.CANCELLING
                Mission.ARRIVED -> TripPhase.ARRIVED
                Mission.UNKNOWN, Mission.ERROR -> TripPhase.UNKNOWN
            })
        is AppEvent.Select -> if (state.canEdit) state.copy(destinationId = event.id?.takeIf { id -> state.catalog.any { it.id == id && it.enabled } }) else state
        is AppEvent.Origin -> if (state.canEdit && state.catalog.any { it.id == event.id && it.enabled }) state.copy(originId = event.id, destinationId = null) else state
        AppEvent.RequestStart -> if (state.canStart) state.copy(phase = TripPhase.START_PENDING) else state
        AppEvent.RequestCancel -> if (state.connection == Connection.READY && state.tripId != null &&
            state.phase in listOf(TripPhase.ACTIVE, TripPhase.START_PENDING)) state.copy(phase = TripPhase.CANCELLING) else state
        AppEvent.CommandRejected -> state.copy(phase = when (state.mission) {
            Mission.IDLE, Mission.CANCELLED -> TripPhase.READY
            Mission.STARTING -> TripPhase.START_PENDING
            Mission.ACTIVE -> TripPhase.ACTIVE
            Mission.CANCELLING -> TripPhase.CANCELLING
            Mission.ARRIVED -> TripPhase.ARRIVED
            else -> TripPhase.UNKNOWN
        })
        AppEvent.CommandUncertain -> state.copy(resultUnknown = true)
        is AppEvent.Tick -> state.copy(nowMs = event.atMs)
        is AppEvent.SosRequested -> state.copy(sos = state.sos + (event.id to (state.sos[event.id] ?: Sos(event.id, event.reason))))
        is AppEvent.SosDismissed -> state.copy(sos = state.sos.mapValues { (id, sos) -> if (id == event.id) sos.copy(dismissed = true) else sos })
        is AppEvent.SosCleared -> state.copy(sos = state.sos - event.id)
    }
    return if (next.connection == Connection.SYNCING && next.catalogReceived && next.statusAtMs != null)
        next.copy(connection = Connection.READY) else next
}
