@file:UseSerializers(LocalDateSerializer::class)

package app.dailycare.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Who or what an entry in the log comes from. Inferences are never upgraded to confirmations. */
enum class Provenance(val label: String) {
    USER_CONFIRMED("You confirmed"),
    USER_REPORTED("You reported"),
    INFERRED("Phone inferred"),
    SYSTEM("App"),
}

@Serializable
sealed class CareEvent {
    abstract val at: Long
}

@Serializable @SerialName("place_entered")
data class PlaceEntered(override val at: Long, val place: PlaceKind, val confidence: Confidence, val basis: String) : CareEvent()

@Serializable @SerialName("place_exited")
data class PlaceExited(override val at: Long, val place: PlaceKind, val confidence: Confidence) : CareEvent()

/** A walk seen by the phone. [at] is the start. Short ones are logged too (with qualifies=false) for calibration. */
@Serializable @SerialName("walk")
data class WalkDetected(
    override val at: Long,
    val end: Long,
    val steps: Int?,
    val activeMinutes: Int,
    val qualifies: Boolean,
    val basis: WalkBasis,
) : CareEvent()

@Serializable @SerialName("walk_self_reported")
data class WalkSelfReported(override val at: Long) : CareEvent()

/** [suppressed] = observe mode: logged as "would have reminded", no notification shown. */
@Serializable @SerialName("break_reminder")
data class BreakReminderSent(override val at: Long, val followUp: Boolean, val suppressed: Boolean = false) : CareEvent()

@Serializable @SerialName("break_snoozed")
data class BreakSnoozed(override val at: Long, val until: Long) : CareEvent()

@Serializable
enum class TherapyReminderKind { MORNING_NUDGE, EVENING }

@Serializable @SerialName("therapy_reminder")
data class TherapyReminderSent(override val at: Long, val day: LocalDate, val kind: TherapyReminderKind, val suppressed: Boolean = false) : CareEvent()

@Serializable @SerialName("therapy_visit_asked")
data class TherapyVisitAsked(override val at: Long, val visitStart: Long) : CareEvent()

@Serializable @SerialName("therapy_visit_verdict")
data class TherapyVisitVerdict(override val at: Long, val visitStart: Long, val attended: Boolean) : CareEvent()

@Serializable @SerialName("therapy_self_reported")
data class TherapySelfReported(override val at: Long, val day: LocalDate) : CareEvent()

@Serializable @SerialName("therapy_skipped")
data class TherapySkipped(override val at: Long, val day: LocalDate) : CareEvent()

@Serializable @SerialName("exercise_round")
data class ExerciseRound(override val at: Long, val partial: Boolean = false) : CareEvent()

@Serializable @SerialName("exercise_reminder")
data class ExerciseReminderSent(override val at: Long, val day: LocalDate, val checkpoint: Int) : CareEvent()

@Serializable @SerialName("dose_taken")
data class DoseTaken(override val at: Long, val slotKey: String, val medicationId: String, val slotLabel: String = "") : CareEvent()

@Serializable @SerialName("dose_skipped")
data class DoseSkipped(override val at: Long, val slotKey: String, val medicationId: String, val slotLabel: String = "") : CareEvent()

@Serializable @SerialName("dose_reminder")
data class DoseReminderSent(override val at: Long, val slotKey: String, val followUp: Boolean) : CareEvent()

@Serializable @SerialName("course_end_notice")
data class CourseEndNotice(override val at: Long, val medicationId: String) : CareEvent()

/** Monitoring was not running between [at] and [end] (app stopped by the system, phone off, ...). */
@Serializable @SerialName("monitoring_gap")
data class MonitoringGap(override val at: Long, val end: Long, val reason: String) : CareEvent()

val CareEvent.provenance: Provenance
    get() = when (this) {
        is PlaceEntered, is PlaceExited, is WalkDetected -> Provenance.INFERRED
        is WalkSelfReported, is TherapySelfReported -> Provenance.USER_REPORTED
        is TherapyVisitVerdict, is ExerciseRound, is DoseTaken, is DoseSkipped,
        is TherapySkipped, is BreakSnoozed -> Provenance.USER_CONFIRMED
        is BreakReminderSent, is TherapyReminderSent, is TherapyVisitAsked, is ExerciseReminderSent,
        is DoseReminderSent, is CourseEndNotice, is MonitoringGap -> Provenance.SYSTEM
    }

/** User-entered events may be removed (e.g. a mistaken tap). Inferences and app records may not. */
val CareEvent.isUserEntered: Boolean
    get() = provenance == Provenance.USER_CONFIRMED || provenance == Provenance.USER_REPORTED

/** An event as stored, with its database id (null for events not yet saved). */
data class StoredEvent(val id: Long?, val event: CareEvent)

val CareJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "event"
}

fun CareEvent.toJson(): String = CareJson.encodeToString(CareEvent.serializer(), this)

fun careEventFromJson(json: String): CareEvent? =
    runCatching { CareJson.decodeFromString(CareEvent.serializer(), json) }.getOrNull()

fun CareEvent.typeName(): String = this::class.simpleName ?: "Event"

fun CareSettings.toJson(): String = CareJson.encodeToString(CareSettings.serializer(), this)

fun careSettingsFromJson(json: String?): CareSettings =
    json?.let { runCatching { CareJson.decodeFromString(CareSettings.serializer(), it) }.getOrNull() } ?: CareSettings()
