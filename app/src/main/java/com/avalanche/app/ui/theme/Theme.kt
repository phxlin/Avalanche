package com.avalanche.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Glacier palette: fresh snow for surfaces, deep glacier blue as the accent, cold slate for text.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E8A), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6FA), onPrimaryContainer = Color(0xFF001E33),
    secondary = Color(0xFF52606D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4F0), onSecondaryContainer = Color(0xFF0E1D29),
    tertiary = Color(0xFFB35A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2), onTertiaryContainer = Color(0xFF311300),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F8FC), onBackground = Color(0xFF12191F),
    surface = Color(0xFFF4F8FC), onSurface = Color(0xFF12191F),
    surfaceVariant = Color(0xFFDDE4EB), onSurfaceVariant = Color(0xFF3F4850),
    outline = Color(0xFF6F7881), outlineVariant = Color(0xFFBEC7D0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEEF3F8),
    surfaceContainer = Color(0xFFE8EEF3), surfaceContainerHigh = Color(0xFFE2E8EE),
    surfaceContainerHighest = Color(0xFFDCE3E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ECDF5), onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B70), onPrimaryContainer = Color(0xFFCFE6FA),
    secondary = Color(0xFFB9C8D6), onSecondary = Color(0xFF243240),
    secondaryContainer = Color(0xFF3A4957), onSecondaryContainer = Color(0xFFD6E4F0),
    tertiary = Color(0xFFFFB77C), onTertiary = Color(0xFF4E2600),
    tertiaryContainer = Color(0xFF703700), onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0C1319), onBackground = Color(0xFFDEE3E9),
    surface = Color(0xFF0C1319), onSurface = Color(0xFFDEE3E9),
    surfaceVariant = Color(0xFF3F4850), onSurfaceVariant = Color(0xFFBEC7D0),
    outline = Color(0xFF89929B), outlineVariant = Color(0xFF3F4850),
    surfaceContainerLowest = Color(0xFF070D12), surfaceContainerLow = Color(0xFF131A20),
    surfaceContainer = Color(0xFF171E25), surfaceContainerHigh = Color(0xFF212930),
    surfaceContainerHighest = Color(0xFF2C343B),
)

/**
 * Debt-kind colours. Revolving debt (credit cards) is warm orange, the heat against the ice;
 * installment debt (loans) is indigo. Colour is never the only cue: cards also carry an icon and a text label.
 */
@Immutable
data class KindColors(
    val revolving: Color,
    val revolvingContainer: Color,
    val onRevolvingContainer: Color,
    val installment: Color,
    val installmentContainer: Color,
    val onInstallmentContainer: Color,
)

private val LightKindColors = KindColors(
    revolving = Color(0xFFB35A00), revolvingContainer = Color(0xFFFFDCC2), onRevolvingContainer = Color(0xFF311300),
    installment = Color(0xFF5A57B8), installmentContainer = Color(0xFFE3E0FF), onInstallmentContainer = Color(0xFF14106B),
)
private val DarkKindColors = KindColors(
    revolving = Color(0xFFFFB77C), revolvingContainer = Color(0xFF703700), onRevolvingContainer = Color(0xFFFFDCC2),
    installment = Color(0xFFC3C0FF), installmentContainer = Color(0xFF423F9B), onInstallmentContainer = Color(0xFFE3E0FF),
)

val LocalKindColors = staticCompositionLocalOf { LightKindColors }

val MaterialTheme.kindColors: KindColors
    @Composable get() = LocalKindColors.current

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AvalancheTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalKindColors provides if (darkTheme) DarkKindColors else LightKindColors) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
    }
}
