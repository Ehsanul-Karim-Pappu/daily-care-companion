package app.dailycare.core

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CareCalendarTest {
    @Test
    fun `times before the boundary belong to the previous care day`() {
        assertEquals(DAY, CAL.dayOf(t(23, 50)))
        assertEquals(DAY, CAL.dayOf(t(0, 20, DAY.plusDays(1))))
        assertEquals(DAY.plusDays(1), CAL.dayOf(t(5, 30, DAY.plusDays(1))))
    }

    @Test
    fun `at() puts after-midnight times on the next date`() {
        assertEquals(t(0, 30, DAY.plusDays(1)), CAL.at(DAY, LocalTime.of(0, 30)))
        assertEquals(t(21, 0), CAL.at(DAY, LocalTime.of(21, 0)))
    }

    @Test
    fun `quiet hours wrap around midnight`() {
        val r = ReminderSettings(quietStart = LocalTime.of(23, 30), quietEnd = LocalTime.of(5, 0))
        assertTrue(r.isQuiet(LocalTime.of(23, 45)))
        assertTrue(r.isQuiet(LocalTime.of(4, 59)))
        assertFalse(r.isQuiet(LocalTime.of(5, 0)))
        assertFalse(r.isQuiet(LocalTime.of(12, 0)))
    }
}

class PlaceResolverTest {
    private val cfg = PlaceDetectionSettings()
    private val places = listOf(HOME, WORK, THERAPY)

    @Test
    fun `connected office Wi-Fi means work with high confidence`() {
        val r = PlaceResolver.resolve(SignalSnapshot(t(9), wifi = ConnectedWifi("OfficeNet", null)), places, cfg)
        assertEquals(PlaceKind.WORK, r.place)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `clinic Wi-Fi fingerprint identifies therapy without connecting`() {
        val scan = WifiScan(t(5, 40), listOf(WifiAccessPoint("cc:00:00:00:00:01", rssi = -55), WifiAccessPoint("cc:00:00:00:00:02", rssi = -68)))
        val r = PlaceResolver.resolve(SignalSnapshot(t(5, 41), scan = scan), places, cfg)
        assertEquals(PlaceKind.THERAPY, r.place)
    }

    @Test
    fun `an imprecise fix that reaches both home and therapy is low confidence`() {
        val midway = LocationFix(t(5, 40), HOME_LAT + 0.0012, HOME_LON, accuracyMeters = 120.0)
        val r = PlaceResolver.resolve(SignalSnapshot(t(5, 41), fix = midway), places, cfg)
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test
    fun `a precise fix at the therapy place is confident`() {
        val fix = LocationFix(t(5, 40), THERAPY.latitude!!, THERAPY.longitude!!, accuracyMeters = 15.0)
        val r = PlaceResolver.resolve(SignalSnapshot(t(5, 41), fix = fix), places, cfg)
        assertEquals(PlaceKind.THERAPY, r.place)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `no signal at all is not the same as away`() {
        val r = PlaceResolver.resolve(SignalSnapshot(t(10)), places, cfg)
        assertNull(r.place)
        assertFalse(r.hasEvidence)
    }

    @Test
    fun `stale fixes are ignored`() {
        val fix = LocationFix(t(9), WORK.latitude!!, WORK.longitude!!, 10.0)
        val r = PlaceResolver.resolve(SignalSnapshot(t(10), fix = fix), places, cfg)
        assertFalse(r.hasEvidence)
    }
}

class PlaceStateMachineTest {
    private val cfg = PlaceDetectionSettings()
    private val atWork = PlaceResolution(PlaceKind.WORK, Confidence.HIGH, "wifi", true)
    private val away = PlaceResolution(null, Confidence.MEDIUM, "not at a saved place", true)
    private val noSignal = PlaceResolution(null, Confidence.LOW, "no location signal", false)

    @Test
    fun `wifi arrival counts immediately`() {
        val (s, ev) = PlaceStateMachine.update(PlaceMachineState(), atWork, t(9), cfg)
        assertEquals(PlaceKind.WORK, s.current)
        assertEquals(listOf<CareEvent>(PlaceEntered(t(9), PlaceKind.WORK, Confidence.HIGH, "wifi")), ev)
    }

    @Test
    fun `load-shedding Wi-Fi loss with no other signal does not end the work visit for a while`() {
        var s = PlaceStateMachine.update(PlaceMachineState(), atWork, t(9), cfg).first
        s = PlaceStateMachine.update(s, atWork, t(11), cfg).first
        val (held, ev) = PlaceStateMachine.update(s, noSignal, t(11, 20), cfg)
        assertEquals(PlaceKind.WORK, held.current)
        assertTrue(ev.isEmpty())
        val (left, ev2) = PlaceStateMachine.update(held, noSignal, t(11, 31), cfg)
        assertNull(left.current)
        assertEquals(PlaceExited(t(11), PlaceKind.WORK, Confidence.HIGH), ev2.single())
    }

    @Test
    fun `leaving needs to persist and is back-dated`() {
        var s = PlaceStateMachine.update(PlaceMachineState(), atWork, t(9), cfg).first
        s = PlaceStateMachine.update(s, away, t(13, 0), cfg).first
        assertEquals(PlaceKind.WORK, s.current)
        // Back before the delay: nothing happened.
        s = PlaceStateMachine.update(s, atWork, t(13, 2), cfg).first
        assertEquals(PlaceKind.WORK, s.current)
        s = PlaceStateMachine.update(s, away, t(13, 10), cfg).first
        val (left, ev) = PlaceStateMachine.update(s, away, t(13, 16), cfg)
        assertNull(left.current)
        assertEquals(PlaceExited(t(13, 10), PlaceKind.WORK, Confidence.HIGH), ev.single())
    }

    @Test
    fun `visits pair arrivals and departures`() {
        val visits = Visits.from(
            listOf(
                PlaceEntered(t(9), PlaceKind.WORK, Confidence.MEDIUM, "x"),
                PlaceExited(t(13), PlaceKind.WORK, Confidence.HIGH),
                PlaceEntered(t(14), PlaceKind.WORK, Confidence.HIGH, "y"),
            ),
            PlaceMachineState(current = PlaceKind.WORK, since = t(14), confidence = Confidence.HIGH),
        )
        assertEquals(2, visits.size)
        assertEquals(Confidence.HIGH, visits[0].confidence)
        assertNull(visits[1].end)
    }
}

class PlaceSeparationTest {
    @Test
    fun `distinct Wi-Fi makes a nearby therapy place distinguishable`() {
        val a = PlaceSeparation.assess(HOME, THERAPY)
        assertEquals(SeparationLevel.GOOD, a.level)
        assertTrue(a.distanceMeters!! in 240.0..260.0)
    }

    @Test
    fun `very close places without Wi-Fi are poor`() {
        val close = THERAPY.copy(latitude = HOME_LAT + 0.0005, fingerprint = emptyList())
        assertEquals(SeparationLevel.POOR, PlaceSeparation.assess(HOME.copy(fingerprint = emptyList()), close).level)
    }
}
