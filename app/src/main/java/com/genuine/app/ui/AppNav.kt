package com.genuine.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.genuine.app.data.ProfileRepo
import com.genuine.app.data.Supa
import com.genuine.app.ui.auth.AuthScreen
import com.genuine.app.ui.chat.ChatScreen
import com.genuine.app.ui.discover.HomeScreen
import com.genuine.app.ui.meetup.CheckInScreen
import com.genuine.app.ui.meetup.MeetupScreen
import com.genuine.app.ui.onboarding.CameraCaptureScreen
import com.genuine.app.ui.onboarding.ProfileSetupScreen
import com.genuine.app.ui.onboarding.VerificationScreen
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.exceptions.RestException
import androidx.navigation.compose.currentBackStackEntryAsState
import com.genuine.app.data.PushRepo
import com.genuine.app.push.AppScope
import com.genuine.app.push.ChatLink
import kotlinx.coroutines.launch

object Routes {
    const val GATE = "gate"
    const val AUTH = "auth"
    const val SETUP = "setup"
    const val CAMERA = "camera"
    const val VERIFY = "verify"
    const val HOME = "home"
    const val CHAT = "chat/{matchId}/{otherId}/{name}"
    const val MEETUP = "meetup/{matchId}/{name}"
    const val CHECKIN = "checkin/{meetupId}/{name}"

    const val ADD_PHOTO = "photo/add"

    fun chat(matchId: String, otherId: String, name: String) = "chat/$matchId/$otherId/${Uri.encode(name)}"
    fun meetup(matchId: String, name: String) = "meetup/$matchId/${Uri.encode(name)}"
    fun checkIn(meetupId: String, name: String) = "checkin/$meetupId/${Uri.encode(name)}"
}

@Composable
fun AppNav(deepLink: ChatLink? = null, onDeepLinkHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val toGate: () -> Unit = { nav.resetTo(Routes.GATE) }

    // Open the chat from a tapped notification, but only once the user is past login/onboarding
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(deepLink, currentRoute) {
        val link = deepLink ?: return@LaunchedEffect
        val ready = currentRoute != null && currentRoute !in setOf(
            Routes.GATE, Routes.AUTH, Routes.SETUP, Routes.CAMERA, Routes.VERIFY,
        )
        if (ready) {
            onDeepLinkHandled()
            nav.navigate(Routes.chat(link.matchId, link.otherId, link.name))
        }
    }

    NavHost(nav, startDestination = Routes.GATE) {
        composable(Routes.GATE) { GateScreen(go = { nav.resetTo(it) }) }
        composable(Routes.AUTH) { AuthScreen(onSignedIn = toGate) }
        composable(Routes.SETUP) { ProfileSetupScreen(onDone = toGate) }
        composable(Routes.CAMERA) { CameraCaptureScreen(onDone = toGate) }
        composable(Routes.VERIFY) { VerificationScreen(onVerified = toGate) }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { m -> nav.navigate(Routes.chat(m.matchId, m.otherId, m.otherName)) },
                onAddPhoto = { nav.navigate(Routes.ADD_PHOTO) },
                onSignedOut = { nav.resetTo(Routes.GATE) },
            )
        }
        composable(Routes.CHAT) { e ->
            val a = e.arguments!!
            val matchId = a.getString("matchId")!!
            val name = a.getString("name")!!
            ChatScreen(
                matchId = matchId,
                otherId = a.getString("otherId")!!,
                otherName = name,
                onPlanMeetup = { nav.navigate(Routes.meetup(matchId, name)) },
                onCheckIn = { meetupId -> nav.navigate(Routes.checkIn(meetupId, name)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MEETUP) { e ->
            val a = e.arguments!!
            MeetupScreen(a.getString("matchId")!!, a.getString("name")!!, onBack = { nav.popBackStack() })
        }
        composable(Routes.CHECKIN) { e ->
            val a = e.arguments!!
            CheckInScreen(a.getString("meetupId")!!, a.getString("name")!!, onDone = { nav.popBackStack() })
        }
        composable(Routes.ADD_PHOTO) { CameraCaptureScreen(onDone = { nav.popBackStack() }) }
    }
}

private fun NavHostController.resetTo(route: String) =
    navigate(route) { popUpTo(0) { inclusive = true } }

/** Routes a user to the right onboarding step based on server state. */
@Composable
private fun GateScreen(go: (String) -> Unit) {
    val status by Supa.client.auth.sessionStatus.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(status, attempt) {
        when (status) {
            is SessionStatus.Authenticated -> {
                error = null
                // Ask the server whether this login still belongs to a real account.
                val accountGone = runCatching {
                    Supa.client.auth.retrieveUserForCurrentSession(updateSession = true)
                }.exceptionOrNull().let { e -> e is RestException && e.statusCode in listOf(401, 403, 404) }

                if (accountGone) {
                    // Clears the saved login; the status changes to NotAuthenticated,
                    // and this same effect then sends the user to the login screen.
                    runCatching { Supa.client.auth.clearSession() }
                    return@LaunchedEffect
                }

                runCatching { ProfileRepo.nextStep() }
                    .onSuccess { route ->
                        if (route == Routes.HOME) AppScope.launch {
                            runCatching { PushRepo.register() }
                                .onSuccess { android.util.Log.d("Push", "Device registered") }
                                .onFailure { android.util.Log.e("Push", "Register failed", it) }
                        }
                        go(route)
                    }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it // superseded by a newer check; not an error
                        android.util.Log.e("Gate", "nextStep failed", it)
                        error = it.message ?: it.toString()
                    }
            }
            is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> go(Routes.AUTH)
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val msg = error
        if (msg == null) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load your account:\n$msg")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
        }
    }
}
