package app.dailycare.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.dailycare.LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.format(HHMM))
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.dailycare.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

fun floorMinute(millis: Long): Long = Math.floorDiv(millis, MINUTE_MS) * MINUTE_MS

fun LocalTime.hm(): String = format(HHMM)

/** "47 min", "1 h 12 min". */
fun formatMinutes(minutes: Long): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "$h h" else "$h h $m min"
}

/**
 * A "care day" runs from [boundary] (default 03:00) to the same time the next calendar day,
 * so a therapy session at 23:30–00:20 or a late night dose still belongs to the evening it started.
 */
class CareCalendar(val zone: ZoneId, val boundary: LocalTime) {

    fun dayOf(millis: Long): LocalDate {
        val z = Instant.ofEpochMilli(millis).atZone(zone)
        return if (z.toLocalTime() < boundary) z.toLocalDate().minusDays(1) else z.toLocalDate()
    }

    fun dayStart(day: LocalDate): Long = day.atTime(boundary).atZone(zone).toInstant().toEpochMilli()

    fun dayEnd(day: LocalDate): Long = dayStart(day.plusDays(1))

    /** The instant of wall-clock [time] that belongs to care day [day]; times before the boundary fall on the next date. */
    fun at(day: LocalDate, time: LocalTime): Long {
        val date = if (time < boundary) day.plusDays(1) else day
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun localTime(millis: Long): LocalTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()

    fun hm(millis: Long): String = localTime(millis).hm()

    fun dayLabel(day: LocalDate): String = day.format(DAY_LABEL)

    /** Next instant strictly after [now] whose local time is [time]. */
    fun nextOccurrence(now: Long, time: LocalTime): Long {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        var candidate = z.toLocalDate().atTime(time).atZone(zone)
        if (!candidate.toInstant().isAfter(z.toInstant())) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }
}
