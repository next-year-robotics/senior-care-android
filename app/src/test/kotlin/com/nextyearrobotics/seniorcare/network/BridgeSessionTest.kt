@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.data.TripRepository
import com.nextyearrobotics.seniorcare.data.TripScreen
import com.nextyearrobotics.seniorcare.domain.TripPhase
import com.nextyearrobotics.seniorcare.domain.Clock
import com.nextyearrobotics.seniorcare.domain.Connection
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.collect
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.test.*

internal class MemorySettings(var endpoint: String? = null) : ConnectionSettings {
    val id = UUID.randomUUID().toString()
    override suspend fun instanceId() = id
    override suspend fun lastEndpoint() = endpoint
    override suspend fun saveEndpoint(endpoint: String) { this.endpoint = endpoint }
}
internal fun fixture(name: String): Payload = PayloadCodec.decode(File("../contracts/phone-bridge/v1/fixtures/$name.json").readText()).payload
private class FakeTransport : BridgeTransport {
    override val events = Channel<SocketEvent>(64)
    val sent = mutableListOf<Message>()
    var closed = false
    override fun send(text: String): Boolean { sent += PayloadCodec.decode(text); return !closed }
    override fun close() { closed = true; events.close() }
    fun deliver(payload: Payload, replyTo: String? = null, id: String = UUID.randomUUID().toString()) {
        events.trySend(SocketEvent.Text(Protocol.encode(PayloadCodec.envelope(payload, 0, replyTo, id))))
    }
}
class BridgeSessionTest {
    private fun TestScope.setup(settings: MemorySettings = MemorySettings(), discovery: BridgeDiscovery = BridgeDiscovery { emptyList() }): Pair<BridgeSession, MutableList<FakeTransport>> {
        val transports = mutableListOf<FakeTransport>()
        val clock = object : Clock { override fun monotonicMs() = testScheduler.currentTime; override fun epochMs() = 0L }
        return BridgeSession(backgroundScope, TransportFactory { FakeTransport().also { transports += it } }, discovery, settings, "test", clock, ReconnectBackoff { .5 }) to transports
    }
    private suspend fun TestScope.ready(session: BridgeSession, transports: List<FakeTransport>, start: Boolean = true): FakeTransport {
        if (start) session.start("ws://127.0.0.1:8765/bridge/v1")
        runCurrent()
        val t = transports.last()
        t.events.send(SocketEvent.Open); runCurrent()
        t.deliver(fixture("hello-ack"), t.sent.single().envelope.id)
        t.deliver(fixture("place-catalog-default6"))
        t.deliver(fixture("robot-status-idle"))
        runCurrent()
        assertEquals(Connection.READY, session.state.value.connection)
        return t
    }
    @Test fun handshakeHeartbeatAndTenSecondTimeout() = runTest {
        val (session, transports) = setup()
        val t = ready(session, transports)
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, t.sent.count { it.payload is Heartbeat })
        // A status is not a heartbeat ACK and cannot keep the connection alive.
        t.deliver(fixture("robot-status-idle")); runCurrent()
        advanceTimeBy(8000); runCurrent()
        assertTrue(t.closed)
        assertEquals(Connection.RECONNECTING, session.state.value.connection)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, transports.size)
        session.stop()
    }
    @Test fun heartbeatMustMatchSessionAndRequestAndStatusBecomesStale() = runTest {
        val (session, transports) = setup()
        val t = ready(session, transports)
        advanceTimeBy(2000); runCurrent()
        val heartbeat = t.sent.last()
        t.deliver(HeartbeatAck(UUID.randomUUID().toString()), heartbeat.envelope.id); runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertTrue(session.state.value.stale)
        t.deliver(HeartbeatAck((fixture("hello-ack") as HelloAck).sessionId), heartbeat.envelope.id); runCurrent()
        advanceTimeBy(5100); runCurrent()
        assertFalse(t.closed)
        advanceTimeBy(4900); runCurrent()
        assertTrue(t.closed)
        session.stop()
    }
    @Test fun commandAckTimeoutNeverReplaysAndDisconnectIsUnknown() = runTest {
        val (session, transports) = setup()
        assertEquals(CommandResult.NotConnected, session.request(fixture("trip-start")))
        val t = ready(session, transports)
        val result = async { session.request(fixture("trip-start")) }; runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(CommandResult.Unknown, result.await())
        t.deliver(fixture("trip-start-ack"), t.sent.last().envelope.id); runCurrent()
        val cancel = async { session.request(fixture("trip-cancel")) }; runCurrent()
        t.events.send(SocketEvent.Closed); runCurrent()
        assertEquals(CommandResult.Unknown, cancel.await())
        advanceTimeBy(1000); runCurrent()
        ready(session, transports, start = false)
        assertTrue(transports.last().sent.none { it.payload is TripStart || it.payload is TripCancel })
        session.stop()
    }
    @Test fun catalogAndStatusAreRequiredAndPreHandshakeFramesIgnored() = runTest {
        val (session, transports) = setup()
        session.start("ws://127.0.0.1:8765/bridge/v1"); runCurrent()
        val t = transports.single()
        t.events.send(SocketEvent.Open); runCurrent()
        t.deliver(fixture("place-catalog-default6")); t.deliver(fixture("robot-status-idle")); runCurrent()
        assertNull(session.state.value.catalog)
        t.deliver(fixture("hello-ack"), UUID.randomUUID().toString()); runCurrent()
        assertNull(session.state.value.hello)
        t.deliver(fixture("hello-ack"), t.sent.single().envelope.id)
        t.deliver(fixture("place-catalog-default6")); runCurrent()
        assertEquals(Connection.SYNCING, session.state.value.connection)
        t.deliver(fixture("robot-status-idle")); runCurrent()
        assertEquals(Connection.READY, session.state.value.connection)
        session.stop()
    }
    @Test fun unsupportedVersionStopsRetries() = runTest {
        val (session, transports) = setup()
        val t = ready(session, transports)
        t.deliver(ErrorPayload(ErrorCode.UNSUPPORTED_VERSION, "version", false))
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(Connection.STOPPED, session.state.value.connection)
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, session.state.value.error)
        assertEquals(1, transports.size)
        session.stop()
    }
    @Test fun savedEndpointHasTwoSecondProbeThenDiscoveryAndMultipleRobotsNeedSelection() = runTest {
        var discoveries = 0
        val settings = MemorySettings("ws://127.0.0.1:9999/bridge/v1")
        val (session, transports) = setup(settings, BridgeDiscovery {
            discoveries++
            listOf(RobotEndpoint("a", "ws://127.0.0.1:8765/bridge/v1"), RobotEndpoint("b", "ws://127.0.0.1:8766/bridge/v1"))
        })
        session.start(); runCurrent()
        advanceTimeBy(1999); runCurrent(); assertEquals(0, discoveries)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, discoveries)
        assertTrue(session.state.value.needsEndpoint)
        assertEquals(2, session.state.value.candidates.size)
        assertTrue(transports.single().closed)
        session.stop()
    }
    @Test fun sosDuplicatesReachConsumerButRepeatedStatusDoesNotRefreshStaleness() = runTest {
        val (session, transports) = setup()
        val t = ready(session, transports)
        val received = mutableListOf<ReceivedMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.messages.collect { received += it } }
        val id = UUID.randomUUID().toString()
        t.deliver(fixture("sos-request"), id = id); t.deliver(fixture("sos-request"), id = id); runCurrent()
        assertEquals(listOf(false, true), received.map { it.duplicate })
        val statusId = UUID.randomUUID().toString()
        t.deliver(fixture("robot-status-idle"), id = statusId); runCurrent()
        advanceTimeBy(5000); runCurrent()
        t.deliver(fixture("robot-status-idle"), id = statusId); runCurrent()
        assertTrue(session.state.value.stale)
        session.stop()
    }
    @Test fun backoffResetsOnlyAfterThirtyStableSeconds() = runTest {
        val (session, transports) = setup()
        var t = ready(session, transports)
        t.events.send(SocketEvent.Closed); runCurrent()
        advanceTimeBy(1000); runCurrent(); t = ready(session, transports, start = false)
        t.events.send(SocketEvent.Closed); runCurrent()
        advanceTimeBy(1999); runCurrent(); assertEquals(2, transports.size)
        advanceTimeBy(1); runCurrent(); t = ready(session, transports, start = false)
        repeat(15) {
            advanceTimeBy(2000); runCurrent()
            val beat = t.sent.last { it.payload is Heartbeat }
            t.deliver(HeartbeatAck((beat.payload as Heartbeat).sessionId), beat.envelope.id); runCurrent()
        }
        t.events.send(SocketEvent.Closed); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(4, transports.size)
        session.stop()
    }
    @Test fun tripDoubleTapUndoExpiryAndCancelAckWaitForActualStop() = runTest {
        val (session, transports) = setup()
        val clock = object : Clock { override fun monotonicMs() = testScheduler.currentTime; override fun epochMs() = 0L }
        val repository = TripRepository(session, backgroundScope, clock)
        val t = ready(session, transports)
        repository.select("place_market")
        assertEquals(TripScreen.CONFIRM, repository.state.value.screen)
        repository.startTrip(); repository.startTrip(); runCurrent()
        assertEquals(1, t.sent.count { it.payload is TripStart })
        val start = t.sent.last()
        t.deliver(fixture("trip-start-ack"), start.envelope.id)
        t.deliver(fixture("robot-status")); runCurrent()
        assertTrue(repository.state.value.canUndo)
        advanceTimeBy(5000); runCurrent()
        assertFalse(repository.state.value.canUndo)
        repository.cancelTrip(); repository.cancelTrip(); runCurrent()
        val cancel = t.sent.last { it.payload is TripCancel }
        assertEquals(1, t.sent.count { it.payload is TripCancel })
        t.deliver(fixture("trip-cancel-ack"), cancel.envelope.id)
        t.deliver(fixture("robot-status")); runCurrent()
        assertEquals(TripPhase.CANCELLING, repository.state.value.app.phase)
        t.deliver((fixture("robot-status") as RobotStatus).copy(missionState = RobotStatusMissionState.CANCELLED)); runCurrent()
        assertEquals(TripScreen.HOME, repository.state.value.screen)
        session.stop()
    }
    @Test fun urgentSosDuringSyncAndBlockedCommandAckIsNotRequired() = runTest {
        val (session, transports) = setup()
        session.onSos = { message ->
            val request = message.payload as SosRequest
            session.sendUrgent(SosAck(request.sosId, true), message.envelope.id)
            session.sendUrgent((fixture("location-none") as LocationUpdate).copy(sosId = request.sosId))
        }
        session.start("ws://127.0.0.1:8765/bridge/v1"); runCurrent()
        val t = transports.single(); t.events.send(SocketEvent.Open); runCurrent()
        t.deliver(fixture("hello-ack"), t.sent.single().envelope.id); runCurrent()
        t.deliver(fixture("sos-request")); runCurrent()
        assertEquals(Connection.SYNCING, session.state.value.connection)
        assertEquals(listOf("sos.ack", "location.update"), t.sent.takeLast(2).map { it.envelope.type })
        t.deliver(fixture("place-catalog-default6")); t.deliver(fixture("robot-status-idle")); runCurrent()
        val trip = async { session.request(fixture("trip-start")) }; runCurrent()
        t.deliver(fixture("sos-request")); runCurrent()
        assertFalse(trip.isCompleted)
        assertEquals(2, t.sent.count { it.payload is SosAck })
        session.stop(); trip.await()
    }
    @Test fun placeEditorWaitsForCatalogRejectsDuplicateSaveAndDefaultDelete() = runTest {
        val (session, transports) = setup()
        val t = ready(session, transports)
        val editor = com.nextyearrobotics.seniorcare.places.PlaceEditor(session, backgroundScope)
        editor.open("place_market")
        editor.update(editor.state.value.draft!!.copy(label = "수정한 시장"))
        editor.save(); editor.save(); runCurrent()
        assertEquals(1, t.sent.count { it.payload is PlaceUpsert })
        val request = t.sent.last()
        val catalog = session.state.value.catalog!!
        val next = catalog.copy(revision = "2026-09-27T12:00:01Z", places = catalog.places.map { if (it.id == "place_market") it.copy(label = "수정한 시장") else it })
        t.deliver(Ack(AckStatus.ACCEPTED, catalogRevision = next.revision), request.envelope.id); runCurrent()
        assertTrue(editor.state.value.busy)
        t.deliver(next); runCurrent()
        assertEquals(com.nextyearrobotics.seniorcare.places.EditNotice.SAVED, editor.state.value.notice)
        assertNull(editor.state.value.draft)
        editor.open("place_home"); editor.delete(); runCurrent()
        assertEquals(com.nextyearrobotics.seniorcare.places.EditNotice.DEFAULT_ORIGIN, editor.state.value.notice)
        assertTrue(t.sent.none { it.payload is PlaceDelete })
        editor.open("place_market")
        t.deliver(next.copy(revision = "2026-09-27T12:00:02Z")); runCurrent()
        editor.save(); runCurrent()
        assertEquals(com.nextyearrobotics.seniorcare.places.EditNotice.CONFLICT, editor.state.value.notice)
        assertEquals(1, t.sent.count { it.payload is PlaceUpsert })
        session.stop()
    }
    @Test fun backoffCapsAndJitterBounds() {
        val center = ReconnectBackoff { .5 }
        assertEquals(listOf(1000L, 2000, 4000, 8000, 10000, 10000), List(6) { center.nextMs() })
        center.reset(); assertEquals(1000, center.nextMs())
        assertEquals(800, ReconnectBackoff { 0.0 }.nextMs())
        assertEquals(1200, ReconnectBackoff { 1.0 }.nextMs())
    }
    @Test fun manualEndpointValidationRejectsCredentialsPathsAndPorts() {
        assertEquals("ws://192.168.1.20:8765/bridge/v1", Endpoint.manual("192.168.1.20", "8765"))
        listOf("256.1.1.1", "a.local", "1.2.3", "1.2.3.-1").forEach { assertFailsWith<IllegalArgumentException> { Endpoint.manual(it, "8765") } }
        listOf("0", "65536", "abc").forEach { assertFailsWith<IllegalArgumentException> { Endpoint.manual("1.2.3.4", it) } }
        listOf("ws://user:pass@host:8765/bridge/v1", "http://host:8765/bridge/v1", "ws://host:8765/other").forEach { assertFailsWith<IllegalArgumentException> { Endpoint.validate(it) } }
    }
}
