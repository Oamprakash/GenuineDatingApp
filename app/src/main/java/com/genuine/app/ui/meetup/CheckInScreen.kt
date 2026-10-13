package com.genuine.app.ui.meetup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.CheckInInput
import com.genuine.app.data.MeetupRepo
import kotlinx.coroutines.launch

class CheckInViewModel : ViewModel() {
    var didMeet by mutableStateOf<Boolean?>(null)
    var accurate by mutableStateOf<Boolean?>(null)
    var safe by mutableStateOf<Boolean?>(null)
    var submitting by mutableStateOf(false)
    var submitted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var wantAgain by mutableStateOf<Boolean?>(null)

    val complete get() = didMeet == false ||
            (didMeet == true && accurate != null && safe != null && wantAgain != null)

    fun submit(meetupId: String) = viewModelScope.launch {
        submitting = true; error = null
        runCatching {
            val met = didMeet == true
            MeetupRepo.checkIn(
                CheckInInput(
                    meetupId = meetupId,
                    didMeet = met,
                    profileAccurate = accurate.takeIf { met },
                    feltSafe = safe.takeIf { met },
                    wantAgain = wantAgain.takeIf { met },
                )
            )
        }.onSuccess { submitted = true }
            .onFailure { error = friendlyError(it) }
        submitting = false
    }

    private fun friendlyError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            "duplicate" in msg || "23505" in msg -> "You've already checked in for this meetup."
            "row-level security" in msg || "42501" in msg -> "Check-in opens after the meetup time."
            else -> "Couldn't submit. Please try again."
        }
    }
}

@Composable
fun CheckInScreen(meetupId: String, otherName: String, onDone: () -> Unit, vm: CheckInViewModel = viewModel()) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (vm.submitted) {
            ThankYou(feltUnsafe = vm.safe == false, onDone = onDone)
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How did it go?", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "$otherName won't see your answers. They help keep profiles on Genuine real.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Question("Did you meet $otherName in person?", null, vm.didMeet) {
            vm.didMeet = it
            if (!it) { vm.accurate = null; vm.safe = null; vm.wantAgain = null }
        }

        if (vm.didMeet == true) {
            Question("Were they who they claimed to be?", "Same person as their photos and profile.", vm.accurate) {
                vm.accurate = it
            }
            Question("Did you feel safe?", null, vm.safe) { vm.safe = it }
            Question(
                "Would you like to meet $otherName again?",
                "They'll only find out if you both say yes.",
                vm.wantAgain,
            ) { vm.wantAgain = it }

            if (vm.safe == false) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "We're sorry. We'll review this privately. If you're in danger right now, call 112.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = { vm.submit(meetupId) },
            enabled = vm.complete && !vm.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.submitting) "Submitting…" else "Submit") }

        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

@Composable
private fun Question(title: String, hint: String?, value: Boolean?, onChange: (Boolean) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.selectableGroup().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(true to "Yes", false to "No").forEach { (option, label) ->
                Row(
                    Modifier.selectable(selected = value == option, onClick = { onChange(option) }, role = Role.RadioButton)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == option, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ThankYou(feltUnsafe: Boolean, onDone: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Icon(
        Icons.Default.CheckCircle, null,
        Modifier.size(72.dp).align(Alignment.CenterHorizontally),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        "Thanks for checking in",
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(
        if (feltUnsafe) "We'll review what happened privately. Look after yourself, and call 112 if you need urgent help."
        else "Your answers help keep Genuine real. If you both want to meet again, we'll let you know.",
        Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}