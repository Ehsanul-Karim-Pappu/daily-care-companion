package app.dailycare.core

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

enum class TimelineCategory { PLACE, MOVEMENT, THERAPY, EXERCISE, MEDICINE, SYSTEM }

data class TimelineItem(
    val at: Long,
    val text: String,
    val provenance: Provenance,
    val category: TimelineCategory,
    val eventId: Long?,
    /** User-entered items can be removed (mistaken taps). */
    val removable: Boolean,
)

data class DaySummary(
    val day: LocalDate,
    val dayLabel: String,
    val now: Long,
    val currentPlace: PlaceVisit?,
    val workVisits: List<PlaceVisit>,
    val workMinutes: Long,
    val breakStatus: BreakStatus?,
    val breaksInferred: Int,
    val breaksReported: Int,
    val shortMovements: Int,
    val longestStretchMinutes: Long?,
    val breakReminders: Int,
    val breakRemindersSuppressed: Int,
    val therapy: TherapyDayStatus,
    val exercise: ExerciseStatus,
    val doses: List<DoseStatus>,
    val endedCourses: List<Medication>,
    val gaps: List<MonitoringGap>,
    val timeline: List<TimelineItem>,
    val observeOnly: Boolean,
)

/** Qualifying walks (phone) and walks you reported, as break intervals. */
fun breakIntervals(events: List<CareEvent>): List<BreakInterval> =
    events.mapNotNull { e ->
        when {
            e is WalkDetected && e.qualifies -> BreakInterval(e.at, e.end, Provenance.INFERRED)
            e is WalkSelfReported -> BreakInterval(e.at, e.at, Provenance.USER_REPORTED)
            else -> null
        }
    }

object Summarizer {

    fun build(
        day: LocalDate,
        cal: CareCalendar,
        now: Long,
        settings: CareSettings,
        stored: List<StoredEvent>,
        placeState: PlaceMachineState?,
        breakStatus: BreakStatus?,
    ): DaySummary {
        val events = stored.map { it.event }
        val dayStart = cal.dayStart(day)
        val dayEnd = cal.dayEnd(day)
        val effectiveNow = min(now, dayEnd)
        val visits = Visits.from(events, placeState)
        val dayVisits = visits.filter { it.start < dayEnd && it.endOr(effectiveNow) > dayStart }
        val workVisits = dayVisits.filter { it.place == PlaceKind.WORK }
            .map { it.copy(start = max(it.start, dayStart), end = it.end?.let { e -> min(e, dayEnd) }) }
        val workMinutes = workVisits.sumOf { (it.endOr(effectiveNow) - it.start).coerceAtLeast(0) } / MINUTE_MS
        fun duringWork(t: Long) = workVisits.any { t >= it.start && t <= it.endOr(effectiveNow) }

        val walks = events.filterIsInstance<WalkDetected>()
        val breaks = breakIntervals(events)
        val workBreaks = breaks.filter { duringWork(it.end) }
        val longest = workVisits.maxOfOrNull { BreakPlanner.longestStretch(it, breaks, effectiveNow) }?.div(MINUTE_MS)
        val inDay = { t: Long -> t in dayStart until dayEnd }
        val reminders = events.filterIsInstance<BreakReminderSent>().filter { inDay(it.at) }

        val slots = MedicinePlanner.slots(day, cal, settings.medicine)
        val doses = MedicinePlanner.statuses(slots, events, effectiveNow)

        return DaySummary(
            day = day,
            dayLabel = cal.dayLabel(day),
            now = now,
            currentPlace = if (now < dayEnd) visits.lastOrNull { it.end == null } else null,
            workVisits = workVisits,
            workMinutes = workMinutes,
            breakStatus = breakStatus,
            breaksInferred = workBreaks.count { it.provenance == Provenance.INFERRED },
            breaksReported = workBreaks.count { it.provenance == Provenance.USER_REPORTED },
            shortMovements = walks.count { !it.qualifies && duringWork(it.end) },
            longestStretchMinutes = longest,
            breakReminders = reminders.count { !it.suppressed },
            breakRemindersSuppressed = reminders.count { it.suppressed },
            therapy = TherapyPlanner.status(day, cal, effectiveNow, visits, events, settings.therapy),
            exercise = ExercisePlanner.status(day, cal, events, settings.exercise),
            doses = doses,
            endedCourses = settings.medicine.medications.filter { MedicinePlanner.courseEnded(it, day) && it.active },
            gaps = events.filterIsInstance<MonitoringGap>().filter { it.at < dayEnd && it.end > dayStart },
            timeline = timeline(stored, doses, cal, settings, dayStart, dayEnd, effectiveNow),
            observeOnly = settings.observeOnly,
        )
    }

    private fun timeline(
        stored: List<StoredEvent>,
        doses: List<DoseStatus>,
        cal: CareCalendar,
        settings: CareSettings,
        dayStart: Long,
        dayEnd: Long,
        now: Long,
    ): List<TimelineItem> {
        val items = stored.filter { it.event.at in dayStart until dayEnd }.mapNotNull { se ->
            val e = se.event
            val (text, category) = describe(e, cal, settings) ?: return@mapNotNull null
            TimelineItem(e.at, text, e.provenance, category, se.id, se.id != null && e.isUserEntered)
        }
        val unconfirmed = doses.filter { it.state == DoseState.NOT_CONFIRMED && it.slot.windowEnd <= now }.map {
            TimelineItem(
                it.slot.windowEnd,
                "No confirmation for ${it.slot.medication.displayName} (${it.slot.label}, due ${cal.hm(it.slot.dueAt)})",
                Provenance.SYSTEM,
                TimelineCategory.MEDICINE,
                null,
                false,
            )
        }
        return (items + unconfirmed).sortedBy { it.at }
    }

    fun describe(e: CareEvent, cal: CareCalendar, settings: CareSettings): Pair<String, TimelineCategory>? {
        val hm = cal::hm
        fun medName(id: String) = settings.medicine.medications.firstOrNull { it.id == id }?.displayName ?: "medicine"
        return when (e) {
            is PlaceEntered -> "Arrived at ${settings.placeName(e.place)} — ${e.basis}" to TimelineCategory.PLACE
            is PlaceExited -> "Left ${settings.placeName(e.place)}" to TimelineCategory.PLACE
            is WalkDetected -> if (e.qualifies) {
                val steps = e.steps?.let { " · $it steps" } ?: " · from activity sensor"
                "Walking break ${hm(e.at)}–${hm(e.end)} · ${e.activeMinutes} min$steps" to TimelineCategory.MOVEMENT
            } else {
                null
            }
            is WalkSelfReported -> "Walked without phone" to TimelineCategory.MOVEMENT
            is BreakReminderSent -> when {
                e.suppressed -> "Would have sent a break reminder (observe mode)"
                e.followUp -> "Break reminder (follow-up)"
                else -> "Break reminder"
            } to TimelineCategory.MOVEMENT
            is BreakSnoozed -> "Snoozed break reminders until ${hm(e.until)}" to TimelineCategory.MOVEMENT
            is TherapyReminderSent -> {
                val what = if (e.kind == TherapyReminderKind.MORNING_NUDGE) "Morning therapy nudge" else "Evening therapy reminder"
                (if (e.suppressed) "Would have sent: $what (observe mode)" else what) to TimelineCategory.THERAPY
            }
            is TherapyVisitAsked -> null
            is TherapyVisitVerdict -> (
                if (e.attended) "Visit at ${hm(e.visitStart)} was therapy" else "Visit at ${hm(e.visitStart)} was not therapy"
                ) to TimelineCategory.THERAPY
            is TherapySelfReported -> "Attended therapy" to TimelineCategory.THERAPY
            is TherapySkipped -> "No therapy today" to TimelineCategory.THERAPY
            is ExerciseRound -> (if (e.partial) "Partial exercise round" else "Exercise round done") to TimelineCategory.EXERCISE
            is ExerciseReminderSent -> "Exercise reminder" to TimelineCategory.EXERCISE
            is DoseTaken -> "Took ${medName(e.medicationId)} (${e.slotLabel})" to TimelineCategory.MEDICINE
            is DoseSkipped -> "Skipped ${medName(e.medicationId)} (${e.slotLabel})" to TimelineCategory.MEDICINE
            is DoseReminderSent -> null
            is CourseEndNotice -> "Asked whether ${medName(e.medicationId)} course was extended" to TimelineCategory.MEDICINE
            is MonitoringGap -> "No data ${hm(e.at)}–${hm(e.end)} (${e.reason})" to TimelineCategory.SYSTEM
        }
    }
}
