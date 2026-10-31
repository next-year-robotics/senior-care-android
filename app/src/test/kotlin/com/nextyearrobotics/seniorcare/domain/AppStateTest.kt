package com.nextyearrobotics.seniorcare.domain

import kotlin.test.*
import org.junit.Test

class AppStateTest {
    private val places = listOf(Destination("home", "home", "집", true, 20), Destination("market", "market", "시장", true, 10))
    private fun ready(): AppState = listOf(AppEvent.Start, AppEvent.EndpointFound, AppEvent.SocketOpened,
        AppEvent.Catalog(places, "home"), AppEvent.Status(Mission.IDLE, null, 100)).fold(AppState(), ::reduce)

    @Test fun syncNeedsBothCatalogAndStatus() {
        var s = reduce(AppState(connection = Connection.SYNCING), AppEvent.Status(Mission.IDLE, null, 0))
        assertEquals(Connection.SYNCING, s.connection)
        s = reduce(s, AppEvent.Catalog(places, "home"))
        assertEquals(Connection.READY, s.connection)
        assertEquals("market", s.catalog.first().id)
    }
    @Test fun statusCarriesBatteryAndTracking() {
        var s = reduce(ready(), AppEvent.Status(Mission.ACTIVE, "t", 200, 55, Tracking.FOLLOWING))
        assertEquals(55, s.batteryPercent)
        assertEquals(Tracking.FOLLOWING, s.tracking)
        s = reduce(s, AppEvent.Status(Mission.IDLE, null, 300))
        assertNull(s.batteryPercent)
        assertEquals(Tracking.UNKNOWN, s.tracking)
    }
    @Test fun duplicateStartAndSameDestinationAreBlocked() {
        var s = reduce(ready(), AppEvent.Select("home"))
        assertFalse(s.canStart)
        s = reduce(s, AppEvent.Select("market"))
        assertTrue(s.canStart)
        s = reduce(s, AppEvent.RequestStart)
        assertFalse(s.canStart)
        assertEquals(s, reduce(s, AppEvent.RequestStart))
        s = reduce(s, AppEvent.Disconnected)
        assertTrue(s.resultUnknown)
        assertEquals(TripPhase.START_PENDING, s.phase)
    }
    @Test fun disconnectAndCancelNeverClaimRobotStopped() {
        var s = reduce(ready(), AppEvent.Status(Mission.ACTIVE, "trip", 100))
        s = reduce(s, AppEvent.RequestCancel)
        assertEquals(TripPhase.CANCELLING, s.phase)
        assertEquals(Mission.ACTIVE, s.mission)
        s = reduce(s, AppEvent.Disconnected)
        assertEquals(TripPhase.CANCELLING, s.phase)
        assertFalse(s.canEdit)
        s = reduce(s, AppEvent.Status(Mission.CANCELLED, "trip", 200))
        assertEquals(TripPhase.READY, s.phase)
    }
    @Test fun staleAndCatalogInvalidationBlockCommands() {
        var s = reduce(ready(), AppEvent.Select("market"))
        assertFalse(reduce(s, AppEvent.Tick(5_100)).canStart)
        s = reduce(s, AppEvent.Catalog(places.map { it.copy(enabled = it.id != "market") }, "home"))
        assertNull(s.destinationId)
        assertFalse(s.canStart)
    }
    @Test fun sosIsAnOverlayAndDuplicatesDoNotReopenDismissedEvent() {
        var s = reduce(ready(), AppEvent.Status(Mission.ACTIVE, "trip", 100))
        s = reduce(s, AppEvent.SosRequested("sos", "tracking_lost"))
        s = reduce(s, AppEvent.SosDismissed("sos"))
        s = reduce(s, AppEvent.SosRequested("sos", "tracking_lost"))
        assertNull(s.visibleSos)
        assertEquals(1, s.sos.size)
        s = reduce(s, AppEvent.Status(Mission.CANCELLED, "trip", 200))
        assertEquals(1, reduce(s, AppEvent.SosCleared("different")).sos.size)
        s = reduce(s, AppEvent.SosCleared("sos"))
        assertTrue(s.sos.isEmpty())
        assertEquals(TripPhase.READY, s.phase)
    }
}
