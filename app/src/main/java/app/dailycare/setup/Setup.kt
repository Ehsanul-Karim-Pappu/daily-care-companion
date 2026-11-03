package app.dailycare.setup

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {
    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context) = has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivityRecognition(context: Context) = has(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(context, Manifest.permission.POST_NOTIFICATIONS)

    fun hasNearbyWifi(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(context, Manifest.permission.NEARBY_WIFI_DEVICES)

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The monitoring service needs at least one foreground-service type it is allowed to use. */
    fun canRunService(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasFineLocation(context) || hasActivityRecognition(context)
        } else {
            true
        }
}

/** Shortcuts into system settings, including Vivo and Xiaomi/Poco specific screens. */
object PhoneSettings {
    val manufacturer: String = Build.MANUFACTURER.orEmpty()
    private val maker = manufacturer.lowercase()
    val isXiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")
    val isVivo = maker.contains("vivo") || maker.contains("iqoo")

    /** Steps the person needs to do by hand on this phone so Android doesn't stop the app. */
    fun backgroundGuidance(): List<String> = when {
        isXiaomi -> listOf(
            "Settings → Apps → Manage apps → Daily Care → Autostart: turn on.",
            "Same screen → Battery saver → No restrictions.",
            "Open recent apps, long-press Daily Care and tap the lock icon so it isn't cleared.",
            "Notifications: allow all, including on the lock screen.",
        )
        isVivo -> listOf(
            "Settings → Battery → Background power consumption management → Daily Care → Allow (don't restrict).",
            "Settings → Apps → Autostart (or i Manager → App manager → Autostart): turn Daily Care on.",
            "Open recent apps and lock Daily Care (pull it down or tap the lock) so it isn't cleared.",
            "Notifications: allow all, including banners and lock screen.",
        )
        else -> listOf(
            "Allow the app to run without battery restrictions.",
            "If your phone has an 'autostart' or 'background activity' list, turn Daily Care on there.",
        )
    }

    private fun autostartIntents(): List<Intent> = when {
        isXiaomi -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        isVivo -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        else -> emptyList()
    }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun tryStart(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    fun openAutostart(context: Context) {
        tryStart(context, autostartIntents() + appDetails(context))
    }

    fun openAppDetails(context: Context) {
        tryStart(context, listOf(appDetails(context)))
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        tryStart(
            context,
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(context),
            ),
        )
    }

    fun requestExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tryStart(
                context,
                listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")), appDetails(context)),
            )
        }
    }
}
