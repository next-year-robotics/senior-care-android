@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nextyearrobotics.seniorcare.domain

import com.nextyearrobotics.seniorcare.location.*
import com.nextyearrobotics.seniorcare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.util.UUID
import kotlin.test.*

class SosCoordinatorTest {
    private class Source : LocationSource {
        var failure: LocationUpdateFailureReason? = null
        var calls = 0
        var result = CompletableDeferred<FixResult>()
        override fun failure() = failure
        override fun precise() = false
        override fun start(onFix: (LocationFix) -> Unit) = Unit
        override fun stop() = Unit
        override suspend fun fresh(): FixResult { calls++; return result.await() }
    }
    private class Rig(val test: TestScope) {
        val clock = object : Clock { override fun epochMs() = 123L; override fun monotonicMs() = test.testScheduler.currentTime }
        val source = Source()
        val cache = LocationCache(clock)
        val sent = mutableListOf<Pair<Payload, String?>>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var firstAlerts = 0
        var releases = 0
        var timeout = 0L
        val cleared = mutableListOf<String>()
        val coordinator = SosCoordinator(test.backgroundScope, source, cache, UrgentSender { payload, replyTo, done -> sent += payload to replyTo; callbacks += done },
            object : SosAlerts {
                override fun show(state: SosState, first: Boolean) { if (first) firstAlerts++ }
                override fun clear(id: String) { cleared += id }
            }, WakeLease { timeout = it; AutoCloseable { releases++ } })
        val request = SosRequest(UUID.randomUUID().toString(), SosRequestReason.TRACKING_LOST, SosRequestSeverity.CRITICAL, null)
        fun request(): Message = PayloadCodec.decode(Protocol.encode(PayloadCodec.envelope(request, 0))).also(coordinator::handle)
        fun clear() { coordinator.handle(PayloadCodec.decode(Protocol.encode(PayloadCodec.envelope(SosClear(request.sosId, SosClearResolution.HANDLED), 0)))) }
    }
    @Test fun ackAndCacheImmediateWithoutWaitingForFixOrServerAckAndDuplicateIsQuiet() = runTest {
        val r = Rig(this)
        r.cache.update(LocationFix(36.1, 128.1, 4.0, 123, 0)) // synthetic fixture area, never a device fix
        val first = r.request()
        assertEquals(2, r.sent.size)
        assertEquals(first.envelope.id, r.sent[0].second)
        assertIs<SosAck>(r.sent[0].first)
        assertEquals(LocationUpdateSource.CACHE, (r.sent[1].first as LocationUpdate).source)
        runCurrent()
        assertEquals(1, r.source.calls)
        r.coordinator.dismiss(r.request.sosId)
        r.request(); r.request(); runCurrent()
        assertEquals(3, r.sent.count { it.first is SosAck })
        assertEquals(1, r.firstAlerts)
        assertTrue(r.coordinator.state.value.getValue(r.request.sosId).dismissed)
        assertEquals(1, r.source.calls)
        assertEquals(35_000, r.timeout)
        r.source.result.complete(FixResult(LocationFix(36.1, 128.1, 5.0, 124, 0))); runCurrent()
        assertEquals(LocationUpdateSource.FRESH, (r.sent.last().first as LocationUpdate).source)
        assertEquals(1, r.releases)
        assertEquals(LocationDelivery.CHECKING, r.coordinator.state.value.getValue(r.request.sosId).delivery)
        r.callbacks.last()(true)
        assertEquals(LocationDelivery.SENT, r.coordinator.state.value.getValue(r.request.sosId).delivery)
        r.clear(); assertTrue(r.coordinator.state.value.isEmpty())
    }
    @Test fun noCacheThenTimeoutAtThirtySecondsReleasesWakeLock() = runTest {
        val r = Rig(this); r.request(); runCurrent()
        assertEquals(LocationUpdateFailureReason.UNAVAILABLE, (r.sent[1].first as LocationUpdate).failureReason)
        advanceTimeBy(29_999); runCurrent(); assertEquals(2, r.sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(LocationUpdateFailureReason.TIMEOUT, (r.sent.last().first as LocationUpdate).failureReason)
        assertEquals(1, r.releases)
    }
    @Test fun clearCancelsFreshAndLateReceiptCannotResurrectSos() = runTest {
        val r = Rig(this); r.request(); runCurrent(); r.clear(); runCurrent()
        assertEquals(1, r.releases)
        r.source.result.complete(FixResult(LocationFix(36.1, 128.1, 5.0, 124, 0)))
        r.callbacks.forEach { it(true) }; advanceTimeBy(30_000); runCurrent()
        assertTrue(r.coordinator.state.value.isEmpty())
        assertEquals(2, r.sent.size)
    }
    @Test fun permissionAndProviderFailureSuppressCachedCoordinates() = runTest {
        for (failure in listOf(LocationUpdateFailureReason.PERMISSION_DENIED, LocationUpdateFailureReason.PROVIDER_DISABLED)) {
            val r = Rig(this); r.cache.update(LocationFix(36.1, 128.1, 4.0, 123, 0)); r.source.failure = failure
            r.source.result.complete(FixResult(failure = failure)); r.request(); runCurrent()
            r.sent.mapNotNull { it.first as? LocationUpdate }.forEach { assertFalse(it.hasFix); assertNull(it.lat); assertEquals(failure, it.failureReason) }
            r.coordinator.close()
        }
    }
    @Test fun ageUsesMonotonicAndLowConfidenceIsRetainedWhenFreshFails() = runTest {
        val r = Rig(this); r.cache.update(LocationFix(36.1, 128.1, 60.0, 123, 0))
        advanceTimeBy(300_001); r.request(); runCurrent()
        assertEquals(300_001, (r.sent[1].first as LocationUpdate).ageMs)
        r.callbacks[1](true)
        assertEquals(LocationDelivery.LOW_CONFIDENCE, r.coordinator.state.value.getValue(r.request.sosId).delivery)
        r.source.result.complete(FixResult(failure = LocationUpdateFailureReason.UNAVAILABLE)); runCurrent(); r.callbacks.last()(true)
        assertEquals(LocationDelivery.LOW_CONFIDENCE, r.coordinator.state.value.getValue(r.request.sosId).delivery)
    }
}
