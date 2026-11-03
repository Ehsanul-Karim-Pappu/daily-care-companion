package app.dailycare.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.dailycare.DailyCareApp
import app.dailycare.TAG
import app.dailycare.careApp
import app.dailycare.core.ActionId
import app.dailycare.core.BreakSnoozed
import app.dailycare.core.CareCalendar
import app.dailycare.core.CareEngine
import app.dailycare.core.CareSettings
import app.dailycare.core.DaySummary
import app.dailycare.core.DoseSkipped
import app.dailycare.core.DoseTaken
import app.dailycare.core.EngineInput
import app.dailycare.core.EngineOutput
import app.dailycare.core.ExerciseRound
import app.dailycare.core.HOUR_MS
import app.dailycare.core.MINUTE_MS
import app.dailycare.core.MedicinePlanner
import app.dailycare.core.SignalSnapshot
import app.dailycare.core.TherapySelfReported
import app.dailycare.core.TherapySkipped
import app.dailycare.core.TherapyVisitVerdict
import app.dailycare.core.WalkSelfReported
import app.dailycare.receivers.AlarmReceiver
import app.dailycare.setup.Permissions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** Gathers inputs, runs the pure engine, and applies its output on the phone. */
object EngineRunner {
    private val mutex = Mutex()

    private fun input(app: DailyCareApp, settings: CareSettings, now: Long, snapshot: SignalSnapshot?): EngineInput {
        val repo = app.repository
        val zone = ZoneId.systemDefault()
        val cal = CareCalendar(zone, settings.dayBoundary)
        val dayStart = cal.dayStart(cal.dayOf(now))
        return EngineInput(
            now = now,
            zone = zone,
            settings = settings,
            events = repo.events(dayStart - 24 * HOUR_MS),
            placeState = repo.state.placeState,
            snapshot = snapshot,
            stepBins = repo.stepBins(dayStart - 3 * HOUR_MS, now + MINUTE_MS),
            transitions = repo.transitions(dayStart - 3 * HOUR_MS, now + MINUTE_MS),
            stepSensorAvailable = repo.state.stepSensorAvailable,
        )
    }

    /** One evaluation: update place, log walks, send due reminders, schedule the next wake-up. */
    suspend fun tick(context: Context, updatePlace: Boolean = true): EngineOutput = mutex.withLock {
        val app = context.careApp
        val repo = app.repository
        val settings = repo.settings.value
        val now = System.currentTimeMillis()
        val out = CareEngine.tick(input(app, settings, now, if (updatePlace) app.signals.snapshot(now) else null))
        repo.state.placeState = out.placeState
        repo.append(out.newEvents)
        out.cancel.forEach { app.notifier.cancel(it) }
        out.notify.forEach { app.notifier.post(it, settings) }
        AlarmScheduler.schedule(context, out.nextWakeAt)
        out
    }

    fun summary(context: Context): DaySummary {
        val app = context.careApp
        return CareEngine.summarize(input(app, app.repository.settings.value, System.currentTimeMillis(), null))
    }

    /** The previous [days] care days, most recent first. */
    fun history(context: Context, days: Int): List<DaySummary> {
        val app = context.careApp
        val settings = app.repository.settings.value
        val zone = ZoneId.systemDefault()
        val cal = CareCalendar(zone, settings.dayBoundary)
        val now = System.currentTimeMillis()
        val today = cal.dayOf(now)
        val oldest = today.minusDays(days.toLong())
        val events = app.repository.events(cal.dayStart(oldest) - 24 * HOUR_MS)
        return (1..days).map { today.minusDays(it.toLong()) }.map { day -> CareEngine.summarizeDay(day, now, zone, settings, events) }
    }
}

object AlarmScheduler {
    fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            2001,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Permissions.canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Things the person taps: in notifications, on the Today screen, or in the quick-settings tile. */
object UserActions {

    /** Records the action and returns a short confirmation message. */
    fun perform(context: Context, action: ActionId, arg: String = "", now: Long = System.currentTimeMillis()): String? {
        val repo = context.careApp.repository
        val s = repo.settings.value
        val cal = CareCalendar(ZoneId.systemDefault(), s.dayBoundary)
        val day = cal.dayOf(now)
        return when (action) {
            ActionId.BREAK_SNOOZE -> {
                repo.append(BreakSnoozed(now, now + s.breaks.snoozeMinutes * MINUTE_MS))
                "Break reminders snoozed for ${s.breaks.snoozeMinutes} min"
            }
            ActionId.BREAK_WALKED_WITHOUT_PHONE -> {
                repo.append(WalkSelfReported(now))
                "Break recorded (you reported it)"
            }
            ActionId.THERAPY_ATTENDED -> {
                repo.append(TherapySelfReported(now, day))
                "Therapy recorded for today"
            }
            ActionId.THERAPY_NOT_TODAY -> {
                repo.append(TherapySkipped(now, day))
                "No more therapy reminders today"
            }
            ActionId.THERAPY_VISIT_YES, ActionId.THERAPY_VISIT_NO -> {
                val start = arg.toLongOrNull() ?: return null
                repo.append(TherapyVisitVerdict(now, start, attended = action == ActionId.THERAPY_VISIT_YES))
                "Noted"
            }
            ActionId.EXERCISE_ROUND_DONE -> {
                repo.append(ExerciseRound(now))
                "Exercise round recorded"
            }
            ActionId.EXERCISE_PARTIAL -> {
                repo.append(ExerciseRound(now, partial = true))
                "Partial round recorded"
            }
            ActionId.DOSE_TAKEN, ActionId.DOSE_SKIPPED -> dose(context, action, arg, now, s, cal)
        }
    }

    private fun dose(context: Context, action: ActionId, slotKey: String, now: Long, s: CareSettings, cal: CareCalendar): String? {
        val repo = context.careApp.repository
        val medId = MedicinePlanner.medicationIdFromKey(slotKey) ?: return null
        val slotDay = runCatching { LocalDate.parse(slotKey.substringBefore('#')) }.getOrNull() ?: return null
        val slot = MedicinePlanner.slots(slotDay, cal, s.medicine).firstOrNull { it.key == slotKey }
        val taken = repo.events(cal.dayStart(slotDay) - 24 * HOUR_MS).map { it.event }
            .filterIsInstance<DoseTaken>().firstOrNull { it.slotKey == slotKey }
        if (taken != null) {
            // Guard against double-dosing from a second tap.
            return "Already recorded as taken at ${cal.hm(taken.at)}"
        }
        val label = slot?.label.orEmpty()
        val name = slot?.medication?.displayName ?: "medicine"
        val message: String
        if (action == ActionId.DOSE_TAKEN) {
            repo.append(DoseTaken(now, slotKey, medId, label))
            message = "$name ($label) recorded as taken"
        } else {
            repo.append(DoseSkipped(now, slotKey, medId, label))
            message = "$name ($label) recorded as skipped"
        }
        Log.i(TAG, message)
        return message
    }
}
