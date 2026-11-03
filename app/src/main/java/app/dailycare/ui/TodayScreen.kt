package app.dailycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import app.dailycare.core.ActionId
import app.dailycare.core.CareCalendar
import app.dailycare.core.CareSettings
import app.dailycare.core.DaySummary
import app.dailycare.core.DoseState
import app.dailycare.core.DoseStatus
import app.dailycare.core.MINUTE_MS
import app.dailycare.core.PlaceKind
import app.dailycare.core.Provenance
import app.dailycare.core.TherapyState
import app.dailycare.core.TimelineItem
import app.dailycare.core.formatMinutes
import java.time.ZoneId

@Composable
fun TodayScreen(vm: MainViewModel) {
    val summary by vm.today.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val cal = remember(settings.dayBoundary) { CareCalendar(ZoneId.systemDefault(), settings.dayBoundary) }
    val s = summary
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(s.dayLabel, style = MaterialTheme.typography.headlineSmall) }
        if (!setup.essentialsReady || !setup.monitoringEnabled) {
            item { Banner("Monitoring needs attention: open Setup to allow permissions and save your places.", warning = true) }
        }
        if (s.observeOnly) {
            item {
                Banner(
                    "Observe week: walks and visits are being detected and logged, but break and therapy reminders are " +
                        "off so you can check detection first. Turn this off in Settings when it looks right.",
                )
            }
        }
        item { PlaceCard(s, settings, cal) }
        item { BreaksCard(s, settings, cal, vm) }
        if (settings.therapy.enabled) item { TherapyCard(s, settings, cal, vm) }
        item { ExerciseCard(s, settings, vm) }
        item { MedicineCard(s, settings, cal, vm) }
        item { Text("Timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (s.timeline.isEmpty()) item { Muted("Nothing recorded yet today.") }
        items(s.timeline.reversed()) { TimelineRow(it, cal, vm) }
    }
}

@Composable
private fun PlaceCard(s: DaySummary, settings: CareSettings, cal: CareCalendar) {
    SectionCard("Where you are") {
        val cur = s.currentPlace
        when {
            settings.places.isEmpty() -> Muted("No places saved yet. In Setup, save Home, Work and Therapy while you're at each one.")
            cur == null -> Text("Not at a saved place")
            else -> {
                Text("${settings.placeName(cur.place)} since ${cal.hm(cur.start)}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProvenanceBadge(Provenance.INFERRED)
                    Muted("${cur.confidence.label} confidence · ${cur.basis}")
                }
            }
        }
        if (s.workMinutes > 0) Muted("At work today: ${formatMinutes(s.workMinutes)}")
    }
}

@Composable
private fun BreaksCard(s: DaySummary, settings: CareSettings, cal: CareCalendar, vm: MainViewModel) {
    SectionCard("Walking breaks") {
        val b = s.breakStatus
        when {
            !settings.breaks.enabled -> Muted("Break reminders are off (Settings).")
            b != null && b.atWork -> {
                val anchor = b.anchor ?: s.now
                Text("No walking break detected for ${formatMinutes((s.now - anchor) / MINUTE_MS)}", style = MaterialTheme.typography.titleSmall)
                b.snoozedUntil?.let { Muted("Snoozed until ${cal.hm(it)}") }
                b.dueAt?.let { due ->
                    Muted(
                        when {
                            settings.observeOnly -> "Would remind at ${cal.hm(due)} (observe week)"
                            due <= s.now -> "Reminder due now"
                            else -> "Next reminder at ${cal.hm(due)}"
                        },
                    )
                }
            }
            else -> Muted("Tracked while you're at work. Any walk of ${settings.breaks.minWalkMinutes}+ minutes with your phone counts.")
        }
        Text("At work today: ${s.breaksInferred} break(s) detected by the phone, ${s.breaksReported} reported by you")
        s.longestStretchMinutes?.let { Muted("Longest stretch without a break: ${formatMinutes(it)}") }
        if (s.shortMovements > 0) Muted("${s.shortMovements} short movement(s) too brief to count")
        if (s.breakRemindersSuppressed > 0) Muted("Observe week: ${s.breakRemindersSuppressed} reminder(s) would have been sent")
        Buttons {
            OutlinedButton(onClick = { vm.perform(ActionId.BREAK_WALKED_WITHOUT_PHONE) }) { Text("I walked without my phone") }
            if (b?.atWork == true) {
                TextButton(onClick = { vm.perform(ActionId.BREAK_SNOOZE) }) { Text("Snooze ${settings.breaks.snoozeMinutes} min") }
            }
        }
    }
}

@Composable
private fun TherapyCard(s: DaySummary, settings: CareSettings, cal: CareCalendar, vm: MainViewModel) {
    SectionCard("Therapy") {
        val th = s.therapy
        Text(th.state.label, style = MaterialTheme.typography.titleSmall)
        if (settings.place(PlaceKind.THERAPY) == null) {
            Muted("Save the therapy place in Setup (while you're there) so visits can be recognised.")
        }
        th.visits.forEach { v ->
            val end = v.visit.end?.let { cal.hm(it) } ?: "now"
            val verdict = when (v.verdict) {
                true -> " · you confirmed"
                false -> " · you said not therapy"
                null -> ""
            }
            val short = if (!v.qualifies) " · too short to count" else ""
            Muted("${cal.hm(v.visit.start)}–$end · ${formatMinutes(v.dwellMinutes)} · ${v.window.label} · ${v.visit.confidence.label} confidence$short$verdict")
        }
        val best = th.best
        if (best != null && best.verdict == null && th.state != TherapyState.CONFIRMED) {
            Muted("Optional: was the visit at ${cal.hm(best.visit.start)} a therapy session?")
            Buttons {
                Button(onClick = { vm.perform(ActionId.THERAPY_VISIT_YES, best.visit.start.toString()) }) { Text("Yes") }
                OutlinedButton(onClick = { vm.perform(ActionId.THERAPY_VISIT_NO, best.visit.start.toString()) }) { Text("No") }
            }
        }
        if (!th.satisfied) {
            Buttons {
                Button(onClick = { vm.perform(ActionId.THERAPY_ATTENDED) }) { Text("I attended today") }
                OutlinedButton(onClick = { vm.perform(ActionId.THERAPY_NOT_TODAY) }) { Text("Not today") }
            }
        }
    }
}

@Composable
private fun ExerciseCard(s: DaySummary, settings: CareSettings, vm: MainViewModel) {
    SectionCard("Exercises") {
        val ex = s.exercise
        if (!settings.exercise.enabled) {
            Muted("Off until your physiotherapist's routine is entered (Settings → Exercises).")
        } else {
            val partial = if (ex.partialRounds > 0) " (+${ex.partialRounds} partial)" else ""
            Text("${ex.fullRounds} of ${ex.target} rounds confirmed today$partial", style = MaterialTheme.typography.titleSmall)
            settings.exercise.movements.forEach { m ->
                val detail = m.describe()
                Muted("• ${m.name}" + if (detail.isBlank()) "" else " — $detail")
            }
            Buttons {
                Button(onClick = { vm.perform(ActionId.EXERCISE_ROUND_DONE) }) { Text("Round done") }
                OutlinedButton(onClick = { vm.perform(ActionId.EXERCISE_PARTIAL) }) { Text("Partial round") }
            }
        }
    }
}

@Composable
private fun MedicineCard(s: DaySummary, settings: CareSettings, cal: CareCalendar, vm: MainViewModel) {
    SectionCard("Medicines") {
        when {
            settings.medicine.medications.none { it.active } ->
                Muted("No medicines entered. Add them in Settings → Medicines once your regimen is confirmed.")
            s.doses.isEmpty() -> Muted("No doses scheduled today.")
        }
        s.doses.forEach { DoseRow(it, cal, vm) }
        s.endedCourses.forEach {
            Muted("${it.displayName}: the course you entered ended ${it.lastDay}. If your doctor extended it, update it in Settings.")
        }
    }
}

@Composable
private fun DoseRow(d: DoseStatus, cal: CareCalendar, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth()) {
        val med = d.slot.medication
        Text("${med.displayName} · ${d.slot.quantity} ${med.form}")
        val state = when (d.state) {
            DoseState.TAKEN -> "taken at ${d.confirmedAt?.let { cal.hm(it) }.orEmpty()} (you confirmed)"
            DoseState.SKIPPED -> "skipped (you confirmed)"
            else -> d.state.label
        }
        Muted("${d.slot.label} · ${med.meal.label} · due ${cal.hm(d.slot.dueAt)} · $state")
        if (d.state != DoseState.TAKEN) {
            Buttons {
                TextButton(onClick = { vm.perform(ActionId.DOSE_TAKEN, d.slot.key) }) {
                    Text(if (d.state == DoseState.NOT_CONFIRMED) "Taken (late)" else "Taken")
                }
                if (d.state != DoseState.SKIPPED) {
                    TextButton(onClick = { vm.perform(ActionId.DOSE_SKIPPED, d.slot.key) }) { Text("Skipped") }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, cal: CareCalendar, vm: MainViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(cal.hm(item.at), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvenanceBadge(item.provenance)
                if (item.removable && item.eventId != null) {
                    TextButton(onClick = { confirmRemove = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Remove") }
                }
            }
        }
    }
    val id = item.eventId
    if (confirmRemove && id != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this entry?") },
            text = { Text("\"${item.text}\" at ${cal.hm(item.at)} will be removed from your log.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.deleteEvent(id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}
