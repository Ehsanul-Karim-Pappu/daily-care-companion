package app.dailycare.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.dailycare.TAG
import app.dailycare.careApp
import app.dailycare.core.ActionId
import app.dailycare.core.LocationFix
import app.dailycare.core.MotionTransition
import app.dailycare.core.PlaceKind
import app.dailycare.engine.EngineRunner
import app.dailycare.engine.UserActions
import app.dailycare.sensing.ActivityRegistrar
import app.dailycare.sensing.GeofenceRegistrar
import app.dailycare.service.CareService
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Run [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeout(9_000) { block() }
        } catch (t: Throwable) {
            Log.w(TAG, "${this@work::class.simpleName} failed", t)
        } finally {
            pending.finish()
        }
    }
}

/** Buttons on reminder notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.action?.removePrefix(PREFIX) ?: return
        val action = runCatching { ActionId.valueOf(name) }.getOrNull() ?: return
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val arg = intent.getStringExtra(EXTRA_ARG).orEmpty()
        work {
            UserActions.perform(context, action, arg)
            context.careApp.notifier.cancel(key)
            EngineRunner.tick(context, updatePlace = false)
        }
    }

    companion object {
        const val PREFIX = "app.dailycare.ACTION."
        const val EXTRA_KEY = "key"
        const val EXTRA_ARG = "arg"
    }
}

/** The engine's own wake-up (works in Doze, so reminders fire while the phone sits on the desk). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CareService.start(context)
        work { EngineRunner.tick(context) }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val inside = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val hub = context.careApp.signals
        event.triggeringGeofences?.forEach { fence ->
            runCatching { PlaceKind.valueOf(fence.requestId) }.getOrNull()?.let { hub.setGeofenceInside(it, inside) }
        }
        event.triggeringLocation?.let {
            hub.recordFix(LocationFix(it.time, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 100.0))
        }
        CareService.start(context)
        work { EngineRunner.tick(context) }
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now = System.currentTimeMillis()
        val bootNanos = SystemClock.elapsedRealtimeNanos()
        val transitions = result.transitionEvents.map { e ->
            MotionTransition(
                at = now - (bootNanos - e.elapsedRealTimeNanos) / 1_000_000,
                motion = ActivityRegistrar.motionOf(e.activityType),
                entering = e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            )
        }
        context.careApp.repository.addTransitions(transitions)
        CareService.start(context)
        work { EngineRunner.tick(context) }
    }
}

/** Geofences and activity updates are cleared on reboot and app update; set them up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CareService.start(context)
        work {
            GeofenceRegistrar.sync(context, context.careApp.repository.settings.value.places)
            ActivityRegistrar.register(context)
            EngineRunner.tick(context)
        }
    }
}
