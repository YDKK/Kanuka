package YDKK.kanuka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF005F73),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF9B2226),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFCA6702),
    onTertiary = Color(0xFFFFFFFF),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDEEAF0),
    onSurfaceVariant = Color(0xFF334155)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF74C4D4),
    onPrimary = Color(0xFF002B33),
    secondary = Color(0xFFFFB3A8),
    onSecondary = Color(0xFF5A1115),
    tertiary = Color(0xFFFFD7A6),
    onTertiary = Color(0xFF653200),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1)
)

@Composable
fun KanukaTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
