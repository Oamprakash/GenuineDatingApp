package com.genuine.app.ui.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.AuthRepo
import com.genuine.app.data.ProfileInput
import com.genuine.app.data.ProfilePrivate
import com.genuine.app.data.ProfileRepo
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.round
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.Alignment
import com.genuine.app.data.coarseLocation
import java.time.Period
import java.time.format.DateTimeFormatter

private val GENDERS = listOf("woman" to "Woman", "man" to "Man", "nonbinary" to "Non-binary")

class ProfileSetupViewModel : ViewModel() {
    var name by mutableStateOf("")
    var birthDate by mutableStateOf<LocalDate?>(null)
    var gender by mutableStateOf<String?>(null)
    var interestedIn by mutableStateOf(setOf<String>())
    var bio by mutableStateOf("")
    var lat by mutableStateOf<Double?>(null)
    var lng by mutableStateOf<Double?>(null)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val isAdult get() = birthDate?.let { !it.plusYears(18).isAfter(LocalDate.now()) } == true
    val canSave get() = name.trim().length >= 2 && isAdult && gender != null &&
        interestedIn.isNotEmpty() && lat != null && !saving

    /** Rounded to 2 decimals (~1 km) before it ever leaves the phone. */
    /*@SuppressLint("MissingPermission")
    fun loadLocation(context: Context) = viewModelScope.launch {
        runCatching {
            val loc = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            lat = round(loc.latitude * 100) / 100
            lng = round(loc.longitude * 100) / 100
        }.onFailure { error = "Couldn't get your area. Check location is on." }
    }*/

    @SuppressLint("MissingPermission")
    fun loadLocation(context: Context) = viewModelScope.launch {
        error = null
        runCatching { coarseLocation(context) }
            .onSuccess { (la, ln) -> lat = la; lng = ln }
            .onFailure { error = "Couldn't get your area. Check location is on." }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        saving = true; error = null
        runCatching {
            val uid = AuthRepo.uid()
            ProfileRepo.create(
                ProfileInput(uid, name.trim(), birthDate.toString(), gender!!, interestedIn.toList(), bio.trim().ifEmpty { null }),
                ProfilePrivate(uid, lat, lng),
            )
        }.onSuccess { onDone() }.onFailure { error = it.message }
        saving = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileSetupScreen(onDone: () -> Unit, vm: ProfileSetupViewModel = viewModel()) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadLocation(context) else vm.error = "Location is needed to match you with people nearby."
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("About you", style = MaterialTheme.typography.headlineMedium)
        Text("Fields with a lock can't be changed later, so check them carefully. They're matched against your ID.")

        OutlinedTextField(vm.name, { vm.name = it.take(30) }, label = { Text("First name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        LockedLabel("Date of birth")
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(vm.birthDate?.withAge() ?: "Select date of birth")
        }
        if (vm.birthDate != null && !vm.isAdult) Text("You must be 18 or older.", color = MaterialTheme.colorScheme.error)

        LockedLabel("I am")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GENDERS.forEach { (key, label) ->
                FilterChip(selected = vm.gender == key, onClick = { vm.gender = key }, label = { Text(label) })
            }
        }

        Text("Interested in", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GENDERS.forEach { (key, label) ->
                FilterChip(
                    selected = key in vm.interestedIn,
                    onClick = { vm.interestedIn = if (key in vm.interestedIn) vm.interestedIn - key else vm.interestedIn + key },
                    label = { Text(label) },
                )
            }
        }

        OutlinedTextField(vm.bio, { vm.bio = it.take(300) }, label = { Text("Short bio (optional)") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${vm.bio.length}/300") })

        OutlinedButton(
            onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.lat != null) "Area set ✓" else "Set my area (approximate only)") }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = { confirmSave = true }, enabled = vm.canSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.saving) "Saving…" else "Continue")
        }
    }

    if (showDatePicker) {
        val now = LocalDate.now()
        val state = rememberDatePickerState(
            initialDisplayedMonthMillis = now.minusYears(25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1940..(now.year - 18),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.birthDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
        ) { DatePicker(state) }
    }

    if (confirmSave) {
        val genderLabel = GENDERS.firstOrNull { it.first == vm.gender }?.second.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Is this correct?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Date of birth: ${vm.birthDate?.withAge()}")
                    Text("Gender: $genderLabel")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "These can't be changed later and must match your ID.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton({ confirmSave = false; vm.save(onDone) }) { Text("Yes, continue") } },
            dismissButton = { TextButton({ confirmSave = false }) { Text("Edit") } },
        )
    }
}

@Composable
private fun LockedLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.Lock, contentDescription = "Can't be changed later",
            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LocalDate.withAge(): String =
    "${format(DateTimeFormatter.ofPattern("d MMM yyyy"))}  (age ${Period.between(this, LocalDate.now()).years})"