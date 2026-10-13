package com.genuine.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.material.icons.filled.Favorite

// Overlay colours are fixed (not theme-based): text always sits on the dark scrim.
private val OnPhoto = Color.White
private val OnPhotoMuted = Color.White.copy(alpha = 0.85f)
private val VerifiedTeal = Color(0xFF86D4CE)

/** Used by Discovery and by the "How others see you" preview, so both always match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileCard(
    name: String,
    age: Int,
    bio: String?,
    interests: List<String>,
    photoUrl: String?,
    distanceKm: Int?,              // null hides the distance line (own preview)
    swipeProgress: Float = 0f,
    modifier: Modifier = Modifier,
    goal: String? = null,
) {
    Card(
        modifier,
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // ----- Photo (or a warm gradient with the initial) -----
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl, contentDescription = name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.displayLarge,
                        color = OnPhoto.copy(alpha = 0.9f),
                    )
                }
            }

            // ----- Scrim: clear at the top, dark at the bottom, so white text is always readable -----
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.8f),
                        )
                    ),
            )

            // ----- Details over the photo -----
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$name, $age",
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnPhoto,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Verified, "Verified", Modifier.size(24.dp), tint = VerifiedTeal)
                }

                distanceKm?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp), tint = OnPhotoMuted)
                        Spacer(Modifier.width(4.dp))
                        Text("About ${it.coerceAtLeast(1)} km away",
                            style = MaterialTheme.typography.bodyMedium, color = OnPhotoMuted)
                    }
                }

                if (goal != null || interests.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        goal?.let {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Favorite, null, Modifier.size(14.dp), tint = OnPhoto)
                                    Spacer(Modifier.width(4.dp))
                                    Text(it, style = MaterialTheme.typography.labelMedium, color = OnPhoto)
                                }
                            }
                        }
                        interests.forEach { tag ->
                            Surface(shape = RoundedCornerShape(50), color = OnPhoto.copy(alpha = 0.2f)) {
                                Text(tag, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium, color = OnPhoto)
                            }
                        }
                    }
                }

                if (!bio.isNullOrBlank()) {
                    Text(
                        bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnPhotoMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ----- Swipe labels -----
            if (swipeProgress > 0f) {
                SwipeLabel("LIKE", MaterialTheme.colorScheme.primary, Alignment.TopStart, swipeProgress, -15f)
            }
            if (swipeProgress < 0f) {
                SwipeLabel("PASS", OnPhoto, Alignment.TopEnd, -swipeProgress, 15f)
            }
        }
    }
}

@Composable
private fun BoxScope.SwipeLabel(text: String, color: Color, alignment: Alignment, alpha: Float, tilt: Float) {
    Text(
        text,
        Modifier.align(alignment).padding(24.dp)
            .graphicsLayer { this.alpha = alpha; rotationZ = tilt }
            .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
}