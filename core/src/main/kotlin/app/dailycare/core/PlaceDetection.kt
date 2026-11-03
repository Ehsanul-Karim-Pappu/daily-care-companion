package app.dailycare.core

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
data class ConnectedWifi(val ssid: String?, val bssid: String?)

@Serializable
data class WifiScan(val atMillis: Long, val accessPoints: List<WifiAccessPoint>)

@Serializable
data class LocationFix(val atMillis: Long, val latitude: Double, val longitude: Double, val accuracyMeters: Double)

/** Everything the phone currently knows about where it is. */
data class SignalSnapshot(
    val now: Long,
    val wifi: ConnectedWifi? = null,
    val scan: WifiScan? = null,
    val fix: LocationFix? = null,
    /** Places whose geofence Play services last reported us inside. */
    val geofencesInside: Set<PlaceKind> = emptySet(),
    /** Whether geofences are registered, so "not inside" means something. */
    val geofencesRegistered: Boolean = false,
)

data class PlaceResolution(
    val place: PlaceKind?,
    val confidence: Confidence,
    val basis: String,
    /** False when no signal at all was available (not the same as "away"). */
    val hasEvidence: Boolean,
)

/**
 * Turns one snapshot of signals into "which saved place am I at".
 * Evidence order: connected Wi-Fi > visible Wi-Fi fingerprint > location fix > geofence.
 * Merely *not* seeing home Wi-Fi never puts you anywhere; a place needs positive evidence.
 */
object PlaceResolver {

    fun resolve(s: SignalSnapshot, places: List<Place>, cfg: PlaceDetectionSettings): PlaceResolution {
        var evidence = false

        // 1. Connected Wi-Fi.
        s.wifi?.let { wifi ->
            evidence = true
            val bssid = wifi.bssid?.lowercase()
            val match = places.firstOrNull { p -> bssid != null && bssid in p.knownBssids.map { it.lowercase() } }
                ?: places.firstOrNull { p -> wifi.ssid != null && wifi.ssid in p.knownNetworks }
            if (match != null) {
                return PlaceResolution(match.kind, Confidence.HIGH, "connected to Wi-Fi \"${wifi.ssid ?: bssid}\"", true)
            }
        }

        // 2. Wi-Fi networks visible around (no connection needed).
        val scan = s.scan?.takeIf { s.now - it.atMillis <= cfg.wifiScanMaxAgeMinutes * MINUTE_MS && it.accessPoints.isNotEmpty() }
        if (scan != null) {
            evidence = true
            val scored = places.filter { it.fingerprint.isNotEmpty() }
                .map { it to WifiFingerprint.similarity(scan.accessPoints, it.fingerprint) }
                .sortedByDescending { it.second }
            val best = scored.firstOrNull()
            val second = scored.getOrNull(1)?.second ?: 0.0
            if (best != null && best.second >= cfg.minWifiSimilarity && best.second - second >= cfg.minWifiMargin) {
                val conf = if (best.second >= 0.5) Confidence.HIGH else Confidence.MEDIUM
                val pct = (best.second * 100).roundToInt()
                return PlaceResolution(best.first.kind, conf, "nearby Wi-Fi matches ${best.first.name} ($pct%)", true)
            }
        }

        // 3. Location fix.
        val fix = s.fix?.takeIf { s.now - it.atMillis <= cfg.locationFixMaxAgeMinutes * MINUTE_MS }
        if (fix != null) {
            evidence = true
            val withDistance = places.mapNotNull { p -> p.distanceTo(fix.latitude, fix.longitude)?.let { p to it } }
            // "Possibly inside": the fix's accuracy circle reaches the place.
            val possible = withDistance.filter { (p, d) -> d - fix.accuracyMeters <= p.radiusMeters }.sortedBy { it.second }
            val inside = possible.filter { (p, d) -> d <= p.radiusMeters }
            if (possible.isNotEmpty()) {
                val (place, d) = inside.firstOrNull() ?: possible.first()
                val ambiguous = possible.size > 1
                val conf = when {
                    ambiguous || inside.isEmpty() -> Confidence.LOW
                    d + min(fix.accuracyMeters, place.radiusMeters) <= place.radiusMeters * 1.5 -> Confidence.HIGH
                    else -> Confidence.MEDIUM
                }
                val why = "location ${d.roundToInt()} m from ${place.name} (±${fix.accuracyMeters.roundToInt()} m)" +
                    if (ambiguous) ", could also be ${possible.drop(1).joinToString { it.first.name }}" else ""
                return PlaceResolution(place.kind, conf, why, true)
            }
        }

        // 4. Geofences.
        if (s.geofencesRegistered) {
            evidence = true
            val inside = places.filter { it.kind in s.geofencesInside && it.hasLocation }
            if (inside.isNotEmpty()) {
                val chosen = if (fix != null) inside.minBy { it.distanceTo(fix.latitude, fix.longitude) ?: Double.MAX_VALUE } else inside.first()
                val conf = if (inside.size == 1) Confidence.MEDIUM else Confidence.LOW
                return PlaceResolution(chosen.kind, conf, "inside ${chosen.name} geofence", true)
            }
        }

        return if (evidence) {
            PlaceResolution(null, Confidence.MEDIUM, "not at a saved place", true)
        } else {
            PlaceResolution(null, Confidence.LOW, "no location signal", false)
        }
    }
}

@Serializable
data class PlaceMachineState(
    val current: PlaceKind? = null,
    val since: Long? = null,
    /** Highest confidence seen during the current visit. */
    val confidence: Confidence = Confidence.LOW,
    val basis: String = "",
    val lastPositiveAt: Long? = null,
    val candidateActive: Boolean = false,
    val candidate: PlaceKind? = null,
    val candidateSince: Long? = null,
    val candidateConfidence: Confidence = Confidence.LOW,
    val candidateBasis: String = "",
)

/**
 * Hysteresis over [PlaceResolution]s so brief signal drops don't create fake departures.
 * Transitions are back-dated to when the new state first appeared.
 */
object PlaceStateMachine {

    fun update(
        state: PlaceMachineState,
        res: PlaceResolution,
        now: Long,
        cfg: PlaceDetectionSettings,
    ): Pair<PlaceMachineState, List<CareEvent>> {
        // Same as now: refresh.
        if (res.hasEvidence && res.place == state.current) {
            val refreshed = if (state.current != null) {
                state.copy(
                    lastPositiveAt = now,
                    confidence = maxOf(state.confidence, res.confidence),
                    candidateActive = false,
                )
            } else {
                state.copy(candidateActive = false)
            }
            return refreshed to emptyList()
        }

        // No signal at all: hold the current place for a while, then let it go.
        if (!res.hasEvidence) {
            val current = state.current
            val lastPositive = state.lastPositiveAt
            if (current != null && lastPositive != null && now - lastPositive >= cfg.unknownHoldMinutes * MINUTE_MS) {
                val exit = PlaceExited(lastPositive, current, state.confidence)
                return PlaceMachineState(current = null, since = lastPositive) to listOf(exit)
            }
            return state to emptyList()
        }

        // Different place (or away) with evidence.
        val continuing = state.candidateActive && state.candidate == res.place
        val candSince = if (continuing) state.candidateSince ?: now else now
        val candConf = if (continuing) maxOf(state.candidateConfidence, res.confidence) else res.confidence
        val delayMs = when {
            res.place == null -> cfg.leaveDelayMinutes * MINUTE_MS
            res.confidence == Confidence.HIGH -> 0L
            else -> cfg.enterDelayMinutes * MINUTE_MS
        }
        if (now - candSince < delayMs) {
            return state.copy(
                candidateActive = true,
                candidate = res.place,
                candidateSince = candSince,
                candidateConfidence = candConf,
                candidateBasis = res.basis,
            ) to emptyList()
        }

        val events = mutableListOf<CareEvent>()
        state.current?.let { events += PlaceExited(candSince, it, state.confidence) }
        res.place?.let { events += PlaceEntered(candSince, it, candConf, res.basis) }
        val committed = PlaceMachineState(
            current = res.place,
            since = candSince,
            confidence = candConf,
            basis = res.basis,
            lastPositiveAt = if (res.place != null) now else null,
        )
        return committed to events
    }
}

data class PlaceVisit(
    val place: PlaceKind,
    val start: Long,
    val end: Long?,
    val confidence: Confidence,
    val basis: String,
) {
    fun endOr(now: Long): Long = end ?: now
    fun durationMs(now: Long): Long = endOr(now) - start
}

object Visits {
    /** Pairs logged arrivals and departures into visits. The current visit (if any) has `end == null`. */
    fun from(events: List<CareEvent>, state: PlaceMachineState?): List<PlaceVisit> {
        val out = mutableListOf<PlaceVisit>()
        var open: PlaceVisit? = null
        for (e in events.sortedBy { it.at }) {
            when (e) {
                is PlaceEntered -> {
                    open?.let { out += it.copy(end = e.at) }
                    open = PlaceVisit(e.place, e.at, null, e.confidence, e.basis)
                }
                is PlaceExited -> {
                    val o = open
                    if (o != null && o.place == e.place) {
                        out += o.copy(end = e.at, confidence = maxOf(o.confidence, e.confidence))
                        open = null
                    }
                }
                else -> Unit
            }
        }
        open?.let { o ->
            out += if (state != null && state.current == o.place) o.copy(confidence = maxOf(o.confidence, state.confidence)) else o
        }
        return out
    }
}
