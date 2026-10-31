package com.nextyearrobotics.seniorcare.protocol

import com.nextyearrobotics.seniorcare.domain.Clock
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test

class PayloadCodecTest {
    private val dir = File("../contracts/phone-bridge/v1/fixtures")
    private fun fixture(name: String) = dir.resolve("$name.json").readText()
    private fun mutate(name: String, key: String, value: JsonElement, payload: Boolean = true): String {
        val root = Protocol.json.parseToJsonElement(fixture(name)).jsonObject
        return if (payload) JsonObject(root + ("payload" to JsonObject(root.getValue("payload").jsonObject + (key to value)))).toString()
        else JsonObject(root + (key to value)).toString()
    }
    private fun rejects(text: String) = assertEquals("INVALID_MESSAGE", assertFailsWith<ProtocolException> { PayloadCodec.decode(text) }.code)

    @Test fun allFixturesHaveTypedRoundTripsAndCanBeSent() {
        val types = mutableSetOf<String>()
        dir.listFiles()!!.filter { it.extension == "json" }.forEach { file ->
            val message = PayloadCodec.decode(file.readText())
            assertFalse(message.payload is UnknownPayload, file.name)
            assertEquals(message.payload, PayloadCodec.decode(PayloadCodec.encode(message)).payload, file.name)
            val e = message.envelope
            assertEquals(message.payload, PayloadCodec.decode(Protocol.encode(PayloadCodec.envelope(message.payload, e.sentAtMs, e.replyTo, e.id))).payload)
            types += e.type
        }
        assertEquals(18, types.size)
    }
    @Test fun everyRequiredFieldIncludingNullableFieldsMustBePresent() {
        val schema = Protocol.json.parseToJsonElement(File("../contracts/phone-bridge/v1/schema/messages.schema.json").readText()).jsonObject.getValue("\$defs").jsonObject
        dir.listFiles()!!.filter { it.extension == "json" }.forEach { file ->
            val root = Protocol.json.parseToJsonElement(file.readText()).jsonObject
            root.keys.forEach { key -> rejects(JsonObject(root - key).toString()) }
            val type = root.getValue("type").jsonPrimitive.content
            val required = schema.getValue(type).jsonObject.getValue("properties").jsonObject.getValue("payload").jsonObject.getValue("required").jsonArray
            required.forEach { key ->
                val body = JsonObject(root.getValue("payload").jsonObject - key.jsonPrimitive.content)
                rejects(JsonObject(root + ("payload" to body)).toString())
            }
        }
    }
    @Test fun typesNullNumbersUuidAndFramesAreStrict() {
        rejects(mutate("hello", "app_version", JsonPrimitive(5)))
        rejects(mutate("hello", "sent_at_ms", JsonPrimitive("12"), false))
        rejects(mutate("hello", "sent_at_ms", JsonPrimitive(-1), false))
        rejects(mutate("hello", "id", JsonPrimitive("NOT-A-UUID"), false))
        rejects(mutate("hello", "reply_to", JsonPrimitive("broken"), false))
        rejects(mutate("hello", "capabilities", JsonNull))
        rejects(mutate("place-upsert", "label", JsonPrimitive("가".repeat(21))))
        rejects(mutate("place-upsert", "label", JsonPrimitive("")))
        listOf("lat" to 91, "lon" to -181, "radius_m" to 4, "radius_m" to 501).forEach { (key, n) -> rejects(mutate("place-upsert", key, JsonPrimitive(n))) }
        rejects(mutate("robot-status", "battery_percent", JsonPrimitive(101)))
        rejects(mutate("place-upsert", "lat", JsonPrimitive("NaN")))
        rejects(mutate("hello", "app_version", JsonPrimitive("가".repeat(6000))))
        assertEquals("UNSUPPORTED_VERSION", assertFailsWith<ProtocolException> { PayloadCodec.decode(mutate("hello", "version", JsonPrimitive(2), false)) }.code)
    }
    @Test fun idleTrackingStateHasATypedRoundTrip() {
        val message = PayloadCodec.decode(mutate("robot-status", "tracking_state", JsonPrimitive("idle")))
        assertEquals(RobotStatusTrackingState.IDLE, (message.payload as RobotStatus).trackingState)
        assertEquals(message.payload, PayloadCodec.decode(PayloadCodec.encode(message)).payload)
        PayloadCodec.envelope(message.payload, 0)
    }
    @Test fun forwardCompatibleEnumsAndTypesDoNotCrash() {
        val status = PayloadCodec.decode(mutate("robot-status", "mission_state", JsonPrimitive("future"))).payload as RobotStatus
        assertEquals(RobotStatusMissionState.UNKNOWN, status.missionState)
        assertIs<UnknownPayload>(PayloadCodec.decode(mutate("hello", "type", JsonPrimitive("future.type"), false)).payload)
        assertEquals(ErrorCode.UNKNOWN, (PayloadCodec.decode(mutate("error", "code", JsonPrimitive("FUTURE_ERROR"))).payload as ErrorPayload).code)
        assertFailsWith<ProtocolException> { PayloadCodec.envelope(status.copy(bridgeState = RobotStatusBridgeState.UNKNOWN), 0) }
    }
    @Test fun catalogConstraintsAndLocationConditionalFields() {
        val catalogFile = dir.resolve("place-catalog-default6.json")
        val msg = PayloadCodec.decode(catalogFile.readText())
        val catalog = msg.payload as PlaceCatalog
        if (catalog.places.isNotEmpty()) {
            assertFailsWith<ProtocolException> { PayloadCodec.envelope(catalog.copy(maxPlaces = 0), 0) }
            assertFailsWith<ProtocolException> { PayloadCodec.envelope(catalog.copy(places = catalog.places + catalog.places.first()), 0) }
            assertFailsWith<ProtocolException> { PayloadCodec.envelope(catalog.copy(defaultOriginPlaceId = null), 0) }
        }
        dir.listFiles()!!.filter { it.name.startsWith("location-") }.forEach { file ->
            val m = PayloadCodec.decode(file.readText())
            val location = m.payload as LocationUpdate
            assertFailsWith<ProtocolException> { PayloadCodec.envelope(location.copy(hasFix = !location.hasFix), 0) }
            assertFailsWith<ProtocolException> { PayloadCodec.envelope(location.copy(reason = LocationUpdateReason.SOS, sosId = null), 0) }
        }
        val upsert = PayloadCodec.decode(fixture("place-upsert")).payload as PlaceUpsert
        assertFailsWith<ProtocolException> { PayloadCodec.envelope(upsert.copy(lat = 0.0, lon = 0.0), 0) }
        PayloadCodec.envelope(upsert.copy(lat = -90.0, lon = 180.0, radiusM = 500.0, label = "가".repeat(20)), 0)
    }
    @Test fun duplicateIdReplaysCachedResultUntilTenMinutes() {
        var now = 0L
        val clock = object : Clock { override fun monotonicMs() = now; override fun epochMs() = 0L }
        val history = MessageHistory(clock, 1)
        history.remember("id", "ack")
        now = 599_999
        assertTrue(history.contains("id"))
        history.remember("id", "other")
        assertEquals("ack", history.response("id"))
        assertFailsWith<ProtocolException> { history.remember("second") }
        now = 600_000
        assertFalse(history.contains("id"))
        history.remember("second")
    }
}
