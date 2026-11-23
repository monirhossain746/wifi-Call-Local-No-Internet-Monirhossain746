package com.android.wificalllocal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Blue500,
    onPrimary = Color.White,
    primaryContainer = Blue100,
    onPrimaryContainer = Navy900,
    secondary = Cyan500,
    onSecondary = Navy950,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = Navy900,
    tertiary = Green500,
    onTertiary = Navy950,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = Red700,
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue400,
    onPrimary = Navy950,
    primaryContainer = Navy700,
    onPrimaryContainer = Blue100,
    secondary = Cyan500,
    onSecondary = Navy950,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = Green500,
    onTertiary = Navy950,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = Red500,
)

@Composable
fun WifiCallLocalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WifiCallTypography,
        content = content,
    )
}
