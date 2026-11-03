@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class)

package app.dailycare.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime

/**
 * Everything the person configures once. Numbers that a clinician should decide
 * (break interval, walk length, exercise rounds, medicines) are placeholders until set.
 */
@Serializable
data class CareSettings(
    val dayBoundary: LocalTime = LocalTime.of(3, 0),
    /** First-week calibration: detect and log, but don't send break or therapy reminders. */
    val observeOnly: Boolean = true,
    val places: List<Place> = emptyList(),
    val placeDetection: PlaceDetectionSettings = PlaceDetectionSettings(),
    val breaks: BreakSettings = BreakSettings(),
    val therapy: TherapySettings = TherapySettings(),
    val exercise: ExerciseSettings = ExerciseSettings(),
    val medicine: MedicineSettings = MedicineSettings(),
    val reminders: ReminderSettings = ReminderSettings(),
) {
    fun place(kind: PlaceKind): Place? = places.firstOrNull { it.kind == kind }

    fun placeName(kind: PlaceKind): String = place(kind)?.name?.ifBlank { null } ?: kind.label

    fun withPlace(place: Place): CareSettings = copy(places = places.filterNot { it.kind == place.kind } + place)

    fun withoutPlace(kind: PlaceKind): CareSettings = copy(places = places.filterNot { it.kind == kind })
}

@Serializable
data class PlaceDetectionSettings(
    /** A medium/low-confidence arrival must persist this long before it counts. Wi-Fi arrivals count at once. */
    val enterDelayMinutes: Int = 2,
    /** Leaving must persist this long (absorbs brief Wi-Fi drops). */
    val leaveDelayMinutes: Int = 5,
    /** With no location signal at all (e.g. Wi-Fi off during load-shedding), keep the last place this long. */
    val unknownHoldMinutes: Int = 30,
    val wifiScanMaxAgeMinutes: Int = 15,
    val locationFixMaxAgeMinutes: Int = 10,
    val minWifiSimilarity: Double = 0.3,
    val minWifiMargin: Double = 0.1,
)

@Serializable
data class BreakSettings(
    val enabled: Boolean = true,
    /** Remind after this long at work without a qualifying walk. Placeholder until agreed with the physiotherapist. */
    val inactivityMinutes: Int = 50,
    /** A walk counts as a break only if it has at least this many walking minutes... */
    val minWalkMinutes: Int = 3,
    /** ...and at least this many steps. */
    val minSteps: Int = 250,
    /** A minute counts as "walking" when it has at least this many steps. */
    val activeMinuteSteps: Int = 30,
    /** Pauses up to this many minutes (e.g. stopping to talk) don't split a walk. */
    val mergeGapMinutes: Int = 1,
    val followUpMinutes: Int = 10,
    val maxFollowUps: Int = 1,
    val snoozeMinutes: Int = 30,
)

@Serializable
data class TherapySettings(
    val enabled: Boolean = true,
    val morningEarliestStart: LocalTime = LocalTime.of(4, 30),
    /** Morning sessions must *start* by this time. */
    val morningLatestStart: LocalTime = LocalTime.of(6, 30),
    val eveningEarliestStart: LocalTime = LocalTime.of(21, 0),
    val eveningLatestStart: LocalTime = LocalTime.of(23, 30),
    /** Arrival detection can lag; allow this much slack around window edges. */
    val startToleranceMinutes: Int = 10,
    val typicalSessionMinutes: Int = 30,
    /** Time at the therapy place needed before a visit is called "likely". */
    val minDwellMinutes: Int = 20,
    val countVisitsOutsideWindows: Boolean = true,
    /** After a likely visit, offer an optional yes/no. Ignoring it is fine. */
    val askToConfirmVisits: Boolean = true,
    val morningNudgeEnabled: Boolean = false,
    val morningNudgeTime: LocalTime = LocalTime.of(5, 30),
    val eveningReminderEnabled: Boolean = true,
    val eveningReminderTime: LocalTime = LocalTime.of(20, 45),
)

@Serializable
data class Movement(
    val id: String,
    val name: String,
    val reps: Int? = 10,
    val holdSeconds: Int? = null,
    val perSide: Boolean = false,
    val notes: String = "",
) {
    fun describe(): String = buildList {
        reps?.let { add("$it reps" + if (perSide) " each side" else "") }
        holdSeconds?.let { add("hold ${it}s") }
        if (notes.isNotBlank()) add(notes)
    }.joinToString(" · ")
}

@Serializable
data class ExerciseSettings(
    /** Off until the physiotherapist's routine is entered. */
    val enabled: Boolean = false,
    val movements: List<Movement> = emptyList(),
    val roundsPerDay: Int = 3,
    val firstReminder: LocalTime = LocalTime.of(10, 0),
    val lastReminder: LocalTime = LocalTime.of(21, 30),
)

@Serializable
enum class MealRelation(val label: String) {
    BEFORE("before meal"), AFTER("after meal"), WITH("with meal"), ANY("any time")
}

@Serializable
data class Medication(
    val id: String,
    val genericName: String,
    val brandName: String = "",
    val strength: String = "",
    val form: String = "tablet",
    /** Bangladeshi prescription style: morning+noon+night, e.g. "1+0+1", "½+0+1". Four parts adds an afternoon dose. */
    val pattern: String = "1+0+1",
    val meal: MealRelation = MealRelation.AFTER,
    /** If set, these clock times replace the meal-based times (one unit each). */
    val customTimes: List<LocalTime> = emptyList(),
    /** 1 = daily, 7 = weekly. */
    val everyNDays: Int = 1,
    val startDate: LocalDate,
    /** Null means "until a doctor stops it". */
    val durationDays: Int? = null,
    val prescriber: String = "",
    val prescriptionDate: LocalDate? = null,
    val notes: String = "",
    val active: Boolean = true,
) {
    val displayName: String
        get() = listOf(brandName.ifBlank { genericName }, strength).filter { it.isNotBlank() }.joinToString(" ")

    val lastDay: LocalDate? get() = durationDays?.let { startDate.plusDays(it - 1L) }
}

@Serializable
data class MedicineSettings(
    val breakfast: LocalTime = LocalTime.of(8, 0),
    val lunch: LocalTime = LocalTime.of(13, 30),
    val dinner: LocalTime = LocalTime.of(21, 30),
    val beforeMealMinutes: Int = 30,
    val afterMealMinutes: Int = 15,
    /** After this long without a tap, a dose is shown as "not confirmed" (never as missed or taken). */
    val confirmWindowMinutes: Int = 180,
    val followUpMinutes: Int = 30,
    val medications: List<Medication> = emptyList(),
)

@Serializable
data class ReminderSettings(
    val quietHoursEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(23, 30),
    val quietEnd: LocalTime = LocalTime.of(5, 0),
    val quietHoursIncludeMedicines: Boolean = false,
    /** Reminders posted while at work vibrate without sound. */
    val vibrateOnlyAtWork: Boolean = true,
) {
    fun isQuiet(t: LocalTime): Boolean {
        if (!quietHoursEnabled || quietStart == quietEnd) return false
        return if (quietStart < quietEnd) t >= quietStart && t < quietEnd else t >= quietStart || t < quietEnd
    }
}

/** Human-readable problems with a configuration; empty when everything is consistent. */
fun CareSettings.validate(): List<String> = buildList {
    with(breaks) {
        if (inactivityMinutes < 10) add("Break interval should be at least 10 minutes.")
        if (minWalkMinutes < 1) add("Minimum walk must be at least 1 minute.")
        if (minWalkMinutes >= inactivityMinutes) add("Minimum walk is longer than the break interval.")
        if (activeMinuteSteps < 1) add("Steps per walking minute must be at least 1.")
    }
    with(therapy) {
        if (morningEarliestStart >= morningLatestStart) add("Therapy morning window: earliest start must be before latest start.")
        if (eveningEarliestStart >= eveningLatestStart && eveningLatestStart >= dayBoundary) {
            add("Therapy evening window: earliest start must be before latest start.")
        }
        if (minDwellMinutes < 5) add("Therapy minimum stay should be at least 5 minutes.")
    }
    with(exercise) {
        if (enabled && movements.isEmpty()) add("Exercises are on but no movements are entered yet.")
        if (roundsPerDay !in 1..12) add("Exercise rounds per day should be between 1 and 12.")
    }
    medicine.medications.filter { it.active }.forEach { med ->
        if (med.customTimes.isEmpty() && MedicinePlanner.parsePattern(med.pattern) == null) {
            add("${med.displayName}: dose pattern \"${med.pattern}\" is not understood (use e.g. 1+0+1).")
        }
        if (med.everyNDays < 1) add("${med.displayName}: 'every N days' must be at least 1.")
    }
    PlaceKind.entries.forEach { kind ->
        val p = place(kind)
        if (p != null && !p.hasLocation && p.knownNetworks.isEmpty() && p.fingerprint.isEmpty()) {
            add("${p.name}: saved without location or Wi-Fi, so it can't be recognised.")
        }
    }
}
