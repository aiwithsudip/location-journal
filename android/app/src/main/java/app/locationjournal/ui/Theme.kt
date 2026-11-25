package app.locationjournal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.locationjournal.domain.ThemeMode

private val Copper = Color(0xFFB5522A)
private val Cream = Color(0xFFF7F1E6)
private val Ink = Color(0xFF241C16)

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4D7C8),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF6D5A4C),
    background = Cream,
    onBackground = Ink,
    surface = Color(0xFFFFFBF4),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFE5D8),
    onSurfaceVariant = Color(0xFF5D5148),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0A27C),
    onPrimary = Color(0xFF3A1B0E),
    primaryContainer = Color(0xFF5E2C16),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFCBB8A8),
    background = Color(0xFF171310),
    onBackground = Color(0xFFF5EDE4),
    surface = Color(0xFF211B17),
    onSurface = Color(0xFFF5EDE4),
    surfaceVariant = Color(0xFF332A23),
    onSurfaceVariant = Color(0xFFD4C5B8),
)

@Composable
fun JournalTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
