package com.nextyearrobotics.seniorcare.places

import com.nextyearrobotics.seniorcare.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MapPoint(val lat: Double, val lon: Double) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
        (kotlin.math.abs(lat) >= 0.000001 || kotlin.math.abs(lon) >= 0.000001)
}
data class SearchPlace(val name: String, val address: String, val point: MapPoint)
interface PlaceSearchProvider {
    suspend fun search(query: String): List<SearchPlace>
    suspend fun address(point: MapPoint): String?
}
/** Dedicated HTTPS client; no request logging, no persistent cache, no key-bearing redirects. */
class KakaoPlaceSearchProvider(private val key: String = BuildConfig.KAKAO_REST_API_KEY,
    private val client: Call.Factory = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()) : PlaceSearchProvider {
    private suspend fun documents(path: String, parameters: Map<String, String>): JsonArray {
        check(key.isNotBlank() && key != "dummy") { "Search unavailable" }
        val url = ("https://dapi.kakao.com/v2/local/" + path).toHttpUrl().newBuilder().apply {
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
        val body = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Search unavailable")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        if (!response.isSuccessful) continuation.resumeWithException(IOException("Search unavailable"))
                        else try { continuation.resume(response.body.string()) } catch (_: IOException) { continuation.resumeWithException(IOException("Search unavailable")) }
                    }
                }
            })
        }
        return Json.parseToJsonElement(body).jsonObject.getValue("documents").jsonArray
    }
    override suspend fun search(query: String): List<SearchPlace> {
        if (query.isBlank()) return emptyList()
        return documents("search/keyword.json", mapOf("query" to query.trim(), "size" to "15")).mapNotNull { item ->
            runCatching {
                val o = item.jsonObject
                val point = MapPoint(o.getValue("y").jsonPrimitive.content.toDouble(), o.getValue("x").jsonPrimitive.content.toDouble())
                if (!point.valid()) null else SearchPlace(o.getValue("place_name").jsonPrimitive.content,
                    o["road_address_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: o["address_name"]?.jsonPrimitive?.content.orEmpty(), point)
            }.getOrNull()
        }
    }
    override suspend fun address(point: MapPoint): String? {
        require(point.valid())
        val first = documents("geo/coord2address.json", mapOf("x" to point.lon.toString(), "y" to point.lat.toString())).firstOrNull()?.jsonObject ?: return null
        return (first["road_address"] as? JsonObject)?.get("address_name")?.jsonPrimitive?.content
            ?: (first["address"] as? JsonObject)?.get("address_name")?.jsonPrimitive?.content
    }
}
