package ai.labs32.khaata.core.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Date and time serialisers for backup files.
 *
 * ISO-8601 text rather than epoch numbers: a backup is something a user may reasonably open and
 * inspect, and a readable file is easier to trust and to support.
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()
        return try {
            LocalDate.parse(raw)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Malformed date '$raw' (expected YYYY-MM-DD)", error)
        }
    }
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            Instant.parse(raw)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Malformed timestamp '$raw' (expected ISO-8601)", error)
        }
    }
}

/**
 * Serialises a [BigDecimal] as its plain decimal string.
 *
 * Encoded as text, not as a JSON number: a JSON number round-tripped through a double loses
 * precision, which is exactly the failure mode this codebase avoids everywhere else.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString()
        return try {
            BigDecimal(raw)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("Malformed decimal '$raw'", error)
        }
    }
}
