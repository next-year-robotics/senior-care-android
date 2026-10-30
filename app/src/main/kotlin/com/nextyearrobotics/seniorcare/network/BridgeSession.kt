package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.domain.Clock
import com.nextyearrobotics.seniorcare.domain.Connection
import com.nextyearrobotics.seniorcare.domain.SystemClock
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One service-owned session, independent of Activity lifetime. Commands are never replayed. */
data class SessionState(
    val connection: Connection = Connection.STOPPED,
    val hello: HelloAck? = null,
    val catalog: PlaceCatalog? = null,
    val status: RobotStatus? = null,
    val statusAtMs: Long? = null,
    val nowMs: Long = 0,
    val candidates: List<RobotEndpoint> = emptyList(),
    val needsEndpoint: Boolean = false,
    val error: ErrorCode? = null,
) {
    val stale get() = connection != Connection.READY || statusAtMs?.let { nowMs - it >= 5000 } != false
}
sealed interface CommandResult {
    data class Accepted(val ack: Ack) : CommandResult
    data class Rejected(val error: ErrorPayload) : CommandResult
    data object Unknown : CommandResult
    data object NotConnected : CommandResult
}
data class ReceivedMessage(val message: Message, val duplicate: Boolean)

class BridgeSession(
    private val scope: CoroutineScope,
    private val transportFactory: TransportFactory,
    private val discovery: BridgeDiscovery,
    private val settings: ConnectionSettings,
    private val appVersion: String,
    private val clock: Clock = SystemClock,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val capabilities: List<HelloCapabilities> = emptyList(),
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    val messages = mutableMessages.asSharedFlow()
    private data class Request(val payload: Payload, val replyTo: String?, val result: CompletableDeferred<CommandResult>)
    private val requests = Channel<Request>(16)
    private val urgent = Channel<Request>(64)
    var onSos: ((Message) -> Unit)? = null
    fun sendUrgent(payload: Payload, replyTo: String? = null, delivered: (Boolean) -> Unit = {}) {
        require(payload is SosAck || payload is LocationUpdate)
        val result = CompletableDeferred<CommandResult>()
        if (state.value.hello == null || state.value.connection !in listOf(Connection.READY, Connection.SYNCING) ||
            urgent.trySend(Request(payload, replyTo, result)).isFailure) { delivered(false); return }
        scope.launch { delivered(result.await() is CommandResult.Accepted) }
    }
    private val history = MessageHistory(clock)
    private var job: Job? = null
    private val lifecycle = Mutex()

    suspend fun start(endpoint: String? = null) = lifecycle.withLock {
        val valid = endpoint?.let { Endpoint.validate(it) }
        stopInternal()
        backoff.reset()
        job = scope.launch { runLoop(valid) }
    }
    suspend fun stop() = lifecycle.withLock { stopInternal() }
    private suspend fun stopInternal() {
        job?.cancelAndJoin(); job = null
        drainRequests()
        mutableState.value = mutableState.value.copy(connection = Connection.STOPPED, needsEndpoint = false, candidates = emptyList())
    }
    suspend fun request(payload: Payload, replyTo: String? = null): CommandResult {
        require(payload is TripStart || payload is TripCancel || payload is PlaceUpsert || payload is PlaceDelete || payload is SosAck || payload is LocationUpdate)
        if (state.value.connection != Connection.READY) return CommandResult.NotConnected
        val result = CompletableDeferred<CommandResult>()
        if (requests.trySend(Request(payload, replyTo, result)).isFailure) return CommandResult.NotConnected
        return try { result.await() } finally { if (!result.isCompleted) result.cancel() }
    }
    private fun drainRequests() {
        for (channel in listOf(urgent, requests)) while (true) (channel.tryReceive().getOrNull() ?: break).result.complete(CommandResult.NotConnected)
    }

    private suspend fun runLoop(explicit: String?) {
        var endpoint = explicit ?: settings.lastEndpoint()
        var savedProbe = explicit == null && endpoint != null
        try {
            val instance = settings.instanceId()
            while (currentCoroutineContext().isActive) {
                if (endpoint == null) {
                    mutableState.value = state.value.copy(connection = Connection.DISCOVERING, candidates = emptyList(), needsEndpoint = false, error = null)
                    val found = try { withTimeoutOrNull(10_500) { discovery.discover() }.orEmpty() } catch (_: SecurityException) { emptyList() }
                    if (found.size != 1) {
                        mutableState.value = state.value.copy(connection = Connection.STOPPED, candidates = found, needsEndpoint = true)
                        return
                    }
                    endpoint = found.single().url
                }
                val result = connectOnce(endpoint, instance, if (savedProbe) 2000 else 3000)
                if (result == Outcome.FATAL) return
                if (savedProbe && result == Outcome.NOT_SYNCED) { endpoint = null; savedProbe = false; continue }
                savedProbe = false
                mutableState.value = state.value.copy(connection = Connection.RECONNECTING)
                drainRequests()
                delay(backoff.nextMs())
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            mutableState.value = state.value.copy(connection = Connection.STOPPED, needsEndpoint = true, error = ErrorCode.INTERNAL_ERROR)
        } finally { drainRequests() }
    }
    private enum class Outcome { NOT_SYNCED, DISCONNECTED, FATAL }
    private data class Pending(val request: Request, val sentAt: Long)
    private sealed interface Input {
        data class Socket(val event: SocketEvent?) : Input
        data class Command(val request: Request) : Input
    }

    private suspend fun connectOnce(endpoint: String, instance: String, handshakeMs: Long): Outcome {
        mutableState.value = SessionState(connection = Connection.CONNECTING)
        val transport = try { transportFactory.connect(endpoint) } catch (_: Exception) { return Outcome.NOT_SYNCED }
        val pending = mutableMapOf<String, Pending>()
        val heartbeatIds = mutableSetOf<String>()
        var helloId: String? = null
        var hello: HelloAck? = null
        val started = clock.monotonicMs()
        var negotiatedAt = started
        var readyAt: Long? = null
        var lastHeartbeatAck = started
        var nextHeartbeat = started
        var stableReset = false
        fun send(payload: Payload, replyTo: String? = null): Envelope {
            val envelope = PayloadCodec.envelope(payload, clock.epochMs(), replyTo)
            check(transport.send(Protocol.encode(envelope)))
            return envelope
        }
        try {
            while (currentCoroutineContext().isActive) {
                val input = withTimeoutOrNull(100) {
                    select<Input> {
                        urgent.onReceive { Input.Command(it) }
                        transport.events.onReceiveCatching { Input.Socket(it.getOrNull()) }
                        requests.onReceive { Input.Command(it) }
                    }
                }
                val now = clock.monotonicMs()
                mutableState.value = state.value.copy(nowMs = now)
                when (input) {
                    is Input.Command -> {
                        if ((state.value.connection != Connection.READY && !(hello != null && (input.request.payload is SosAck || input.request.payload is LocationUpdate))) || input.request.result.isCancelled) input.request.result.complete(CommandResult.NotConnected)
                        else {
                            try {
                                val e = send(input.request.payload, input.request.replyTo)
                                pending[e.id] = Pending(input.request, now)
                            } catch (_: ProtocolException) {
                                input.request.result.complete(CommandResult.Rejected(ErrorPayload(ErrorCode.INVALID_MESSAGE, "Invalid command", false)))
                            } catch (_: Exception) { input.request.result.complete(CommandResult.Unknown); return Outcome.DISCONNECTED }
                        }
                    }
                    is Input.Socket -> when (val event = input.event) {
                        SocketEvent.Open -> {
                            helloId = send(Hello(instance, appVersion, HelloPlatform.ANDROID, capabilities)).id
                            mutableState.value = state.value.copy(connection = Connection.SYNCING)
                        }
                        is SocketEvent.Text -> {
                            val message = try { PayloadCodec.decode(event.text) } catch (e: ProtocolException) {
                                if (e.code == "UNSUPPORTED_VERSION") {
                                    mutableState.value = state.value.copy(connection = Connection.STOPPED, error = ErrorCode.UNSUPPORTED_VERSION)
                                    return Outcome.FATAL
                                }
                                if (hello != null) send(ErrorPayload(ErrorCode.INVALID_MESSAGE, "Invalid message", false))
                                continue
                            }
                            val env = message.envelope
                            val payload = message.payload
                            if (payload is ErrorPayload && payload.code in listOf(ErrorCode.UNSUPPORTED_VERSION, ErrorCode.PERMISSION_DENIED)) {
                                mutableState.value = state.value.copy(connection = Connection.STOPPED, error = payload.code)
                                return Outcome.FATAL
                            }
                            if (hello == null) {
                                if (payload is HelloAck && env.replyTo == helloId && helloId != null) {
                                    hello = payload
                                    negotiatedAt = now; lastHeartbeatAck = now; nextHeartbeat = now + 2000
                                    mutableState.value = state.value.copy(hello = payload)
                                } else if (payload is ErrorPayload) return Outcome.NOT_SYNCED
                                continue
                            }
                            val duplicate = history.contains(env.id)
                            if (duplicate && payload !is SosRequest) {
                                history.response(env.id)?.let { transport.send(it) }
                                continue
                            }
                            if (!duplicate) history.remember(env.id)
                            when (payload) {
                                is HeartbeatAck -> if (payload.sessionId == hello.sessionId && heartbeatIds.remove(env.replyTo)) lastHeartbeatAck = now
                                is PlaceCatalog -> mutableState.value = state.value.copy(catalog = payload)
                                is RobotStatus -> {
                                    if (payload.robotId != hello.robotId) continue
                                    mutableState.value = state.value.copy(status = payload, statusAtMs = now)
                                }
                                is Ack -> pending.remove(env.replyTo)?.request?.result?.complete(CommandResult.Accepted(payload))
                                is ErrorPayload -> pending.remove(env.replyTo)?.request?.result?.complete(CommandResult.Rejected(payload))
                                is SosRequest, is SosClear -> onSos?.invoke(message)
                                else -> {
                                    val response = send(ErrorPayload(ErrorCode.UNSUPPORTED_TYPE, "Unsupported message type", false), env.id)
                                    history.recordResponse(env.id, Protocol.encode(response))
                                    continue
                                }
                            }
                            // SOS repeats intentionally reach the service so it can ACK/reply with best location each time.
                            mutableMessages.emit(ReceivedMessage(message, duplicate))
                            if (state.value.catalog != null && state.value.status != null && readyAt == null) {
                                readyAt = now
                                mutableState.value = state.value.copy(connection = Connection.READY)
                                settings.saveEndpoint(endpoint)
                            }
                        }
                        SocketEvent.Binary -> { if (hello != null) send(ErrorPayload(ErrorCode.INVALID_MESSAGE, "Text frames required", false)); return Outcome.DISCONNECTED }
                        SocketEvent.Closed, null -> return if (readyAt == null) Outcome.NOT_SYNCED else Outcome.DISCONNECTED
                    }
                    null -> Unit
                }
                pending.entries.removeAll { (_, p) ->
                    if (now - p.sentAt >= 1000) { p.request.result.complete(CommandResult.Unknown); true } else false
                }
                if (hello == null && now - started >= handshakeMs) return Outcome.NOT_SYNCED
                if (hello != null) {
                    if (now - lastHeartbeatAck >= 10_000) return Outcome.DISCONNECTED
                    if (readyAt == null && now - negotiatedAt >= 5000) return Outcome.NOT_SYNCED
                    if (now >= nextHeartbeat) {
                        heartbeatIds += send(Heartbeat(hello.sessionId)).id
                        nextHeartbeat = now + 2000
                    }
                    if (!stableReset && readyAt?.let { now - it >= 30_000 } == true) { backoff.reset(); stableReset = true }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { return if (readyAt == null) Outcome.NOT_SYNCED else Outcome.DISCONNECTED
        } finally {
            transport.close()
            pending.values.forEach { it.request.result.complete(CommandResult.Unknown) }
            drainRequests()
        }
        return Outcome.DISCONNECTED
    }
}
