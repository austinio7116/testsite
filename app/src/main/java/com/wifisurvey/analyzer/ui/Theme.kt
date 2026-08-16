package com.wifisurvey.analyzer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0B1020)
private val InkRaised = Color(0xFF141B33)
private val InkCard = Color(0xFF19213D)
private val Teal = Color(0xFF21C7A8)
private val Amber = Color(0xFFF2B134)
private val Rose = Color(0xFFE8622A)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201A),
    primaryContainer = Color(0xFF14524A),
    onPrimaryContainer = Color(0xFFB8F5E8),
    secondary = Amber,
    onSecondary = Color(0xFF2A1D00),
    tertiary = Rose,
    onTertiary = Color(0xFF2A0F00),
    background = Ink,
    onBackground = Color(0xFFE4E8F5),
    surface = InkRaised,
    onSurface = Color(0xFFE4E8F5),
    surfaceVariant = InkCard,
    onSurfaceVariant = Color(0xFFA9B2CC),
    outline = Color(0xFF3A4463),
    outlineVariant = Color(0xFF2A3352),
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F7F6C),
    secondary = Color(0xFF9A6B00),
    tertiary = Color(0xFFB2431A),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7ECF5),
    onSurfaceVariant = Color(0xFF48506B)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun WiFiSurveyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}

/** Signal colours shared by the list, the graphs and the heatmap legend. */
object SignalColors {
    fun forDbm(dbm: Int): Color =
        Color(com.wifisurvey.analyzer.survey.HeatmapEngine.colorForDbm(dbm.toFloat()))
}
