package dev.ubuntu4a.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ubuntu4a.core.data.model.AppSettings
import dev.ubuntu4a.core.data.model.ThemeMode

// ---------- Material 3 Expressive shape scale (asymmetric / rounded) ----------
val UbuntuShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(40.dp),
)

// ---------- Material 3 Expressive typography ----------
val UbuntuTypography = Typography(
    displayLarge = androidx.compose.material3.Typography().displayLarge.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    ),
    displayMedium = androidx.compose.material3.Typography().displayMedium.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    ),
    headlineLarge = androidx.compose.material3.Typography().headlineLarge.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    ),
    titleLarge = androidx.compose.material3.Typography().titleLarge.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    ),
    labelLarge = androidx.compose.material3.Typography().labelLarge.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    ),
)

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

@Composable
fun resolveColorScheme(settings: AppSettings, darkTheme: Boolean): ColorScheme {
    val ctx = LocalContext.current
    val useDynamic = settings.dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    return when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(ctx)
        useDynamic -> dynamicLightColorScheme(ctx)
        darkTheme -> DarkColors
        else -> LightColors
    }
}

@Composable
fun Ubuntu4ATheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = resolveColorScheme(settings, darkTheme)
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = UbuntuShapes,
            typography = UbuntuTypography,
            content = content,
        )
    }
}

val LocalIsDarkTheme = staticCompositionLocalOf { false }

object UbuntuBrand {
    @ReadOnlyComposable
    @Composable
    fun gradient(): Brush = Brush.linearGradient(
        listOf(UbuntuOrange, UbuntuAubergine),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}
