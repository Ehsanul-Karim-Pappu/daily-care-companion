package app.dailycare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dailycare.core.MealRelation
import app.dailycare.core.Medication
import app.dailycare.core.MedicinePlanner
import app.dailycare.core.Movement
import app.dailycare.core.hm
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private val NumberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        keyboardOptions = if (number) NumberKeyboard else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun MovementDialog(initial: Movement?, onDismiss: () -> Unit, onSave: (Movement) -> Unit, onDelete: (Movement) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var reps by remember(initial) { mutableStateOf(initial?.reps?.toString() ?: "10") }
    var hold by remember(initial) { mutableStateOf(initial?.holdSeconds?.toString().orEmpty()) }
    var perSide by remember(initial) { mutableStateOf(initial?.perSide ?: false) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add movement" else "Edit movement") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name (as your physiotherapist calls it)", name, { name = it })
                Field("Repetitions", reps, { reps = it.filter(Char::isDigit).take(3) }, number = true)
                Field("Hold, seconds (optional)", hold, { hold = it.filter(Char::isDigit).take(3) }, number = true)
                SwitchSetting("Repetitions are for each side", perSide) { perSide = it }
                Field("Notes (optional)", notes, { notes = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        Movement(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            reps = reps.toIntOrNull(),
                            holdSeconds = hold.toIntOrNull(),
                            perSide = perSide,
                            notes = notes.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "8:00, 20:30" → times; null if anything is malformed. */
private fun parseTimes(text: String): List<LocalTime>? {
    if (text.isBlank()) return emptyList()
    return text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
        val bits = part.split(':', '.')
        val h = bits.getOrNull(0)?.toIntOrNull() ?: return null
        val m = bits.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        LocalTime.of(h, m)
    }
}

@Composable
fun MedicationDialog(initial: Medication?, onDismiss: () -> Unit, onSave: (Medication) -> Unit, onDelete: (Medication) -> Unit) {
    var generic by remember(initial) { mutableStateOf(initial?.genericName.orEmpty()) }
    var brand by remember(initial) { mutableStateOf(initial?.brandName.orEmpty()) }
    var strength by remember(initial) { mutableStateOf(initial?.strength.orEmpty()) }
    var form by remember(initial) { mutableStateOf(initial?.form ?: "tablet") }
    var pattern by remember(initial) { mutableStateOf(initial?.pattern ?: "1+0+1") }
    var meal by remember(initial) { mutableStateOf(initial?.meal ?: MealRelation.AFTER) }
    var customTimes by remember(initial) { mutableStateOf(initial?.customTimes?.joinToString(", ") { it.hm() }.orEmpty()) }
    var everyN by remember(initial) { mutableStateOf((initial?.everyNDays ?: 1).toString()) }
    var start by remember(initial) { mutableStateOf(initial?.startDate ?: LocalDate.now()) }
    var duration by remember(initial) { mutableStateOf(initial?.durationDays?.toString().orEmpty()) }
    var prescriber by remember(initial) { mutableStateOf(initial?.prescriber.orEmpty()) }
    var rxDate by remember(initial) { mutableStateOf(initial?.prescriptionDate?.toString().orEmpty()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    var active by remember(initial) { mutableStateOf(initial?.active ?: true) }

    val times = parseTimes(customTimes)
    val patternOk = MedicinePlanner.parsePattern(pattern) != null
    val rxParsed = if (rxDate.isBlank()) null else runCatching { LocalDate.parse(rxDate.trim()) }.getOrNull()
    val rxError = rxDate.isNotBlank() && rxParsed == null
    val everyNValue = everyN.toIntOrNull()
    val valid = generic.isNotBlank() && times != null && (patternOk || !times.isNullOrEmpty()) && !rxError &&
        everyNValue != null && everyNValue >= 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add medicine" else "Edit medicine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Generic name", generic, { generic = it })
                Field("Brand name (optional)", brand, { brand = it })
                Field("Strength, e.g. 500 mg", strength, { strength = it })
                Field("Form, e.g. tablet, capsule", form, { form = it })
                Field(
                    "Dose pattern (morning+noon+night)",
                    pattern,
                    { pattern = it },
                    error = if (!patternOk && times.isNullOrEmpty()) "Use e.g. 1+0+1 or ½+0+1" else null,
                )
                Text("Timing", style = MaterialTheme.typography.labelLarge)
                MealRelation.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = meal == option, onClick = { meal = option }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = meal == option, onClick = { meal = option })
                        Text(option.label)
                    }
                }
                Field(
                    "Exact times instead (optional), e.g. 8:00, 20:00",
                    customTimes,
                    { customTimes = it },
                    error = if (times == null) "Use HH:MM separated by commas" else null,
                )
                Field("Every N days (1 = daily, 7 = weekly)", everyN, { everyN = it.filter(Char::isDigit).take(3) }, number = true)
                DateSetting("Start date", start) { start = it }
                Field("Number of days (blank = until stopped)", duration, { duration = it.filter(Char::isDigit).take(4) }, number = true)
                Field("Prescribed by (optional)", prescriber, { prescriber = it })
                Field("Prescription date YYYY-MM-DD (optional)", rxDate, { rxDate = it }, error = if (rxError) "Use YYYY-MM-DD" else null)
                Field("Notes (optional)", notes, { notes = it })
                SwitchSetting("Active", active, "Pause instead of deleting to keep history readable.") { active = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        Medication(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            genericName = generic.trim(),
                            brandName = brand.trim(),
                            strength = strength.trim(),
                            form = form.trim(),
                            pattern = pattern.trim(),
                            meal = meal,
                            customTimes = times.orEmpty(),
                            everyNDays = everyNValue ?: 1,
                            startDate = start,
                            durationDays = duration.toIntOrNull()?.takeIf { it > 0 },
                            prescriber = prescriber.trim(),
                            prescriptionDate = rxParsed,
                            notes = notes.trim(),
                            active = active,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
