package com.genuine.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.genuine.app.R

private val Fraunces = FontFamily(
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)

private val DmSans = FontFamily(
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_semibold, FontWeight.SemiBold),
)

private val base = Typography()

/** Serif for headings (names, screen titles); sans-serif for everything you read or tap. */
val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),

    titleMedium = base.titleMedium.copy(fontFamily = DmSans, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = DmSans, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = DmSans),
    bodyMedium = base.bodyMedium.copy(fontFamily = DmSans),
    bodySmall = base.bodySmall.copy(fontFamily = DmSans),
    labelLarge = base.labelLarge.copy(fontFamily = DmSans, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = DmSans, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = DmSans, fontWeight = FontWeight.Medium),
)