package com.nextyearrobotics.seniorcare.protocol

import kotlinx.serialization.json.*
import java.util.UUID

data class Message(val envelope: Envelope, val payload: Payload)
data class UnknownPayload(val type: String, val raw: JsonObject) : Payload

object PayloadCodec {
    fun decode(text: String): Message {
        val env = Protocol.decode(text)
        ContractValidation.validate(env, outbound = false)
        val payload: Payload = when (env.type) {
            "hello" -> Protocol.json.decodeFromJsonElement(Hello.serializer(), env.payload)
            "hello.ack" -> Protocol.json.decodeFromJsonElement(HelloAck.serializer(), env.payload)
            "heartbeat" -> Protocol.json.decodeFromJsonElement(Heartbeat.serializer(), env.payload)
            "heartbeat.ack" -> Protocol.json.decodeFromJsonElement(HeartbeatAck.serializer(), env.payload)
            "ack" -> Protocol.json.decodeFromJsonElement(Ack.serializer(), env.payload)
            "error" -> Protocol.json.decodeFromJsonElement(ErrorPayload.serializer(), env.payload)
            "trip.start" -> Protocol.json.decodeFromJsonElement(TripStart.serializer(), env.payload)
            "trip.cancel" -> Protocol.json.decodeFromJsonElement(TripCancel.serializer(), env.payload)
            "place.upsert" -> Protocol.json.decodeFromJsonElement(PlaceUpsert.serializer(), env.payload)
            "place.delete" -> Protocol.json.decodeFromJsonElement(PlaceDelete.serializer(), env.payload)
            "location.update" -> Protocol.json.decodeFromJsonElement(LocationUpdate.serializer(), env.payload)
            "sos.ack" -> Protocol.json.decodeFromJsonElement(SosAck.serializer(), env.payload)
            "place.catalog" -> Protocol.json.decodeFromJsonElement(PlaceCatalog.serializer(), env.payload)
            "robot.status" -> Protocol.json.decodeFromJsonElement(RobotStatus.serializer(), env.payload)
            "sos.request" -> Protocol.json.decodeFromJsonElement(SosRequest.serializer(), env.payload)
            "sos.clear" -> Protocol.json.decodeFromJsonElement(SosClear.serializer(), env.payload)
            else -> UnknownPayload(env.type, env.payload)
        }
        return Message(env, payload)
    }

    fun envelope(payload: Payload, sentAtMs: Long, replyTo: String? = null, id: String = UUID.randomUUID().toString()): Envelope {
        val (type, body) = when (payload) {
            is Hello -> "hello" to Protocol.json.encodeToJsonElement(Hello.serializer(), payload).jsonObject
            is HelloAck -> "hello.ack" to Protocol.json.encodeToJsonElement(HelloAck.serializer(), payload).jsonObject
            is Heartbeat -> "heartbeat" to Protocol.json.encodeToJsonElement(Heartbeat.serializer(), payload).jsonObject
            is HeartbeatAck -> "heartbeat.ack" to Protocol.json.encodeToJsonElement(HeartbeatAck.serializer(), payload).jsonObject
            is Ack -> "ack" to Protocol.json.encodeToJsonElement(Ack.serializer(), payload).jsonObject
            is ErrorPayload -> "error" to Protocol.json.encodeToJsonElement(ErrorPayload.serializer(), payload).jsonObject
            is TripStart -> "trip.start" to Protocol.json.encodeToJsonElement(TripStart.serializer(), payload).jsonObject
            is TripCancel -> "trip.cancel" to Protocol.json.encodeToJsonElement(TripCancel.serializer(), payload).jsonObject
            is PlaceUpsert -> "place.upsert" to Protocol.json.encodeToJsonElement(PlaceUpsert.serializer(), payload).jsonObject
            is PlaceDelete -> "place.delete" to Protocol.json.encodeToJsonElement(PlaceDelete.serializer(), payload).jsonObject
            is LocationUpdate -> "location.update" to Protocol.json.encodeToJsonElement(LocationUpdate.serializer(), payload).jsonObject
            is SosAck -> "sos.ack" to Protocol.json.encodeToJsonElement(SosAck.serializer(), payload).jsonObject
            is PlaceCatalog -> "place.catalog" to Protocol.json.encodeToJsonElement(PlaceCatalog.serializer(), payload).jsonObject
            is RobotStatus -> "robot.status" to Protocol.json.encodeToJsonElement(RobotStatus.serializer(), payload).jsonObject
            is SosRequest -> "sos.request" to Protocol.json.encodeToJsonElement(SosRequest.serializer(), payload).jsonObject
            is SosClear -> "sos.clear" to Protocol.json.encodeToJsonElement(SosClear.serializer(), payload).jsonObject
            is UnknownPayload -> throw ProtocolException("UNSUPPORTED_TYPE", "unknown outgoing type")
        }
        val env = Envelope(1, type, id, sentAtMs, replyTo, body)
        ContractValidation.validate(env, outbound = true)
        Protocol.decode(Protocol.encode(env))
        return env
    }

    /** For fixture round trips; received future enums normalize to unknown. */
    fun encode(message: Message): String {
        val env = message.envelope
        val payload = message.payload
        val body = when (payload) {
            is Hello -> Protocol.json.encodeToJsonElement(Hello.serializer(), payload).jsonObject
            is HelloAck -> Protocol.json.encodeToJsonElement(HelloAck.serializer(), payload).jsonObject
            is Heartbeat -> Protocol.json.encodeToJsonElement(Heartbeat.serializer(), payload).jsonObject
            is HeartbeatAck -> Protocol.json.encodeToJsonElement(HeartbeatAck.serializer(), payload).jsonObject
            is Ack -> Protocol.json.encodeToJsonElement(Ack.serializer(), payload).jsonObject
            is ErrorPayload -> Protocol.json.encodeToJsonElement(ErrorPayload.serializer(), payload).jsonObject
            is TripStart -> Protocol.json.encodeToJsonElement(TripStart.serializer(), payload).jsonObject
            is TripCancel -> Protocol.json.encodeToJsonElement(TripCancel.serializer(), payload).jsonObject
            is PlaceUpsert -> Protocol.json.encodeToJsonElement(PlaceUpsert.serializer(), payload).jsonObject
            is PlaceDelete -> Protocol.json.encodeToJsonElement(PlaceDelete.serializer(), payload).jsonObject
            is LocationUpdate -> Protocol.json.encodeToJsonElement(LocationUpdate.serializer(), payload).jsonObject
            is SosAck -> Protocol.json.encodeToJsonElement(SosAck.serializer(), payload).jsonObject
            is PlaceCatalog -> Protocol.json.encodeToJsonElement(PlaceCatalog.serializer(), payload).jsonObject
            is RobotStatus -> Protocol.json.encodeToJsonElement(RobotStatus.serializer(), payload).jsonObject
            is SosRequest -> Protocol.json.encodeToJsonElement(SosRequest.serializer(), payload).jsonObject
            is SosClear -> Protocol.json.encodeToJsonElement(SosClear.serializer(), payload).jsonObject
            is UnknownPayload -> payload.raw
        }
        return Protocol.encode(env.copy(payload = body))
    }
}
