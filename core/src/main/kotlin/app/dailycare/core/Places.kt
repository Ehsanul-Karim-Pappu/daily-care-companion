package app.dailycare.core

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
enum class PlaceKind(val label: String) { HOME("Home"), WORK("Work"), THERAPY("Therapy") }

@Serializable
enum class Confidence(val label: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

@Serializable
data class WifiAccessPoint(val bssid: String, val ssid: String = "", val rssi: Int = -70)

@Serializable
data class Place(
    val kind: PlaceKind,
    val name: String = kind.label,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Double? = null,
    val radiusMeters: Double = 100.0,
    /** Network names (SSIDs) the phone connects to here. */
    val knownNetworks: Set<String> = emptySet(),
    /** Access-point addresses the phone was connected to here. */
    val knownBssids: Set<String> = emptySet(),
    /** Networks visible here when the place was saved; used even if the phone never connects. */
    val fingerprint: List<WifiAccessPoint> = emptyList(),
    val savedAtMillis: Long? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    fun distanceTo(lat: Double, lon: Double): Double? =
        if (latitude != null && longitude != null) Geo.distanceMeters(latitude, longitude, lat, lon) else null

    fun distanceTo(other: Place): Double? =
        if (other.latitude != null && other.longitude != null) distanceTo(other.latitude, other.longitude) else null
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }
}

object WifiFingerprint {
    /** Stronger signals weigh more: -100 dBm → 0, -40 dBm or better → 1. */
    fun weight(rssi: Int): Double = (rssi + 100).coerceIn(0, 60) / 60.0

    /** Weighted Jaccard similarity of two sets of visible access points (0 = nothing shared, 1 = identical). */
    fun similarity(a: List<WifiAccessPoint>, b: List<WifiAccessPoint>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val wa = a.associate { it.bssid.lowercase() to weight(it.rssi) }
        val wb = b.associate { it.bssid.lowercase() to weight(it.rssi) }
        var inter = 0.0
        var union = 0.0
        for (k in wa.keys + wb.keys) {
            val x = wa[k] ?: 0.0
            val y = wb[k] ?: 0.0
            inter += min(x, y)
            union += max(x, y)
        }
        return if (union <= 0.0) 0.0 else inter / union
    }

    /** Keep the strongest distinct access points, which is what a saved fingerprint needs. */
    fun compact(aps: List<WifiAccessPoint>, limit: Int = 20): List<WifiAccessPoint> =
        aps.filter { it.bssid.isNotBlank() }
            .map { it.copy(bssid = it.bssid.lowercase()) }
            .groupBy { it.bssid }
            .map { (_, list) -> list.maxBy { it.rssi } }
            .sortedByDescending { it.rssi }
            .take(limit)
}

enum class SeparationLevel(val label: String) { GOOD("good"), FAIR("fair"), POOR("poor"), UNKNOWN("unknown") }

data class SeparationAssessment(
    val distanceMeters: Double?,
    val wifiOverlap: Double?,
    val level: SeparationLevel,
    val explanation: String,
    val suggestedRadiusMeters: Double?,
)

/**
 * How reliably two saved places (typically home and the therapy place) can be told apart.
 * This answers "how far is it and does it have its own Wi-Fi" from data the app collects.
 */
object PlaceSeparation {
    fun assess(a: Place, b: Place): SeparationAssessment {
        val distance = a.distanceTo(b)
        val overlap = if (a.fingerprint.isNotEmpty() && b.fingerprint.isNotEmpty()) {
            WifiFingerprint.similarity(a.fingerprint, b.fingerprint)
        } else {
            null
        }
        if (distance == null && overlap == null) {
            return SeparationAssessment(null, null, SeparationLevel.UNKNOWN, "Save both places to see how well they can be told apart.", null)
        }
        val byLocation = when {
            distance == null -> null
            distance >= 300 -> SeparationLevel.GOOD
            distance >= 150 -> SeparationLevel.FAIR
            else -> SeparationLevel.POOR
        }
        val byWifi = when {
            overlap == null -> null
            overlap < 0.2 -> SeparationLevel.GOOD
            overlap < 0.5 -> SeparationLevel.FAIR
            else -> SeparationLevel.POOR
        }
        val level = listOfNotNull(byLocation, byWifi).minByOrNull { it.ordinal } ?: SeparationLevel.UNKNOWN
        val parts = mutableListOf<String>()
        if (distance != null) {
            parts += "${a.name} and ${b.name} are about ${distance.roundToInt()} m apart"
            parts += when (byLocation) {
                SeparationLevel.GOOD -> "far enough for location alone"
                SeparationLevel.FAIR -> "location alone will sometimes confuse them"
                else -> "too close for location alone, especially indoors"
            }
        }
        if (overlap != null) {
            val pct = (overlap * 100).roundToInt()
            parts += when (byWifi) {
                SeparationLevel.GOOD -> "the Wi-Fi networks seen at each differ ($pct% overlap), which separates them well"
                SeparationLevel.FAIR -> "the Wi-Fi networks partly overlap ($pct%)"
                else -> "the same Wi-Fi networks are visible at both ($pct% overlap), so Wi-Fi won't help"
            }
        } else if (distance != null && byLocation != SeparationLevel.GOOD) {
            parts += "re-save each place while standing there to record its Wi-Fi networks, which may separate them"
        }
        val suggested = distance?.let { (it / 2 - 10).coerceIn(40.0, 150.0) }
        return SeparationAssessment(distance, overlap, level, parts.joinToString("; ") + ".", suggested)
    }
}
