package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CoffeeLightCream,
    secondary = CoffeeWarmBeige,
    tertiary = CoffeeMediumBrown,
    background = CoffeeEspressoBlack,
    surface = CoffeeEspressoDark,
    surfaceVariant = CoffeeCardDark,
    onPrimary = CoffeeBrownishBlack,
    onSecondary = CoffeeBrownishBlack,
    onBackground = CoffeeLightCream,
    onSurface = CoffeeLightCream,
    onSurfaceVariant = CoffeeWarmBeige,
    outline = CoffeeWarmBrown
)

private val LightColorScheme = lightColorScheme(
    primary = CoffeeBrownishBlack,
    secondary = CoffeeWarmBrown,
    tertiary = CoffeeMediumBrown,
    background = CoffeeLatteWhite,
    surface = CoffeeCardLight,
    surfaceVariant = CoffeeLatteLight,
    onPrimary = CoffeeLightCream,
    onSecondary = CoffeeLightCream,
    onBackground = CoffeeBrownishBlack,
    onSurface = CoffeeBrownishBlack,
    onSurfaceVariant = CoffeeWarmBrown,
    outline = CoffeeWarmBeige
)

@Composable
fun MyApplicationTheme(
    themeMode: String = "System",
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDark = when (themeMode) {
        "Light" -> false
        "Dark" -> true
        else -> darkTheme
    }
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
