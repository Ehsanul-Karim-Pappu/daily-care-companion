package app.dailycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dailycare.core.Medication
import app.dailycare.core.Movement
import app.dailycare.core.validate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var movementDialog by remember { mutableStateOf<Movement?>(null) }
    var newMovement by remember { mutableStateOf(false) }
    var medicationDialog by remember { mutableStateOf<Medication?>(null) }
    var newMedication by remember { mutableStateOf(false) }
    val problems = remember(s) { s.validate() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        if (problems.isNotEmpty()) item { Banner(problems.joinToString("\n"), warning = true) }

        item {
            SectionCard("General") {
                SwitchSetting(
                    "Observe week",
                    s.observeOnly,
                    "Detect and log, but don't send break or therapy reminders yet. Medicine and exercise reminders still work.",
                ) { v -> vm.updateSettings { it.copy(observeOnly = v) } }
                TimeSetting("Day starts at", s.dayBoundary) { v -> vm.updateSettings { it.copy(dayBoundary = v) } }
                Muted("Anything before this time counts for the previous day (e.g. a late therapy session).")
            }
        }

        item {
            val b = s.breaks
            fun set(f: (app.dailycare.core.BreakSettings) -> app.dailycare.core.BreakSettings) = vm.updateSettings { it.copy(breaks = f(it.breaks)) }
            SectionCard("Walking breaks at work") {
                SwitchSetting("Break reminders", b.enabled) { v -> set { it.copy(enabled = v) } }
                IntSetting("Remind after (minutes without a break)", b.inactivityMinutes, 10..240) { v -> set { it.copy(inactivityMinutes = v) } }
                IntSetting("Minimum walk (minutes)", b.minWalkMinutes, 1..60) { v -> set { it.copy(minWalkMinutes = v) } }
                IntSetting("Minimum steps in a walk", b.minSteps, 0..5000) { v -> set { it.copy(minSteps = v) } }
                IntSetting("Steps for a minute to count as walking", b.activeMinuteSteps, 5..200) { v -> set { it.copy(activeMinuteSteps = v) } }
                IntSetting("Pause allowed inside a walk (minutes)", b.mergeGapMinutes, 0..10) { v -> set { it.copy(mergeGapMinutes = v) } }
                IntSetting("Follow-up after (minutes)", b.followUpMinutes, 1..120) { v -> set { it.copy(followUpMinutes = v) } }
                IntSetting("Number of follow-ups", b.maxFollowUps, 0..5) { v -> set { it.copy(maxFollowUps = v) } }
                IntSetting("Snooze length (minutes)", b.snoozeMinutes, 5..180) { v -> set { it.copy(snoozeMinutes = v) } }
                Muted("Starting values only. Agree the interval and walk length with your physiotherapist.")
            }
        }

        item {
            val t = s.therapy
            fun set(f: (app.dailycare.core.TherapySettings) -> app.dailycare.core.TherapySettings) = vm.updateSettings { it.copy(therapy = f(it.therapy)) }
            SectionCard("Therapy") {
                SwitchSetting("Track therapy visits", t.enabled) { v -> set { it.copy(enabled = v) } }
                Muted("One session a day, in the morning or the evening. Times are when the session starts.")
                TimeSetting("Morning: earliest start", t.morningEarliestStart) { v -> set { it.copy(morningEarliestStart = v) } }
                TimeSetting("Morning: latest start", t.morningLatestStart) { v -> set { it.copy(morningLatestStart = v) } }
                TimeSetting("Evening: earliest start", t.eveningEarliestStart) { v -> set { it.copy(eveningEarliestStart = v) } }
                TimeSetting("Evening: latest start", t.eveningLatestStart) { v -> set { it.copy(eveningLatestStart = v) } }
                IntSetting("Typical session (minutes)", t.typicalSessionMinutes, 5..240) { v -> set { it.copy(typicalSessionMinutes = v) } }
                IntSetting("Time there needed to count (minutes)", t.minDwellMinutes, 5..240) { v -> set { it.copy(minDwellMinutes = v) } }
                IntSetting("Arrival tolerance (minutes)", t.startToleranceMinutes, 0..60) { v -> set { it.copy(startToleranceMinutes = v) } }
                SwitchSetting("Count visits outside these times", t.countVisitsOutsideWindows) { v -> set { it.copy(countVisitsOutsideWindows = v) } }
                SwitchSetting("Ask \"was this therapy?\" after a visit", t.askToConfirmVisits, "Optional; ignoring it is fine.") { v ->
                    set { it.copy(askToConfirmVisits = v) }
                }
                SwitchSetting("Morning nudge", t.morningNudgeEnabled) { v -> set { it.copy(morningNudgeEnabled = v) } }
                if (t.morningNudgeEnabled) TimeSetting("Morning nudge at", t.morningNudgeTime) { v -> set { it.copy(morningNudgeTime = v) } }
                SwitchSetting("Evening reminder if no visit yet", t.eveningReminderEnabled) { v -> set { it.copy(eveningReminderEnabled = v) } }
                if (t.eveningReminderEnabled) TimeSetting("Evening reminder at", t.eveningReminderTime) { v -> set { it.copy(eveningReminderTime = v) } }
            }
        }

        item {
            val r = s.reminders
            fun set(f: (app.dailycare.core.ReminderSettings) -> app.dailycare.core.ReminderSettings) = vm.updateSettings { it.copy(reminders = f(it.reminders)) }
            SectionCard("Reminders") {
                SwitchSetting("Vibrate only at work", r.vibrateOnlyAtWork) { v -> set { it.copy(vibrateOnlyAtWork = v) } }
                SwitchSetting("Quiet hours", r.quietHoursEnabled) { v -> set { it.copy(quietHoursEnabled = v) } }
                if (r.quietHoursEnabled) {
                    TimeSetting("Quiet from", r.quietStart) { v -> set { it.copy(quietStart = v) } }
                    TimeSetting("Quiet until", r.quietEnd) { v -> set { it.copy(quietEnd = v) } }
                    SwitchSetting("Also silence medicine reminders", r.quietHoursIncludeMedicines) { v -> set { it.copy(quietHoursIncludeMedicines = v) } }
                }
                Muted("Each reminder type also has its own channel in Android's notification settings.")
            }
        }

        item {
            val e = s.exercise
            fun set(f: (app.dailycare.core.ExerciseSettings) -> app.dailycare.core.ExerciseSettings) = vm.updateSettings { it.copy(exercise = f(it.exercise)) }
            SectionCard("Exercises") {
                SwitchSetting("Exercise tracking", e.enabled, "Turn on once your physiotherapist's routine is entered.") { v -> set { it.copy(enabled = v) } }
                IntSetting("Rounds per day", e.roundsPerDay, 1..12) { v -> set { it.copy(roundsPerDay = v) } }
                TimeSetting("First reminder", e.firstReminder) { v -> set { it.copy(firstReminder = v) } }
                TimeSetting("Last reminder", e.lastReminder) { v -> set { it.copy(lastReminder = v) } }
                Muted("Reminders only come when you're behind. Do the rounds whenever suits you and tap \"Round done\".")
                e.movements.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name)
                            Muted(m.describe())
                        }
                        TextButton(onClick = { movementDialog = m }) { Text("Edit") }
                    }
                }
                OutlinedButton(onClick = { newMovement = true }) { Text("Add movement") }
            }
        }

        item {
            val m = s.medicine
            fun set(f: (app.dailycare.core.MedicineSettings) -> app.dailycare.core.MedicineSettings) = vm.updateSettings { it.copy(medicine = f(it.medicine)) }
            SectionCard("Medicines") {
                Muted("Enter only the regimen your doctor confirmed. A dose is never assumed taken: each needs your tap.")
                TimeSetting("Breakfast", m.breakfast) { v -> set { it.copy(breakfast = v) } }
                TimeSetting("Lunch", m.lunch) { v -> set { it.copy(lunch = v) } }
                TimeSetting("Dinner", m.dinner) { v -> set { it.copy(dinner = v) } }
                IntSetting("\"Before meal\" = minutes before", m.beforeMealMinutes, 0..120) { v -> set { it.copy(beforeMealMinutes = v) } }
                IntSetting("\"After meal\" = minutes after", m.afterMealMinutes, 0..120) { v -> set { it.copy(afterMealMinutes = v) } }
                IntSetting("Show as not confirmed after (minutes)", m.confirmWindowMinutes, 30..720) { v -> set { it.copy(confirmWindowMinutes = v) } }
                IntSetting("Follow-up after (minutes)", m.followUpMinutes, 5..240) { v -> set { it.copy(followUpMinutes = v) } }
                m.medications.forEach { med ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(med.displayName + if (!med.active) " (paused)" else "")
                            val until = med.lastDay?.let { " to $it" } ?: " (no end date)"
                            Muted("${med.pattern} · ${med.meal.label} · from ${med.startDate}$until")
                        }
                        TextButton(onClick = { medicationDialog = med }) { Text("Edit") }
                    }
                }
                OutlinedButton(onClick = { newMedication = true }) { Text("Add medicine") }
            }
        }
    }

    if (newMovement || movementDialog != null) {
        MovementDialog(
            initial = movementDialog,
            onDismiss = { newMovement = false; movementDialog = null },
            onSave = { mv ->
                vm.updateSettings { st -> st.copy(exercise = st.exercise.copy(movements = st.exercise.movements.upsert(mv) { it.id == mv.id })) }
                newMovement = false
                movementDialog = null
            },
            onDelete = { mv ->
                vm.updateSettings { st -> st.copy(exercise = st.exercise.copy(movements = st.exercise.movements.filterNot { it.id == mv.id })) }
                movementDialog = null
            },
        )
    }
    if (newMedication || medicationDialog != null) {
        MedicationDialog(
            initial = medicationDialog,
            onDismiss = { newMedication = false; medicationDialog = null },
            onSave = { med ->
                vm.updateSettings { st -> st.copy(medicine = st.medicine.copy(medications = st.medicine.medications.upsert(med) { it.id == med.id })) }
                newMedication = false
                medicationDialog = null
            },
            onDelete = { med ->
                vm.updateSettings { st -> st.copy(medicine = st.medicine.copy(medications = st.medicine.medications.filterNot { it.id == med.id })) }
                medicationDialog = null
            },
        )
    }
}
