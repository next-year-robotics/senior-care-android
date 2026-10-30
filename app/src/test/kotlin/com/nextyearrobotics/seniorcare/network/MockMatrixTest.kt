package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.domain.*
import com.nextyearrobotics.seniorcare.location.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** All fixtures are synthetic. This is a JVM socket regression, not Android UI acceptance. */
class MockMatrixTest {
    @Test fun twelveScenarios(): Unit = runBlocking {
        val base = System.getenv("MOCK_MATRIX_BASE_PORT")?.toIntOrNull()
        assumeTrue("Run tools/mock-bridge/check.py for the twelve-scenario matrix", base != null)
        val scenarios = listOf("default6", "empty", "conflict", "limit", "in-use", "slow-ack", "error-ack", "flaky", "sos", "sos-x3", "sos-clear", "garbage")
        for ((index, scenario) in scenarios.withIndex()) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val client = OkHttpClient()
            val session = BridgeSession(scope, OkHttpTransportFactory(client), BridgeDiscovery { emptyList() }, MemorySettings(), "matrix")
            var requests = 0; var acked = 0; var alerts = 0
            val source = object : LocationSource {
                override fun failure(): LocationUpdateFailureReason? = null
                override fun precise() = true
                override fun start(onFix: (LocationFix) -> Unit) = Unit
                override fun stop() = Unit
                override suspend fun fresh() = FixResult(failure = LocationUpdateFailureReason.UNAVAILABLE)
            }
            val sos = SosCoordinator(scope, source, LocationCache(SystemClock), UrgentSender { payload, reply, done ->
                session.sendUrgent(payload, reply) { delivered -> if (payload is SosAck && delivered) acked++; done(delivered) }
            }, object : SosAlerts { override fun show(state: SosState, first: Boolean) { if (first) alerts++ }; override fun clear(id: String) = Unit }, WakeLease { AutoCloseable {} })
            session.onSos = { if (it.payload is SosRequest) requests++; sos.handle(it) }
            try {
                session.start("ws://127.0.0.1:${base!! + index}/bridge/v1")
                withTimeout(5000) { session.state.first { it.connection == Connection.READY } }
                val catalog = session.state.value.catalog!!
                val start = TripStart("place_home", "place_market", catalog.revision)
                when (scenario) {
                    "default6" -> {
                        assertEquals(6, catalog.places.size)
                        assertIs<CommandResult.Accepted>(session.request(start))
                        withTimeout(3000) { session.state.first { it.status?.missionState == RobotStatusMissionState.ACTIVE } }
                        assertIs<CommandResult.Accepted>(session.request(TripCancel(session.state.value.status!!.tripId!!, "user_request")))
                        withTimeout(3000) { session.state.first { it.status?.missionState == RobotStatusMissionState.CANCELLED } }
                    }
                    "empty" -> {
                        assertTrue(catalog.places.isEmpty())
                        val create = (fixture("place-upsert") as PlaceUpsert).copy(placeId = null, catalogRevision = catalog.revision)
                        assertIs<CommandResult.Accepted>(session.request(create))
                        withTimeout(3000) { session.state.first { it.catalog?.places?.size == 1 } }
                        assertNotNull(session.state.value.catalog?.defaultOriginPlaceId)
                    }
                    "conflict" -> assertEquals(ErrorCode.CATALOG_CONFLICT, assertIs<CommandResult.Rejected>(session.request(start)).error.code)
                    "limit" -> assertEquals(ErrorCode.PLACE_LIMIT, assertIs<CommandResult.Rejected>(session.request((fixture("place-upsert") as PlaceUpsert).copy(placeId = null, catalogRevision = catalog.revision))).error.code)
                    "in-use" -> assertEquals(ErrorCode.PLACE_IN_USE, assertIs<CommandResult.Rejected>(session.request(PlaceDelete("place_market", catalog.revision))).error.code)
                    "slow-ack" -> assertEquals(CommandResult.Unknown, session.request(start))
                    "error-ack" -> assertIs<CommandResult.Rejected>(session.request(start))
                    "flaky" -> {
                        val first = session.state.value.hello!!.sessionId
                        withTimeout(26_000) { session.state.first { it.connection == Connection.READY && it.hello?.sessionId != first } }
                    }
                    "sos", "sos-x3", "sos-clear" -> {
                        withTimeout(18_000) {
                            while (acked < if (scenario == "sos-x3") 3 else 1) delay(20)
                            if (scenario == "sos-clear") sos.state.first { it.isEmpty() }
                        }
                        assertEquals(1, alerts)
                        assertTrue(requests >= if (scenario == "sos-x3") 3 else 1)
                    }
                    "garbage" -> {
                        delay(13_000)
                        withTimeout(5000) { session.state.first { it.connection == Connection.READY } }
                        assertNotNull(session.state.value.catalog)
                    }
                }
                println("MATRIX $scenario PASS")
            } finally { sos.close(); session.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
