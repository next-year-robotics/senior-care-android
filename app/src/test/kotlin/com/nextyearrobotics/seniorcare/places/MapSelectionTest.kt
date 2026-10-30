@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nextyearrobotics.seniorcare.places

import com.nextyearrobotics.seniorcare.protocol.PlaceSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

class MapSelectionTest {
    @Test fun pinGeocodingDebouncesAndStaleResponsesCannotReplaceSelection() = runTest {
        val requested = mutableListOf<MapPoint>()
        val provider = object : PlaceSearchProvider {
            override suspend fun search(query: String) = emptyList<SearchPlace>()
            override suspend fun address(point: MapPoint): String { requested += point; delay(500); return "합성 주소" }
        }
        val selection = MapSelection(provider, backgroundScope)
        selection.open(PlaceDraft(revision = "2026-09-27T00:00:00Z"))
        selection.moved(MapPoint(36.1, 128.1)); advanceTimeBy(399); runCurrent(); assertTrue(requested.isEmpty())
        selection.moved(MapPoint(36.2, 128.2)); advanceTimeBy(400); runCurrent(); assertEquals(1, requested.size)
        selection.choose(SearchPlace("합성 장소", "검색 주소", MapPoint(36.3, 128.3)))
        selection.moved(MapPoint(36.3000000001, 128.2999999999)) // SDK camera roundoff is not a new pin selection.
        advanceTimeBy(1000); runCurrent()
        assertEquals("검색 주소", selection.state.value.address)
        val draft = selection.apply(PlaceDraft(revision = "2026-09-27T00:00:00Z"))!!
        assertEquals(PlaceSource.MAP_SEARCH, draft.source)
        assertEquals("합성 장소", draft.label)
        assertEquals(36.3, draft.command().lat)
    }
    @Test fun failureAllowsManualAndApplyNeverSendsCommand() = runTest {
        val provider = object : PlaceSearchProvider {
            override suspend fun search(query: String): List<SearchPlace> = error("offline")
            override suspend fun address(point: MapPoint): String? = null
        }
        val selection = MapSelection(provider, backgroundScope)
        val draft = PlaceDraft(revision = "2026-09-27T00:00:00Z", label = "테스트")
        selection.open(draft); selection.query("장소"); selection.search(); runCurrent()
        assertTrue(selection.state.value.searchFailed)
        selection.manual(); selection.coordinates("0", "0"); assertNull(selection.apply(draft))
        selection.coordinates("NaN", "128"); assertNull(selection.apply(draft))
        selection.coordinates("36.1", "128.1"); selection.radius(500f)
        assertEquals(PlaceSource.MANUAL_COORDS, selection.apply(draft)!!.source)
        assertEquals(500.0, selection.apply(draft)!!.command().radiusM)
    }
}
