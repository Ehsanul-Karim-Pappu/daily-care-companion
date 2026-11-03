package app.dailycare.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepAccumulatorTest {
    @Test
    fun `first sample is only a baseline`() {
        val (_, inc) = StepAccumulator.add(StepAccumulatorState(), t(10), 5000)
        assertTrue(inc.isEmpty())
    }

    @Test
    fun `short spans are spread over the minutes they cover`() {
        val (_, inc) = StepAccumulator.add(StepAccumulatorState(t(10), 5000), t(10, 2), 5200)
        assertEquals(200, inc.values.sum())
        assertEquals(100, inc[t(10)])
        assertEquals(100, inc[t(10, 1)])
    }

    @Test
    fun `steps after a long silence are placed just before the sample`() {
        // Phone slept at the desk for 45 minutes, then 300 steps were reported at 10:45.
        val (_, inc) = StepAccumulator.add(StepAccumulatorState(t(10), 5000), t(10, 45), 5300)
        assertEquals(300, inc.values.sum())
        assertTrue(inc.keys.all { it >= t(10, 42) }, "steps should be in the last few minutes, got ${inc.keys}")
    }

    @Test
    fun `a counter reset (reboot) takes a new baseline instead of inventing steps`() {
        val (state, inc) = StepAccumulator.add(StepAccumulatorState(t(10), 5000), t(11), 40)
        assertTrue(inc.isEmpty())
        assertEquals(40, state.lastTotal)
    }
}

class WalkDetectorTest {
    private val s = BreakSettings(minWalkMinutes = 3, minSteps = 250, activeMinuteSteps = 30, mergeGapMinutes = 1)

    @Test
    fun `carrying the phone to the printer is not a break`() {
        val walks = WalkDetector.fromSteps(walkBins(t(10), 1, 60), t(9), t(11), s)
        assertEquals(1, walks.size)
        assertFalse(walks.single().qualifies)
    }

    @Test
    fun `a five minute walk qualifies`() {
        val w = WalkDetector.fromSteps(walkBins(t(10), 5, 100), t(9), t(11), s).single()
        assertTrue(w.qualifies)
        assertEquals(5, w.activeMinutes)
        assertEquals(500, w.steps)
        assertEquals(t(10), w.start)
        assertEquals(t(10, 5), w.end)
    }

    @Test
    fun `a short pause to talk does not split a walk`() {
        val bins = walkBins(t(10), 2, 100) + mapOf(t(10, 2) to 5) + walkBins(t(10, 3), 2, 100)
        val w = WalkDetector.fromSteps(bins, t(9), t(11), s).single()
        assertEquals(4, w.activeMinutes)
        assertEquals(405, w.steps)
    }

    @Test
    fun `without a step counter, activity recognition is used`() {
        val tr = listOf(
            MotionTransition(t(10), Motion.WALKING, true),
            MotionTransition(t(10, 6), Motion.WALKING, false),
        )
        val w = WalkDetector.fromActivity(tr, t(9), t(11), s).single()
        assertTrue(w.qualifies)
        assertNull(w.steps)
        assertEquals(WalkBasis.ACTIVITY, w.basis)
    }

    @Test
    fun `a walk is final only after the merge gap has passed`() {
        val w = WalkDetector.fromSteps(walkBins(t(10), 5, 100), t(9), t(10, 6), s).single()
        assertFalse(WalkDetector.isFinal(w, t(10, 6), s))
        assertTrue(WalkDetector.isFinal(w, t(10, 7), s))
    }
}

class BreakPlannerTest {
    private val s = BreakSettings(inactivityMinutes = 50, followUpMinutes = 10, maxFollowUps = 1)
    private val work = PlaceVisit(PlaceKind.WORK, t(9), null, Confidence.HIGH, "wifi")

    @Test
    fun `first reminder is due one interval after arriving`() {
        val st = BreakPlanner.status(t(9, 30), work, emptyList(), emptyList(), emptyList(), s)
        assertEquals(t(9, 50), st.dueAt)
        assertFalse(st.isFollowUp)
    }

    @Test
    fun `an unprompted walk resets the timer`() {
        val walk = BreakInterval(t(9, 30), t(9, 36), Provenance.INFERRED)
        val st = BreakPlanner.status(t(9, 40), work, listOf(walk), emptyList(), emptyList(), s)
        assertEquals(t(10, 26), st.dueAt)
    }

    @Test
    fun `one follow-up then quiet until the next break`() {
        val r1 = BreakReminderSent(t(9, 50), false)
        val st1 = BreakPlanner.status(t(9, 55), work, emptyList(), listOf(r1), emptyList(), s)
        assertEquals(t(10, 0), st1.dueAt)
        assertTrue(st1.isFollowUp)
        val r2 = BreakReminderSent(t(10, 0), true)
        assertNull(BreakPlanner.status(t(10, 5), work, emptyList(), listOf(r1, r2), emptyList(), s).dueAt)
    }

    @Test
    fun `snooze delays the reminder`() {
        val r1 = BreakReminderSent(t(9, 50), false)
        val snooze = BreakSnoozed(t(9, 51), t(10, 21))
        val st = BreakPlanner.status(t(10), work, emptyList(), listOf(r1), listOf(snooze), s)
        assertEquals(t(10, 21), st.dueAt)
    }

    @Test
    fun `not at work means no reminders`() {
        val st = BreakPlanner.status(t(10), null, emptyList(), emptyList(), emptyList(), s)
        assertFalse(st.atWork)
        assertNull(st.dueAt)
    }

    @Test
    fun `longest stretch accounts for breaks`() {
        val visit = work.copy(end = t(12))
        val breaks = listOf(BreakInterval(t(10), t(10, 5), Provenance.INFERRED))
        assertEquals(115 * MINUTE_MS, BreakPlanner.longestStretch(visit, breaks, t(12)))
    }
}
