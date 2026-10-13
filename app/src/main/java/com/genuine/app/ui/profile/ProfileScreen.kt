package com.genuine.app.ui.profile

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genuine.app.data.*
import com.genuine.app.ui.common.ProfileCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import com.genuine.app.ui.common.ProfileDetail
import com.genuine.app.ui.common.ProfileOptions
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

const val MAX_INTERESTS = 5

val INTEREST_OPTIONS = listOf(
    "Coffee", "Cooking", "Foodie", "Fitness", "Running", "Cycling", "Yoga", "Trekking",
    "Travel", "Beach walks", "Movies", "Music", "Carnatic music", "Dance", "Reading",
    "Photography", "Art", "Board games", "Gaming", "Cricket", "Football", "Tech", "Pets", "Volunteering",
)

private val GENDERS = listOf("woman" to "Woman", "man" to "Man", "nonbinary" to "Non-binary")

class ProfileViewModel : ViewModel() {
    var loading by mutableStateOf(true)
    var profile by mutableStateOf<Profile?>(null)
    var areaSet by mutableStateOf(false)
    var paused by mutableStateOf(false)

    // ----- Editable: basics you already had -----
    var name by mutableStateOf("")
    var bio by mutableStateOf("")
    var interestedIn by mutableStateOf(setOf<String>())
    var interests by mutableStateOf(listOf<String>())
    var ageRange by mutableStateOf(21f..35f)
    var distanceKm by mutableFloatStateOf(10f)

    // ----- Editable: "More about you" -----
    var goal by mutableStateOf<String?>(null)
    var kids by mutableStateOf<String?>(null)
    var heightText by mutableStateOf("")
    var education by mutableStateOf<String?>(null)
    var jobTitle by mutableStateOf("")
    var languages by mutableStateOf(listOf<String>())
    var hometown by mutableStateOf("")
    var drinking by mutableStateOf<String?>(null)
    var smoking by mutableStateOf<String?>(null)
    var exercise by mutableStateOf<String?>(null)
    var diet by mutableStateOf<String?>(null)
    var prompts by mutableStateOf(listOf<PromptAnswer>())

    // ----- Photos -----
    var photos by mutableStateOf<List<Photo>>(emptyList())
    var photoUrls by mutableStateOf<Map<String, String>>(emptyMap())
    var photoBusy by mutableStateOf(false)
    val photoUrl: String? get() = photos.firstOrNull()?.let { photoUrls[it.id] }

    var saving by mutableStateOf(false)
    var locating by mutableStateOf(false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    var message: String?
        get() = null
        set(value) { value?.let { _messages.trySend(it) } }

    val heightCm: Int? get() = heightText.trim().toIntOrNull()
    val heightValid get() = heightText.isBlank() || heightCm in 120..230

    private data class Snapshot(
        val name: String, val bio: String, val interestedIn: Set<String>, val interests: List<String>,
        val minAge: Int, val maxAge: Int, val distance: Int,
        val goal: String?, val kids: String?, val height: String, val education: String?,
        val jobTitle: String, val languages: List<String>, val hometown: String,
        val drinking: String?, val smoking: String?, val exercise: String?, val diet: String?,
        val prompts: List<PromptAnswer>,
    )
    private var saved by mutableStateOf<Snapshot?>(null)

    var deleting by mutableStateOf(false)

    fun deleteAccount(onDeleted: () -> Unit) = viewModelScope.launch {
        deleting = true
        runCatching { AuthRepo.deleteAccount() }
            .onSuccess { onDeleted() }
            .onFailure { message = "Couldn't delete your account. Check your connection and try again." }
        deleting = false
    }

    private fun current() = Snapshot(
        name.trim(), bio.trim(), interestedIn, interests,
        ageRange.start.roundToInt(), ageRange.endInclusive.roundToInt(), distanceKm.roundToInt(),
        goal, kids, heightText.trim(), education, jobTitle.trim(), languages, hometown.trim(),
        drinking, smoking, exercise, diet, prompts,
    )

    val dirty get() = saved != null && current() != saved
    val valid get() = name.trim().length in 2..30 && interestedIn.isNotEmpty() && heightValid &&
            prompts.all { it.a.length <= ProfileOptions.PROMPT_MAX_CHARS }

    val age: Int get() = profile?.let { Period.between(LocalDate.parse(it.birthDate), LocalDate.now()).years } ?: 0

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching {
            val p = ProfileRepo.myProfile() ?: return@runCatching
            val pp = ProfileRepo.myPrivate()
            profile = p
            name = p.displayName
            bio = p.bio.orEmpty()
            interestedIn = p.interestedIn.toSet()
            interests = p.interests
            goal = p.relationshipGoal
            kids = p.kids
            heightText = p.heightCm?.toString().orEmpty()
            education = p.education
            jobTitle = p.jobTitle.orEmpty()
            languages = p.languages
            hometown = p.hometown.orEmpty()
            drinking = p.drinking
            smoking = p.smoking
            exercise = p.exercise
            diet = p.diet
            prompts = p.prompts
            pp?.let {
                ageRange = it.minAge.toFloat()..it.maxAge.toFloat()
                distanceKm = it.maxDistanceKm.toFloat()
                areaSet = it.lat != null
                paused = it.paused
            }
            saved = current()
        }.onFailure { message = it.message }
        loading = false
    }

    // ----- Edit helpers -----
    fun toggleInterest(tag: String) {
        interests = when {
            tag in interests -> interests - tag
            interests.size < MAX_INTERESTS -> interests + tag
            else -> interests
        }
    }

    fun toggleInterestedIn(key: String) {
        interestedIn = if (key in interestedIn) interestedIn - key else interestedIn + key
    }

    fun toggleLanguage(lang: String) {
        languages = when {
            lang in languages -> languages - lang
            languages.size < ProfileOptions.MAX_LANGUAGES -> languages + lang
            else -> languages
        }
    }

    fun addPrompt(key: String) {
        if (prompts.size < ProfileOptions.MAX_PROMPTS && prompts.none { it.q == key }) {
            prompts = prompts + PromptAnswer(key, "")
        }
    }

    fun updatePrompt(key: String, answer: String) {
        prompts = prompts.map { if (it.q == key) it.copy(a = answer.take(ProfileOptions.PROMPT_MAX_CHARS)) else it }
    }

    fun removePrompt(key: String) {
        prompts = prompts.filterNot { it.q == key }
    }

    fun save() = viewModelScope.launch {
        saving = true
        // Drop prompts left empty, and trim answers
        prompts = prompts.filter { it.a.isNotBlank() }.map { it.copy(a = it.a.trim()) }
        val s = current()
        runCatching {
            ProfileRepo.updateDetails(
                ProfileUpdate(
                    displayName = s.name,
                    bio = s.bio.ifEmpty { null },
                    interestedIn = s.interestedIn.toList(),
                    interests = s.interests,
                    relationshipGoal = s.goal,
                    kids = s.kids,
                    heightCm = heightCm,
                    education = s.education,
                    jobTitle = s.jobTitle.ifEmpty { null },
                    languages = s.languages,
                    hometown = s.hometown.ifEmpty { null },
                    drinking = s.drinking,
                    smoking = s.smoking,
                    exercise = s.exercise,
                    diet = s.diet,
                    prompts = s.prompts,
                )
            )
            ProfileRepo.updateSettings(DiscoverySettings(s.minAge, s.maxAge, s.distance))
        }.onSuccess {
            saved = s
            message = "Profile saved"
        }.onFailure { message = "Couldn't save: ${it.message}" }
        saving = false
    }

    fun updateArea(context: Context) = viewModelScope.launch {
        locating = true
        runCatching {
            val (lat, lng) = coarseLocation(context)
            ProfileRepo.updateArea(lat, lng)
        }.onSuccess {
            areaSet = true
            message = "Area updated. Pull down on Today to see people nearby."
        }.onFailure { message = "Couldn't get your area. Turn on location and try again." }
        locating = false
    }

    // ----- Photos -----
    fun loadPhotos() = viewModelScope.launch {
        AuthRepo.awaitSignedIn()
        runCatching { refreshPhotos() } }

    private suspend fun refreshPhotos() {
        val list = ProfileRepo.myPhotos()
        photoUrls = list.associate { it.id to (photoUrls[it.id] ?: ProfileRepo.photoUrl(it.storagePath)) }
        photos = list
    }

    fun makeMain(p: Photo) = photoOp { ProfileRepo.setMainPhoto(p.id) }
    fun deletePhoto(p: Photo) = photoOp { ProfileRepo.deletePhoto(p) }

    private fun photoOp(block: suspend () -> Unit) = viewModelScope.launch {
        photoBusy = true
        runCatching { block(); refreshPhotos() }.onFailure {
            val msg = it.message.orEmpty()
            message = when {
                "at least one photo" in msg -> "Keep at least one photo on your profile."
                "up to 6" in msg -> "You can have up to 6 photos."
                else -> "Couldn't update photos. Please try again."
            }
        }
        photoBusy = false
    }

    // ----- Account -----
    fun setPaused(value: Boolean) = viewModelScope.launch {
        val before = paused
        paused = value
        runCatching { ProfileRepo.setPaused(value) }
            .onSuccess {
                message = if (value) "Profile paused. You're hidden from Discovery."
                else "Welcome back! You're visible in Discovery again."
            }
            .onFailure {
                paused = before
                message = "Couldn't update. Please try again."
            }
    }

    fun refreshPaused() = viewModelScope.launch {
        AuthRepo.awaitSignedIn()
        runCatching { ProfileRepo.myPrivate()?.paused == true }.onSuccess { paused = it }
    }

    fun signOut(onSignedOut: () -> Unit) = viewModelScope.launch {
        AuthRepo.signOut()
        onSignedOut()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileTab(onAddPhoto: () -> Unit, onSignedOut: () -> Unit, vm: ProfileViewModel = viewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.loadPhotos()
        vm.refreshPaused()
    }
    var confirmDelete by remember { mutableStateOf<Photo?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showFull by remember { mutableStateOf(false) }
    var fullKey by remember { mutableLongStateOf(0L) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // collectLatest: a new message replaces the one on screen instead of queueing
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.updateArea(context) else vm.message = "Location permission is needed to update your area."
    }

    if (vm.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val p = vm.profile ?: return

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Profile", style = MaterialTheme.typography.titleLarge)
                if (vm.paused) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("Paused", Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (vm.dirty) {
                    TextButton({ vm.save() }, enabled = vm.valid && !vm.saving) { Text("Save") }
                }
            }

            // ----- Preview -----
            Section("How others see you") {
                ProfileCard(
                    name = vm.name.trim().ifEmpty { p.displayName },
                    age = vm.age,
                    bio = vm.bio,
                    interests = vm.interests,
                    photoUrl = vm.photoUrl,
                    distanceKm = null,
                    modifier = Modifier.fillMaxWidth().height(460.dp),
                    goal = ProfileOptions.show(ProfileOptions.goals, vm.goal),
                )
                OutlinedButton(
                    onClick = { fullKey = System.currentTimeMillis(); showFull = true },
                    enabled = !vm.dirty,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.dirty) "Save changes to preview your full profile" else "See full profile") }
            }

            Section("Photos (${vm.photos.size}/$MAX_PHOTOS)") {
                val tiles: List<Photo?> = vm.photos + if (vm.photos.size < MAX_PHOTOS) listOf(null) else emptyList()
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { photo ->
                            Box(Modifier.weight(1f).aspectRatio(0.8f)) {
                                if (photo == null) {
                                    AddPhotoTile(enabled = !vm.photoBusy, onClick = onAddPhoto)
                                } else {
                                    PhotoTile(
                                        url = vm.photoUrls[photo.id],
                                        isMain = photo.id == vm.photos.first().id,
                                        canDelete = vm.photos.size > 1,
                                        enabled = !vm.photoBusy,
                                        onMakeMain = { vm.makeMain(photo) },
                                        onDelete = { confirmDelete = photo },
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "Photos can only be taken with the in-app camera. Your main photo is shown first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ----- About you -----
            Section("About you") {
                OutlinedTextField(
                    vm.name, { vm.name = it.take(30) }, label = { Text("First name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = vm.name.trim().length < 2,
                )
                OutlinedTextField(
                    vm.bio, { vm.bio = it.take(300) }, label = { Text("Short bio") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${vm.bio.length}/300") },
                )
                val dob = LocalDate.parse(p.birthDate).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                LockedRow("Date of birth", "$dob (age ${vm.age})")
                LockedRow("Gender", GENDERS.firstOrNull { it.first == p.gender }?.second ?: p.gender)
            }

            // ----- Interested in -----
            Section("Interested in") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GENDERS.forEach { (key, label) ->
                        FilterChip(key in vm.interestedIn, { vm.toggleInterestedIn(key) }, label = { Text(label) })
                    }
                }
                if (vm.interestedIn.isEmpty()) {
                    Text("Pick at least one.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            // ----- Interests -----
            Section("Interests (${vm.interests.size}/$MAX_INTERESTS)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    INTEREST_OPTIONS.forEach { tag ->
                        val selected = tag in vm.interests
                        FilterChip(
                            selected = selected,
                            onClick = { vm.toggleInterest(tag) },
                            label = { Text(tag) },
                            enabled = selected || vm.interests.size < MAX_INTERESTS,
                        )
                    }
                }
            }

            //----- More About You -----
            MoreAboutYou(vm)

            // ----- Discovery -----
            Section("Discovery") {
                val minAge = vm.ageRange.start.roundToInt()
                val maxAge = vm.ageRange.endInclusive.roundToInt()
                Text("Age range: $minAge–$maxAge")
                RangeSlider(
                    value = vm.ageRange,
                    onValueChange = { vm.ageRange = it },
                    valueRange = 18f..60f,
                    steps = 41,
                )
                Text("Maximum distance: ${vm.distanceKm.roundToInt()} km")
                Slider(
                    value = vm.distanceKm,
                    onValueChange = { vm.distanceKm = it },
                    valueRange = 1f..50f,
                    steps = 48,
                )
                OutlinedButton(
                    onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    enabled = !vm.locating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.locating) "Finding your area…" else "Update my area")
                }
                Text(
                    if (vm.areaSet) "Your area is saved as an approximate location (about 1 km). Your exact location is never shared."
                    else "Set your area so people nearby can see you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { vm.save() },
                enabled = vm.dirty && vm.valid && !vm.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.saving) "Saving…" else "Save changes") }

            Section("Account") {
                ListItem(
                    headlineContent = { Text("Pause my profile") },
                    supportingContent = { Text("Hide from Discovery. Your matches and chats stay.") },
                    trailingContent = { Switch(checked = vm.paused, onCheckedChange = { vm.setPaused(it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                AuthRepo.phone()?.let {
                    Text("Signed in as ${formatPhone(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
                TextButton(
                    onClick = { confirmDeleteAccount = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete account") }
            }

            Spacer(Modifier.height(24.dp))
        }
        confirmDelete?.let { photo ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete this photo?") },
                text = { Text("It will be removed from your profile straight away.") },
                confirmButton = {
                    TextButton({ confirmDelete = null; vm.deletePhoto(photo) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
            )
        }
        if (confirmSignOut) {
            AlertDialog(
                onDismissRequest = { confirmSignOut = false },
                title = { Text("Sign out?") },
                text = { Text("You'll need your phone number and a code to sign back in.") },
                confirmButton = { TextButton({ confirmSignOut = false; vm.signOut(onSignedOut) }) { Text("Sign out") } },
                dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } },
            )
        }
        if (confirmDeleteAccount) {
            var typed by remember { mutableStateOf("") }
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { if (!vm.deleting) confirmDeleteAccount = false },
                title = { Text("Delete your account?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "This permanently deletes your profile, photos, matches, messages and meetups. " +
                                    "It can't be undone."
                        )
                        if (!vm.paused) {
                            Text(
                                "Just need a break? Pausing hides you from Discovery and keeps your matches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { confirmDeleteAccount = false; vm.setPaused(true) }) { Text("Pause instead") }
                        }
                        OutlinedTextField(
                            typed, { typed = it },
                            label = { Text("Type DELETE to confirm") },
                            singleLine = true,
                            enabled = !vm.deleting,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.deleteAccount {
                                Toast.makeText(context, "Your account has been deleted.", Toast.LENGTH_LONG).show()
                                onSignedOut()
                            }
                        },
                        enabled = typed.trim() == "DELETE" && !vm.deleting,
                    ) {
                        Text(if (vm.deleting) "Deleting…" else "Delete forever", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton({ confirmDeleteAccount = false }, enabled = !vm.deleting) { Text("Cancel") }
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if (showFull) {
            ProfileDetail(p.id, refreshKey = fullKey, onClose = { showFull = false }) {
                OutlinedButton({ showFull = false }, Modifier.weight(1f)) { Text("Back to editing") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun LockedRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Default.Lock, "Can't be changed", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

const val MAX_PHOTOS = 6

@Composable
private fun PhotoTile(
    url: String?,
    isMain: Boolean,
    canDelete: Boolean,
    enabled: Boolean,
    onMakeMain: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (isMain) {
            Surface(
                Modifier.align(Alignment.TopStart).padding(6.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text("Main", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(
                onClick = { menu = true },
                enabled = enabled,
                modifier = Modifier.padding(2.dp).size(32.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape),
            ) { Icon(Icons.Default.MoreVert, "Photo options", Modifier.size(18.dp)) }
            DropdownMenu(menu, { menu = false }) {
                if (!isMain) {
                    DropdownMenuItem(text = { Text("Make main photo") }, onClick = { menu = false; onMakeMain() })
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            if (canDelete) "Delete" else "Delete (keep at least one)",
                            color = if (canDelete) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    enabled = canDelete,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun AddPhotoTile(enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.AddAPhoto, "Add photo")
            Spacer(Modifier.height(4.dp))
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** "919999999999" → "+91 99999 99999" */
private fun formatPhone(p: String): String =
    if (p.length == 12) "+${p.take(2)} ${p.substring(2, 7)} ${p.substring(7)}" else "+$p"