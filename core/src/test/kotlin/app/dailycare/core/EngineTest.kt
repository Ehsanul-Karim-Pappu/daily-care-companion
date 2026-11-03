package app.dailycare.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End-to-end scenarios through the engine, the way the phone would drive it. */
class EngineTest {

    private class Phone(var settings: CareSettings) {
        val events = mutableListOf<StoredEvent>()
        var state = PlaceMachineState()
        val bins = mutableMapOf<Long, Int>()
        val posted = mutableListOf<NotificationRequest>()

        fun tick(now: Long, snapshot: SignalSnapshot?): EngineOutput {
            val out = CareEngine.tick(EngineInput(now, ZONE, settings, events.toList(), state, snapshot, bins.toMap(), emptyList(), true))
            state = out.placeState
            out.newEvents.forEach { events += StoredEvent(events.size + 1L, it) }
            posted += out.notify
            return out
        }

        /** Tick every [stepMin] minutes from [from] to [to] with the same signals. */
        fun run(from: Long, to: Long, stepMin: Int = 1, snap: (Long) -> SignalSnapshot?) {
            var now = from
            while (now <= to) {
                tick(now, snap(now))
                now += stepMin * MINUTE_MS
            }
        }
    }

    private fun office(now: Long) = SignalSnapshot(now, wifi = ConnectedWifi("OfficeNet", "00:11:22:33:44:55"))

    @Test
    fun `typical workday - arrive, sit, reminder, walk, no repeat reminder`() {
        val phone = Phone(settings())
        phone.run(t(9, 0), t(9, 49)) { office(it) }
        assertTrue(phone.posted.none { it.channel == ReminderChannel.BREAK })

        phone.run(t(9, 50), t(9, 50)) { office(it) }
        val reminder = phone.posted.single { it.channel == ReminderChannel.BREAK }
        assertTrue("50 min" in reminder.text, reminder.text)

        // Walk 6 minutes with the phone, then sit again.
        phone.bins += walkBins(t(9, 55), 6, 100)
        phone.run(t(9, 51), t(10, 40)) { office(it) }
        val walks = phone.events.map { it.event }.filterIsInstance<WalkDetected>()
        assertEquals(1, walks.size)
        assertTrue(walks.single().qualifies)
        // Follow-up would have been at 10:00, but the walk began at 09:55 (moving) and reset the timer.
        assertEquals(1, phone.posted.count { it.channel == ReminderChannel.BREAK })

        // Next reminder 50 minutes after the walk ended (10:01 -> 10:51).
        phone.run(t(10, 41), t(10, 52)) { office(it) }
        assertEquals(2, phone.posted.count { it.channel == ReminderChannel.BREAK })
    }

    @Test
    fun `an unprompted walk counts without any reminder`() {
        val phone = Phone(settings())
        phone.bins += walkBins(t(9, 30), 5, 100)
        phone.run(t(9, 0), t(10, 20)) { office(it) }
        assertTrue(phone.posted.none { it.channel == ReminderChannel.BREAK })
        val summary = phone.tick(t(10, 21), office(t(10, 21))).summary
        assertEquals(1, summary.breaksInferred)
    }

    @Test
    fun `observe mode logs would-be reminders without notifying`() {
        val phone = Phone(settings().copy(observeOnly = true))
        phone.run(t(9, 0), t(10, 0)) { office(it) }
        assertTrue(phone.posted.isEmpty())
        val logged = phone.events.map { it.event }.filterIsInstance<BreakReminderSent>()
        assertTrue(logged.isNotEmpty() && logged.all { it.suppressed })
    }

    @Test
    fun `morning therapy visit means no evening reminder`() {
        val phone = Phone(settings())
        val clinic = WifiScan(0, THERAPY.fingerprint)
        phone.run(t(5, 30), t(5, 44), 2) { SignalSnapshot(it, wifi = ConnectedWifi("HomeNet", null)) }
        phone.run(t(5, 45), t(6, 25), 2) { SignalSnapshot(it, scan = clinic.copy(atMillis = it)) }
        phone.run(t(6, 27), t(7, 0), 2) { SignalSnapshot(it, wifi = ConnectedWifi("HomeNet", null)) }
        // Optional yes/no was offered once.
        assertEquals(1, phone.posted.count { it.title.startsWith("Was this a therapy") })

        phone.run(t(20, 40), t(21, 30), 5) { SignalSnapshot(it, wifi = ConnectedWifi("HomeNet", null)) }
        assertTrue(phone.posted.none { it.key == NotificationKeys.THERAPY_EVENING })
        val summary = phone.tick(t(21, 31), null).summary
        assertEquals(TherapyState.LIKELY, summary.therapy.state)
    }

    @Test
    fun `no morning visit gives exactly one evening reminder`() {
        val phone = Phone(settings())
        phone.run(t(20, 30), t(21, 30), 5) { SignalSnapshot(it, wifi = ConnectedWifi("HomeNet", null)) }
        assertEquals(1, phone.posted.count { it.key == NotificationKeys.THERAPY_EVENING })
    }

    @Test
    fun `load-shedding at home in the morning is not a therapy visit`() {
        val phone = Phone(settings())
        phone.run(t(5, 0), t(5, 20), 2) { SignalSnapshot(it, wifi = ConnectedWifi("HomeNet", null)) }
        // Router off for an hour; no location fix, no geofences.
        phone.run(t(5, 22), t(6, 30), 2) { SignalSnapshot(it) }
        assertTrue(phone.events.none { (it.event as? PlaceEntered)?.place == PlaceKind.THERAPY })
    }

    @Test
    fun `medicine reminders follow up once and doses are only taken when confirmed`() {
        val med = Medication("m1", "Generic", "Brand", "10 mg", pattern = "1+0+0", meal = MealRelation.AFTER, startDate = LocalDate.of(2026, 11, 1))
        val phone = Phone(settings().copy(medicine = MedicineSettings(medications = listOf(med))))
        phone.run(t(8, 0), t(9, 0), 5) { null }
        assertEquals(2, phone.posted.count { it.channel == ReminderChannel.MEDICINE })
        val summary = phone.tick(t(12, 0), null).summary
        assertEquals(DoseState.NOT_CONFIRMED, summary.doses.single().state)

        val slotKey = summary.doses.single().slot.key
        phone.events += StoredEvent(999, DoseTaken(t(12, 1), slotKey, "m1", "morning"))
        val after = phone.tick(t(12, 2), null)
        assertEquals(DoseState.TAKEN, after.summary.doses.single().state)
        assertTrue(NotificationKeys.dose(slotKey) in after.cancel)
    }

    @Test
    fun `exercise reminders only when behind`() {
        val s = settings().copy(exercise = ExerciseSettings(enabled = true, roundsPerDay = 3, movements = listOf(Movement("a", "A"))))
        val phone = Phone(s)
        phone.events += StoredEvent(500, ExerciseRound(t(9, 30)))
        phone.run(t(9, 55), t(10, 5), 5) { null }
        assertTrue(phone.posted.none { it.channel == ReminderChannel.EXERCISE })
        phone.run(t(15, 45), t(15, 50), 5) { null }
        assertEquals(1, phone.posted.count { it.channel == ReminderChannel.EXERCISE })
    }

    @Test
    fun `timeline separates what you confirmed from what the phone inferred`() {
        val phone = Phone(settings())
        phone.run(t(9, 0), t(9, 5)) { office(it) }
        phone.events += StoredEvent(700, ExerciseRound(t(9, 3)))
        val tl = phone.tick(t(9, 6), office(t(9, 6))).summary.timeline
        val arrived = tl.first { it.text.startsWith("Arrived at Work") }
        assertEquals(Provenance.INFERRED, arrived.provenance)
        assertFalse(arrived.removable)
        val round = tl.first { it.text == "Exercise round done" }
        assertEquals(Provenance.USER_CONFIRMED, round.provenance)
        assertTrue(round.removable)
    }

    @Test
    fun `settings and events round-trip through JSON`() {
        val s = settings().copy(exercise = ExerciseSettings(enabled = true, movements = listOf(Movement("a", "A", 10, 5, true))))
        assertEquals(s, careSettingsFromJson(s.toJson()))
        val e: CareEvent = WalkDetected(t(10), t(10, 5), 500, 5, true, WalkBasis.STEPS)
        assertEquals(e, careEventFromJson(e.toJson()))
        assertEquals(CareSettings(), careSettingsFromJson("not json"))
    }

    @Test
    fun `csv export has one row per event`() {
        val csv = CsvExport.render(stored(ExerciseRound(t(9)), WalkSelfReported(t(10))), settings(), ZONE)
        assertEquals(3, csv.trim().lines().size)
        assertTrue("You confirmed" in csv)
    }
}
