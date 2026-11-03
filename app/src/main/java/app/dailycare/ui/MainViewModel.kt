package app.dailycare.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dailycare.DailyCareApp
import app.dailycare.core.ActionId
import app.dailycare.core.CareSettings
import app.dailycare.core.CsvExport
import app.dailycare.core.DaySummary
import app.dailycare.core.Place
import app.dailycare.core.PlaceKind
import app.dailycare.core.WifiFingerprint
import app.dailycare.engine.EngineRunner
import app.dailycare.engine.UserActions
import app.dailycare.sensing.ActivityRegistrar
import app.dailycare.sensing.GeofenceRegistrar
import app.dailycare.sensing.LocationReader
import app.dailycare.sensing.WifiReader
import app.dailycare.service.CareService
import app.dailycare.setup.Permissions
import app.dailycare.setup.PhoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.roundToInt

data class SetupStatus(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val activityRecognition: Boolean,
    val notifications: Boolean,
    val nearbyWifi: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
    val stepSensor: Boolean,
    val monitoringEnabled: Boolean,
    val geofencesActive: Boolean,
    val manufacturer: String,
) {
    val essentialsReady: Boolean get() = fineLocation && backgroundLocation && activityRecognition && notifications
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DailyCareApp
    val settings: StateFlow<CareSettings> = app.repository.settings

    private val _today = MutableStateFlow<DaySummary?>(null)
    val today: StateFlow<DaySummary?> = _today.asStateFlow()

    private val _history = MutableStateFlow<List<DaySummary>>(emptyList())
    val history: StateFlow<List<DaySummary>> = _history.asStateFlow()

    private val _setup = MutableStateFlow(readSetup())
    val setup: StateFlow<SetupStatus> = _setup.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.repository.changes, settings) { c, s -> c to s }.collectLatest { refreshToday() }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                refreshToday()
            }
        }
    }

    private suspend fun refreshToday() {
        _today.value = withContext(Dispatchers.IO) { runCatching { EngineRunner.summary(app) }.getOrNull() }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { runCatching { EngineRunner.history(app, 14) }.getOrDefault(emptyList()) }
        }
    }

    private fun readSetup() = SetupStatus(
        fineLocation = Permissions.hasFineLocation(app),
        backgroundLocation = Permissions.hasBackgroundLocation(app),
        activityRecognition = Permissions.hasActivityRecognition(app),
        notifications = Permissions.hasNotifications(app),
        nearbyWifi = Permissions.hasNearbyWifi(app),
        exactAlarms = Permissions.canScheduleExactAlarms(app),
        batteryUnrestricted = Permissions.ignoringBatteryOptimizations(app),
        stepSensor = app.repository.state.stepSensorAvailable,
        monitoringEnabled = app.repository.state.monitoringEnabled,
        geofencesActive = app.repository.state.geofencesRegistered,
        manufacturer = PhoneSettings.manufacturer,
    )

    fun refreshSetup() {
        _setup.value = readSetup()
    }

    /** After permissions change: (re)start monitoring and re-register geofences and activity updates. */
    fun onPermissionsChanged() {
        refreshSetup()
        CareService.start(app, tick = true)
        GeofenceRegistrar.sync(app, settings.value.places)
        ActivityRegistrar.register(app)
        viewModelScope.launch {
            delay(1500)
            refreshSetup()
        }
    }

    fun setMonitoring(enabled: Boolean) {
        app.repository.state.monitoringEnabled = enabled
        if (enabled) CareService.start(app, tick = true) else CareService.stop(app)
        refreshSetup()
    }

    fun perform(action: ActionId, arg: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = UserActions.perform(app, action, arg)
            runCatching { EngineRunner.tick(app, updatePlace = false) }
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = if (app.repository.deleteUserEvent(id)) "Removed" else "Only your own entries can be removed"
            runCatching { EngineRunner.tick(app, updatePlace = false) }
        }
    }

    fun updateSettings(transform: (CareSettings) -> CareSettings) {
        val before = settings.value
        val after = app.repository.updateSettings(transform)
        if (before.places != after.places) GeofenceRegistrar.sync(app, after.places)
        viewModelScope.launch(Dispatchers.IO) { runCatching { EngineRunner.tick(app, updatePlace = false) } }
    }

    /** Save "I'm here now" as a place: precise location plus the Wi-Fi networks around. */
    fun savePlace(kind: PlaceKind) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Saving ${kind.label}: finding location and Wi-Fi…"
            val result = withContext(Dispatchers.IO) {
                WifiReader.requestScan(app)
                val fix = LocationReader.current(app, highAccuracy = true, timeoutMs = 45_000)
                delay(2_000)
                val now = System.currentTimeMillis()
                Triple(fix, WifiReader.latestScan(app, now), WifiReader.connected(app))
            }
            _busy.value = null
            val (fix, scan, wifi) = result
            if (fix == null && scan == null && wifi == null) {
                _message.value = "Couldn't get location or Wi-Fi. Check that location is on and permissions are granted."
                return@launch
            }
            updateSettings { s ->
                val old = s.place(kind) ?: Place(kind)
                s.withPlace(
                    old.copy(
                        latitude = fix?.latitude ?: old.latitude,
                        longitude = fix?.longitude ?: old.longitude,
                        accuracyMeters = fix?.accuracyMeters ?: old.accuracyMeters,
                        knownNetworks = old.knownNetworks + listOfNotNull(wifi?.ssid),
                        knownBssids = old.knownBssids + listOfNotNull(wifi?.bssid),
                        // Re-saving adds networks (e.g. entrance and inside); "Clear Wi-Fi" starts over.
                        fingerprint = scan?.let { WifiFingerprint.compact(old.fingerprint + it.accessPoints, limit = 30) } ?: old.fingerprint,
                        savedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            _message.value = buildString {
                append("${kind.label} saved")
                fix?.let { append(" · location ±${it.accuracyMeters.roundToInt()} m") }
                scan?.let { append(" · ${it.accessPoints.size} Wi-Fi networks") }
                wifi?.ssid?.let { append(" · connected to \"$it\"") }
            }
        }
    }

    fun clearPlaceWifi(kind: PlaceKind) = updateSettings { s ->
        s.place(kind)?.let { s.withPlace(it.copy(knownNetworks = emptySet(), knownBssids = emptySet(), fingerprint = emptyList())) } ?: s
    }

    fun removePlace(kind: PlaceKind) = updateSettings { it.withoutPlace(kind) }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = try {
                val csv = CsvExport.render(app.repository.allEvents(), settings.value, ZoneId.systemDefault())
                app.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                "Exported"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
