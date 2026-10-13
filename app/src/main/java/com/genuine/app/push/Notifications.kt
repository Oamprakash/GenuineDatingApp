package com.genuine.app.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.genuine.app.MainActivity
import com.genuine.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Long-lived scope for work that must outlive a screen (e.g. registering the device). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** The chat currently on screen, so we don't notify about it. */
object ActiveChat {
    @Volatile var matchId: String? = null
}

/** Opened from a notification tap. */
data class ChatLink(val matchId: String, val otherId: String, val name: String) {
    companion object {
        fun from(intent: Intent?): ChatLink? {
            val e = intent?.extras ?: return null
            val matchId = e.getString("match_id") ?: return null
            val otherId = e.getString("other_id") ?: return null
            return ChatLink(matchId, otherId, e.getString("other_name").orEmpty())
        }
    }
}

object Notifications {
    const val CH_MATCHES = "matches"
    const val CH_MESSAGES = "messages"
    const val CH_MEETUPS = "meetups"

    const val CH_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MATCHES, "New matches", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_MEETUPS, "Meetup plans", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    /** Builds the notification ourselves when the app is in the foreground. */
    @SuppressLint("MissingPermission") // checked below
    fun show(context: Context, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val type = data["type"] ?: "message"
        val channel = when (type) {
            "match" -> CH_MATCHES
            "message" -> CH_MESSAGES
            "reminder", "checkin" -> CH_REMINDERS
            else -> CH_MEETUPS
        }
        val tag = if (channel == CH_REMINDERS) "$type:${data["match_id"]}" else (data["match_id"] ?: type)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (k, v) -> putExtra(k, v) }
        }
        val pending = PendingIntent.getActivity(
            context, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_coral))
            .setContentTitle(data["title"])
            .setContentText(data["body"])
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Same tag replaces the previous notification for this chat
        NotificationManagerCompat.from(context).notify(tag, 0, notification)
    }

    fun clear(context: Context, matchId: String) {
        NotificationManagerCompat.from(context).cancel(matchId, 0)
    }
}