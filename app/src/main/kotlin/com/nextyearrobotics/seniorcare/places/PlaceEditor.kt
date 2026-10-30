package com.nextyearrobotics.seniorcare.places

import com.nextyearrobotics.seniorcare.network.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class EditNotice { NONE, SAVED, INVALID, CONFLICT, LIMIT, IN_USE, DEFAULT_ORIGIN, DISABLED, UNKNOWN, REJECTED }
data class PlaceDraft(val id: String? = null, val revision: String, val kind: PlaceKind = PlaceKind.OTHER,
    val label: String = "", val lat: String = "", val lon: String = "", val radius: Float = 30f,
    val enabled: Boolean = true, val order: String = "10", val defaultOrigin: Boolean = false,
    val address: String? = null, val source: PlaceSource = PlaceSource.MANUAL_COORDS) {
    fun command(): PlaceUpsert {
        val payload = PlaceUpsert(id, revision, kind, label.trim(), lat.toDouble(), lon.toDouble(), radius.toDouble(),
            enabled, order.toLong(), defaultOrigin, address?.takeIf { it.isNotBlank() }, source)
        PayloadCodec.envelope(payload, 0) // Same outbound contract validation as the transport.
        return payload
    }
}
data class PlaceEditorState(val draft: PlaceDraft? = null, val busy: Boolean = false, val notice: EditNotice = EditNotice.NONE)

class PlaceEditor(private val session: BridgeSession, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlaceEditorState())
    val state = mutable.asStateFlow()
    fun canEdit(): Boolean = session.state.value.let {
        it.connection == com.nextyearrobotics.seniorcare.domain.Connection.READY && !it.stale && it.hello?.placeEditEnabled == true &&
            it.status?.missionState in listOf(RobotStatusMissionState.IDLE, RobotStatusMissionState.CANCELLED, RobotStatusMissionState.ARRIVED)
    }
    fun open(id: String? = null) {
        val catalog = session.state.value.catalog ?: return
        if (state.value.busy) return
        val p = catalog.places.find { it.id == id }
        if (id != null && p == null) { mutable.value = state.value.copy(notice = EditNotice.CONFLICT); return }
        mutable.value = PlaceEditorState(draft = if (p == null) PlaceDraft(revision = catalog.revision,
            order = ((catalog.places.maxOfOrNull { it.sortOrder } ?: 0) + 10).toString(), defaultOrigin = catalog.places.isEmpty())
        else PlaceDraft(p.id, catalog.revision, p.kind, p.label, p.lat.toString(), p.lon.toString(), p.radiusM.toFloat(), p.enabled,
            p.sortOrder.toString(), p.isDefaultOrigin, p.address))
    }
    fun update(draft: PlaceDraft) { if (!state.value.busy) mutable.value = state.value.copy(draft = draft, notice = EditNotice.NONE) }
    fun close() { if (!state.value.busy) mutable.value = PlaceEditorState() }
    fun dismissNotice() { mutable.value = state.value.copy(notice = EditNotice.NONE) }
    fun reload() { open(state.value.draft?.id) }
    fun save() {
        val draft = state.value.draft ?: return
        if (!guard(draft)) return
        if (draft.id == null && (session.state.value.catalog?.places?.size ?: 0) >= (session.state.value.hello?.maxPlaces ?: 0)) {
            mutable.value = state.value.copy(notice = EditNotice.LIMIT); return
        }
        val command = runCatching { draft.command() }.getOrNull()
        if (command == null) { mutable.value = state.value.copy(notice = EditNotice.INVALID); return }
        send(command)
    }
    fun delete() {
        val draft = state.value.draft ?: return
        if (!guard(draft)) return
        val id = draft.id ?: return
        if (session.state.value.catalog?.defaultOriginPlaceId == id) { mutable.value = state.value.copy(notice = EditNotice.DEFAULT_ORIGIN); return }
        send(PlaceDelete(id, draft.revision))
    }
    private fun guard(draft: PlaceDraft): Boolean {
        if (state.value.busy) return false
        val notice = when {
            !canEdit() -> EditNotice.DISABLED
            draft.revision != session.state.value.catalog?.revision -> EditNotice.CONFLICT
            else -> EditNotice.NONE
        }
        mutable.value = state.value.copy(notice = notice)
        return notice == EditNotice.NONE
    }
    private fun send(command: Payload) {
        mutable.value = state.value.copy(busy = true, notice = EditNotice.NONE)
        scope.launch {
            val result = session.request(command)
            val notice = when (result) {
                is CommandResult.Accepted -> {
                    val revision = result.ack.catalogRevision
                    if (revision != null && withTimeoutOrNull(2500) { session.state.first { it.catalog?.revision == revision } } != null) EditNotice.SAVED else EditNotice.UNKNOWN
                }
                is CommandResult.Rejected -> when (result.error.code) {
                    ErrorCode.CATALOG_CONFLICT, ErrorCode.UNKNOWN_PLACE -> EditNotice.CONFLICT
                    ErrorCode.PLACE_LIMIT -> EditNotice.LIMIT
                    ErrorCode.PLACE_IN_USE -> EditNotice.IN_USE
                    ErrorCode.INVALID_STATE, ErrorCode.PERMISSION_DENIED -> EditNotice.DISABLED
                    ErrorCode.INVALID_MESSAGE -> EditNotice.INVALID
                    else -> EditNotice.REJECTED
                }
                else -> EditNotice.UNKNOWN
            }
            mutable.value = state.value.copy(busy = false, notice = notice, draft = if (notice == EditNotice.SAVED) null else state.value.draft)
        }
    }
}
