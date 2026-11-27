package com.emptysol.aeroplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AeroDarkPrimary,
    onPrimary = AeroDarkOnPrimary,
    primaryContainer = AeroDarkPrimaryContainer,
    onPrimaryContainer = AeroDarkOnPrimaryContainer,
    secondary = AeroDarkSecondary,
    onSecondary = AeroDarkOnSecondary,
    secondaryContainer = AeroDarkSecondaryContainer,
    onSecondaryContainer = AeroDarkOnSecondaryContainer,
    tertiary = AeroDarkTertiary,
    onTertiary = AeroDarkOnTertiary,
    tertiaryContainer = AeroDarkTertiaryContainer,
    onTertiaryContainer = AeroDarkOnTertiaryContainer,
    background = AeroDarkBackground,
    onBackground = AeroDarkOnBackground,
    surface = AeroDarkSurface,
    onSurface = AeroDarkOnSurface,
    onSurfaceVariant = AeroDarkOnSurfaceVariant,
    surfaceContainerLowest = AeroDarkSurfaceContainerLowest,
    surfaceContainerLow = AeroDarkSurfaceContainerLow,
    surfaceContainer = AeroDarkSurfaceContainer,
    surfaceContainerHigh = AeroDarkSurfaceContainerHigh,
    surfaceContainerHighest = AeroDarkSurfaceContainerHighest,
    outline = AeroDarkOutline,
    outlineVariant = AeroDarkOutlineVariant,
    error = AeroDarkError,
    onError = AeroDarkOnError,
    errorContainer = AeroDarkErrorContainer,
    onErrorContainer = AeroDarkOnErrorContainer
)

private val LightColorScheme = lightColorScheme(
    primary = AeroLightPrimary,
    onPrimary = AeroLightOnPrimary,
    primaryContainer = AeroLightPrimaryContainer,
    onPrimaryContainer = AeroLightOnPrimaryContainer,
    secondary = AeroLightSecondary,
    onSecondary = AeroLightOnSecondary,
    secondaryContainer = AeroLightSecondaryContainer,
    onSecondaryContainer = AeroLightOnSecondaryContainer,
    tertiary = AeroLightTertiary,
    onTertiary = AeroLightOnTertiary,
    tertiaryContainer = AeroLightTertiaryContainer,
    onTertiaryContainer = AeroLightOnTertiaryContainer,
    background = AeroLightBackground,
    onBackground = AeroLightOnBackground,
    surface = AeroLightSurface,
    onSurface = AeroLightOnSurface,
    onSurfaceVariant = AeroLightOnSurfaceVariant,
    surfaceContainerLowest = AeroLightSurfaceContainerLowest,
    surfaceContainerLow = AeroLightSurfaceContainerLow,
    surfaceContainer = AeroLightSurfaceContainer,
    surfaceContainerHigh = AeroLightSurfaceContainerHigh,
    surfaceContainerHighest = AeroLightSurfaceContainerHighest,
    outline = AeroLightOutline,
    outlineVariant = AeroLightOutlineVariant,
    error = AeroLightError,
    onError = AeroLightOnError,
    errorContainer = AeroLightErrorContainer,
    onErrorContainer = AeroLightOnErrorContainer
)

@Composable
fun MyApplicationTheme(
    themeMode: String = "System",
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = when (themeMode) {
        "Dark" -> true
        "Light" -> false
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = useDynamicColor,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalAppSpacing provides AppSpacing(),
        LocalExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
