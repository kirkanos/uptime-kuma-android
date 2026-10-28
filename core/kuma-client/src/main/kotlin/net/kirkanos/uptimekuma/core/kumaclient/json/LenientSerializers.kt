package net.kirkanos.uptimekuma.core.kumaclient.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder

/** Boolean that also accepts `0`/`1` (SQLite rows) and `null` (-> false). */
object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kuma.LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return json.decodeJsonElement().asLenientBoolean() ?: false
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) = encoder.encodeBoolean(value)
}

/** Long that also accepts numeric strings (`monitorList` keys, `heartbeatList` monitor id). */
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kuma.LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = json.decodeJsonElement()
        return requireNotNull(element.asLenientLong()) { "Not a number: $element" }
    }

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) = encoder.encodeLong(value)
}

/** Nullable Long that tolerates strings and garbage (-> null). */
object LenientNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kuma.LenientLongOrNull", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return json.decodeJsonElement().asLenientLong()
    }

    override fun serialize(
        encoder: Encoder,
        value: Long?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/** Nullable Int that tolerates strings and garbage (-> null), e.g. `port`. */
object LenientNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kuma.LenientIntOrNull", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return json.decodeJsonElement().asLenientLong()?.toInt()
    }

    override fun serialize(
        encoder: Encoder,
        value: Int?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/** Nullable Double that tolerates strings and garbage (-> null), e.g. `ping`. */
object LenientNullableDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kuma.LenientDoubleOrNull", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        return json.decodeJsonElement().asLenientDouble()
    }

    override fun serialize(
        encoder: Encoder,
        value: Double?,
    ) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}
