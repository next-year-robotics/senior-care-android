package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.domain.Connection
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OkHttpSessionTest {
    @Test fun actualWebSocketHandshakeCommandAndReconnect(): Unit = runBlocking {
        val server = MockWebServer()
        val seen = LinkedBlockingQueue<Message>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = PayloadCodec.decode(text)
                seen.add(m)
                fun reply(payload: Payload, replyTo: String? = null) = webSocket.send(Protocol.encode(PayloadCodec.envelope(payload, 0, replyTo)))
                when (m.payload) {
                    is Hello -> { reply(fixture("hello-ack"), m.envelope.id); reply(fixture("place-catalog-default6")); reply(fixture("robot-status-idle")) }
                    is Heartbeat -> reply(HeartbeatAck(m.payload.sessionId), m.envelope.id)
                    is TripStart -> reply(fixture("trip-start-ack"), m.envelope.id)
                    else -> Unit
                }
            }
        }
        repeat(2) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build()) }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        val settings = MemorySettings()
        val session = BridgeSession(scope, OkHttpTransportFactory(client), BridgeDiscovery { emptyList() }, settings, "test", backoff = ReconnectBackoff { 0.0 })
        try {
            session.start(server.url("/bridge/v1").toString().replace("http://", "ws://"))
            withTimeout(5000) { session.state.first { it.connection == Connection.READY } }
            assertEquals(6, session.state.value.catalog?.places?.size)
            assertIs<CommandResult.Accepted>(withTimeout(3000) { session.request(fixture("trip-start")) })
            withTimeout(4000) { while (seen.none { it.payload is Heartbeat }) delay(20) }
            sockets.poll(1, TimeUnit.SECONDS)!!.close(1000, "test")
            withTimeout(5000) { session.state.first { it.connection == Connection.RECONNECTING } }
            withTimeout(5000) { session.state.first { it.connection == Connection.READY } }
            assertEquals(2, seen.count { it.payload is Hello })
            assertEquals(1, seen.count { it.payload is TripStart })
            assertNotNull(settings.endpoint)
        } finally {
            session.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.close()
        }
    }
}
