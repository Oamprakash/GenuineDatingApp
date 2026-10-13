package com.genuine.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.genuine.app.data.ChatRepo
import com.genuine.app.data.DiscoverRepo
import com.genuine.app.data.MatchSummary
import com.genuine.app.data.ProfileRepo
import com.genuine.app.data.Suggestion
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.genuine.app.data.Supa
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.debounce
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.style.TextOverflow
import com.genuine.app.data.SafetyRepo
import com.genuine.app.ui.common.ProfileCard
import androidx.compose.material.icons.filled.Person
import com.genuine.app.ui.profile.ProfileTab
import androidx.compose.foundation.clickable
import com.genuine.app.ui.common.ProfileDetail
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import com.genuine.app.ui.common.ProfileOptions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.genuine.app.data.AuthRepo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.genuine.app.push.TypingHub
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.text.font.FontStyle
import java.util.UUID


@Composable
fun HomeScreen(onOpenChat: (MatchSummary) -> Unit, onAddPhoto: () -> Unit, onSignedOut: () -> Unit) {
    val matchesVm: MatchesViewModel = viewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { matchesVm.load() }
    val unread = matchesVm.matches.sumOf { it.unreadCount }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Today") })
                NavigationBarItem(
                    tab == 1, { tab = 1 },
                    icon = {
                        BadgedBox(badge = {
                            if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                        }) { Icon(Icons.Default.ChatBubble, null) }
                    },
                    label = { Text("Matches") },
                )
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> DiscoverTab(onOpenChat)
                1 -> MatchesTab(onOpenChat, matchesVm)
                else -> ProfileTab(onAddPhoto, onSignedOut)
            }
        }
    }
}

// ---------------- Discover ----------------

private const val DAILY_LIMIT = 10 // must match enforce_daily_swipes() in schema.sql

class DiscoverViewModel : ViewModel() {
    var queue by mutableStateOf<List<Suggestion>>(emptyList())
    var photoUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var swipesUsed by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Setting this sends a one-off snackbar message; existing `message = "…"` lines keep working. */
    var message: String?
        get() = null
        set(value) { value?.let { _messages.trySend(it) } }
    data class NewMatch(val matchId: String, val otherId: String, val name: String, val theirPhoto: String?)

    var newMatch by mutableStateOf<NewMatch?>(null)
    var myPhotoUrl by mutableStateOf<String?>(null)
    var myName by mutableStateOf("")

    val swipesLeft get() = (DAILY_LIMIT - swipesUsed).coerceAtLeast(0)

    var paused by mutableStateOf(false)

    init {
        load()
        viewModelScope.launch {
            runCatching {
                myName = ProfileRepo.myProfile()?.displayName.orEmpty()
                myPhotoUrl = ProfileRepo.myPhotos().firstOrNull()?.let { ProfileRepo.photoUrl(it.storagePath) }
            }
        }
    }

    fun load(isRefresh: Boolean = false) = viewModelScope.launch {
        if (isRefresh) refreshing = true else loading = true
        AuthRepo.awaitSignedIn()
        runCatching {
            paused = ProfileRepo.myPrivate()?.paused == true
            swipesUsed = DiscoverRepo.swipesUsedToday()
            val list = if (paused) emptyList() else DiscoverRepo.suggestions()
            queue = list
            photoUrls = list.mapNotNull { s -> s.photoPath?.let { s.id to ProfileRepo.photoUrl(it) } }.toMap()
        }.onFailure { message = it.message }
        loading = false
        refreshing = false
    }

    /** Called when the tab reappears; reloads only if pause was toggled in the Profile tab. */
    fun checkPaused() =
        viewModelScope.launch {
            AuthRepo.awaitSignedIn()
        val now = runCatching { ProfileRepo.myPrivate()?.paused == true }.getOrNull() ?: return@launch
        if (now != paused) load()
    }

    fun unpause() = viewModelScope.launch {
        runCatching { ProfileRepo.setPaused(false) }
            .onSuccess {
                message = "Welcome back! You're visible in Discovery again."
                load()
            }
            .onFailure { message = "Couldn't unpause. Please try again." }
    }

    fun swipe(s: Suggestion, liked: Boolean) = viewModelScope.launch {
        val theirPhoto = photoUrls[s.id] // grab before the card leaves the queue
        queue = queue.drop(1)
        swipesUsed++
        runCatching { DiscoverRepo.swipe(s.id, liked) }
            .onSuccess { matchId ->
                if (matchId != null) newMatch = NewMatch(matchId, s.id, s.displayName, theirPhoto)
            }
            .onFailure { swipesUsed--; message = it.message }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverTab(onOpenChat: (MatchSummary) -> Unit, vm: DiscoverViewModel = viewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkPaused() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // collectLatest: a new message replaces the one on screen instead of queueing
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    var viewing by remember { mutableStateOf<Suggestion?>(null) }
    var decision by remember { mutableStateOf<Boolean?>(null) } // true = like, false = pass

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.load(isRefresh = true) },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.fillParentMaxSize().padding(16.dp)) {
                        Text(
                            if (vm.loading || vm.paused) "Discovery" else "Discovery (${vm.swipesLeft} left today)",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            DiscoverContent(vm, onOpen = { viewing = it }, decision = decision,
                                onDecisionHandled = { decision = null })
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        viewing?.let { s ->
            ProfileDetail(s.id, onClose = { viewing = null }) {
                OutlinedButton({ viewing = null; decision = false }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(8.dp)); Text("Pass")
                }
                Button({ viewing = null; decision = true }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(8.dp)); Text("Like")
                }
            }
        }

        vm.newMatch?.let { m ->
            MatchCelebration(
                match = m,
                myPhotoUrl = vm.myPhotoUrl,
                myName = vm.myName,
                onMessage = {
                    vm.newMatch = null
                    onOpenChat(MatchSummary(matchId = m.matchId, otherId = m.otherId, otherName = m.name, status = "active"))
                },
                onDismiss = { vm.newMatch = null },
            )
        }
    }
}

@Composable
private fun BoxScope.DiscoverContent(
    vm: DiscoverViewModel,
    onOpen: (Suggestion) -> Unit,
    decision: Boolean?,
    onDecisionHandled: () -> Unit,
) {
    val current = vm.queue.firstOrNull()
    when {
        vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

        vm.paused -> Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Your profile is paused", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "You're hidden from Discovery. Your matches and chats are still here.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.unpause() }) { Text("Unpause") }
        }

        vm.swipesLeft == 0 -> EmptyState(
            "You've seen your 10 profiles for today",
            "New people at midnight IST. A few thoughtful picks beat endless swiping.",
        )

        current == null -> EmptyState("No one new nearby right now", "Pull down to check again later.")

        else -> {
            val scope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            val offsetX = remember(current.id) { Animatable(0f) }
            var swiping by remember(current.id) { mutableStateOf(false) }
            var cardWidth by remember { mutableFloatStateOf(1f) }
            val threshold = cardWidth * 0.3f

            // -1 = fully "pass", +1 = fully "like"
            val progress = (offsetX.value / threshold).coerceIn(-1f, 1f)

            // Vibrate once when the drag passes the like/pass point
            val armed = abs(progress) >= 1f
            LaunchedEffect(armed) { if (armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

            fun flyOut(liked: Boolean) {
                if (swiping) return
                swiping = true
                scope.launch {
                    offsetX.animateTo(if (liked) cardWidth * 1.5f else -cardWidth * 1.5f, tween(250))
                    vm.swipe(current, liked)
                }
            }

            // Like/Pass chosen on the full profile: play the same fly-out
            LaunchedEffect(decision, current.id) {
                decision?.let { onDecisionHandled(); flyOut(liked = it) }
            }

            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { cardWidth = it.width.toFloat() }) {
                    // Next card waits behind and grows as the top card moves away
                    vm.queue.getOrNull(1)?.let { next ->
                        val scale = 0.95f + 0.05f * abs(progress)
                        // the card waiting behind
                        ProfileCard(next.displayName, next.age, next.bio, next.interests, vm.photoUrls[next.id], next.distanceKm, 0f,
                            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale },
                            goal = ProfileOptions.show(ProfileOptions.goals, next.relationshipGoal), )
                    }

                    // the top card
                    ProfileCard(
                        current.displayName, current.age, current.bio, current.interests,
                        vm.photoUrls[current.id], current.distanceKm, progress,
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                                translationX = offsetX.value
                                rotationZ = offsetX.value / cardWidth * 12f
                            }
                            // Outer: only fires if the swipe detector below didn't claim the touch
                            .clickable(enabled = !swiping) { onOpen(current) }
                            // Inner: sees the touch first, and claims it as soon as your finger moves sideways
                            .pointerInput(current.id) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                                    },
                                    onDragEnd = {
                                        when {
                                            offsetX.value > threshold -> flyOut(liked = true)
                                            offsetX.value < -threshold -> flyOut(liked = false)
                                            else -> scope.launch { offsetX.animateTo(0f, spring()) }
                                        }
                                    },
                                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                                )
                            },
                        goal = ProfileOptions.show(ProfileOptions.goals, current.relationshipGoal),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FloatingActionButton(
                        onClick = { flyOut(liked = false) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Icon(Icons.Default.Close, contentDescription = "Pass") }

                    FloatingActionButton(
                        onClick = { flyOut(liked = true) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) { Icon(Icons.Default.Favorite, contentDescription = "Like") }
                }
            }
        }
    }
}



@Composable
private fun BoxScope.EmptyState(title: String, body: String) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- Matches ----------------

@OptIn(FlowPreview::class)
class MatchesViewModel : ViewModel() {
    var matches by mutableStateOf<List<MatchSummary>>(emptyList())
    var photoUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var refreshing by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    var typingIds by mutableStateOf<Set<String>>(emptySet())
    private val typingMutex = Mutex()
    private var typingJoined = emptySet<String>()
    private val typingOwner = "matches-${UUID.randomUUID()}"

    /** Setting this sends a one-off snackbar message; existing `message = "…"` lines keep working. */
    var message: String?
        get() = null
        set(value) { value?.let { _messages.trySend(it) } }
    private var channel: RealtimeChannel? = null

    init {
        load()
        viewModelScope.launch {
            runCatching {
                val (ch, events) = ChatRepo.inboxEvents()
                channel = ch
                launch { events.debounce(300).collect { load() } }
                ch.subscribe()
            }.onFailure { android.util.Log.e("Matches", "realtime failed", it) }
        }
        viewModelScope.launch { TypingHub.typing.collect { typingIds = it } }
    }

    fun load(isRefresh: Boolean = false) = viewModelScope.launch {
        if (isRefresh) refreshing = true
        AuthRepo.awaitSignedIn()
        runCatching { ChatRepo.myMatches() }.onSuccess { list ->
            matches = list
            photoUrls = list.mapNotNull { m -> m.otherPhoto?.let { m.matchId to ProfileRepo.photoUrl(it) } }.toMap()
            syncTyping(list)
            launch { runCatching { ChatRepo.markDelivered() } }
        } // on failure, keep the list we already have
        refreshing = false
        loaded = true
    }

    /** Keeps typing connections open for the 20 most recent matches only. */
    private fun syncTyping(list: List<MatchSummary>) = viewModelScope.launch {
        typingMutex.withLock {
            val wanted = list.take(20).map { it.matchId }.toSet()
            (wanted - typingJoined).forEach { TypingHub.join(it, typingOwner) }
            (typingJoined - wanted).forEach { TypingHub.leave(it, typingOwner) }
            typingJoined = wanted
        }
    }

    /** Removes the row right away; restores it if the server call fails. */
    fun unmatch(m: MatchSummary, block: Boolean) = viewModelScope.launch {
        val before = matches
        matches = matches.filterNot { it.matchId == m.matchId }
        runCatching {
            if (block) SafetyRepo.block(m.otherId)
            ChatRepo.unmatch(m.matchId)
        }.onSuccess {
            message = if (block) "${m.otherName} is unmatched and blocked." else "Unmatched ${m.otherName}."
        }.onFailure {
            matches = before
            message = "Couldn't unmatch. Please try again."
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        val joined = typingJoined
        val ch = channel
        GlobalScope.launch {
            joined.forEach { TypingHub.leave(it, typingOwner) }
            ch?.let { runCatching { Supa.client.realtime.removeChannel(it) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchesTab(onOpenChat: (MatchSummary) -> Unit, vm: MatchesViewModel = viewModel()) {


    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // collectLatest: a new message replaces the one on screen instead of queueing
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    // (match, block?) waiting for confirmation
    var confirm by remember { mutableStateOf<Pair<MatchSummary, Boolean>?>(null) }

    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = { vm.load(isRefresh = true) },
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    if (vm.matches.isEmpty()) "Matches" else "Matches (${vm.matches.size})",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (vm.loaded && vm.matches.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxHeight(0.8f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No matches yet", style = MaterialTheme.typography.titleMedium)
                            Text("When someone likes you back, they'll show up here.")
                        }
                    }
                }
            }
            items(vm.matches, key = { it.matchId }) { m ->
                MatchRow(
                    m = m,
                    photoUrl = vm.photoUrls[m.matchId],
                    typing = m.matchId in vm.typingIds,
                    onClick = { onOpenChat(m) },
                    onUnmatch = { block -> confirm = m to block },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    confirm?.let { (m, block) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (block) "Unmatch and block ${m.otherName}?" else "Unmatch ${m.otherName}?") },
            text = {
                Text(
                    if (block) "Your chat will close and you won't see each other again anywhere in the app."
                    else "Your chat will close. This can't be undone."
                )
            },
            confirmButton = {
                TextButton({ confirm = null; vm.unmatch(m, block) }) {
                    Text(if (block) "Unmatch & block" else "Unmatch", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MatchRow(
    m: MatchSummary,
    photoUrl: String?,
    typing: Boolean,
    onClick: () -> Unit,
    onUnmatch: (block: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val (status, tone) = matchStatus(m)

    Card(onClick = onClick, modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (photoUrl != null) {
                    AsyncImage(photoUrl, m.otherName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(m.otherName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val unread = m.unreadCount > 0

                Text(m.otherName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else null)
                if (typing) {
                    Text("typing…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, fontStyle = FontStyle.Italic)
                } else {
                    Text(
                        m.lastMessage ?: "Say hi and suggest a place to meet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (unread) FontWeight.SemiBold else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (tone == Tone.Action) FontWeight.SemiBold else null,
                        color = when (tone) {
                            Tone.Action -> MaterialTheme.colorScheme.primary
                            Tone.Good -> MaterialTheme.colorScheme.secondary
                            Tone.Urgent -> MaterialTheme.colorScheme.error
                            Tone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (unread) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(if (m.unreadCount > 99) "99+" else m.unreadCount.toString())
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }

            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Unmatch") }, onClick = { menu = false; onUnmatch(false) })
                    DropdownMenuItem(
                        text = { Text("Unmatch & block", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onUnmatch(true) },
                    )
                }
            }
        }
    }
}

private val IST = ZoneId.of("Asia/Kolkata")
private val meetupFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

/** Action = something for you to do; Good = all set; Urgent = about to expire. */
private enum class Tone { Action, Good, Normal, Urgent }

private fun matchStatus(m: MatchSummary): Pair<String, Tone> {
    val name = m.otherName
    val at = m.meetupAt?.let { OffsetDateTime.parse(it) }
    val now = OffsetDateTime.now()
    val meetAgain = "You both want to meet again · Plan next" to Tone.Action

    // A plan is in progress
    when (m.meetupStatus) {
        "proposed" -> return when {
            m.meetupByMe -> "Waiting for $name's reply" to Tone.Normal
            m.meetupUpdated -> "$name updated the plan" to Tone.Action
            else -> "$name suggested a meetup" to Tone.Action
        }

        "accepted" -> if (at != null) {
            if (at.isAfter(now)) return "Meetup ${at.atZoneSameInstant(IST).format(meetupFmt)}" to Tone.Good
            return when {
                !m.checkedIn -> "How did it go? Check in" to Tone.Action
                m.wantsAgain -> meetAgain
                else -> "You've met" to Tone.Normal
            }
        }
    }

    // No plan in progress, but they've met before (e.g. a later plan was cancelled)
    if (m.status == "meetup_planned") {
        return if (m.wantsAgain) meetAgain else "You've met" to Tone.Normal
    }

    // Still getting to know each other: show the chat countdown
    val exp = m.chatExpiresAt?.let { OffsetDateTime.parse(it) } ?: return "" to Tone.Normal
    val hours = Duration.between(now, exp).toHours().coerceAtLeast(0)
    return when {
        hours >= 48 -> "Chat closes in ${hours / 24} days" to Tone.Normal
        hours >= 24 -> "Chat closes in 1 day" to Tone.Urgent
        else -> "Closes in ${hours}h. Plan a meetup!" to Tone.Urgent
    }
}
