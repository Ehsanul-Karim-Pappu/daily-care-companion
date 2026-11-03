package app.dailycare.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
val DAY: LocalDate = LocalDate.of(2026, 11, 3)
val CAL = CareCalendar(ZONE, LocalTime.of(3, 0))

/** Epoch millis for a wall-clock time on [DAY] (or a later date). */
fun t(h: Int, m: Int = 0, date: LocalDate = DAY): Long = date.atTime(h, m).atZone(ZONE).toInstant().toEpochMilli()

const val HOME_LAT = 23.8000
const val HOME_LON = 90.4000

val HOME = Place(
    PlaceKind.HOME, latitude = HOME_LAT, longitude = HOME_LON, radiusMeters = 80.0,
    knownNetworks = setOf("HomeNet"),
    fingerprint = listOf(WifiAccessPoint("aa:00:00:00:00:01", "HomeNet", -45), WifiAccessPoint("aa:00:00:00:00:02", "Neighbour", -70)),
)

/** About 250 m north of home. */
val THERAPY = Place(
    PlaceKind.THERAPY, latitude = HOME_LAT + 0.00225, longitude = HOME_LON, radiusMeters = 80.0,
    fingerprint = listOf(WifiAccessPoint("cc:00:00:00:00:01", "ClinicWiFi", -50), WifiAccessPoint("cc:00:00:00:00:02", "Shop", -65)),
)

val WORK = Place(
    PlaceKind.WORK, latitude = 23.7500, longitude = 90.3900, radiusMeters = 120.0,
    knownNetworks = setOf("OfficeNet"),
)

fun settings(vararg places: Place = arrayOf(HOME, WORK, THERAPY)) = CareSettings(places = places.toList(), observeOnly = false)

/** Steps per minute: [minutes] consecutive minutes from [start] with [perMinute] steps each. */
fun walkBins(start: Long, minutes: Int, perMinute: Int): Map<Long, Int> =
    (0 until minutes).associate { floorMinute(start) + it * MINUTE_MS to perMinute }

fun stored(vararg events: CareEvent): List<StoredEvent> = events.mapIndexed { i, e -> StoredEvent(i + 1L, e) }
