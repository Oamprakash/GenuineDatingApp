package com.genuine.app.ui.meetup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.*
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val IST = ZoneId.of("Asia/Kolkata")
private val TIMES = listOf(LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(19, 0))
private val pretty = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

// ---------------- ViewModel ----------------

class MeetupViewModel(private val matchId: String) : ViewModel() {
    val me = AuthRepo.uid()
    var meetup by mutableStateOf<Meetup?>(null)
    var editing by mutableStateOf(false)
    var venue by mutableStateOf("")
    var day by mutableStateOf(LocalDate.now(IST).plusDays(1))
    var time by mutableStateOf(TIMES[3])
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var channel: RealtimeChannel? = null

    init {
        load()
        viewModelScope.launch {
            runCatching {
                val (ch, changes) = MeetupRepo.live(matchId)
                channel = ch
                launch { changes.collect { onRemoteChange() } }
                ch.subscribe()
            }.onFailure { android.util.Log.e("Meetup", "realtime failed", it) }
        }
    }

    fun load() = viewModelScope.launch { runCatching { meetup = MeetupRepo.latest(matchId) } }

    private fun onRemoteChange() = viewModelScope.launch {
        val before = meetup?.id
        runCatching { MeetupRepo.latest(matchId) }.onSuccess { latest ->
            if (editing && !busy && latest?.id != before) {
                editing = false
                error = "The plan was just changed. Here's the latest version."
            }
            meetup = latest
        }
    }

    fun startEditing() {
        val m = meetup ?: return
        val at = OffsetDateTime.parse(m.scheduledAt).atZoneSameInstant(IST)
        venue = m.venueName
        day = at.toLocalDate().takeIf { !it.isBefore(LocalDate.now(IST)) } ?: LocalDate.now(IST).plusDays(1)
        time = TIMES.firstOrNull { it == at.toLocalTime() } ?: TIMES[3]
        error = null
        editing = true
    }

    fun submit() = act {
        val at = day.atTime(time).atZone(IST).toOffsetDateTime()
        require(at.isAfter(OffsetDateTime.now())) { "Pick a time later than now." }
        val current = meetup
        if (editing && current != null) MeetupRepo.reschedule(current.id, venue.trim(), at.toString())
        else MeetupRepo.propose(MeetupInput(matchId, venue.trim(), at.toString()))
        editing = false
    }

    fun respond(accept: Boolean) = act { MeetupRepo.respond(meetup!!.id, accept) }

    fun cancel() = act { MeetupRepo.cancel(meetup!!.id); venue = "" }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true; error = null
        runCatching { block(); meetup = MeetupRepo.latest(matchId) }.onFailure { error = it.message }
        busy = false
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        channel?.let { ch -> GlobalScope.launch { runCatching { Supa.client.realtime.removeChannel(ch) } } }
    }
}

// ---------------- Screen ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetupScreen(matchId: String, otherName: String, onBack: () -> Unit) {
    val vm: MeetupViewModel = viewModel(key = "meetup-$matchId") { MeetupViewModel(matchId) }
    val m = vm.meetup
    var confirmCancel by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Meet $otherName") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val passedAccepted = m != null && m.status == "accepted" &&
                    OffsetDateTime.parse(m.scheduledAt).isBefore(OffsetDateTime.now())

            if (m != null && (m.status == "proposed" || m.status == "accepted") && !vm.editing && !passedAccepted) {
                CurrentPlan(m, vm, otherName, onCancel = { confirmCancel = true })
            } else {
                PlanForm(vm, m)
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SafetyTipsCard()
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this meetup?") },
            text = { Text("$otherName will see a note in your chat. The chat stays open for 3 more days so you can plan again.") },
            confirmButton = {
                TextButton({ confirmCancel = false; vm.cancel() }) {
                    Text("Cancel meetup", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmCancel = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun CurrentPlan(m: Meetup, vm: MeetupViewModel, otherName: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    val at = OffsetDateTime.parse(m.scheduledAt)
    val whenText = at.atZoneSameInstant(IST).format(pretty)
    val iProposed = m.proposedBy == vm.me
    val confirmed = m.status == "accepted"
    val passed = at.isBefore(OffsetDateTime.now())

    // ----- Plan card -----
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (confirmed) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Event, null, Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.venueName, style = MaterialTheme.typography.titleLarge)
                Text(whenText, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                StatusPill(
                    when {
                        confirmed -> "Confirmed"
                        iProposed -> "Waiting for $otherName"
                        m.replaces != null -> "Updated · waiting for your reply"
                        else -> "Waiting for your reply"
                    },
                    if (confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ----- Actions -----
    when {
        m.status == "proposed" && !iProposed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ vm.respond(false) }, Modifier.weight(1f), enabled = !vm.busy) { Text("Not this one") }
            Button({ vm.respond(true) }, Modifier.weight(1f), enabled = !vm.busy) { Text("I'll be there") }
        }
        confirmed -> {
            Text("It's on. Your chat no longer expires.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val text = "I'm meeting $otherName (from Genuine) at ${m.venueName} on $whenText. " +
                                "I'll check in with you after."
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share with a friend"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share plan")
                }
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(m.venueName + ", Chennai"))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Directions, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Directions")
                }
            }
        }
    }

    // ----- Change / cancel (only before the meetup time) -----
    if (!passed) {
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ vm.startEditing() }, Modifier.weight(1f), enabled = !vm.busy) {
                Text(if (m.status == "proposed" && !iProposed) "Suggest a change" else "Change plan")
            }
            if (confirmed || iProposed) {
                OutlinedButton(
                    onCancel, Modifier.weight(1f), enabled = !vm.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (confirmed) "Cancel meetup" else "Withdraw") }
            }
        }
        Text("$otherName will see any change in your chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanForm(vm: MeetupViewModel, m: Meetup?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title = when {
            vm.editing -> "Change the plan"
            m?.status == "accepted" -> "Plan your next meetup"
            else -> "Suggest a meetup"
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Once you both agree, your chat stops expiring.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (!vm.editing) {
        when (m?.status) {
            "declined" -> Text("Your last suggestion was declined. Try another time or place.")
            "cancelled" -> Text("The last plan was cancelled. Suggest a new one when you're ready.")
        }
    }

    OutlinedTextField(
        vm.venue, { vm.venue = it.take(80) },
        label = { Text("Public place & area") },
        placeholder = { Text("e.g. Blue Tokai, Velachery") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    Text("Day", style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0L..6L).map { LocalDate.now(IST).plusDays(it) }) { d ->
            FilterChip(vm.day == d, { vm.day = d }, label = {
                Text(if (d == LocalDate.now(IST)) "Today" else d.format(DateTimeFormatter.ofPattern("EEE d")))
            })
        }
    }

    Text("Time", style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TIMES) { t ->
            FilterChip(vm.time == t, { vm.time = t }, label = { Text(t.format(DateTimeFormatter.ofPattern("h:mm a"))) })
        }
    }

    Button({ vm.submit() }, enabled = vm.venue.isNotBlank() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (vm.editing) "Send updated plan" else "Suggest this meetup")
    }
    if (vm.editing) {
        TextButton({ vm.editing = false }, Modifier.fillMaxWidth()) { Text("Keep current plan") }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SafetyTipsCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Safety tips for meetups", style = MaterialTheme.typography.titleSmall)
            listOf(
                "Meet in a busy, public place.",
                "Tell a friend where you're going and share your plan.",
                "Arrange your own travel both ways.",
                "Leave anytime if something feels off.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}