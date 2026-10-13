package com.genuine.app.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun MatchCelebration(
    match: DiscoverViewModel.NewMatch,
    myPhotoUrl: String?,
    myName: String,
    onMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val haptic = LocalHapticFeedback.current

    // 0 → 1 entrance: photos slide in from the sides, title grows in
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
    }
    val heartScale by rememberInfiniteTransition(label = "heart").animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "heartScale",
    )

    // Surface blocks touches from reaching the cards underneath
    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
            ),
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "It's a match!",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    modifier = Modifier.graphicsLayer {
                        val v = enter.value.coerceIn(0f, 1f)
                        scaleX = 0.8f + 0.2f * enter.value; scaleY = scaleX; alpha = v
                    },
                )
                Spacer(Modifier.height(40.dp))

                Box(contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-28).dp)) {
                        MatchAvatar(myPhotoUrl, myName, Modifier.graphicsLayer {
                            translationX = -(1f - enter.value) * 400f; rotationZ = -8f
                        })
                        MatchAvatar(match.theirPhoto, match.name, Modifier.graphicsLayer {
                            translationX = (1f - enter.value) * 400f; rotationZ = 8f
                        })
                    }
                    Surface(
                        shape = CircleShape, color = Color.White, shadowElevation = 4.dp,
                        modifier = Modifier.size(52.dp).graphicsLayer {
                            scaleX = heartScale * enter.value.coerceIn(0f, 1f); scaleY = scaleX
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))
                Text(
                    "You and ${match.name} liked each other.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Say hi and plan a meetup within 7 days.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = onMessage,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Send a message") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep discovering", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MatchAvatar(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(132.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.displaySmall, color = Color.White)
        }
    }
}