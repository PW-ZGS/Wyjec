package pl.siren.mobile.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.siren.mobile.R
import pl.siren.mobile.ui.MainActivity

/** Loud, unmissable alarm: high-priority notification with full-screen intent, alarm tone and vibration. */
class Alerts(private val context: Context) {
    private var ringtone: Ringtone? = null

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Emergency alarms and orders"
            enableVibration(true)
            vibrationPattern = PATTERN
            setBypassDnd(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Standby", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Siren keeps listening for alarms"
        })
    }

    fun standbyNotification(text: String) = NotificationCompat.Builder(context, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_siren)
        .setContentTitle("Siren on standby")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    fun alarm(instanceId: String, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("ALARM — $title")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFD7262E.toInt())
            .setFullScreenIntent(openApp(), true)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notify(instanceId.hashCode(), n)
        startSound()
    }

    fun cancelled(instanceId: String, title: String) {
        stopSound()
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("$title ended")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        notify(instanceId.hashCode(), n)
    }

    fun startSound() {
        if (ringtone?.isPlaying == true) return
        ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        vibrator().vibrate(VibrationEffect.createWaveform(PATTERN, 0))
    }

    fun stopSound() {
        ringtone?.stop()
        ringtone = null
        vibrator().cancel()
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private fun notify(id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) } // SecurityException if notifications denied
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_ALARM = "alarms"
        const val CH_SERVICE = "standby"
        private val PATTERN = longArrayOf(0, 600, 300, 600, 300, 1200)
    }
}
