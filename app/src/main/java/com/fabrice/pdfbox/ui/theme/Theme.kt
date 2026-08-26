package com.fabrice.pdfbox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bleu ardoise pour l'interface, rouge PDF réservé aux accents : la couleur du
// format ne doit pas devenir la couleur de l'application, sans quoi tout
// l'écran crie « erreur ».
private val Slate = Color(0xFF3F6FB5)
private val SlateLight = Color(0xFF9EC2FF)
private val Amber = Color(0xFFFFC24B)
private val Ink = Color(0xFF101418)
private val InkVariant = Color(0xFF1A2027)

private val DarkColors = darkColorScheme(
    primary = SlateLight,
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF2A4A75),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Amber,
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = Color(0xFF574400),
    onSecondaryContainer = Color(0xFFFFE0A3),
    tertiary = Color(0xFF8FD8C0),
    background = Ink,
    onBackground = Color(0xFFE2E6EB),
    surface = Ink,
    onSurface = Color(0xFFE2E6EB),
    surfaceVariant = InkVariant,
    onSurfaceVariant = Color(0xFFB9C2CC),
    outline = Color(0xFF6B7580),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColors = lightColorScheme(
    primary = Slate,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF7A5900),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0A3),
    onSecondaryContainer = Color(0xFF261A00),
    tertiary = Color(0xFF176C55),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E3E8),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

/**
 * U2 — sombre par défaut, mais l'utilisateur qui a choisi le clair au niveau du
 * système le retrouve ici : imposer un thème contre ce réglage est une faute
 * d'accessibilité, pas un parti pris esthétique.
 */
@Composable
fun PdfBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
