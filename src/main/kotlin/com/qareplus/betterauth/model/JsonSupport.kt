package com.qareplus.betterauth.model

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal val EmptyObject: JsonObject = JsonObject(emptyMap())

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.instant(key: String): Instant? = this[key]?.toInstantOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Accepts ISO-8601 strings (what Better Auth sends) and epoch-millisecond numbers. */
internal fun JsonElement.toInstantOrNull(): Instant? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return if (p.isString) {
        runCatching { Instant.parse(p.content) }.getOrNull()
    } else {
        p.longOrNull?.let { Instant.fromEpochMilliseconds(it) }
    }
}

/** `kotlin.time.Instant` as an ISO-8601 string. Also tolerates epoch-millisecond numbers when reading. */
public object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.qareplus.betterauth.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
        return element?.toInstantOrNull() ?: Instant.parse(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }
}

/**
 * Base for models that keep server-defined extra fields. Known fields are mapped by hand; every other key lands in the
 * model's `additionalFields`, and is written back out when encoding.
 */
internal abstract class JsonObjectSerializer<T>(name: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name)

    abstract fun fromJson(obj: JsonObject): T

    abstract fun toJson(value: T): JsonObject

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: error("${descriptor.serialName} can only be decoded from JSON")
        return fromJson(json.decodeJsonElement().jsonObject)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as? JsonEncoder ?: error("${descriptor.serialName} can only be encoded to JSON")
        json.encodeJsonElement(toJson(value))
    }
}

internal fun JsonObject.without(keys: Set<String>): JsonObject =
    if (keys.isEmpty()) this else JsonObject(filterKeys { it !in keys })

internal class ObjectBuilder {
    private val map = LinkedHashMap<String, JsonElement>()

    fun put(key: String, value: String?) { if (value != null) map[key] = JsonPrimitive(value) }

    fun put(key: String, value: Boolean?) { if (value != null) map[key] = JsonPrimitive(value) }

    fun put(key: String, value: Number?) { if (value != null) map[key] = JsonPrimitive(value) }

    fun put(key: String, value: Instant?) { if (value != null) map[key] = JsonPrimitive(value.toString()) }

    fun put(key: String, value: JsonElement?) { if (value != null) map[key] = value }

    fun putStrings(key: String, values: List<String>?) {
        if (values != null) map[key] = JsonArray(values.map { JsonPrimitive(it) })
    }

    fun putAll(values: JsonObject?) { values?.let { map.putAll(it) } }

    fun build(): JsonObject = JsonObject(map)
}

internal inline fun jsonBody(block: ObjectBuilder.() -> Unit): JsonObject = ObjectBuilder().apply(block).build()
