package app.dailycare.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dailycare.core.CareSettings
import app.dailycare.core.PlaceKind
import app.dailycare.core.PlaceSeparation
import app.dailycare.setup.PhoneSettings
import kotlin.math.roundToInt

@Composable
fun SetupScreen(vm: MainViewModel) {
    val setup by vm.setup.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSetup() }
    val requestMany = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionsChanged() }
    val requestOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionsChanged() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Setup", style = MaterialTheme.typography.headlineSmall)
            Muted("Do this once. Everything stays on this phone.")
        }
        item {
            SectionCard("1. Permissions") {
                PermissionRow("Precise location", "To recognise home, work and the therapy place.", setup.fineLocation) {
                    requestMany.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                PermissionRow(
                    "Location all the time",
                    "Choose \"Allow all the time\" so places are recognised while the app is closed.",
                    setup.backgroundLocation,
                    enabled = setup.fineLocation,
                ) { requestOne.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                if (setup.fineLocation && !setup.backgroundLocation) {
                    TextButton(onClick = { PhoneSettings.openAppDetails(context) }) { Text("Open app settings instead") }
                }
                PermissionRow("Physical activity", "To count steps and recognise walks.", setup.activityRecognition) {
                    requestOne.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow("Notifications", "For reminders.", setup.notifications) {
                        requestOne.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    PermissionRow("Nearby Wi-Fi", "To see Wi-Fi networks around you; helps tell home and the therapy place apart.", setup.nearbyWifi) {
                        requestOne.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PermissionRow("Exact alarms", "So reminders arrive on time while the phone sleeps.", setup.exactAlarms) {
                        PhoneSettings.requestExactAlarms(context)
                    }
                }
                PermissionRow("No battery restriction", "So Android doesn't pause monitoring.", setup.batteryUnrestricted) {
                    PhoneSettings.requestIgnoreBatteryOptimizations(context)
                }
                Muted(
                    if (setup.stepSensor) {
                        "Step counter: available."
                    } else {
                        "Step counter: not available or not permitted. Walks will be recognised from activity only (less precise)."
                    },
                )
            }
        }
        item {
            SectionCard("2. ${setup.manufacturer.ifBlank { "Phone" }} background settings") {
                Muted("Vivo and Xiaomi/Poco phones stop background apps unless you allow them. Only you can change these:")
                PhoneSettings.backgroundGuidance().forEachIndexed { i, step -> Text("${i + 1}. $step") }
                OutlinedButton(onClick = { PhoneSettings.openAutostart(context) }) { Text("Open phone settings") }
            }
        }
        item {
            SectionCard("3. Monitoring") {
                SwitchSetting("Monitoring on", setup.monitoringEnabled, "Shows a small ongoing notification while active.") { vm.setMonitoring(it) }
                Muted(
                    if (setup.geofencesActive) {
                        "Place geofences: active."
                    } else {
                        "Place geofences: not active yet (needs 'location all the time' and at least one saved place)."
                    },
                )
            }
        }
        item {
            Text("4. Places", style = MaterialTheme.typography.titleMedium)
            Muted("Stand at each place and tap save. Re-saving adds the Wi-Fi networks seen there and updates the location.")
        }
        busy?.let { item { Banner(it) } }
        items(PlaceKind.entries.toList()) { kind -> PlaceSetupCard(kind, settings, busy == null, vm) }
        item { SeparationCard(settings) }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, enabled: Boolean = true, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Muted(why)
        }
        if (granted) {
            Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        } else {
            Button(onClick = onRequest, enabled = enabled) { Text("Allow") }
        }
    }
}

private fun hint(kind: PlaceKind) = when (kind) {
    PlaceKind.HOME -> "Save while you're at home."
    PlaceKind.WORK -> "Save while at your desk, connected to the office Wi-Fi if you use it."
    PlaceKind.THERAPY -> "Save while you're at the therapy place. The app records its Wi-Fi networks, which helps tell it apart from home."
}

@Composable
private fun PlaceSetupCard(kind: PlaceKind, settings: CareSettings, canSave: Boolean, vm: MainViewModel) {
    val place = settings.place(kind)
    SectionCard(kind.label) {
        if (place == null) {
            Muted(hint(kind))
        } else {
            val accuracy = place.accuracyMeters?.let { "±${it.roundToInt()} m" } ?: "accuracy unknown"
            Text(if (place.hasLocation) "Location saved ($accuracy) · radius ${place.radiusMeters.roundToInt()} m" else "No location saved")
            val connects = if (place.knownNetworks.isEmpty()) "not connected here" else "connects to ${place.knownNetworks.joinToString()}"
            Muted("Wi-Fi: $connects · ${place.fingerprint.size} nearby networks recorded")
        }
        Buttons {
            Button(onClick = { vm.savePlace(kind) }, enabled = canSave) { Text(if (place == null) "I'm here — save" else "I'm here — re-save") }
            if (place != null) {
                TextButton(onClick = { vm.clearPlaceWifi(kind) }) { Text("Clear Wi-Fi") }
                TextButton(onClick = { vm.removePlace(kind) }) { Text("Remove") }
            }
        }
        if (place != null && place.hasLocation) {
            IntSetting("Radius (metres)", place.radiusMeters.roundToInt(), 30..500, help = "Smaller separates nearby places; larger tolerates GPS drift.") { r ->
                vm.updateSettings { s -> s.place(kind)?.let { s.withPlace(it.copy(radiusMeters = r.toDouble())) } ?: s }
            }
        }
    }
}

@Composable
private fun SeparationCard(settings: CareSettings) {
    val home = settings.place(PlaceKind.HOME)
    val therapy = settings.place(PlaceKind.THERAPY)
    SectionCard("Home vs therapy place") {
        if (home == null || therapy == null) {
            Muted("Save both to see how reliably the phone can tell them apart.")
        } else {
            val a = PlaceSeparation.assess(home, therapy)
            Text("Separation: ${a.level.label}", style = MaterialTheme.typography.titleSmall)
            Muted(a.explanation)
            a.suggestedRadiusMeters?.let { r ->
                if (home.radiusMeters > r || therapy.radiusMeters > r) {
                    Muted("Tip: set both radii to about ${r.roundToInt()} m or less so they don't overlap.")
                }
            }
            Muted("Being at the place is not proof of treatment, so visits are shown as \"likely\" unless you confirm them.")
        }
    }
}
