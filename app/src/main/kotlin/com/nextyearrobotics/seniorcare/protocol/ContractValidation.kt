package com.nextyearrobotics.seniorcare.protocol

import kotlinx.serialization.json.*

/** Validator for the subset of JSON Schema used by our bundled, read-only v1 contract.
 * The Gradle resource source points at contracts/ so there is no second schema copy to drift.
 * Inbound enum additions are allowed; serializers map them to UNKNOWN. Outgoing enums are strict.
 */
internal object ContractValidation {
    private val schema by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/messages.schema.json"))
        stream.bufferedReader().use { Protocol.json.parseToJsonElement(it.readText()).jsonObject }
    }
    private val defs get() = schema.getValue("\$defs").jsonObject
    private fun invalid(): Nothing = throw ProtocolException("INVALID_MESSAGE", "payload violates contract")
    private fun ensure(ok: Boolean) { if (!ok) invalid() }

    fun validateEnvelope(value: JsonElement) = checkRule(value, defs.getValue("envelope").jsonObject, false)

    fun validate(env: Envelope, outbound: Boolean) {
        val rule = defs[env.type]?.jsonObject ?: return
        val value = Protocol.json.encodeToJsonElement(Envelope.serializer(), env)
        checkRule(value, rule, outbound)
        val p = env.payload
        when (env.type) {
            "place.upsert" -> if (outbound) ensure(kotlin.math.abs(p.getValue("lat").jsonPrimitive.double) >= 0.000001 || kotlin.math.abs(p.getValue("lon").jsonPrimitive.double) >= 0.000001)
            "trip.start" -> ensure(p["from_place_id"] != p["to_place_id"])
            "place.catalog" -> {
                val places = p.getValue("places").jsonArray
                ensure(places.size <= p.getValue("max_places").jsonPrimitive.long)
                val ids = places.map { it.jsonObject.getValue("id") }
                ensure(ids.distinct().size == ids.size)
                val origins = places.filter { it.jsonObject.getValue("is_default_origin").jsonPrimitive.boolean }
                val origin = p.getValue("default_origin_place_id")
                ensure(if (places.isEmpty()) origin == JsonNull else origins.size == 1 && origins.single().jsonObject["id"] == origin)
            }
        }
    }

    private fun checkRule(value: JsonElement, rule: JsonObject, outbound: Boolean) {
        rule["\$ref"]?.jsonPrimitive?.content?.let { checkRule(value, defs.getValue(it.substringAfterLast('/')).jsonObject, outbound) }
        rule["allOf"]?.jsonArray?.forEach { checkRule(value, it.jsonObject, outbound) }
        rule["oneOf"]?.jsonArray?.let { options ->
            ensure(options.count { option -> try { checkRule(value, option.jsonObject, outbound); true } catch (_: ProtocolException) { false } } == 1)
        }
        rule["type"]?.let { types ->
            val list = if (types is JsonArray) types.map { it.jsonPrimitive.content } else listOf(types.jsonPrimitive.content)
            ensure(list.any { type -> when (type) {
                "null" -> value == JsonNull
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
                else -> error("Unsupported contract type")
            } })
        }
        rule["const"]?.let { ensure(value == it) }
        if (outbound) rule["enum"]?.jsonArray?.let { ensure(value in it) }
        if (value is JsonObject) {
            rule["required"]?.jsonArray?.forEach { ensure(value.containsKey(it.jsonPrimitive.content)) }
            rule["properties"]?.jsonObject?.forEach { (key, child) -> value[key]?.let { checkRule(it, child.jsonObject, outbound) } }
        }
        if (value is JsonArray) {
            rule["items"]?.let { item -> value.forEach { checkRule(it, item.jsonObject, outbound) } }
            if (rule["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true) ensure(value.distinct().size == value.size)
        }
        if (value is JsonPrimitive && value != JsonNull) {
            if (value.isString) {
                val text = value.content
                val length = text.codePointCount(0, text.length)
                rule["minLength"]?.let { ensure(length >= it.jsonPrimitive.int) }
                rule["maxLength"]?.let { ensure(length <= it.jsonPrimitive.int) }
                rule["pattern"]?.let { ensure(Regex(it.jsonPrimitive.content).containsMatchIn(text)) }
            } else {
                value.doubleOrNull?.let { n ->
                    rule["minimum"]?.let { ensure(n >= it.jsonPrimitive.double) }
                    rule["maximum"]?.let { ensure(n <= it.jsonPrimitive.double) }
                }
            }
        }
        rule["if"]?.let { condition ->
            // Condition const/enum checks remain strict, independent of inbound enum compatibility.
            val matches = try { checkRule(value, condition.jsonObject, true); true } catch (_: ProtocolException) { false }
            rule[if (matches) "then" else "else"]?.let { checkRule(value, it.jsonObject, outbound) }
        }
    }
}
