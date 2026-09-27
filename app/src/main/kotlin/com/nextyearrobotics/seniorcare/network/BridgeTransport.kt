package com.nextyearrobotics.seniorcare.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

sealed interface SocketEvent {
    data object Open : SocketEvent
    data class Text(val text: String) : SocketEvent
    data object Closed : SocketEvent
    data object Binary : SocketEvent
}
interface BridgeTransport {
    val events: ReceiveChannel<SocketEvent>
    fun send(text: String): Boolean
    fun close()
}
fun interface TransportFactory { fun connect(endpoint: String): BridgeTransport }

class OkHttpTransportFactory(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()) : TransportFactory {
    override fun connect(endpoint: String): BridgeTransport = OkHttpTransport(client, endpoint)
}
private class OkHttpTransport(client: OkHttpClient, endpoint: String) : BridgeTransport {
    private val channel = Channel<SocketEvent>(64)
    override val events: ReceiveChannel<SocketEvent> = channel
    private val socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
        private fun offer(socket: WebSocket, event: SocketEvent) {
            if (channel.trySend(event).isFailure) { socket.cancel(); channel.close() }
        }
        override fun onOpen(webSocket: WebSocket, response: Response) = offer(webSocket, SocketEvent.Open)
        override fun onMessage(webSocket: WebSocket, text: String) = offer(webSocket, SocketEvent.Text(text))
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = offer(webSocket, SocketEvent.Binary)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); offer(webSocket, SocketEvent.Closed) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { channel.close() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { channel.close() }
    })
    override fun send(text: String) = socket.send(text)
    override fun close() { socket.cancel(); channel.close() }
}
