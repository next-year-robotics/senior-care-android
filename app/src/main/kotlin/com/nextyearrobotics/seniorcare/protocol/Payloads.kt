package com.nextyearrobotics.seniorcare.protocol

// DTO fields follow contracts/phone-bridge/v1/schema/messages.schema.json.
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

sealed interface Payload

abstract class WireEnumSerializer<T : Enum<T>>(name: String, private val values: Array<T>) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): T {
        val value = decoder.decodeString()
        return values.firstOrNull { (it as WireEnum).wire == value } ?: values.last()
    }
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString((value as WireEnum).wire)
}
interface WireEnum { val wire: String }

@Serializable(with = HelloPlatformSerializer::class)
enum class HelloPlatform(override val wire: String) : WireEnum { ANDROID("android"), UNKNOWN("unknown") }
object HelloPlatformSerializer : WireEnumSerializer<HelloPlatform>("HelloPlatform", HelloPlatform.entries.toTypedArray())

@Serializable(with = HelloCapabilitiesSerializer::class)
enum class HelloCapabilities(override val wire: String) : WireEnum { LOCATION_CACHE("location.cache"), LOCATION_FRESH("location.fresh"), PLACE_EDIT("place.edit"), UNKNOWN("unknown") }
object HelloCapabilitiesSerializer : WireEnumSerializer<HelloCapabilities>("HelloCapabilities", HelloCapabilities.entries.toTypedArray())

@Serializable(with = AckStatusSerializer::class)
enum class AckStatus(override val wire: String) : WireEnum { ACCEPTED("accepted"), UNKNOWN("unknown") }
object AckStatusSerializer : WireEnumSerializer<AckStatus>("AckStatus", AckStatus.entries.toTypedArray())

@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode(override val wire: String) : WireEnum { INVALID_MESSAGE("INVALID_MESSAGE"), UNSUPPORTED_TYPE("UNSUPPORTED_TYPE"), UNSUPPORTED_VERSION("UNSUPPORTED_VERSION"), NOT_READY("NOT_READY"), UNKNOWN_PLACE("UNKNOWN_PLACE"), INVALID_STATE("INVALID_STATE"), CATALOG_CONFLICT("CATALOG_CONFLICT"), PLACE_LIMIT("PLACE_LIMIT"), PLACE_IN_USE("PLACE_IN_USE"), PERMISSION_DENIED("PERMISSION_DENIED"), INTERNAL_ERROR("INTERNAL_ERROR"), UNKNOWN("unknown") }
object ErrorCodeSerializer : WireEnumSerializer<ErrorCode>("ErrorCode", ErrorCode.entries.toTypedArray())

@Serializable(with = PlaceKindSerializer::class)
enum class PlaceKind(override val wire: String) : WireEnum { HOME("home"), MARKET("market"), MART("mart"), SENIOR_CENTER("senior_center"), CHURCH("church"), TEMPLE("temple"), HOSPITAL("hospital"), PHARMACY("pharmacy"), OTHER("other"), UNKNOWN("unknown") }
object PlaceKindSerializer : WireEnumSerializer<PlaceKind>("PlaceKind", PlaceKind.entries.toTypedArray())

@Serializable(with = PlaceSourceSerializer::class)
enum class PlaceSource(override val wire: String) : WireEnum { MAP_SEARCH("map_search"), MAP_PIN("map_pin"), MANUAL_COORDS("manual_coords"), CURRENT_LOCATION("current_location"), UNKNOWN("unknown") }
object PlaceSourceSerializer : WireEnumSerializer<PlaceSource>("PlaceSource", PlaceSource.entries.toTypedArray())

@Serializable(with = LocationUpdateReasonSerializer::class)
enum class LocationUpdateReason(override val wire: String) : WireEnum { PERIODIC("periodic"), SOS("sos"), UNKNOWN("unknown") }
object LocationUpdateReasonSerializer : WireEnumSerializer<LocationUpdateReason>("LocationUpdateReason", LocationUpdateReason.entries.toTypedArray())

@Serializable(with = LocationUpdateSourceSerializer::class)
enum class LocationUpdateSource(override val wire: String) : WireEnum { CACHE("cache"), FRESH("fresh"), NONE("none"), UNKNOWN("unknown") }
object LocationUpdateSourceSerializer : WireEnumSerializer<LocationUpdateSource>("LocationUpdateSource", LocationUpdateSource.entries.toTypedArray())

@Serializable(with = LocationUpdateFailureReasonSerializer::class)
enum class LocationUpdateFailureReason(override val wire: String) : WireEnum { PERMISSION_DENIED("permission_denied"), PROVIDER_DISABLED("provider_disabled"), TIMEOUT("timeout"), UNAVAILABLE("unavailable"), UNKNOWN("unknown") }
object LocationUpdateFailureReasonSerializer : WireEnumSerializer<LocationUpdateFailureReason>("LocationUpdateFailureReason", LocationUpdateFailureReason.entries.toTypedArray())

@Serializable(with = RobotStatusBridgeStateSerializer::class)
enum class RobotStatusBridgeState(override val wire: String) : WireEnum { READY("ready"), DEGRADED("degraded"), ERROR("error"), UNKNOWN("unknown") }
object RobotStatusBridgeStateSerializer : WireEnumSerializer<RobotStatusBridgeState>("RobotStatusBridgeState", RobotStatusBridgeState.entries.toTypedArray())

@Serializable(with = RobotStatusMissionStateSerializer::class)
enum class RobotStatusMissionState(override val wire: String) : WireEnum { IDLE("idle"), STARTING("starting"), ACTIVE("active"), ARRIVED("arrived"), CANCELLING("cancelling"), CANCELLED("cancelled"), ERROR("error"), UNKNOWN("unknown") }
object RobotStatusMissionStateSerializer : WireEnumSerializer<RobotStatusMissionState>("RobotStatusMissionState", RobotStatusMissionState.entries.toTypedArray())

@Serializable(with = RobotStatusTrackingStateSerializer::class)
enum class RobotStatusTrackingState(override val wire: String) : WireEnum { UNAVAILABLE("unavailable"), SEARCHING("searching"), FOLLOWING("following"), LOST("lost"), ERROR("error"), UNKNOWN("unknown") }
object RobotStatusTrackingStateSerializer : WireEnumSerializer<RobotStatusTrackingState>("RobotStatusTrackingState", RobotStatusTrackingState.entries.toTypedArray())

@Serializable(with = SosRequestReasonSerializer::class)
enum class SosRequestReason(override val wire: String) : WireEnum { TRACKING_LOST("tracking_lost"), STUCK("stuck"), LOW_BATTERY("low_battery"), MANUAL("manual"), UNKNOWN("unknown") }
object SosRequestReasonSerializer : WireEnumSerializer<SosRequestReason>("SosRequestReason", SosRequestReason.entries.toTypedArray())

@Serializable(with = SosRequestSeveritySerializer::class)
enum class SosRequestSeverity(override val wire: String) : WireEnum { CRITICAL("critical"), WARNING("warning"), UNKNOWN("unknown") }
object SosRequestSeveritySerializer : WireEnumSerializer<SosRequestSeverity>("SosRequestSeverity", SosRequestSeverity.entries.toTypedArray())

@Serializable(with = SosClearResolutionSerializer::class)
enum class SosClearResolution(override val wire: String) : WireEnum { TRACKING_RECOVERED("tracking_recovered"), HANDLED("handled"), CANCELLED("cancelled"), UNKNOWN("unknown") }
object SosClearResolutionSerializer : WireEnumSerializer<SosClearResolution>("SosClearResolution", SosClearResolution.entries.toTypedArray())

@Serializable
data class Hello(
    @SerialName("app_instance_id") val appInstanceId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("platform") val platform: HelloPlatform,
    @SerialName("capabilities") val capabilities: List<HelloCapabilities>,
) : Payload

@Serializable
data class HelloAck(
    @SerialName("session_id") val sessionId: String,
    @SerialName("robot_id") val robotId: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("heartbeat_interval_ms") val heartbeatIntervalMs: Long,
    @SerialName("heartbeat_timeout_ms") val heartbeatTimeoutMs: Long,
    @SerialName("catalog_revision") val catalogRevision: String,
    @SerialName("place_edit_enabled") val placeEditEnabled: Boolean,
    @SerialName("max_places") val maxPlaces: Long,
) : Payload

@Serializable
data class Heartbeat(
    @SerialName("session_id") val sessionId: String,
) : Payload

@Serializable
data class HeartbeatAck(
    @SerialName("session_id") val sessionId: String,
) : Payload

@Serializable
data class Ack(
    @SerialName("status") val status: AckStatus,
    @SerialName("trip_id") val tripId: String? = null,
    @SerialName("place_id") val placeId: String? = null,
    @SerialName("catalog_revision") val catalogRevision: String? = null,
) : Payload

@Serializable
data class ErrorPayload(
    @SerialName("code") val code: ErrorCode,
    @SerialName("message") val message: String,
    @SerialName("retryable") val retryable: Boolean,
) : Payload

@Serializable
data class TripStart(
    @SerialName("from_place_id") val fromPlaceId: String,
    @SerialName("to_place_id") val toPlaceId: String,
    @SerialName("catalog_revision") val catalogRevision: String,
) : Payload

@Serializable
data class TripCancel(
    @SerialName("trip_id") val tripId: String,
    @SerialName("reason") val reason: String,
) : Payload

@Serializable
data class PlaceUpsert(
    @SerialName("place_id") val placeId: String?,
    @SerialName("catalog_revision") val catalogRevision: String,
    @SerialName("kind") val kind: PlaceKind,
    @SerialName("label") val label: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("sort_order") val sortOrder: Long,
    @SerialName("is_default_origin") val isDefaultOrigin: Boolean,
    @SerialName("address") val address: String?,
    @SerialName("source") val source: PlaceSource,
) : Payload

@Serializable
data class PlaceDelete(
    @SerialName("place_id") val placeId: String,
    @SerialName("catalog_revision") val catalogRevision: String,
) : Payload

@Serializable
data class LocationUpdate(
    @SerialName("reason") val reason: LocationUpdateReason,
    @SerialName("sos_id") val sosId: String?,
    @SerialName("has_fix") val hasFix: Boolean,
    @SerialName("lat") val lat: Double?,
    @SerialName("lon") val lon: Double?,
    @SerialName("horizontal_accuracy_m") val horizontalAccuracyM: Double?,
    @SerialName("captured_at_ms") val capturedAtMs: Long?,
    @SerialName("age_ms") val ageMs: Long?,
    @SerialName("source") val source: LocationUpdateSource,
    @SerialName("failure_reason") val failureReason: LocationUpdateFailureReason?,
) : Payload

@Serializable
data class SosAck(
    @SerialName("sos_id") val sosId: String,
    @SerialName("received") val received: Boolean,
) : Payload

@Serializable
data class PlaceCatalog(
    @SerialName("revision") val revision: String,
    @SerialName("max_places") val maxPlaces: Long,
    @SerialName("default_origin_place_id") val defaultOriginPlaceId: String?,
    @SerialName("places") val places: List<Place>,
) : Payload

@Serializable
data class RobotStatus(
    @SerialName("robot_id") val robotId: String,
    @SerialName("bridge_state") val bridgeState: RobotStatusBridgeState,
    @SerialName("mission_state") val missionState: RobotStatusMissionState,
    @SerialName("tracking_state") val trackingState: RobotStatusTrackingState,
    @SerialName("trip_id") val tripId: String?,
    @SerialName("from_place_id") val fromPlaceId: String?,
    @SerialName("to_place_id") val toPlaceId: String?,
    @SerialName("battery_percent") val batteryPercent: Double?,
    @SerialName("last_error") val lastError: String?,
) : Payload

@Serializable
data class SosRequest(
    @SerialName("sos_id") val sosId: String,
    @SerialName("reason") val reason: SosRequestReason,
    @SerialName("severity") val severity: SosRequestSeverity,
    @SerialName("trip_id") val tripId: String?,
) : Payload

@Serializable
data class SosClear(
    @SerialName("sos_id") val sosId: String,
    @SerialName("resolution") val resolution: SosClearResolution,
) : Payload

@Serializable
data class Place(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: PlaceKind,
    @SerialName("label") val label: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("sort_order") val sortOrder: Long,
    @SerialName("is_default_origin") val isDefaultOrigin: Boolean,
    @SerialName("address") val address: String?,
)
