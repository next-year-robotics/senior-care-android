package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.location.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private class SyntheticSource : LocationSource {
    override fun failure(): LocationUpdateFailureReason? = null
    override fun precise() = true
    override fun start(onFix: (LocationFix) -> Unit) = Unit
    override fun stop() = Unit
    override suspend fun fresh(): FixResult { delay(100); return FixResult(LocationFix(36.1234, 128.5678, 4.0, System.currentTimeMillis(), SystemClock.monotonicMs())) }
}
private class TestAlerts : SosAlerts {
    var first = 0
    override fun show(state: SosState, first: Boolean) { if (first) this.first++ }
    override fun clear(id: String) = Unit
}
class SosSocketTest {
    @Test fun serverMeasuresAckAndCacheUnder500msThenFreshAndClear(): Unit = runBlocking {
        val server = MockWebServer()
        val times = ConcurrentHashMap<String, Long>()
        val socket = CompletableDeferred<WebSocket>()
        var sentAt = 0L
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = PayloadCodec.decode(text)
                fun reply(payload: Payload, replyTo: String? = null) = webSocket.send(Protocol.encode(PayloadCodec.envelope(payload, 0, replyTo)))
                when (val p = m.payload) {
                    is Hello -> { reply(fixture("hello-ack"), m.envelope.id); reply(fixture("place-catalog-default6")); reply(fixture("robot-status-idle")) }
                    is SosAck -> { times["ack"] = (System.nanoTime() - sentAt) / 1_000_000; reply(Ack(AckStatus.ACCEPTED), m.envelope.id) }
                    is LocationUpdate -> { times[p.source.wire] = (System.nanoTime() - sentAt) / 1_000_000; reply(Ack(AckStatus.ACCEPTED), m.envelope.id) }
                    is Heartbeat -> reply(HeartbeatAck(p.sessionId), m.envelope.id)
                    else -> Unit
                }
            }
        }).build())
        server.start()
        val client = OkHttpClient()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = BridgeSession(scope, OkHttpTransportFactory(client), BridgeDiscovery { emptyList() }, MemorySettings(), "test")
        val cache = LocationCache(SystemClock).apply { update(LocationFix(36.1234, 128.5678, 4.0, System.currentTimeMillis(), SystemClock.monotonicMs())) }
        val alerts = TestAlerts()
        val sos = SosCoordinator(scope, SyntheticSource(), cache, UrgentSender(session::sendUrgent), alerts, WakeLease { AutoCloseable {} })
        session.onSos = sos::handle
        try {
            session.start(server.url("/bridge/v1").toString().replace("http://", "ws://"))
            withTimeout(5000) { session.state.first { it.connection == Connection.READY } }
            sentAt = System.nanoTime()
            socket.await().send(Protocol.encode(PayloadCodec.envelope(fixture("sos-request"), 0)))
            val receivedAll = withTimeoutOrNull(3000) { while (times.size < 3) delay(10); true } ?: false
            assertTrue(receivedAll, "Received SOS response types: ${times.keys}")
            assertTrue(times.getValue("ack") < 500)
            assertTrue(times.getValue("cache") < 500)
            assertTrue(times.getValue("fresh") < 30_000)
            withTimeout(2000) { sos.state.first { it.values.firstOrNull()?.delivery == LocationDelivery.SENT } }
            println("Synthetic localhost SOS receipt milliseconds: ack=${times["ack"]}, cache=${times["cache"]}, fresh=${times["fresh"]}")
            socket.await().send(Protocol.encode(PayloadCodec.envelope(fixture("sos-clear"), 0)))
            withTimeout(2000) { sos.state.first { it.isEmpty() } }
            assertEquals(1, alerts.first)
        } finally { sos.close(); session.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.close() }
    }
    @Test fun externalSosScenario(): Unit = runBlocking {
        val url = System.getenv("SOS_TEST_URL")?.takeIf { it.isNotBlank() }
        assumeTrue("Set SOS_TEST_URL for sos-x3 or sos-clear integration", url != null)
        val clear = System.getenv("SOS_TEST_CLEAR") == "1"
        val client = OkHttpClient()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = BridgeSession(scope, OkHttpTransportFactory(client), BridgeDiscovery { emptyList() }, MemorySettings(), "test")
        val alerts = TestAlerts()
        val received = AtomicInteger()
        val acked = AtomicInteger()
        val cache = LocationCache(SystemClock)
        val sos = SosCoordinator(scope, SyntheticSource(), cache, UrgentSender { payload, reply, done ->
            session.sendUrgent(payload, reply) { delivered -> if (payload is SosAck && delivered) acked.incrementAndGet(); done(delivered) }
        }, alerts, WakeLease { AutoCloseable {} })
        session.onSos = { if (it.payload is SosRequest) received.incrementAndGet(); sos.handle(it) }
        try {
            session.start(url)
            withTimeout(20_000) {
                while (acked.get() < if (clear) 1 else 3) delay(20)
                if (clear) sos.state.first { it.isEmpty() }
            }
            assertEquals(1, alerts.first)
            assertTrue(received.get() >= if (clear) 1 else 3)
            if (!clear) assertEquals(1, sos.state.value.size)
        } finally { sos.close(); session.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
