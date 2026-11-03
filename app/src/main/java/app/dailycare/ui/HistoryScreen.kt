package app.dailycare.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dailycare.core.CareCalendar
import app.dailycare.core.CareSettings
import app.dailycare.core.DaySummary
import app.dailycare.core.DoseState
import app.dailycare.core.formatMinutes
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HistoryScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadHistory() }
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cal = remember(settings.dayBoundary) { CareCalendar(ZoneId.systemDefault(), settings.dayBoundary) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Last 14 days", style = MaterialTheme.typography.headlineSmall)
            Muted("Breaks and visits are detected by the phone; exercises, medicines and reports are what you confirmed.")
        }
        item {
            OutlinedButton(onClick = { exporter.launch("daily-care-${LocalDate.now()}.csv") }) { Text("Export full log (CSV)") }
        }
        if (history.isEmpty()) item { Muted("No earlier days yet.") }
        items(history) { HistoryDay(it, settings, cal) }
    }
}

@Composable
private fun HistoryDay(d: DaySummary, settings: CareSettings, cal: CareCalendar) {
    SectionCard(d.dayLabel) {
        if (d.workMinutes > 0) {
            val longest = d.longestStretchMinutes?.let { " · longest stretch ${formatMinutes(it)}" }.orEmpty()
            Text("Work ${formatMinutes(d.workMinutes)} · breaks: ${d.breaksInferred} detected, ${d.breaksReported} reported$longest")
        } else {
            Muted("No time at work detected")
        }
        if (settings.therapy.enabled) {
            val detail = d.therapy.best?.let { " (${cal.hm(it.visit.start)}, ${formatMinutes(it.dwellMinutes)})" }.orEmpty()
            Text("Therapy: ${d.therapy.state.label}$detail")
        }
        if (d.exercise.enabled) {
            Text("Exercises: ${d.exercise.fullRounds} of ${d.exercise.target} rounds (you confirmed)")
        }
        if (d.doses.isNotEmpty()) {
            val taken = d.doses.count { it.state == DoseState.TAKEN }
            val unconfirmed = d.doses.count { it.state == DoseState.NOT_CONFIRMED }
            Text("Medicines: $taken of ${d.doses.size} doses confirmed taken" + if (unconfirmed > 0) " · $unconfirmed not confirmed" else "")
        }
        d.gaps.forEach { Muted("No data ${cal.hm(it.at)}–${cal.hm(it.end)}: ${it.reason}") }
    }
}
