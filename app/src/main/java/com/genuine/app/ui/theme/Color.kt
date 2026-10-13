package com.genuine.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Coral: warmth (primary actions, likes) · Teal: trust (verified, confirmed)
// Marigold: small accents · Cream: backgrounds instead of stark white

val LightColors = lightColorScheme(
    primary = Color(0xFFC2462E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1), onPrimaryContainer = Color(0xFF3D0B02),

    secondary = Color(0xFF1F6F6B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8ECE8), onSecondaryContainer = Color(0xFF002020),

    tertiary = Color(0xFF8A5A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3B8), onTertiaryContainer = Color(0xFF2B1700),

    background = Color(0xFFFFF8F2), onBackground = Color(0xFF231A15),
    surface = Color(0xFFFFF8F2), onSurface = Color(0xFF231A15),
    surfaceVariant = Color(0xFFF3E4DA), onSurfaceVariant = Color(0xFF54443D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1E9),
    surfaceContainer = Color(0xFFFBEBE2),
    surfaceContainerHigh = Color(0xFFF5E5DC),
    surfaceContainerHighest = Color(0xFFEFDFD6),

    outline = Color(0xFF87736B), outlineVariant = Color(0xFFDAC2B8),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A1), onPrimary = Color(0xFF5F1506),
    primaryContainer = Color(0xFF8A2F1C), onPrimaryContainer = Color(0xFFFFDBD1),

    secondary = Color(0xFF86D4CE), onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF00504C), onSecondaryContainer = Color(0xFFA3F0EA),

    tertiary = Color(0xFFF6BD63), onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00), onTertiaryContainer = Color(0xFFFFDDB0),

    background = Color(0xFF1A120F), onBackground = Color(0xFFF1DFD8),
    surface = Color(0xFF1A120F), onSurface = Color(0xFFF1DFD8),
    surfaceVariant = Color(0xFF54443D), onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceContainerLowest = Color(0xFF140D0A),
    surfaceContainerLow = Color(0xFF231A17),
    surfaceContainer = Color(0xFF271E1B),
    surfaceContainerHigh = Color(0xFF322825),
    surfaceContainerHighest = Color(0xFF3D332F),

    outline = Color(0xFFA08D85), outlineVariant = Color(0xFF54443D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)