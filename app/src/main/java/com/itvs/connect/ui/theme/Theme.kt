package com.itvs.connect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF0B1220)
private val Panel = Color(0xFF152033)
private val Mint = Color(0xFF3DDC97)
private val Amber = Color(0xFFF5C542)
private val Mist = Color(0xFFE8F1FF)
private val Muted = Color(0xFF9BB0C9)

private val Scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Night,
    secondary = Amber,
    onSecondary = Night,
    background = Night,
    onBackground = Mist,
    surface = Panel,
    onSurface = Mist,
    surfaceVariant = Color(0xFF1C2A44),
    onSurfaceVariant = Muted,
    outline = Color(0xFF334866)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = (-1).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = Muted
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    )
)

@Composable
fun ItvsTheme(content: @Composable () -> Unit) {
    // Companion app is intentionally dark for night riding readability.
    @Suppress("UNUSED_VARIABLE")
    val unused = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = Scheme,
        typography = AppTypography,
        content = content
    )
}
