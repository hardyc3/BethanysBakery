package com.bethanysbakery.admin.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B3F2A),
    onPrimary = Color(0xFFFFF8ED),
    secondary = Color(0xFFB9783F),
    background = Color(0xFFFFF8ED),
    onBackground = Color(0xFF352418),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8C58D),
    onPrimary = Color(0xFF352418),
    secondary = Color(0xFFD69A62),
    background = Color(0xFF241A14),
    onBackground = Color(0xFFF5E7D2),
    surface = Color(0xFF362820),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BakeryAdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BakeryTypography,
        content = content,
    )
}
