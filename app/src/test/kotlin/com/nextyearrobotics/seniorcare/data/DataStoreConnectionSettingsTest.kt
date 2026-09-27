package com.nextyearrobotics.seniorcare.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DataStoreConnectionSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun installIdAndValidatedEndpointSurviveStoreRecreation() = runBlocking {
        val file = temp.root.resolve("connection.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = DataStoreConnectionSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
        val id = first.instanceId()
        assertEquals(id, first.instanceId())
        first.saveEndpoint("ws://127.0.0.1:8765/bridge/v1")
        assertFailsWith<IllegalArgumentException> { first.saveEndpoint("http://invalid") }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = DataStoreConnectionSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(id, second.instanceId())
            assertEquals("ws://127.0.0.1:8765/bridge/v1", second.lastEndpoint())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
