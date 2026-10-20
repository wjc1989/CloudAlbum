package com.esct.album.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    background = Color.White,
    surface = Color.White,
    onBackground = Color(0xFF333333),
    onSurface = Color(0xFF333333),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF1B1B1B),
    surface = Color(0xFF1B1B1B),
    onBackground = Color.White,
    onSurface = Color.White,
)

/** 跟随系统深色模式（对应原来的 xStore.setDarkModel('auto')） */
@Composable
fun CloudAlbumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
