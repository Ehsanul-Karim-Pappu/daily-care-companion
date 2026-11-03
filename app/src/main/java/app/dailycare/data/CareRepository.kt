package app.dailycare.data

import android.content.Context
import android.content.SharedPreferences
import app.dailycare.core.CareEvent
import app.dailycare.core.CareJson
import app.dailycare.core.CareSettings
import app.dailycare.core.LocationFix
import app.dailycare.core.MotionTransition
import app.dailycare.core.PlaceKind
import app.dailycare.core.PlaceMachineState
import app.dailycare.core.StoredEvent
import app.dailycare.core.WifiScan
import app.dailycare.core.careSettingsFromJson
import app.dailycare.core.isUserEntered
import app.dailycare.core.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer

class CareRepository(context: Context) {
    private val db = CareDatabase(context)
    private val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val state = StateStore(context)

    private val _settings = MutableStateFlow(careSettingsFromJson(settingsPrefs.getString(SETTINGS_KEY, null)))
    val settings: StateFlow<CareSettings> = _settings.asStateFlow()

    /** Bumped on every write so screens can refresh. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    @Synchronized
    fun updateSettings(transform: (CareSettings) -> CareSettings): CareSettings {
        val next = transform(_settings.value)
        settingsPrefs.edit().putString(SETTINGS_KEY, next.toJson()).apply()
        _settings.value = next
        changed()
        return next
    }

    fun append(events: List<CareEvent>) {
        if (events.isEmpty()) return
        db.insertEvents(events)
        changed()
    }

    fun append(event: CareEvent) = append(listOf(event))

    fun events(from: Long, to: Long = Long.MAX_VALUE): List<StoredEvent> = db.events(from, to)

    fun allEvents(): List<StoredEvent> = db.events(Long.MIN_VALUE, Long.MAX_VALUE)

    /** Only things the person entered can be removed; inferences and app records stay. */
    fun deleteUserEvent(id: Long): Boolean {
        val stored = db.event(id) ?: return false
        if (!stored.event.isUserEntered) return false
        return db.deleteEvent(id).also { if (it) changed() }
    }

    fun addSteps(increments: Map<Long, Int>, source: String = "phone") = db.addSteps(increments, source)

    fun stepBins(from: Long, to: Long): Map<Long, Int> = db.stepBins(from, to)

    fun addTransitions(list: List<MotionTransition>) = db.addTransitions(list)

    fun transitions(from: Long, to: Long): List<MotionTransition> = db.transitions(from, to)

    fun pruneSignals(now: Long) = db.pruneSignals(now - 120L * 24 * 3600 * 1000)

    companion object {
        private const val SETTINGS_KEY = "settings_json"
    }
}

/** Small pieces of state that must survive the process being killed. */
class StateStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { CareJson.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, CareJson.encodeToString(serializer, value))
        }.apply()
    }

    var placeState: PlaceMachineState
        get() = read("place", PlaceMachineState.serializer()) ?: PlaceMachineState()
        set(value) = write("place", PlaceMachineState.serializer(), value)

    var lastFix: LocationFix?
        get() = read("fix", LocationFix.serializer())
        set(value) = write("fix", LocationFix.serializer(), value)

    var lastWifiScan: WifiScan?
        get() = read("scan", WifiScan.serializer())
        set(value) = write("scan", WifiScan.serializer(), value)

    var geofencesInside: Set<PlaceKind>
        get() = prefs.getStringSet("geofences_inside", emptySet()).orEmpty()
            .mapNotNull { runCatching { PlaceKind.valueOf(it) }.getOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("geofences_inside", value.map { it.name }.toSet()).apply()

    var geofencesRegistered: Boolean
        get() = prefs.getBoolean("geofences_registered", false)
        set(value) = prefs.edit().putBoolean("geofences_registered", value).apply()

    var lastHeartbeatAt: Long?
        get() = prefs.getLong("heartbeat", -1L).takeIf { it > 0 }
        set(value) = prefs.edit().putLong("heartbeat", value ?: -1L).apply()

    var stepSensorAvailable: Boolean
        get() = prefs.getBoolean("step_sensor", true)
        set(value) = prefs.edit().putBoolean("step_sensor", value).apply()

    /** The person can pause monitoring from Setup. */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoring_enabled", true)
        set(value) = prefs.edit().putBoolean("monitoring_enabled", value).apply()
}
