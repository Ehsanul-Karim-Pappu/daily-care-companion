package app.dailycare.sensing

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.dailycare.TAG
import app.dailycare.careApp
import app.dailycare.core.ConnectedWifi
import app.dailycare.core.LocationFix
import app.dailycare.core.Motion
import app.dailycare.core.Place
import app.dailycare.core.PlaceKind
import app.dailycare.core.SignalSnapshot
import app.dailycare.core.WifiAccessPoint
import app.dailycare.core.WifiFingerprint
import app.dailycare.core.WifiScan
import app.dailycare.data.StateStore
import app.dailycare.receivers.ActivityTransitionReceiver
import app.dailycare.receivers.GeofenceReceiver
import app.dailycare.setup.Permissions
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Latest location-related signals, persisted so a fresh process can still build a snapshot. */
class SignalHub(private val context: Context, private val state: StateStore) {

    fun recordFix(fix: LocationFix) {
        val prev = state.lastFix
        if (prev == null || fix.atMillis >= prev.atMillis) state.lastFix = fix
    }

    fun recordScan(scan: WifiScan?) {
        if (scan != null) state.lastWifiScan = scan
    }

    @Synchronized
    fun setGeofenceInside(kind: PlaceKind, inside: Boolean) {
        state.geofencesInside = if (inside) state.geofencesInside + kind else state.geofencesInside - kind
    }

    fun clearGeofences() {
        state.geofencesInside = emptySet()
    }

    fun setGeofencesRegistered(registered: Boolean) {
        state.geofencesRegistered = registered
    }

    fun snapshot(now: Long): SignalSnapshot = SignalSnapshot(
        now = now,
        wifi = WifiReader.connected(context),
        scan = state.lastWifiScan,
        fix = state.lastFix,
        geofencesInside = state.geofencesInside,
        geofencesRegistered = state.geofencesRegistered,
    )
}

object WifiReader {

    private fun manager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    /** The Wi-Fi network the phone is connected to, if any (needs location permission to see the name). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connected(context: Context): ConnectedWifi? {
        if (!Permissions.hasFineLocation(context)) return null
        return try {
            val info = manager(context)?.connectionInfo ?: return null
            val ssid = info.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
            val bssid = info.bssid?.lowercase()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
            if (ssid == null && bssid == null) null else ConnectedWifi(ssid, bssid)
        } catch (e: SecurityException) {
            null
        }
    }

    /** Networks visible from the last scan (the system scans regularly; we read its results). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun latestScan(context: Context, now: Long): WifiScan? {
        if (!Permissions.hasFineLocation(context)) return null
        return try {
            val results = manager(context)?.scanResults.orEmpty()
            if (results.isEmpty()) return null
            val bootNowMs = SystemClock.elapsedRealtime()
            val newestMicros = results.maxOf { it.timestamp }
            val at = now - (bootNowMs - newestMicros / 1000)
            val aps = results
                .filter { newestMicros - it.timestamp <= 120_000_000L }
                .mapNotNull { r -> r.BSSID?.let { WifiAccessPoint(it.lowercase(), r.SSID.orEmpty(), r.level) } }
            WifiScan(at, WifiFingerprint.compact(aps, limit = 30))
        } catch (e: SecurityException) {
            null
        }
    }

    /** Ask for a fresh scan (Android throttles this; failure is fine). */
    @Suppress("DEPRECATION")
    fun requestScan(context: Context): Boolean = try {
        manager(context)?.startScan() == true
    } catch (e: SecurityException) {
        false
    }
}

object LocationReader {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, highAccuracy: Boolean, timeoutMs: Long = 30_000): LocationFix? {
        if (!Permissions.hasFineLocation(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        return try {
            val priority = if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val location = withTimeoutOrNull(timeoutMs) { client.getCurrentLocation(priority, cts.token).await() }
            location?.let {
                LocationFix(it.time, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 100.0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "location request failed", e)
            null
        } finally {
            cts.cancel()
        }
    }
}

private fun mutableBroadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent {
    // Play services fills in the result, so these must be mutable on Android 12+.
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(context, requestCode, intent, flags)
}

object GeofenceRegistrar {
    private fun pendingIntent(context: Context) =
        mutableBroadcast(context, 1001, Intent(context, GeofenceReceiver::class.java))

    @SuppressLint("MissingPermission")
    fun sync(context: Context, places: List<Place>) {
        val hub = context.careApp.signals
        if (!Permissions.hasFineLocation(context) || !Permissions.hasBackgroundLocation(context)) {
            hub.setGeofencesRegistered(false)
            return
        }
        val fences = places.filter { it.hasLocation }.map { p ->
            Geofence.Builder()
                .setRequestId(p.kind.name)
                .setCircularRegion(p.latitude!!, p.longitude!!, p.radiusMeters.toFloat().coerceAtLeast(50f))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(60_000)
                .build()
        }
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)
        client.removeGeofences(pi).addOnCompleteListener {
            hub.clearGeofences()
            if (fences.isEmpty()) {
                hub.setGeofencesRegistered(false)
                return@addOnCompleteListener
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pi)
                    .addOnSuccessListener { hub.setGeofencesRegistered(true) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "geofences not registered", e)
                        hub.setGeofencesRegistered(false)
                    }
            } catch (e: SecurityException) {
                hub.setGeofencesRegistered(false)
            }
        }
    }
}

object ActivityRegistrar {
    private fun pendingIntent(context: Context) =
        mutableBroadcast(context, 1002, Intent(context, ActivityTransitionReceiver::class.java))

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (!Permissions.hasActivityRecognition(context)) return
        val types = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
        )
        val transitions = types.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { kind ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(kind).build()
            }
        }
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
                .addOnFailureListener { Log.w(TAG, "activity transitions not registered", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "activity recognition permission missing", e)
        }
    }

    fun motionOf(detectedActivityType: Int): Motion = when (detectedActivityType) {
        DetectedActivity.WALKING -> Motion.WALKING
        DetectedActivity.RUNNING -> Motion.RUNNING
        DetectedActivity.ON_FOOT -> Motion.ON_FOOT
        DetectedActivity.STILL -> Motion.STILL
        DetectedActivity.IN_VEHICLE -> Motion.IN_VEHICLE
        DetectedActivity.ON_BICYCLE -> Motion.ON_BICYCLE
        else -> Motion.OTHER
    }
}

/**
 * The phone's hardware step counter. Prefers the wake-up variant so steps are timestamped
 * accurately even when the phone was asleep on the desk.
 */
class StepCounterMonitor(context: Context, private val onSample: (at: Long, total: Long) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val available: Boolean get() = sensor != null

    fun start(): Boolean {
        val s = sensor ?: return false
        return sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000) == true
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val at = now - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        onSample(at.coerceAtMost(now), event.values[0].toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
