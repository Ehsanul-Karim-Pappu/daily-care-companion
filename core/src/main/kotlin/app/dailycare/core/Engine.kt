package app.dailycare.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class ReminderChannel { BREAK, THERAPY, EXERCISE, MEDICINE }

enum class ActionId {
    BREAK_SNOOZE,
    BREAK_WALKED_WITHOUT_PHONE,
    THERAPY_ATTENDED,
    THERAPY_NOT_TODAY,
    THERAPY_VISIT_YES,
    THERAPY_VISIT_NO,
    EXERCISE_ROUND_DONE,
    EXERCISE_PARTIAL,
    DOSE_TAKEN,
    DOSE_SKIPPED,
}

data class NotificationAction(val label: String, val action: ActionId, val arg: String = "")

data class NotificationRequest(
    val key: String,
    val channel: ReminderChannel,
    val title: String,
    val text: String,
    val actions: List<NotificationAction>,
    /** Posted while at work (may be vibrate-only). */
    val atWork: Boolean,
)

data class EngineInput(
    val now: Long,
    val zone: ZoneId,
    val settings: CareSettings,
    /** Stored events from at least the start of the previous care day until now. */
    val events: List<StoredEvent>,
    val placeState: PlaceMachineState,
    /** Current signals; null to evaluate without updating the place (e.g. for display). */
    val snapshot: SignalSnapshot?,
    val stepBins: Map<Long, Int> = emptyMap(),
    val transitions: List<MotionTransition> = emptyList(),
    val stepSensorAvailable: Boolean = true,
)

data class EngineOutput(
    val placeState: PlaceMachineState,
    val newEvents: List<CareEvent>,
    val notify: List<NotificationRequest>,
    val cancel: Set<String>,
    val nextWakeAt: Long,
    val summary: DaySummary,
)

object NotificationKeys {
    const val BREAK = "break"
    const val THERAPY_MORNING = "therapy_morning"
    const val THERAPY_EVENING = "therapy_evening"
    const val EXERCISE = "exercise"
    fun therapyAsk(visitStart: Long) = "therapy_ask_$visitStart"
    fun dose(slotKey: String) = "dose_$slotKey"
    fun course(medId: String) = "course_$medId"
}

/**
 * The whole decision process for one moment in time. Pure: the Android layer gathers inputs,
 * persists [EngineOutput.newEvents], posts/cancels notifications and schedules [EngineOutput.nextWakeAt].
 */
object CareEngine {

    fun tick(input: EngineInput): EngineOutput = run(input, forDisplay = false)

    /** Today's summary without changing anything (for the UI). */
    fun summarize(input: EngineInput): DaySummary = run(input.copy(snapshot = null), forDisplay = true).summary

    /** A past day's summary from stored events only. */
    fun summarizeDay(day: LocalDate, now: Long, zone: ZoneId, settings: CareSettings, events: List<StoredEvent>): DaySummary {
        val cal = CareCalendar(zone, settings.dayBoundary)
        return Summarizer.build(day, cal, now, settings, events, null, null)
    }

    private fun run(input: EngineInput, forDisplay: Boolean): EngineOutput {
        val s = input.settings
        val now = input.now
        val cal = CareCalendar(input.zone, s.dayBoundary)
        val day = cal.dayOf(now)
        val newEvents = mutableListOf<CareEvent>()
        val notify = mutableListOf<NotificationRequest>()
        val cancel = mutableSetOf<String>()
        val wakes = mutableListOf<Long>()

        // 1. Where am I?
        var placeState = input.placeState
        input.snapshot?.let { snap ->
            val res = PlaceResolver.resolve(snap, s.places, s.placeDetection)
            val (next, events) = PlaceStateMachine.update(placeState, res, now, s.placeDetection)
            placeState = next
            newEvents += events
        }

        // 2. Walks that have finished and aren't logged yet.
        val logged = input.events.map { it.event }.filterIsInstance<WalkDetected>()
        val unlogged = WalkDetector.detect(input.stepBins, input.transitions, input.stepSensorAvailable, cal.dayStart(day) - 2 * HOUR_MS, now, s.breaks)
            .filter { w -> w.activeMinutes >= 1 && logged.none { it.at < w.end && w.start < it.end } }
        unlogged.filter { WalkDetector.isFinal(it, now, s.breaks) }
            .forEach { newEvents += WalkDetected(it.start, it.end, it.steps, it.activeMinutes, it.qualifies, it.basis) }
        // A walk that already qualifies counts for break timing straight away, before it is final and logged.
        val pendingBreaks = unlogged.filter { it.qualifies && !WalkDetector.isFinal(it, now, s.breaks) }
            .map { BreakInterval(it.start, it.end, Provenance.INFERRED) }
        val observations = newEvents.toList()

        val events = input.events.map { it.event } + newEvents
        val visits = Visits.from(events, placeState)
        val work = visits.lastOrNull { it.place == PlaceKind.WORK && it.end == null }
        val atWork = work != null
        val quiet = s.reminders.isQuiet(cal.localTime(now))
        if (quiet) wakes += cal.nextOccurrence(now, s.reminders.quietEnd)

        // 3. Walking breaks at work.
        val moving = WalkDetector.movingNow(input.stepBins, input.transitions, input.stepSensorAvailable, now, s.breaks)
        val breakStatus = BreakPlanner.status(
            now, work, breakIntervals(events) + pendingBreaks,
            events.filterIsInstance<BreakReminderSent>(), events.filterIsInstance<BreakSnoozed>(), s.breaks,
        )
        if (!breakStatus.atWork || breakStatus.remindersSinceAnchor == 0) cancel += NotificationKeys.BREAK
        breakStatus.dueAt?.let { due ->
            when {
                now < due -> wakes += due
                moving -> wakes += now + 2 * MINUTE_MS
                quiet -> Unit
                else -> {
                    val minutes = (now - (breakStatus.anchor ?: now)) / MINUTE_MS
                    newEvents += BreakReminderSent(now, breakStatus.isFollowUp, suppressed = s.observeOnly)
                    if (!s.observeOnly) notify += breakReminder(minutes, breakStatus.isFollowUp, s)
                    wakes += now + s.breaks.followUpMinutes * MINUTE_MS
                }
            }
        }

        // 4. Therapy.
        if (s.therapy.enabled) {
            val th = TherapyPlanner.status(day, cal, now, visits, events, s.therapy)
            val sent = events.filterIsInstance<TherapyReminderSent>().filter { it.day == day }
            if (th.satisfied) {
                cancel += NotificationKeys.THERAPY_MORNING
                cancel += NotificationKeys.THERAPY_EVENING
            }
            fun reminder(kind: TherapyReminderKind, enabled: Boolean, atTime: java.time.LocalTime, untilTime: java.time.LocalTime, key: String) {
                if (!enabled) return
                val at = cal.at(day, atTime)
                val until = cal.at(day, untilTime)
                when {
                    now < at -> wakes += at
                    now >= until -> cancel += key
                    th.satisfied || sent.any { it.kind == kind } || quiet -> Unit
                    else -> {
                        newEvents += TherapyReminderSent(now, day, kind, suppressed = s.observeOnly)
                        if (!s.observeOnly) notify += therapyReminder(kind, th, cal, s, atWork)
                        wakes += until
                    }
                }
            }
            reminder(TherapyReminderKind.MORNING_NUDGE, s.therapy.morningNudgeEnabled, s.therapy.morningNudgeTime, s.therapy.morningLatestStart, NotificationKeys.THERAPY_MORNING)
            reminder(TherapyReminderKind.EVENING, s.therapy.eveningReminderEnabled, s.therapy.eveningReminderTime, s.therapy.eveningLatestStart, NotificationKeys.THERAPY_EVENING)

            if (s.therapy.askToConfirmVisits && !quiet) {
                val asked = events.filterIsInstance<TherapyVisitAsked>()
                th.visits
                    .filter { it.qualifies && it.verdict == null && it.visit.end != null && now - it.visit.end <= 6 * HOUR_MS }
                    .filter { tv -> asked.none { abs(it.visitStart - tv.visit.start) <= MINUTE_MS } }
                    .maxByOrNull { it.visit.start }
                    ?.let { tv ->
                        newEvents += TherapyVisitAsked(now, tv.visit.start)
                        notify += therapyAsk(tv, cal, atWork)
                    }
            }
        } else {
            cancel += NotificationKeys.THERAPY_MORNING
            cancel += NotificationKeys.THERAPY_EVENING
        }

        // 5. Exercises.
        val ex = ExercisePlanner.status(day, cal, events, s.exercise)
        if (ex.enabled) {
            val due = ExercisePlanner.due(now, ex, events.filterIsInstance<ExerciseReminderSent>(), day)
            if (due != null && !quiet) {
                newEvents += ExerciseReminderSent(now, day, due.index)
                notify += exerciseReminder(ex, s, atWork)
            }
            ex.checkpoints.filter { it.at > now }.minByOrNull { it.at }?.let { wakes += it.at }
            val latestPassed = ex.checkpoints.filter { it.at <= now }.maxByOrNull { it.at }
            if (latestPassed == null || ex.fullRounds >= latestPassed.expectedRounds) cancel += NotificationKeys.EXERCISE
        } else {
            cancel += NotificationKeys.EXERCISE
        }

        // 6. Medicines (yesterday's late slots can still be open just after the day boundary).
        val quietForMeds = quiet && s.reminders.quietHoursIncludeMedicines
        val slots = MedicinePlanner.slots(day.minusDays(1), cal, s.medicine).filter { it.windowEnd > now } +
            MedicinePlanner.slots(day, cal, s.medicine)
        val doseReminders = events.filterIsInstance<DoseReminderSent>()
        for (st in MedicinePlanner.statuses(slots, events, now)) {
            val key = NotificationKeys.dose(st.slot.key)
            when (st.state) {
                DoseState.UPCOMING -> wakes += st.slot.dueAt
                DoseState.DUE -> {
                    val sentFor = doseReminders.count { it.slotKey == st.slot.key }
                    val followUpAt = st.slot.dueAt + s.medicine.followUpMinutes * MINUTE_MS
                    when {
                        quietForMeds -> Unit
                        sentFor == 0 -> {
                            newEvents += DoseReminderSent(now, st.slot.key, followUp = false)
                            notify += doseReminder(st.slot, false, cal, atWork)
                            wakes += followUpAt
                        }
                        sentFor == 1 && now >= followUpAt -> {
                            newEvents += DoseReminderSent(now, st.slot.key, followUp = true)
                            notify += doseReminder(st.slot, true, cal, atWork)
                        }
                        sentFor == 1 -> wakes += followUpAt
                    }
                    wakes += st.slot.windowEnd
                }
                DoseState.TAKEN, DoseState.SKIPPED, DoseState.NOT_CONFIRMED -> cancel += key
            }
        }
        if (!quiet) {
            MedicinePlanner.recentlyEndedCourses(day, s.medicine)
                .filter { med -> events.none { it is CourseEndNotice && it.medicationId == med.id } }
                .forEach { med ->
                    newEvents += CourseEndNotice(now, med.id)
                    notify += courseEnded(med)
                }
        }

        wakes += cal.dayEnd(day)
        val nextWake = (wakes.filter { it > now } + (now + 30 * MINUTE_MS)).min().coerceAtLeast(now + 30_000)

        val summaryEvents = input.events + (if (forDisplay) observations else newEvents).map { StoredEvent(null, it) }
        val summary = Summarizer.build(day, cal, now, s, summaryEvents, placeState, breakStatus)
        return EngineOutput(placeState, newEvents, notify, cancel, nextWake, summary)
    }

    // ------------------------------------------------------------ notification texts

    private fun breakReminder(minutes: Long, followUp: Boolean, s: CareSettings) = NotificationRequest(
        key = NotificationKeys.BREAK,
        channel = ReminderChannel.BREAK,
        title = if (followUp) "Still time for a short walk" else "Time for a short walk",
        text = "No walking break detected for ${formatMinutes(minutes)} at work. " +
            "Take your phone with you so the walk is recognised (${s.breaks.minWalkMinutes}+ min counts).",
        actions = listOf(
            NotificationAction("Snooze ${s.breaks.snoozeMinutes} min", ActionId.BREAK_SNOOZE),
            NotificationAction("Walked without phone", ActionId.BREAK_WALKED_WITHOUT_PHONE),
        ),
        atWork = true,
    )

    private fun therapyReminder(kind: TherapyReminderKind, th: TherapyDayStatus, cal: CareCalendar, s: CareSettings, atWork: Boolean): NotificationRequest {
        val possible = th.best?.takeIf { th.state == TherapyState.POSSIBLE }
        val text = when {
            possible != null -> "A possible visit was detected at ${cal.hm(possible.visit.start)}, but the phone isn't sure. " +
                "If you already had therapy today, tap \"I went\"."
            kind == TherapyReminderKind.MORNING_NUDGE -> "Sessions starting by ${s.therapy.morningLatestStart.hm()} count as this morning's."
            else -> "No therapy visit recorded today. The evening window starts at ${s.therapy.eveningEarliestStart.hm()}."
        }
        return NotificationRequest(
            key = if (kind == TherapyReminderKind.MORNING_NUDGE) NotificationKeys.THERAPY_MORNING else NotificationKeys.THERAPY_EVENING,
            channel = ReminderChannel.THERAPY,
            title = if (kind == TherapyReminderKind.MORNING_NUDGE) "Therapy this morning?" else "Therapy today?",
            text = text,
            actions = listOf(
                NotificationAction("I went", ActionId.THERAPY_ATTENDED),
                NotificationAction("Not today", ActionId.THERAPY_NOT_TODAY),
            ),
            atWork = atWork,
        )
    }

    private fun therapyAsk(tv: TherapyVisit, cal: CareCalendar, atWork: Boolean): NotificationRequest {
        val end = tv.visit.end ?: tv.visit.start
        return NotificationRequest(
            key = NotificationKeys.therapyAsk(tv.visit.start),
            channel = ReminderChannel.THERAPY,
            title = "Was this a therapy session?",
            text = "You were at the therapy place ${cal.hm(tv.visit.start)}–${cal.hm(end)} (${formatMinutes(tv.dwellMinutes)}). " +
                "Answering is optional; it already counts as a likely visit.",
            actions = listOf(
                NotificationAction("Yes", ActionId.THERAPY_VISIT_YES, tv.visit.start.toString()),
                NotificationAction("No", ActionId.THERAPY_VISIT_NO, tv.visit.start.toString()),
            ),
            atWork = atWork,
        )
    }

    private fun exerciseReminder(ex: ExerciseStatus, s: CareSettings, atWork: Boolean) = NotificationRequest(
        key = NotificationKeys.EXERCISE,
        channel = ReminderChannel.EXERCISE,
        title = "Exercises",
        text = "${ex.fullRounds} of ${ex.target} rounds confirmed today." +
            if (s.exercise.movements.isNotEmpty()) " " + s.exercise.movements.joinToString(", ") { it.name } else "",
        actions = listOf(NotificationAction("Round done", ActionId.EXERCISE_ROUND_DONE)),
        atWork = atWork,
    )

    private fun doseReminder(slot: DoseSlot, followUp: Boolean, cal: CareCalendar, atWork: Boolean): NotificationRequest {
        val med = slot.medication
        val form = med.form.ifBlank { "dose" }
        return NotificationRequest(
            key = NotificationKeys.dose(slot.key),
            channel = ReminderChannel.MEDICINE,
            title = (if (followUp) "Not confirmed yet: " else "Medicine: ") + med.displayName,
            text = "${slot.quantity} $form · ${slot.label} · ${med.meal.label} (due ${cal.hm(slot.dueAt)})",
            actions = listOf(
                NotificationAction("Taken", ActionId.DOSE_TAKEN, slot.key),
                NotificationAction("Skipped", ActionId.DOSE_SKIPPED, slot.key),
            ),
            atWork = atWork,
        )
    }

    private fun courseEnded(med: Medication) = NotificationRequest(
        key = NotificationKeys.course(med.id),
        channel = ReminderChannel.MEDICINE,
        title = "Medicine course ended?",
        text = "The plan you entered for ${med.displayName} ended on ${med.lastDay}. Reminders have stopped. " +
            "If your doctor extended it, update the medicine in Settings.",
        actions = emptyList(),
        atWork = false,
    )
}
