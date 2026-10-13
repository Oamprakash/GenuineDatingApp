package com.genuine.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.genuine.app.data.Supa
import com.genuine.app.push.ChatLink
import com.genuine.app.push.Notifications
import com.genuine.app.ui.AppNav
import com.genuine.app.ui.theme.GenuineTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {
    private val deepLink = mutableStateOf<ChatLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            Supa.client.auth.sessionStatus.value is SessionStatus.Initializing
        }

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        Notifications.createChannels(this)
        deepLink.value = ChatLink.from(intent) // app opened from a notification

        enableEdgeToEdge()
        setContent {
            GenuineTheme {
                Surface {
                    AppNav(deepLink = deepLink.value, onDeepLinkHandled = { deepLink.value = null })
                }
            }
        }
    }

    /** Notification tapped while the app was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ChatLink.from(intent)?.let { deepLink.value = it }
    }
}