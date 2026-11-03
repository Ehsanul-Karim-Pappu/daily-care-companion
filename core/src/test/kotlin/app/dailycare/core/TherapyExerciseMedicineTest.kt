package app.dailycare.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TherapyPlannerTest {
    private val s = TherapySettings()

    private fun visit(startH: Int, startM: Int, minutes: Int, conf: Confidence = Confidence.MEDIUM) =
        PlaceVisit(PlaceKind.THERAPY, t(startH, startM), t(startH, startM) + minutes * MINUTE_MS, conf, "x")

    @Test
    fun `a session starting just before 6 30 is a morning visit`() {
        assertEquals(TherapyWindow.MORNING, TherapyPlanner.window(t(6, 25), DAY, CAL, s))
        // Arrival detected a few minutes late still counts (tolerance).
        assertEquals(TherapyWindow.MORNING, TherapyPlanner.window(t(6, 36), DAY, CAL, s))
        assertEquals(TherapyWindow.OTHER, TherapyPlanner.window(t(7, 0), DAY, CAL, s))
        assertEquals(TherapyWindow.EVENING, TherapyPlanner.window(t(21, 15), DAY, CAL, s))
    }

    @Test
    fun `a 40 minute morning visit is likely and satisfies the day`() {
        val st = TherapyPlanner.status(DAY, CAL, t(12), listOf(visit(5, 45, 40)), emptyList(), s)
        assertEquals(TherapyState.LIKELY, st.state)
        assertTrue(st.satisfied)
        assertEquals(TherapyWindow.MORNING, st.best!!.window)
    }

    @Test
    fun `walking past the clinic is not a visit`() {
        val st = TherapyPlanner.status(DAY, CAL, t(12), listOf(visit(5, 45, 4)), emptyList(), s)
        assertEquals(TherapyState.NONE, st.state)
        assertFalse(st.satisfied)
    }

    @Test
    fun `low confidence visits are only possible and don't silence reminders`() {
        val st = TherapyPlanner.status(DAY, CAL, t(12), listOf(visit(5, 45, 40, Confidence.LOW)), emptyList(), s)
        assertEquals(TherapyState.POSSIBLE, st.state)
        assertFalse(st.satisfied)
    }

    @Test
    fun `saying no to a visit removes it`() {
        val v = visit(5, 45, 40)
        val st = TherapyPlanner.status(DAY, CAL, t(12), listOf(v), listOf(TherapyVisitVerdict(t(7), v.start, false)), s)
        assertEquals(TherapyState.NONE, st.state)
    }

    @Test
    fun `self report and skip are recorded as such`() {
        assertEquals(TherapyState.REPORTED, TherapyPlanner.status(DAY, CAL, t(22), emptyList(), listOf(TherapySelfReported(t(22), DAY)), s).state)
        assertEquals(TherapyState.SKIPPED, TherapyPlanner.status(DAY, CAL, t(22), emptyList(), listOf(TherapySkipped(t(22), DAY)), s).state)
    }

    @Test
    fun `an evening session running past midnight belongs to that day`() {
        val v = PlaceVisit(PlaceKind.THERAPY, t(23, 20), t(0, 0, DAY.plusDays(1)), Confidence.HIGH, "x")
        val st = TherapyPlanner.status(DAY, CAL, t(1, 0, DAY.plusDays(1)), listOf(v), emptyList(), s)
        assertEquals(TherapyState.LIKELY, st.state)
        assertEquals(TherapyWindow.EVENING, st.best!!.window)
    }
}

class ExercisePlannerTest {
    private val s = ExerciseSettings(enabled = true, roundsPerDay = 3, firstReminder = LocalTime.of(10, 0), lastReminder = LocalTime.of(22, 0),
        movements = listOf(Movement("m1", "Movement 1")))

    @Test
    fun `checkpoints are spread evenly`() {
        val cps = ExercisePlanner.checkpoints(DAY, CAL, s)
        assertEquals(listOf(t(10), t(16), t(22)), cps.map { it.at })
        assertEquals(listOf(1, 2, 3), cps.map { it.expectedRounds })
    }

    @Test
    fun `no reminder when already caught up, one when behind`() {
        val done = listOf(ExerciseRound(t(9)), ExerciseRound(t(14)))
        val st = ExercisePlanner.status(DAY, CAL, done, s)
        assertNull(ExercisePlanner.due(t(16, 5), st, emptyList(), DAY))
        val behind = ExercisePlanner.status(DAY, CAL, done.take(1), s)
        assertEquals(1, ExercisePlanner.due(t(16, 5), behind, emptyList(), DAY)!!.index)
        assertNull(ExercisePlanner.due(t(16, 30), behind, listOf(ExerciseReminderSent(t(16, 5), DAY, 1)), DAY))
    }

    @Test
    fun `partial rounds are counted separately`() {
        val st = ExercisePlanner.status(DAY, CAL, listOf(ExerciseRound(t(9)), ExerciseRound(t(10), partial = true)), s)
        assertEquals(1, st.fullRounds)
        assertEquals(1, st.partialRounds)
    }
}

class MedicinePlannerTest {
    private val start = LocalDate.of(2026, 11, 1)
    private val med = Medication("m1", "Paracetamol", "Example", "500 mg", pattern = "1+0+1", startDate = start, durationDays = 5)
    private val s = MedicineSettings(medications = listOf(med))

    @Test
    fun `patterns are parsed in Bangladeshi prescription style`() {
        assertEquals(listOf("1", "0", "1"), MedicinePlanner.parsePattern("1+0+1"))
        assertEquals(listOf("½", "0", "1"), MedicinePlanner.parsePattern("½ + 0 + 1"))
        assertEquals(0.5, MedicinePlanner.quantityValue("1/2"))
        assertEquals(4, MedicinePlanner.parsePattern("1+1+1+1")!!.size)
        assertNull(MedicinePlanner.parsePattern("twice daily"))
    }

    @Test
    fun `1+0+1 after meals gives morning and night slots`() {
        val slots = MedicinePlanner.slots(DAY, CAL, s)
        assertEquals(listOf("morning", "night"), slots.map { it.label })
        assertEquals(t(8, 15), slots[0].dueAt)
        assertEquals(t(21, 45), slots[1].dueAt)
    }

    @Test
    fun `course ends after its duration and weekly medicines skip days`() {
        assertTrue(MedicinePlanner.isActiveOn(med, start.plusDays(4)))
        assertFalse(MedicinePlanner.isActiveOn(med, start.plusDays(5)))
        val weekly = med.copy(everyNDays = 7, durationDays = null)
        assertTrue(MedicinePlanner.isActiveOn(weekly, start.plusDays(7)))
        assertFalse(MedicinePlanner.isActiveOn(weekly, start.plusDays(8)))
        assertEquals(listOf(med), MedicinePlanner.recentlyEndedCourses(start.plusDays(5), s))
        assertTrue(MedicinePlanner.recentlyEndedCourses(start.plusDays(9), s).isEmpty())
    }

    @Test
    fun `doses are never inferred - unconfirmed is shown as not confirmed`() {
        val slots = MedicinePlanner.slots(DAY, CAL, s)
        val st = MedicinePlanner.statuses(slots, listOf(DoseTaken(t(8, 20), slots[0].key, "m1")), t(23, 59))
        assertEquals(DoseState.TAKEN, st[0].state)
        assertEquals(DoseState.DUE, st[1].state)
        val later = MedicinePlanner.statuses(slots, emptyList(), t(2, 0, DAY.plusDays(1)))
        assertEquals(DoseState.NOT_CONFIRMED, later[1].state)
    }

    @Test
    fun `no medicines means no slots`() {
        assertTrue(MedicinePlanner.slots(DAY, CAL, MedicineSettings()).isEmpty())
    }
}
