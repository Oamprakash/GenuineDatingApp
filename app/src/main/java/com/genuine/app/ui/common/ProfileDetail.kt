package com.genuine.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.genuine.app.data.ProfileRepo
import com.genuine.app.data.PromptAnswer
import com.genuine.app.data.PublicProfile
import kotlinx.coroutines.launch

class ProfileDetailViewModel(private val userId: String) : ViewModel() {
    var profile by mutableStateOf<PublicProfile?>(null)
    var photoUrls by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(true)
    var unavailable by mutableStateOf(false)

    init {
        viewModelScope.launch {
            runCatching {
                val p = ProfileRepo.publicProfile(userId)
                if (p == null) unavailable = true
                else {
                    photoUrls = p.photoPaths.map { ProfileRepo.photoUrl(it) }
                    profile = p
                }
            }.onFailure { unavailable = true }
            loading = false
        }
    }
}

/**
 * Full-screen profile. [actions] fills the bottom bar.
 * [refreshKey] forces a fresh load (used for your own preview after saving).
 */
@Composable
fun ProfileDetail(
    userId: String,
    onClose: () -> Unit,
    refreshKey: Any? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val vm: ProfileDetailViewModel = viewModel(key = "profile-$userId-$refreshKey") { ProfileDetailViewModel(userId) }
    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
                Text(vm.profile?.displayName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }

            val p = vm.profile
            when {
                vm.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                vm.unavailable || p == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("This profile isn't available.")
                }
                else -> {
                    val photos = vm.photoUrls
                    val prompts = p.prompts.filter { it.a.isNotBlank() }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProfilePhoto(photos.getOrNull(0), p.displayName)
                        Header(p)
                        LookingForCard(p)
                        if (!p.bio.isNullOrBlank()) {
                            SectionCard("About me") { Text(p.bio, style = MaterialTheme.typography.bodyLarge) }
                        }
                        // Photos and prompts alternate, so it reads like a story
                        prompts.getOrNull(0)?.let { PromptCard(it) }
                        photos.getOrNull(1)?.let { ProfilePhoto(it, p.displayName) }
                        DetailsCard(p)
                        prompts.getOrNull(1)?.let { PromptCard(it) }
                        photos.getOrNull(2)?.let { ProfilePhoto(it, p.displayName) }
                        prompts.getOrNull(2)?.let { PromptCard(it) }
                        photos.drop(3).forEach { ProfilePhoto(it, p.displayName) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilePhoto(url: String?, name: String) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.displayLarge, color = Color.White)
        }
    }
}

@Composable
private fun Header(p: PublicProfile) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.displayName}, ${p.age}", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Verified, "Verified", tint = MaterialTheme.colorScheme.secondary)
        }
        p.distanceKm?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("About ${it.coerceAtLeast(1)} km away", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LookingForCard(p: PublicProfile) {
    val goal = ProfileOptions.show(ProfileOptions.goals, p.relationshipGoal)
    val kids = ProfileOptions.show(ProfileOptions.kids, p.kids)
    if (goal == null && kids == null) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Looking for", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                goal?.let { Text(it, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer) }
                kids?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
    }
}

@Composable
private fun PromptCard(pa: PromptAnswer) {
    val question = ProfileOptions.prompts.firstOrNull { it.key == pa.q }?.edit ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(pa.a, style = MaterialTheme.typography.titleLarge) // serif heading font
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(p: PublicProfile) {
    val basics = buildList {
        p.heightCm?.let { add(Icons.Default.Height to "$it cm (${cmToFeet(it)})") }
        ProfileOptions.show(ProfileOptions.education, p.education)?.let { add(Icons.Default.School to it) }
        p.jobTitle?.takeIf { it.isNotBlank() }?.let { add(Icons.Default.Work to it) }
        p.hometown?.takeIf { it.isNotBlank() }?.let { add(Icons.Default.Home to "From $it") }
        if (p.languages.isNotEmpty()) add(Icons.Default.Translate to p.languages.joinToString(", "))
    }
    val lifestyle = buildList {
        ProfileOptions.show(ProfileOptions.drinking, p.drinking)?.let { add(Icons.Default.LocalBar to it) }
        ProfileOptions.show(ProfileOptions.smoking, p.smoking)?.let { add(Icons.Default.SmokingRooms to it) }
        ProfileOptions.show(ProfileOptions.exercise, p.exercise)?.let { add(Icons.Default.FitnessCenter to it) }
        ProfileOptions.show(ProfileOptions.diet, p.diet)?.let { add(Icons.Default.Restaurant to it) }
    }
    if (basics.isEmpty() && lifestyle.isEmpty() && p.interests.isEmpty()) return

    SectionCard(null) {
        if (basics.isNotEmpty()) ChipGroup("Basics", basics)
        if (lifestyle.isNotEmpty()) ChipGroup("Lifestyle", lifestyle)
        if (p.interests.isNotEmpty()) {
            Text("Interests", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.interests.forEach { tag ->
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(tag, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, items: List<Pair<ImageVector, String>>) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (icon, text) ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            content()
        }
    }
}