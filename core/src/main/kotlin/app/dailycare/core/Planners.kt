package app.dailycare.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

// ---------------------------------------------------------------- Breaks

data class BreakInterval(val start: Long, val end: Long, val provenance: Provenance)

data class BreakStatus(
    val atWork: Boolean,
    val workSince: Long? = null,
    /** Start of the current stretch without a break (arrival at work or end of the last break). */
    val anchor: Long? = null,
    val lastBreakEnd: Long? = null,
    /** When the next reminder is due; null if none (not at work, or follow-ups exhausted). */
    val dueAt: Long? = null,
    val isFollowUp: Boolean = false,
    val remindersSinceAnchor: Int = 0,
    val snoozedUntil: Long? = null,
)

object BreakPlanner {

    fun status(
        now: Long,
        work: PlaceVisit?,
        breaks: List<BreakInterval>,
        reminders: List<BreakReminderSent>,
        snoozes: List<BreakSnoozed>,
        s: BreakSettings,
    ): BreakStatus {
        if (!s.enabled || work == null || work.end != null) return BreakStatus(atWork = work != null && work.end == null)
        val workSince = work.start
        val lastBreakEnd = breaks.filter { it.end in workSince..now }.maxOfOrNull { it.end }
        val anchor = max(workSince, lastBreakEnd ?: workSince)
        val lastSnooze = snoozes.filter { it.at > anchor }.maxByOrNull { it.at }
        val reminderBase = lastSnooze?.at ?: anchor
        val after = reminders.filter { it.at > reminderBase }.sortedBy { it.at }
        val remindersSinceAnchor = reminders.count { it.at > anchor }
        val dueAt = when {
            after.isEmpty() -> lastSnooze?.until ?: (anchor + s.inactivityMinutes * MINUTE_MS)
            after.size <= s.maxFollowUps -> after.last().at + s.followUpMinutes * MINUTE_MS
            else -> null
        }
        return BreakStatus(
            atWork = true,
            workSince = workSince,
            anchor = anchor,
            lastBreakEnd = lastBreakEnd,
            dueAt = dueAt,
            isFollowUp = after.isNotEmpty(),
            remindersSinceAnchor = remindersSinceAnchor,
            snoozedUntil = lastSnooze?.until?.takeIf { it > now },
        )
    }

    /** Longest stretch inside [visit] without a break, in ms. */
    fun longestStretch(visit: PlaceVisit, breaks: List<BreakInterval>, now: Long): Long {
        val end = visit.endOr(now)
        var cursor = visit.start
        var longest = 0L
        for (b in breaks.sortedBy { it.start }) {
            if (b.end < visit.start || b.start > end) continue
            val gapEnd = max(cursor, minOf(b.start, end))
            longest = max(longest, gapEnd - cursor)
            cursor = max(cursor, b.end)
        }
        return max(longest, end - cursor)
    }
}

// ---------------------------------------------------------------- Therapy

enum class TherapyWindow(val label: String) { MORNING("morning"), EVENING("evening"), OTHER("outside usual times") }

enum class TherapyState(val label: String) {
    NONE("No visit detected"),
    POSSIBLE("Possible visit (low confidence)"),
    LIKELY("Likely visit (phone inferred)"),
    REPORTED("You reported attending"),
    CONFIRMED("You confirmed a visit"),
    SKIPPED("Marked not today"),
}

data class TherapyVisit(
    val visit: PlaceVisit,
    val window: TherapyWindow,
    val dwellMinutes: Long,
    val qualifies: Boolean,
    /** true/false from the person's optional yes/no, null if unanswered. */
    val verdict: Boolean?,
)

data class TherapyDayStatus(
    val day: LocalDate,
    val state: TherapyState,
    val visits: List<TherapyVisit>,
    val best: TherapyVisit?,
) {
    /** Satisfied days get no further therapy reminders. */
    val satisfied: Boolean
        get() = state == TherapyState.LIKELY || state == TherapyState.CONFIRMED ||
            state == TherapyState.REPORTED || state == TherapyState.SKIPPED
}

object TherapyPlanner {

    fun window(start: Long, day: LocalDate, cal: CareCalendar, s: TherapySettings): TherapyWindow {
        val tol = s.startToleranceMinutes * MINUTE_MS
        val mStart = cal.at(day, s.morningEarliestStart) - tol
        val mEnd = cal.at(day, s.morningLatestStart) + tol
        val eStart = cal.at(day, s.eveningEarliestStart) - tol
        val eEnd = cal.at(day, s.eveningLatestStart) + tol
        return when (start) {
            in mStart..mEnd -> TherapyWindow.MORNING
            in eStart..eEnd -> TherapyWindow.EVENING
            else -> TherapyWindow.OTHER
        }
    }

    fun status(
        day: LocalDate,
        cal: CareCalendar,
        now: Long,
        visits: List<PlaceVisit>,
        events: List<CareEvent>,
        s: TherapySettings,
    ): TherapyDayStatus {
        val dayStart = cal.dayStart(day)
        val dayEnd = cal.dayEnd(day)
        val verdicts = events.filterIsInstance<TherapyVisitVerdict>()
        val therapyVisits = visits
            .filter { it.place == PlaceKind.THERAPY && it.start in dayStart until dayEnd }
            .map { v ->
                val w = window(v.start, day, cal, s)
                val dwell = (v.endOr(now) - v.start) / MINUTE_MS
                val qualifies = dwell >= s.minDwellMinutes && (w != TherapyWindow.OTHER || s.countVisitsOutsideWindows)
                val verdict = verdicts.filter { kotlin.math.abs(it.visitStart - v.start) <= MINUTE_MS }.maxByOrNull { it.at }?.attended
                TherapyVisit(v, w, dwell, qualifies, verdict)
            }
        val reported = events.any { it is TherapySelfReported && it.day == day }
        val skipped = events.any { it is TherapySkipped && it.day == day }
        val confirmed = therapyVisits.firstOrNull { it.verdict == true }
        val candidates = therapyVisits.filter { it.qualifies && it.verdict != false }
        val likely = candidates.filter { it.visit.confidence >= Confidence.MEDIUM }.maxByOrNull { it.dwellMinutes }
        val possible = candidates.maxByOrNull { it.dwellMinutes }
        val (state, best) = when {
            confirmed != null -> TherapyState.CONFIRMED to confirmed
            reported -> TherapyState.REPORTED to (likely ?: possible)
            likely != null -> TherapyState.LIKELY to likely
            possible != null -> TherapyState.POSSIBLE to possible
            skipped -> TherapyState.SKIPPED to null
            else -> TherapyState.NONE to null
        }
        return TherapyDayStatus(day, state, therapyVisits, best)
    }
}

// ---------------------------------------------------------------- Exercises

data class ExerciseCheckpoint(val index: Int, val at: Long, val expectedRounds: Int)

data class ExerciseStatus(
    val enabled: Boolean,
    val target: Int,
    val fullRounds: Int,
    val partialRounds: Int,
    val checkpoints: List<ExerciseCheckpoint>,
)

object ExercisePlanner {

    /** Reminder checkpoints spread evenly between the first and last reminder; checkpoint i expects i+1 rounds. */
    fun checkpoints(day: LocalDate, cal: CareCalendar, s: ExerciseSettings): List<ExerciseCheckpoint> {
        val n = s.roundsPerDay.coerceIn(1, 12)
        val first = cal.at(day, s.firstReminder)
        val last = cal.at(day, s.lastReminder)
        if (n == 1 || last <= first) return listOf(ExerciseCheckpoint(0, last.coerceAtLeast(first), n))
        return (0 until n).map { i -> ExerciseCheckpoint(i, first + (last - first) * i / (n - 1), i + 1) }
    }

    fun status(day: LocalDate, cal: CareCalendar, events: List<CareEvent>, s: ExerciseSettings): ExerciseStatus {
        val start = cal.dayStart(day)
        val end = cal.dayEnd(day)
        val rounds = events.filterIsInstance<ExerciseRound>().filter { it.at in start until end }
        return ExerciseStatus(
            enabled = s.enabled,
            target = s.roundsPerDay,
            fullRounds = rounds.count { !it.partial },
            partialRounds = rounds.count { it.partial },
            checkpoints = checkpoints(day, cal, s),
        )
    }

    /** The checkpoint to remind about now, if behind and not already reminded at or after it. */
    fun due(now: Long, status: ExerciseStatus, reminders: List<ExerciseReminderSent>, day: LocalDate): ExerciseCheckpoint? {
        if (!status.enabled) return null
        val passed = status.checkpoints.filter { it.at <= now && now - it.at <= 3 * HOUR_MS }.maxByOrNull { it.at } ?: return null
        if (status.fullRounds >= passed.expectedRounds) return null
        if (reminders.any { it.day == day && it.checkpoint >= passed.index }) return null
        return passed
    }
}

// ---------------------------------------------------------------- Medicines

data class DoseSlot(
    val key: String,
    val medication: Medication,
    val label: String,
    val quantity: String,
    val dueAt: Long,
    val windowEnd: Long,
)

enum class DoseState(val label: String) {
    UPCOMING("upcoming"), DUE("due"), TAKEN("taken"), SKIPPED("skipped"), NOT_CONFIRMED("not confirmed")
}

data class DoseStatus(val slot: DoseSlot, val state: DoseState, val confirmedAt: Long?)

object MedicinePlanner {

    /** "1+0+1" → ["1","0","1"]; also accepts "-" separators and fractions like "½" or "1/2". */
    fun parsePattern(pattern: String): List<String>? {
        val parts = pattern.trim().split('+', '-').map { it.trim() }
        if (parts.size !in 3..4 || parts.any { quantityValue(it) == null }) return null
        return parts
    }

    fun quantityValue(q: String): Double? {
        val t = q.trim().replace("½", ".5").replace("¼", ".25").replace("¾", ".75")
        if (t.isEmpty()) return null
        if ('/' in t) {
            val (a, b) = t.split('/', limit = 2).map { it.trim().toDoubleOrNull() ?: return null }
            return if (b == 0.0) null else a / b
        }
        return (if (t.startsWith(".")) "0$t" else t).toDoubleOrNull()
    }

    fun isActiveOn(med: Medication, day: LocalDate): Boolean {
        if (!med.active || day < med.startDate) return false
        val last = med.lastDay
        if (last != null && day > last) return false
        return ChronoUnit.DAYS.between(med.startDate, day) % med.everyNDays.coerceAtLeast(1) == 0L
    }

    fun slots(day: LocalDate, cal: CareCalendar, s: MedicineSettings): List<DoseSlot> {
        val window = s.confirmWindowMinutes * MINUTE_MS
        return s.medications.filter { isActiveOn(it, day) }.flatMap { med ->
            val times: List<Triple<String, String, LocalTime>> = if (med.customTimes.isNotEmpty()) {
                med.customTimes.sorted().map { Triple(it.hm(), "1", it) }
            } else {
                val parts = parsePattern(med.pattern) ?: return@flatMap emptyList()
                val meals = if (parts.size == 4) {
                    val afternoon = s.lunch.plusMinutes(ChronoUnit.MINUTES.between(s.lunch, s.dinner).coerceAtLeast(0) / 2)
                    listOf("morning" to s.breakfast, "noon" to s.lunch, "afternoon" to afternoon, "night" to s.dinner)
                } else {
                    listOf("morning" to s.breakfast, "noon" to s.lunch, "night" to s.dinner)
                }
                parts.zip(meals).filter { (q, _) -> (quantityValue(q) ?: 0.0) > 0.0 }.map { (q, meal) ->
                    val time = when (med.meal) {
                        MealRelation.BEFORE -> meal.second.minusMinutes(s.beforeMealMinutes.toLong())
                        MealRelation.AFTER -> meal.second.plusMinutes(s.afterMealMinutes.toLong())
                        MealRelation.WITH, MealRelation.ANY -> meal.second
                    }
                    Triple(meal.first, q, time)
                }
            }
            times.mapIndexed { i, (label, qty, time) ->
                val due = cal.at(day, time)
                DoseSlot("$day#${med.id}#$i", med, label, qty, due, due + window)
            }
        }.sortedBy { it.dueAt }
    }

    fun statuses(slots: List<DoseSlot>, events: List<CareEvent>, now: Long): List<DoseStatus> = slots.map { slot ->
        val taken = events.filterIsInstance<DoseTaken>().filter { it.slotKey == slot.key }.minByOrNull { it.at }
        val skipped = events.filterIsInstance<DoseSkipped>().filter { it.slotKey == slot.key }.maxByOrNull { it.at }
        when {
            taken != null -> DoseStatus(slot, DoseState.TAKEN, taken.at)
            skipped != null -> DoseStatus(slot, DoseState.SKIPPED, skipped.at)
            now < slot.dueAt -> DoseStatus(slot, DoseState.UPCOMING, null)
            now < slot.windowEnd -> DoseStatus(slot, DoseState.DUE, null)
            else -> DoseStatus(slot, DoseState.NOT_CONFIRMED, null)
        }
    }

    /** Medicines whose entered course ended in the last two days (so the app can ask once, not silently stop). */
    fun recentlyEndedCourses(day: LocalDate, s: MedicineSettings): List<Medication> =
        s.medications.filter { med ->
            val last = med.lastDay
            med.active && last != null && day > last && !day.isAfter(last.plusDays(2))
        }

    fun courseEnded(med: Medication, day: LocalDate): Boolean = med.lastDay?.let { day > it } == true

    fun medicationIdFromKey(key: String): String? = key.split('#').getOrNull(1)
}
