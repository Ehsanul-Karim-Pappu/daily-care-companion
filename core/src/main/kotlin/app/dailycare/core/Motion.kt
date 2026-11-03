package app.dailycare.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class Motion {
    STILL, WALKING, RUNNING, ON_FOOT, IN_VEHICLE, ON_BICYCLE, OTHER;

    val isOnFoot: Boolean get() = this == WALKING || this == RUNNING || this == ON_FOOT
}

/** An activity-recognition transition (e.g. "entered WALKING at 10:21"). */
data class MotionTransition(val at: Long, val motion: Motion, val entering: Boolean)

@Serializable
enum class WalkBasis { STEPS, ACTIVITY }

data class Walk(
    val start: Long,
    val end: Long,
    val steps: Int?,
    val activeMinutes: Int,
    val qualifies: Boolean,
    val basis: WalkBasis,
)

@Serializable
data class StepAccumulatorState(val lastAt: Long? = null, val lastTotal: Long? = null)

/**
 * Converts the phone's cumulative step counter into steps per minute.
 * Reset the state whenever monitoring restarts, so steps taken while the app wasn't running
 * are not invented as a walk.
 */
object StepAccumulator {
    const val ASSUMED_CADENCE_PER_MIN = 100
    private const val SHORT_SPAN_MS = 3 * MINUTE_MS

    fun add(state: StepAccumulatorState, at: Long, total: Long): Pair<StepAccumulatorState, Map<Long, Int>> {
        val lastAt = state.lastAt
        val lastTotal = state.lastTotal
        val next = StepAccumulatorState(at, total)
        // First sample, or the counter went backwards (reboot): just take a new baseline.
        if (lastAt == null || lastTotal == null || total < lastTotal) return next to emptyMap()
        val delta = total - lastTotal
        if (delta == 0L) return next to emptyMap()
        val span = at - lastAt
        val from = when {
            span <= 0 -> at
            span <= SHORT_SPAN_MS -> lastAt
            // Long silence then steps: the sensor batched or the CPU slept. The steps almost certainly
            // happened just before this sample, at a normal walking pace.
            else -> max(lastAt, at - ceil(delta.toDouble() / ASSUMED_CADENCE_PER_MIN).toLong() * MINUTE_MS)
        }
        return next to distribute(delta, from, at)
    }

    /** Spread [steps] over the minutes overlapping [from, to), keeping the total exact. */
    fun distribute(steps: Long, from: Long, to: Long): Map<Long, Int> {
        if (steps <= 0) return emptyMap()
        if (to <= from) return mapOf(floorMinute(to) to steps.toInt())
        val buckets = mutableListOf<Pair<Long, Long>>()
        var m = floorMinute(from)
        while (m < to) {
            val overlap = min(to, m + MINUTE_MS) - max(from, m)
            if (overlap > 0) buckets += m to overlap
            m += MINUTE_MS
        }
        val totalOverlap = buckets.sumOf { it.second }.toDouble()
        val raw = buckets.map { steps * it.second / totalOverlap }
        val counts = raw.map { floor(it).toLong() }.toMutableList()
        var remaining = steps - counts.sum()
        for (i in raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }) {
            if (remaining <= 0) break
            counts[i]++
            remaining--
        }
        return buckets.indices.filter { counts[it] > 0 }.associate { buckets[it].first to counts[it].toInt() }
    }

    fun merge(into: MutableMap<Long, Int>, increments: Map<Long, Int>) {
        increments.forEach { (minute, steps) -> into[minute] = (into[minute] ?: 0) + steps }
    }

    /** Several sources (phone now, a watch later) counting the same steps: take the max per minute. */
    fun mergeSources(vararg sources: Map<Long, Int>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        sources.forEach { src -> src.forEach { (m, n) -> out[m] = max(out[m] ?: 0, n) } }
        return out
    }
}

object WalkDetector {

    fun detect(
        bins: Map<Long, Int>,
        transitions: List<MotionTransition>,
        stepSensorAvailable: Boolean,
        from: Long,
        to: Long,
        s: BreakSettings,
    ): List<Walk> = if (stepSensorAvailable) fromSteps(bins, from, to, s) else fromActivity(transitions, from, to, s)

    /** Runs of minutes with enough steps, allowing short pauses inside a walk. */
    fun fromSteps(bins: Map<Long, Int>, from: Long, to: Long, s: BreakSettings): List<Walk> {
        val walks = mutableListOf<Walk>()
        var runStart = -1L
        var lastActive = -1L
        var active = 0
        var steps = 0L
        var gapSteps = 0L
        var gap = 0

        fun close() {
            if (runStart >= 0) {
                val qualifies = active >= s.minWalkMinutes && steps >= s.minSteps
                walks += Walk(runStart, lastActive + MINUTE_MS, steps.toInt(), active, qualifies, WalkBasis.STEPS)
            }
            runStart = -1L; lastActive = -1L; active = 0; steps = 0L; gapSteps = 0L; gap = 0
        }

        var m = floorMinute(from)
        val last = floorMinute(to)
        while (m <= last) {
            val n = bins[m] ?: 0
            if (n >= s.activeMinuteSteps) {
                if (runStart < 0) runStart = m
                steps += gapSteps + n
                gapSteps = 0
                gap = 0
                active++
                lastActive = m
            } else if (runStart >= 0) {
                gap++
                gapSteps += n
                if (gap > s.mergeGapMinutes) close()
            }
            m += MINUTE_MS
        }
        close()
        return walks
    }

    /** Fallback when the phone has no step counter: activity-recognition walking periods. */
    fun fromActivity(transitions: List<MotionTransition>, from: Long, to: Long, s: BreakSettings): List<Walk> {
        val intervals = mutableListOf<Pair<Long, Long>>()
        var openAt: Long? = null
        for (t in transitions.sortedBy { it.at }) {
            val startsFoot = t.motion.isOnFoot && t.entering
            val endsFoot = (t.motion.isOnFoot && !t.entering) || (!t.motion.isOnFoot && t.entering)
            if (startsFoot) {
                if (openAt == null) openAt = t.at
            } else if (endsFoot) {
                openAt?.let { intervals += it to t.at }
                openAt = null
            }
        }
        openAt?.let { intervals += it to to }

        val merged = mutableListOf<Pair<Long, Long>>()
        for ((a, b) in intervals.map { (a, b) -> max(a, from) to min(b, to) }.filter { it.second > it.first }) {
            val prev = merged.lastOrNull()
            if (prev != null && a - prev.second <= s.mergeGapMinutes * MINUTE_MS) {
                merged[merged.size - 1] = prev.first to max(prev.second, b)
            } else {
                merged += a to b
            }
        }
        return merged.map { (a, b) ->
            val minutes = ((b - a) / MINUTE_MS).toInt()
            Walk(a, b, null, minutes, minutes >= s.minWalkMinutes, WalkBasis.ACTIVITY)
        }
    }

    /** A walk is final once a full merge gap has passed after it. */
    fun isFinal(walk: Walk, now: Long, s: BreakSettings): Boolean =
        now - walk.end >= (s.mergeGapMinutes + 1) * MINUTE_MS

    fun movingNow(
        bins: Map<Long, Int>,
        transitions: List<MotionTransition>,
        stepSensorAvailable: Boolean,
        now: Long,
        s: BreakSettings,
    ): Boolean {
        if (stepSensorAvailable) {
            val cur = bins[floorMinute(now)] ?: 0
            val prev = bins[floorMinute(now) - MINUTE_MS] ?: 0
            return cur + prev >= s.activeMinuteSteps
        }
        val last = transitions.filter { it.entering && it.at <= now }.maxByOrNull { it.at } ?: return false
        return last.motion.isOnFoot && now - last.at <= 30 * MINUTE_MS
    }
}
