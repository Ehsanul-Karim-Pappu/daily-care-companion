package app.dailycare.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dailycare.R
import app.dailycare.core.CareSettings
import app.dailycare.core.NotificationAction
import app.dailycare.core.NotificationRequest
import app.dailycare.core.ReminderChannel
import app.dailycare.receivers.ActionReceiver
import app.dailycare.setup.Permissions
import app.dailycare.ui.MainActivity

class Notifier(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = mutableListOf<NotificationChannel>()
        for (c in ReminderChannel.entries) {
            val importance = if (c == ReminderChannel.MEDICINE) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            channels += NotificationChannel(channelId(c, quiet = false), "${label(c)} reminders", importance).apply {
                enableVibration(true)
            }
            channels += NotificationChannel(channelId(c, quiet = true), "${label(c)} reminders (vibrate only)", importance).apply {
                setSound(null, null)
                enableVibration(true)
                description = "Used while you're at work if 'vibrate only at work' is on."
            }
        }
        channels += NotificationChannel(STATUS_CHANNEL, "Monitoring status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Daily Care is watching for walks and places."
            setShowBadge(false)
        }
        nm.createNotificationChannels(channels)
    }

    private fun label(c: ReminderChannel) = when (c) {
        ReminderChannel.BREAK -> "Walking break"
        ReminderChannel.THERAPY -> "Therapy"
        ReminderChannel.EXERCISE -> "Exercise"
        ReminderChannel.MEDICINE -> "Medicine"
    }

    private fun channelId(c: ReminderChannel, quiet: Boolean) = c.name.lowercase() + if (quiet) "_quiet" else "_sound"

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(key: String, action: NotificationAction): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(ActionReceiver.PREFIX + action.action.name)
            // A distinct URI per notification+action keeps PendingIntents from overwriting each other.
            .setData(Uri.parse("dailycare://action/${action.action.name}/${Uri.encode(key)}/${Uri.encode(action.arg)}"))
            .putExtra(ActionReceiver.EXTRA_KEY, key)
            .putExtra(ActionReceiver.EXTRA_ARG, action.arg)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    fun post(req: NotificationRequest, settings: CareSettings) {
        if (!Permissions.hasNotifications(context)) return
        val quiet = req.atWork && settings.reminders.vibrateOnlyAtWork
        val builder = NotificationCompat.Builder(context, channelId(req.channel, quiet))
            .setSmallIcon(R.drawable.ic_stat_care)
            .setContentTitle(req.title)
            .setContentText(req.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        req.actions.forEach { builder.addAction(0, it.label, actionIntent(req.key, it)) }
        NotificationManagerCompat.from(context).notify(req.key, REMINDER_ID, builder.build())
    }

    fun cancel(key: String) {
        NotificationManagerCompat.from(context).cancel(key, REMINDER_ID)
    }

    fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_care)
            .setContentTitle("Daily Care is on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()

    @SuppressLint("MissingPermission")
    fun updateStatus(text: String) {
        if (!Permissions.hasNotifications(context)) return
        NotificationManagerCompat.from(context).notify(STATUS_ID, statusNotification(text))
    }

    companion object {
        const val STATUS_CHANNEL = "status"
        const val STATUS_ID = 1
        private const val REMINDER_ID = 100
    }
}
