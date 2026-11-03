package app.dailycare.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dailycare.TAG
import app.dailycare.careApp
import app.dailycare.core.CareCalendar
import app.dailycare.core.CareSettings
import app.dailycare.core.EngineOutput
import app.dailycare.core.MINUTE_MS
import app.dailycare.core.MonitoringGap
import app.dailycare.core.PlaceKind
import app.dailycare.core.StepAccumulator
import app.dailycare.core.StepAccumulatorState
import app.dailycare.core.formatMinutes
import app.dailycare.engine.EngineRunner
import app.dailycare.notify.Notifier
import app.dailycare.sensing.ActivityRegistrar
import app.dailycare.sensing.GeofenceRegistrar
import app.dailycare.sensing.LocationReader
import app.dailycare.sensing.StepCounterMonitor
import app.dailycare.sensing.WifiReader
import app.dailycare.setup.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Keeps monitoring alive (Vivo and Xiaomi stop ordinary background apps). It counts steps,
 * samples location around therapy times, watches Wi-Fi changes and ticks the engine every minute
 * while the phone is awake. Exact alarms cover the times the phone is asleep.
 */
class CareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stepMonitor: StepCounterMonitor? = null
    private var accumulator = StepAccumulatorState()
    private val pendingSteps = HashMap<Long, Int>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastFixAt = 0L
    private var lastScanRequestAt = 0L
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        running = true
        val repo = careApp.repository
        val now = System.currentTimeMillis()
        repo.state.lastHeartbeatAt?.let { last ->
            if (now - last > GAP_THRESHOLD_MS) repo.append(MonitoringGap(last, now, "monitoring was not running"))
        }
        repo.state.lastHeartbeatAt = now
        runCatching { repo.pruneSignals(now) }

        val canCountSteps = Permissions.hasActivityRecognition(this)
        stepMonitor = StepCounterMonitor(this) { at, total -> onStepSample(at, total) }.also { monitor ->
            repo.state.stepSensorAvailable = monitor.available && canCountSteps
            if (canCountSteps) monitor.start()
        }
        registerWifiCallback()
        GeofenceRegistrar.sync(this, repo.settings.value.places)
        ActivityRegistrar.register(this)

        scope.launch {
            while (isActive) {
                tickSafely()
                delay(60_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
        // Each startForegroundService() call must be answered with startForeground().
        enterForeground()
        if (intent?.action == ACTION_TICK) scope.launch { tickSafely() }
        return START_STICKY
    }

    override fun onDestroy() {
        stepMonitor?.stop()
        flushSteps()
        wifiCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification = careApp.notifier.statusNotification("Watching for walks, places and reminders")
        var types = 0
        if (Permissions.hasFineLocation(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Permissions.hasActivityRecognition(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && types == 0) return false
        return try {
            ServiceCompat.startForeground(this, Notifier.STATUS_ID, notification, types)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with types $types failed", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Permissions.hasActivityRecognition(this)) {
                try {
                    ServiceCompat.startForeground(this, Notifier.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    true
                } catch (e2: Exception) {
                    Log.w(TAG, "startForeground (health only) failed", e2)
                    false
                }
            } else {
                false
            }
        }
    }

    private fun onStepSample(at: Long, total: Long) {
        synchronized(pendingSteps) {
            val (next, increments) = StepAccumulator.add(accumulator, at, total)
            accumulator = next
            StepAccumulator.merge(pendingSteps, increments)
        }
    }

    private fun flushSteps() {
        val copy = synchronized(pendingSteps) {
            HashMap(pendingSteps).also { pendingSteps.clear() }
        }
        if (copy.isNotEmpty()) runCatching { careApp.repository.addSteps(copy) }
    }

    private fun registerWifiCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = tickSoon()
            override fun onLost(network: Network) = tickSoon()
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "wifi callback not registered", e)
        }
    }

    private fun tickSoon() {
        scope.launch {
            delay(5_000)
            tickSafely()
        }
    }

    private suspend fun tickSafely() {
        try {
            val out = tickOnce()
            careApp.notifier.updateStatus(statusText(out, careApp.repository.settings.value))
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        }
    }

    private suspend fun tickOnce(): EngineOutput {
        val app = careApp
        val now = System.currentTimeMillis()
        flushSteps()
        sampleLocationIfDue(now, app.repository.settings.value)
        app.signals.recordScan(WifiReader.latestScan(this, now))
        app.repository.state.lastHeartbeatAt = now
        return EngineRunner.tick(this)
    }

    /** Location every 15 min normally, every 5 min (and fresh Wi-Fi scans) around therapy times. */
    private suspend fun sampleLocationIfDue(now: Long, s: CareSettings) {
        val therapy = s.place(PlaceKind.THERAPY)
        val nearWindow = therapy != null && s.therapy.enabled && nearTherapyWindow(now, s)
        val interval = if (nearWindow) 5 * MINUTE_MS else 15 * MINUTE_MS
        if (nearWindow && now - lastScanRequestAt >= 5 * MINUTE_MS) {
            lastScanRequestAt = now
            WifiReader.requestScan(this)
        }
        if (now - lastFixAt < interval) return
        lastFixAt = now
        val home = s.place(PlaceKind.HOME)
        val closeToHome = therapy != null && home != null && (therapy.distanceTo(home) ?: Double.MAX_VALUE) < 300.0
        LocationReader.current(this, highAccuracy = nearWindow && closeToHome)?.let { careApp.signals.recordFix(it) }
    }

    private fun nearTherapyWindow(now: Long, s: CareSettings): Boolean {
        val cal = CareCalendar(ZoneId.systemDefault(), s.dayBoundary)
        val day = cal.dayOf(now)
        val t = s.therapy
        val morning = (cal.at(day, t.morningEarliestStart) - 30 * MINUTE_MS)..(cal.at(day, t.morningLatestStart) + 60 * MINUTE_MS)
        val evening = (cal.at(day, t.eveningEarliestStart) - 30 * MINUTE_MS)..(cal.at(day, t.eveningLatestStart) + 60 * MINUTE_MS)
        return now in morning || now in evening
    }

    private fun statusText(out: EngineOutput, s: CareSettings): String {
        val summary = out.summary
        val place = summary.currentPlace?.let { s.placeName(it.place) } ?: "Not at a saved place"
        val b = summary.breakStatus
        return if (b != null && b.atWork && b.anchor != null) {
            "$place · no walking break for ${formatMinutes((summary.now - b.anchor!!) / MINUTE_MS)}"
        } else {
            place
        }
    }

    companion object {
        const val ACTION_TICK = "app.dailycare.TICK"
        private const val GAP_THRESHOLD_MS = 20 * MINUTE_MS

        fun start(context: Context, tick: Boolean = false) {
            if (!context.careApp.repository.state.monitoringEnabled) return
            if (!Permissions.canRunService(context)) return
            val intent = Intent(context, CareService::class.java)
            if (tick) intent.action = ACTION_TICK
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android may refuse to start it from the background; the next alarm or app open will.
                Log.w(TAG, "could not start monitoring service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CareService::class.java))
        }
    }
}
