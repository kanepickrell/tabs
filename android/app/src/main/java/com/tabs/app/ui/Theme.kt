package com.tabs.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dynamic color is deliberately not used. It tinted the card and the page the
// same muddy brown, and a card stack lives or dies on the card reading as an
// object sitting above the background.

private val Ink = Color(0xFF0B0C0E)
private val CardDark = Color(0xFF16191E)
private val RaisedDark = Color(0xFF1E222A)
private val HairlineDark = Color(0xFF2B313B)
private val TextDark = Color(0xFFECEFF3)
private val MutedDark = Color(0xFF99A2AE)
private val AmberDark = Color(0xFFE3A857)

private val Paper = Color(0xFFF3F0EA)
private val CardLight = Color(0xFFFFFFFF)
private val RaisedLight = Color(0xFFEDE9E1)
private val HairlineLight = Color(0xFFDFDAD1)
private val TextLight = Color(0xFF15171B)
private val MutedLight = Color(0xFF5C636D)
private val AmberLight = Color(0xFF9A6418)

val CloseRed = Color(0xFFE5484D)
val KeepGreen = Color(0xFF30A46C)

private val DarkScheme = darkColorScheme(
    primary = AmberDark,
    onPrimary = Ink,
    background = Ink,
    onBackground = TextDark,
    surface = CardDark,
    onSurface = TextDark,
    surfaceVariant = RaisedDark,
    onSurfaceVariant = MutedDark,
    outlineVariant = HairlineDark,
    error = CloseRed,
)

private val LightScheme = lightColorScheme(
    primary = AmberLight,
    onPrimary = Color.White,
    background = Paper,
    onBackground = TextLight,
    surface = CardLight,
    onSurface = TextLight,
    surfaceVariant = RaisedLight,
    onSurfaceVariant = MutedLight,
    outlineVariant = HairlineLight,
    error = CloseRed,
)

@Composable
fun TabsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
