package com.nextyearrobotics.seniorcare.network

import com.nextyearrobotics.seniorcare.data.TripRepository
import com.nextyearrobotics.seniorcare.data.TripScreen
import com.nextyearrobotics.seniorcare.domain.Connection
import com.nextyearrobotics.seniorcare.domain.Mission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Opt in with BRIDGE_TEST_URL against tools/mock-bridge/run.sh --scenario default6. No phone needed. */
class LiveMockBridgeTest {
    @Test fun defaultSixPlacesStartUndoAndServerConfirmedStop(): Unit = runBlocking {
        val url = System.getenv("BRIDGE_TEST_URL")?.takeIf { it.isNotBlank() }
        assumeTrue("Set BRIDGE_TEST_URL to run the external mock integration", url != null)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val client = OkHttpClient()
        val session = BridgeSession(scope, OkHttpTransportFactory(client), BridgeDiscovery { emptyList() }, MemorySettings(), "test")
        val repository = TripRepository(session, scope)
        try {
            session.start(url)
            withTimeout(5000) { repository.state.first { it.app.connection == Connection.READY && it.app.canEdit } }
            assertEquals(6, repository.state.value.app.catalog.size)
            assertEquals(1, repository.state.value.app.catalog.count { !it.enabled })
            val destination = repository.state.value.app.catalog.first { it.enabled && it.id != repository.state.value.app.originId }
            repository.select(destination.id)
            assertEquals(TripScreen.CONFIRM, repository.state.value.screen)
            repository.startTrip()
            repository.startTrip() // double tap must be inert
            withTimeout(5000) { repository.state.first { it.app.mission == Mission.ACTIVE && !it.busy } }
            assertTrue(repository.state.value.canUndo)
            repository.cancelTrip()
            repository.cancelTrip()
            withTimeout(5000) { repository.state.first { it.app.mission == Mission.CANCELLED && !it.busy && it.screen == TripScreen.HOME } }
            assertFalse(repository.state.value.canUndo)
            assertFalse(repository.state.value.app.resultUnknown)
        } finally {
            session.stop(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
