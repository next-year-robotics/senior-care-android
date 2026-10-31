package com.nextyearrobotics.seniorcare.data

import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.network.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TripScreen { HOME, CONFIRM, TRIP, ORIGIN }
enum class TripNotice { NONE, SAME_PLACE, SELECTION_CHANGED, RESULT_UNKNOWN, REJECTED, CATALOG_CHANGED,
    SOS_OFFLINE, SOS_FAILED, SOS_UNSUPPORTED }
data class TripUiState(
    val app: AppState = AppState(), val screen: TripScreen = TripScreen.HOME,
    val busy: Boolean = false, val undoUntilMs: Long? = null, val notice: TripNotice = TripNotice.NONE,
    val sosBusy: Boolean = false,
) { val canUndo get() = undoUntilMs?.let { app.nowMs < it } == true && app.tripId != null && app.phase in listOf(TripPhase.ACTIVE, TripPhase.START_PENDING) }

/** Joins the transport and domain reducer; UI never talks to the socket directly. */
class TripRepository(private val session: BridgeSession, private val scope: CoroutineScope, private val clock: Clock = SystemClock) {
    private val mutable = MutableStateFlow(TripUiState())
    val state = mutable.asStateFlow()
    private var catalog: PlaceCatalog? = null
    private var handledArrival: String? = null
    init {
        scope.launch {
            var previous = SessionState()
            session.state.collect { current ->
                var ui = mutable.value
                var app = ui.app
                if (current.connection == Connection.RECONNECTING && previous.connection != Connection.RECONNECTING) {
                    app = reduce(app, AppEvent.Disconnected)
                    if (app.resultUnknown) ui = ui.copy(notice = TripNotice.RESULT_UNKNOWN)
                }
                app = app.copy(connection = current.connection)
                if (current.catalog != null && current.catalog != catalog) {
                    val before = app.destinationId
                    val followsDefault = app.originId == catalog?.defaultOriginPlaceId
                    catalog = current.catalog
                    app = reduce(app, AppEvent.Catalog(current.catalog.places.map {
                        Destination(it.id, it.kind.wire, it.label, it.enabled, it.sortOrder, it.lat, it.lon)
                    }, current.catalog.defaultOriginPlaceId))
                    if (followsDefault) app = app.copy(originId = current.catalog.defaultOriginPlaceId)
                    if (before != null && app.destinationId == null && ui.screen == TripScreen.CONFIRM)
                        ui = ui.copy(screen = TripScreen.HOME, notice = TripNotice.SELECTION_CHANGED)
                }
                if (current.status != null && (current.statusAtMs != previous.statusAtMs || current.status != previous.status)) {
                    val status = current.status
                    app = reduce(app, AppEvent.Status(Mission.entries.firstOrNull { it.name == status.missionState.name } ?: Mission.UNKNOWN, status.tripId, current.statusAtMs ?: clock.monotonicMs(),
                        status.batteryPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }?.toInt(),
                        Tracking.entries.firstOrNull { it.name == status.trackingState.name } ?: Tracking.UNKNOWN))
                    if (status.missionState in listOf(RobotStatusMissionState.STARTING, RobotStatusMissionState.ACTIVE, RobotStatusMissionState.CANCELLING, RobotStatusMissionState.ARRIVED)) {
                        app = app.copy(originId = status.fromPlaceId, destinationId = status.toPlaceId)
                        if (status.missionState == RobotStatusMissionState.ARRIVED && handledArrival == status.tripId)
                            app = app.copy(phase = TripPhase.READY)
                        else ui = ui.copy(screen = TripScreen.TRIP)
                    }
                    if (status.missionState in listOf(RobotStatusMissionState.IDLE, RobotStatusMissionState.CANCELLED) && !ui.busy && ui.screen == TripScreen.TRIP && !app.resultUnknown)
                        ui = ui.copy(screen = TripScreen.HOME, undoUntilMs = null)
                }
                app = reduce(app, AppEvent.Tick(current.nowMs))
                mutable.value = ui.copy(app = app)
                previous = current
            }
        }
    }
    fun select(id: String) {
        val ui = state.value
        if (!ui.app.canEdit || ui.busy) return
        if (id == ui.app.originId) { mutable.value = ui.copy(notice = TripNotice.SAME_PLACE); return }
        val app = reduce(ui.app, AppEvent.Select(id))
        if (app.destinationId != null) mutable.value = ui.copy(app = app, screen = TripScreen.CONFIRM, notice = TripNotice.NONE)
    }
    fun chooseOrigin() { if (state.value.app.canEdit) mutable.value = state.value.copy(screen = TripScreen.ORIGIN) }
    fun setOrigin(id: String) { mutable.value = state.value.copy(app = reduce(state.value.app, AppEvent.Origin(id)), screen = TripScreen.HOME) }
    fun back() {
        val ui = state.value
        if (ui.app.phase == TripPhase.ARRIVED) {
            handledArrival = ui.app.tripId
            mutable.value = ui.copy(app = ui.app.copy(phase = TripPhase.READY), screen = TripScreen.HOME)
        } else if (!ui.busy && ui.screen != TripScreen.TRIP) mutable.value = ui.copy(screen = TripScreen.HOME)
    }
    fun dismissNotice() { mutable.value = state.value.copy(notice = TripNotice.NONE) }
    fun startTrip() {
        val ui = state.value
        val revision = catalog?.revision ?: return
        if (ui.busy || !ui.app.canStart) return
        mutable.value = ui.copy(app = reduce(ui.app, AppEvent.RequestStart), busy = true)
        scope.launch {
            val result = session.request(TripStart(checkNotNull(ui.app.originId), checkNotNull(ui.app.destinationId), revision))
            val latest = state.value
            mutable.value = when (result) {
                is CommandResult.Accepted -> if (result.ack.tripId == null) uncertain(latest) else latest.copy(
                    app = latest.app.copy(tripId = result.ack.tripId, phase = if (latest.app.phase == TripPhase.READY) TripPhase.START_PENDING else latest.app.phase),
                    busy = false, screen = TripScreen.TRIP, undoUntilMs = clock.monotonicMs() + 5000)
                is CommandResult.Rejected -> rejected(latest, result.error.code)
                else -> uncertain(latest)
            }
        }
    }
    fun cancelTrip() {
        val ui = state.value
        val tripId = ui.app.tripId ?: return
        val app = reduce(ui.app, AppEvent.RequestCancel)
        if (ui.busy || app.phase != TripPhase.CANCELLING || ui.app.phase == TripPhase.CANCELLING) return
        mutable.value = ui.copy(app = app, busy = true, undoUntilMs = null)
        scope.launch {
            val latestResult = session.request(TripCancel(tripId, "user_request"))
            val latest = state.value
            mutable.value = when (latestResult) {
                is CommandResult.Accepted -> latest.copy(busy = false)
                is CommandResult.Rejected -> rejected(latest, latestResult.error.code)
                else -> uncertain(latest)
            }
        }
    }
    /** 앱 SOS 버튼(sos.raise). 로봇이 SOS를 열면 sos.request가 와서 SOS 화면이 뜬다. 앱이 SOS를 직접 만들지 않는다. */
    fun raiseSos() {
        val ui = state.value
        if (ui.sosBusy) return
        if (ui.app.connection != Connection.READY) { mutable.value = ui.copy(notice = TripNotice.SOS_OFFLINE); return }
        mutable.value = ui.copy(sosBusy = true)
        scope.launch {
            val result = session.request(SosRaise(SosRaiseReason.MANUAL))
            val latest = state.value
            mutable.value = latest.copy(sosBusy = false, notice = when (result) {
                is CommandResult.Accepted -> latest.notice
                is CommandResult.Rejected -> if (result.error.code == ErrorCode.UNSUPPORTED_TYPE) TripNotice.SOS_UNSUPPORTED else TripNotice.SOS_FAILED
                CommandResult.NotConnected -> TripNotice.SOS_OFFLINE
                CommandResult.Unknown -> TripNotice.SOS_FAILED
            })
        }
    }
    /** SOS 화면의 '괜찮아요'(sos.cancel). 로봇에 그 SOS가 이미 없으면(INVALID_STATE) 앱에서도 닫는다. */
    fun cancelSos(id: String, forget: (String) -> Unit) {
        scope.launch {
            val result = session.request(SosCancel(id))
            if (result is CommandResult.Rejected && result.error.code == ErrorCode.INVALID_STATE) forget(id)
        }
    }
    private fun uncertain(ui: TripUiState) = ui.copy(app = reduce(ui.app, AppEvent.CommandUncertain), busy = false, screen = TripScreen.TRIP, notice = TripNotice.RESULT_UNKNOWN)
    private fun rejected(ui: TripUiState, code: ErrorCode): TripUiState {
        // Rejection is not proof of stopping. Keep an active trip visible, and wait for robot.status.
        val app = reduce(ui.app, AppEvent.CommandRejected)
        return ui.copy(app = app, busy = false, screen = if (app.phase == TripPhase.READY) TripScreen.HOME else TripScreen.TRIP,
            notice = if (code == ErrorCode.CATALOG_CONFLICT || code == ErrorCode.UNKNOWN_PLACE) TripNotice.CATALOG_CHANGED else TripNotice.REJECTED)
    }
}
