package net.kirkanos.uptimekuma.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme =
    lightColorScheme(
        primary = KumaGreenDark,
        onPrimary = Color.White,
        primaryContainer = KumaGreenContainer,
        onPrimaryContainer = KumaSlate,
        secondary = KumaSlateLight,
        error = KumaRed,
        errorContainer = KumaRedContainer,
        background = KumaBackgroundLight,
        surface = KumaBackgroundLight,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = KumaGreen,
        onPrimary = KumaSlate,
        primaryContainer = KumaGreenDark,
        onPrimaryContainer = KumaGreenContainer,
        secondary = Color(0xFFB0BEC5),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        background = KumaBackgroundDark,
        surface = KumaBackgroundDark,
    )

/**
 * Material 3 theme for Uptime Kuma for Android.
 *
 * The app's minSdk is 31 (Android 12), so Material You dynamic color is always available;
 * [dynamicColor] exists so the user can opt out of it in settings. Status colors
 * ([MaterialTheme.statusColors]) are harmonized with the (dynamic) primary color.
 */
@Composable
fun KumaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }
    val statusColors =
        remember(darkTheme, colorScheme.primary) {
            (if (darkTheme) DarkStatusColors else LightStatusColors).harmonizedWith(colorScheme.primary)
        }

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KumaTypography,
            content = content,
        )
    }
}
