package com.genuine.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.genuine.app.data.*
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.clickable
import com.genuine.app.ui.common.ProfileDetail
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.genuine.app.push.ActiveChat
import com.genuine.app.push.Notifications
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import com.genuine.app.push.TypingHub
import java.util.UUID
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val IST = ZoneId.of("Asia/Kolkata")
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val ReadBlueLight = Color(0xFF1976D2)
private val ReadBlueDark = Color(0xFF64B5F6)

// ---------------- ViewModel ----------------

class ChatViewModel(private val matchId: String, private val otherId: String) : ViewModel() {
    val me = AuthRepo.uid()
    var messages by mutableStateOf<List<Message>>(emptyList())
    var meetup by mutableStateOf<Meetup?>(null)
    var match by mutableStateOf<MatchState?>(null)
    var photoUrl by mutableStateOf<String?>(null)
    var draft by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var channel: RealtimeChannel? = null
    var otherTyping by mutableStateOf(false)
    private var lastTypingSent = 0L
    private val typingOwner = "chat-${UUID.randomUUID()}"
    var replyingTo by mutableStateOf<Message?>(null)
    var otherReadId by mutableLongStateOf(0L)
    var otherDeliveredId by mutableLongStateOf(0L)

    /** False once the chat expired or someone unmatched. */
    val isOpen: Boolean
        get() {
            val m = match ?: return true
            if (m.status == "meetup_planned") return true
            if (m.status != "active") return false
            return m.chatExpiresAt?.let { OffsetDateTime.parse(it).isAfter(OffsetDateTime.now()) } ?: true
        }

    init {
        viewModelScope.launch {
            runCatching {
                messages = ChatRepo.history(matchId)
                markRead()
                runCatching { loadReceipts() }
                loadMeetup()
                match = ChatRepo.matchState(matchId)

                val (ch, newMessages) = ChatRepo.live(matchId)
                channel = ch
                launch {
                    newMessages.collect { msg ->
                        addMessages(listOf(msg))
                        if (msg.senderId != me) {
                            TypingHub.clear(matchId)
                            if (ActiveChat.matchId == matchId) markRead()
                        }
                    }
                }

                val meetupChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "meetups"; filter("match_id", FilterOperator.EQ, matchId)
                }
                launch { meetupChanges.collect { refreshMeetup() } }

                val matchChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "matches"; filter("id", FilterOperator.EQ, matchId)
                }
                launch { matchChanges.collect { refreshMatch() } }

                val readChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "match_reads"; filter("match_id", FilterOperator.EQ, matchId)
                }
                launch { readChanges.collect { refreshReceipts() } }

                ch.subscribe()
            }.onFailure { error = it.message }
        }
        viewModelScope.launch {
            runCatching { ChatRepo.firstPhotoPath(otherId)?.let { photoUrl = ProfileRepo.photoUrl(it) } }
        }
        viewModelScope.launch {
            TypingHub.join(matchId, typingOwner)
            TypingHub.typing.collect { otherTyping = matchId in it }
        }
    }

    var checkedIn by mutableStateOf(false)

    /** Adds a message once, whether it arrives from sending, Realtime, or a reload. */
    private fun addMessages(new: List<Message>) {
        messages = (messages + new).distinctBy { it.id }.sortedBy { it.id }
    }

    /** Called on every keystroke; sends at most one signal every 2.5 seconds. */
    fun onDraftChange(text: String) {
        draft = text.take(1000)
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingSent > 2_500) {
            lastTypingSent = now
            sendTyping(true)
        } else if (text.isBlank() && lastTypingSent != 0L) {
            lastTypingSent = 0L
            sendTyping(false)
        }
    }

    private fun sendTyping(typing: Boolean) = viewModelScope.launch { TypingHub.send(matchId, typing) }

    /** Catches up on anything missed while the live connection was down. */
    fun refreshMessages() = viewModelScope.launch {
        AuthRepo.awaitSignedIn()
        runCatching { ChatRepo.history(matchId) }.onSuccess { addMessages(it); markRead() }
        runCatching { loadReceipts() }
    }

    /** Loads the latest meetup and whether I've already checked in for it. */
    private suspend fun loadMeetup() {
        val latest = MeetupRepo.latest(matchId)
        checkedIn = latest != null && latest.status == "accepted" && MeetupRepo.hasCheckedIn(latest.id)
        wantsAgain = checkedIn && MeetupRepo.wantsAgain(matchId)
        meetup = latest
    }

    fun refreshMeetup() = viewModelScope.launch {
        AuthRepo.awaitSignedIn()
        runCatching { loadMeetup() } }
    private fun refreshMatch() = viewModelScope.launch { runCatching { match = ChatRepo.matchState(matchId) } }

    fun send() = viewModelScope.launch {
        val text = draft.trim().ifEmpty { return@launch }
        val reply = replyingTo
        draft = ""
        replyingTo = null
        lastTypingSent = 0L
        sendTyping(false)
        runCatching { ChatRepo.send(matchId, text, reply?.id) }
            .onSuccess { saved -> addMessages(listOf(saved)) }
            .onFailure {
                draft = text
                replyingTo = reply // put the reply back so nothing is lost
                error = "Couldn't send. This chat may have closed."
                refreshMatch()
            }
    }

    fun unmatch(done: () -> Unit) = viewModelScope.launch {
        runCatching { ChatRepo.unmatch(matchId) }.onSuccess { done() }
    }

    fun report(reason: String, done: () -> Unit) = viewModelScope.launch {
        runCatching { SafetyRepo.report(otherId, reason, null); SafetyRepo.block(otherId); ChatRepo.unmatch(matchId) }
            .onSuccess { done() }
            .onFailure { error = "Couldn't send report. Please try again." }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        val ch = channel
        GlobalScope.launch {
            TypingHub.leave(matchId, typingOwner)
            ch?.let { runCatching { Supa.client.realtime.removeChannel(it) } }
        }
    }

    var wantsAgain by mutableStateOf(false)

    fun markRead() = viewModelScope.launch { runCatching { ChatRepo.markRead(matchId) } }

    private suspend fun loadReceipts() {
        val other = ChatRepo.readStates(matchId).firstOrNull { it.userId != me } ?: return
        otherReadId = other.lastReadId
        otherDeliveredId = other.lastDeliveredId
    }

    fun refreshReceipts() = viewModelScope.launch { runCatching { loadReceipts() } }
}

// ---------------- Screen ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    matchId: String,
    otherId: String,
    otherName: String,
    onPlanMeetup: () -> Unit,
    onCheckIn: (meetupId: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ChatViewModel = viewModel(key = matchId) { ChatViewModel(matchId, otherId) }
    val items = remember(vm.messages) { buildItems(vm.messages) }
    val listState = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    var unmatchDialog by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var highlightedId by remember { mutableStateOf<Long?>(null) }

// Starting a reply opens the keyboard
    LaunchedEffect(vm.replyingTo) {
        if (vm.replyingTo != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    /** Scrolls to a quoted message and highlights it briefly. */
    fun jumpTo(messageId: Long) {
        val index = items.indexOfFirst { it is ChatItem.Msg && it.message.id == messageId }
        if (index < 0) return
        scope.launch {
            listState.animateScrollToItem(index)
            highlightedId = messageId
            delay(1_500)
            highlightedId = null
        }
    }

    val context = LocalContext.current
// While this chat is visible, skip its notifications and clear any already shown
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ActiveChat.matchId = matchId
        Notifications.clear(context, matchId)
        vm.refreshMessages()
        vm.refreshMeetup()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (ActiveChat.matchId == matchId) ActiveChat.matchId = null
    }
    DisposableEffect(matchId) {
        onDispose { if (ActiveChat.matchId == matchId) ActiveChat.matchId = null }
    }

    LaunchedEffect(Unit) { vm.refreshMeetup() }
    LaunchedEffect(items.size, vm.otherTyping) {
        val last = items.size - 1 + if (vm.otherTyping) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { showProfile = true }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(vm.photoUrl, otherName, 40)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(otherName, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.CheckCircle, "Verified",
                                    Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            if (vm.otherTyping) {
                                Text("typing…", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            } else {
                                chatSubtitle(vm.match).takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Unmatch") }, { menu = false; unmatchDialog = true })
                            DropdownMenuItem(
                                { Text("Report & block", color = MaterialTheme.colorScheme.error) },
                                { menu = false; reportDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (vm.isOpen && (!vm.checkedIn || vm.wantsAgain)) {
                MeetupBanner(vm.meetup, vm.me, otherName, vm.wantsAgain, onPlanMeetup, onCheckIn)
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (vm.messages.isEmpty()) item { FirstMessageCard(otherName) }
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.DayHeader -> DayDivider(item.label)
                        is ChatItem.Msg -> {
                            val m = item.message
                            val mine = m.senderId == vm.me
                            if (m.kind == "meetup_note") {
                                SystemNote(m.body, if (mine) "You" else otherName)
                            } else {
                                val quoted = m.replyTo?.let { id -> vm.messages.firstOrNull { it.id == id } }
                                val receipt = if (!mine) null else when {
                                    m.id <= vm.otherReadId -> Receipt.Read
                                    m.id <= vm.otherDeliveredId -> Receipt.Delivered
                                    else -> Receipt.Sent
                                }
                                SwipeToReply(enabled = vm.isOpen, onReply = { vm.replyingTo = m }) {
                                    Bubble(
                                        m = m,
                                        mine = mine,
                                        receipt = receipt,
                                        quoted = quoted,
                                        quotedName = quoted?.let { if (it.senderId == vm.me) "You" else otherName },
                                        highlighted = highlightedId == m.id,
                                        onQuoteClick = { m.replyTo?.let(::jumpTo) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (vm.otherTyping && vm.isOpen) {
                    item(key = "typing") { TypingBubble() }
                }
            }

            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (vm.isOpen) {
                vm.replyingTo?.let { r ->
                    ReplyBar(
                        name = if (r.senderId == vm.me) "yourself" else otherName,
                        text = r.body,
                        onCancel = { vm.replyingTo = null },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        vm.draft, { vm.onDraftChange(it) },
                        Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = { Text("Message") }, maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton({ vm.send() }, enabled = vm.draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (vm.match?.status == "unmatched") "This match has ended."
                        else "This chat closed because no meetup was agreed within 7 days.",
                        Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (showProfile) {
        ProfileDetail(otherId, onClose = { showProfile = false }) {
            OutlinedButton({ showProfile = false; unmatchDialog = true }, Modifier.weight(1f)) {
                Text("Unmatch")
            }
            OutlinedButton(
                { showProfile = false; reportDialog = true }, Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Report") }
        }
    }
}

    if (unmatchDialog) {
        AlertDialog(
            onDismissRequest = { unmatchDialog = false },
            title = { Text("Unmatch $otherName?") },
            text = { Text("Your chat will close. This can't be undone.") },
            confirmButton = {
                TextButton({ unmatchDialog = false; vm.unmatch(onBack) }) {
                    Text("Unmatch", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ unmatchDialog = false }) { Text("Cancel") } },
        )
    }

    if (reportDialog) {
        val reasons = listOf(
            "fake_profile" to "Fake or misleading profile",
            "harassment" to "Harassment or inappropriate messages",
            "scam" to "Asking for money or moving off the app",
            "unsafe" to "Made me feel unsafe",
            "other" to "Something else",
        )
        AlertDialog(
            onDismissRequest = { reportDialog = false },
            title = { Text("Report $otherName") },
            text = {
                Column {
                    Text("They'll be blocked and the match will end. Reports are reviewed privately.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    reasons.forEach { (key, label) ->
                        TextButton({ reportDialog = false; vm.report(key, onBack) }, Modifier.fillMaxWidth()) {
                            Text(label, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ reportDialog = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- Pieces ----------------

@Composable
private fun Avatar(url: String?, name: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MeetupBanner(
    meetup: Meetup?,
    me: String,
    otherName: String,
    wantsAgain: Boolean,
    onPlan: () -> Unit,
    onCheckIn: (String) -> Unit,
) {
    val text: String
    val action: String
    val onClick: () -> Unit
    if (meetup == null || meetup.status == "declined" || meetup.status == "cancelled") {
        text = "This chat closes unless you both agree to meet."; action = "Plan a meetup"; onClick = onPlan
    } else if (meetup.status == "proposed" && meetup.proposedBy == me) {
        text = if (meetup.replaces != null) "You updated the plan. Waiting for $otherName to reply."
        else "You suggested ${meetup.venueName}. Waiting for $otherName to reply."
        action = "View"; onClick = onPlan
    } else if (meetup.status == "proposed") {
        text = if (meetup.replaces != null) "$otherName updated the plan: ${meetup.venueName}."
        else "$otherName suggested meeting at ${meetup.venueName}."
        action = "Respond"; onClick = onPlan
    } else if (wantsAgain && meetup.status == "accepted" &&
        OffsetDateTime.parse(meetup.scheduledAt).isBefore(OffsetDateTime.now())) {
        text = "You and $otherName both want to meet again!"; action = "Plan next"; onClick = onPlan
    } else if (OffsetDateTime.parse(meetup.scheduledAt).isBefore(OffsetDateTime.now())) {
        text = "How did your meetup go?"; action = "Check in"; onClick = { onCheckIn(meetup.id) }
    } else {
        val whenText = OffsetDateTime.parse(meetup.scheduledAt).atZoneSameInstant(IST)
            .format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))
        text = "${meetup.venueName} · $whenText"; action = "Details"; onClick = onPlan
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick) { Text(action) }
        }
    }
}

@Composable
private fun FirstMessageCard(otherName: String) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("You matched with $otherName", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Say hi, and suggest a public place to meet within 7 days.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Tip: keep chatting here until you've met. Being pushed to move to WhatsApp " +
                        "or asked for money early are common scam signs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bubble(
    m: Message,
    mine: Boolean,
    receipt: Receipt?,
    quoted: Message?,
    quotedName: String?,
    highlighted: Boolean,
    onQuoteClick: () -> Unit,
) {
    val time = OffsetDateTime.parse(m.createdAt).atZoneSameInstant(IST).format(timeFmt)
    val base = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val background by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else base,
        label = "bubbleHighlight",
    )
    val readBlue = if (isSystemInDarkTheme()) ReadBlueDark else ReadBlueLight
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier.widthIn(max = 280.dp)
                .background(
                    background,
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (m.replyTo != null) {
                QuotePreview(
                    name = quotedName ?: "Message",
                    text = quoted?.body ?: "Original message",
                    onClick = onQuoteClick,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(m.body)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                receipt?.let {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (it == Receipt.Sent) Icons.Default.Done else Icons.Default.DoneAll,
                        contentDescription = when (it) {
                            Receipt.Sent -> "Sent"
                            Receipt.Delivered -> "Delivered"
                            Receipt.Read -> "Read"
                        },
                        modifier = Modifier.size(16.dp),
                        tint = if (it == Receipt.Read) readBlue
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The quoted original, shown inside a reply bubble. */
@Composable
private fun QuotePreview(name: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Replying to …" bar above the message box. */
@Composable
private fun ReplyBar(name: String, text: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("Replying to $name", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel reply") }
        }
    }
}

/** Swipe a message right to reply; a reply arrow appears behind it. */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }   // how far to swipe to reply
    val maxDrag = with(density) { 96.dp.toPx() }
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }

    val gesture = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, delta ->
                val next = (offset.value + delta).coerceIn(0f, maxDrag)  // right only
                if (next != offset.value) change.consume()
                scope.launch { offset.snapTo(next) }
                val nowArmed = next >= trigger
                if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                armed = nowArmed
            },
            onDragEnd = {
                if (armed) onReply()
                armed = false
                scope.launch { offset.animateTo(0f, spring()) }
            },
            onDragCancel = {
                armed = false
                scope.launch { offset.animateTo(0f, spring()) }
            },
        )
    }

    Box(Modifier.fillMaxWidth().then(gesture)) {
        Icon(
            Icons.AutoMirrored.Filled.Reply, null,
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).graphicsLayer {
                val p = (offset.value / trigger).coerceIn(0f, 1f)
                alpha = p
                scaleX = 0.6f + 0.4f * p
                scaleY = scaleX
            },
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(Modifier.graphicsLayer { translationX = offset.value }) { content() }
    }
}

@Composable
private fun SystemNote(body: String, who: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            "$who ${body.replaceFirstChar { it.lowercase() }}",
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun chatSubtitle(match: MatchState?): String {
    if (match == null) return ""
    return when (match.status) {
        "meetup_planned" -> "Meetup agreed"
        "unmatched" -> "Match ended"
        "expired" -> "Chat closed"
        else -> {
            val exp = match.chatExpiresAt?.let { OffsetDateTime.parse(it) } ?: return ""
            val hours = Duration.between(OffsetDateTime.now(), exp).toHours()
            when {
                hours <= 0 -> "Chat closed"
                hours >= 48 -> "Chat closes in ${hours / 24} days"
                hours >= 24 -> "Chat closes in 1 day"
                else -> "Chat closes in ${hours}h"
            }
        }
    }
}

// ---------------- List items ----------------

private sealed interface ChatItem {
    val key: Any
    data class DayHeader(val label: String) : ChatItem { override val key get() = "day-$label" }
    data class Msg(val message: Message) : ChatItem { override val key get() = message.id }
}

private fun buildItems(messages: List<Message>): List<ChatItem> {
    val today = LocalDate.now(IST)
    var lastDay: LocalDate? = null
    return buildList {
        for (m in messages) {
            val day = OffsetDateTime.parse(m.createdAt).atZoneSameInstant(IST).toLocalDate()
            if (day != lastDay) {
                add(ChatItem.DayHeader(when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(dayFmt)
                }))
                lastDay = day
            }
            add(ChatItem.Msg(m))
        }
    }
}

@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Box(
                    Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
        }
    }
}

private enum class Receipt { Sent, Delivered, Read }