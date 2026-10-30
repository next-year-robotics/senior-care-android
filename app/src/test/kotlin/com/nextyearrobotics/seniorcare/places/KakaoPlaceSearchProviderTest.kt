package com.nextyearrobotics.seniorcare.places

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.*

class KakaoPlaceSearchProviderTest {
    @Test fun usesHttpsEncodedQueryAndLongitudeXLatitudeY(): Unit = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val json = if (chain.request().url.encodedPath.contains("search"))
                """{"documents":[{"place_name":"합성 병원","road_address_name":"테스트 도로","address_name":"테스트 지번","x":"128.5678","y":"36.1234"}]}"""
            else """{"documents":[{"road_address":null,"address":{"address_name":"합성 지번"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val provider = KakaoPlaceSearchProvider("synthetic-key", client)
            val place = provider.search("병원 & 약국").single()
            assertEquals(MapPoint(36.1234, 128.5678), place.point)
            assertEquals("병원 & 약국", requests.single().url.queryParameter("query"))
            assertEquals("https", requests.single().url.scheme)
            assertEquals("합성 지번", provider.address(place.point))
            assertEquals("128.5678", requests.last().url.queryParameter("x"))
            assertEquals("36.1234", requests.last().url.queryParameter("y"))
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
