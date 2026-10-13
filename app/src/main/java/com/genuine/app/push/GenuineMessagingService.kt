package com.genuine.app.push

import com.genuine.app.data.PushRepo
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

class GenuineMessagingService : FirebaseMessagingService() {

    /** Firebase occasionally rotates the device token; keep the server up to date. */
    override fun onNewToken(token: String) {
        AppScope.launch { runCatching { PushRepo.register(token) } }
    }

    /**
     * Called when the app is in the foreground. (In the background, Android shows
     * the notification itself from the FCM payload.)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["match_id"] != null && data["match_id"] == ActiveChat.matchId) return // already reading it
        Notifications.show(this, data)
    }
}