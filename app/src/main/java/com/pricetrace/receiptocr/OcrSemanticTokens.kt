package com.pricetrace.receiptocr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Presentation-only roles. Keep both platform palettes aligned without changing Core. */
internal object OcrSemanticTokens {
    val light = lightColorScheme(
        primary = Color(0xFF202124), onPrimary = Color.White,
        primaryContainer = Color(0xFFE2EBEF), onPrimaryContainer = Color(0xFF202124),
        secondary = Color(0xFF315F78), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2EBEF), onSecondaryContainer = Color(0xFF202124),
        tertiary = Color(0xFF805718), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF5ECD8), onTertiaryContainer = Color(0xFF805718),
        background = Color(0xFFF4F3F0), onBackground = Color(0xFF202124),
        surface = Color.White, onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFEBEAE6), onSurfaceVariant = Color(0xFF5E6268),
        surfaceContainer = Color(0xFFEBEAE6), surfaceContainerLow = Color(0xFFF4F3F0),
        surfaceContainerHigh = Color.White,
        outline = Color(0xFF7B7F84), outlineVariant = Color(0xFFD9D9D4),
        inverseSurface = Color(0xFF202124), inverseOnSurface = Color.White,
        error = Color(0xFFB23532), onError = Color.White,
        errorContainer = Color(0xFFFBECEB), onErrorContainer = Color(0xFF8A2422),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFEEEFED), onPrimary = Color(0xFF202124),
        primaryContainer = Color(0xFF293E4A), onPrimaryContainer = Color(0xFFEEEFED),
        secondary = Color(0xFF9FC8E0), onSecondary = Color(0xFF202124),
        secondaryContainer = Color(0xFF293E4A), onSecondaryContainer = Color(0xFFEEEFED),
        tertiary = Color(0xFFE6C27A), onTertiary = Color(0xFF202124),
        tertiaryContainer = Color(0xFF413621), onTertiaryContainer = Color(0xFFE6C27A),
        background = Color(0xFF141618), onBackground = Color(0xFFEEEFED),
        surface = Color(0xFF1D2023), onSurface = Color(0xFFEEEFED),
        surfaceVariant = Color(0xFF181B1E), onSurfaceVariant = Color(0xFFB8BCC2),
        surfaceContainer = Color(0xFF181B1E), surfaceContainerLow = Color(0xFF141618),
        surfaceContainerHigh = Color(0xFF272B30),
        outline = Color(0xFF9299A1), outlineVariant = Color(0xFF383D43),
        inverseSurface = Color(0xFFEEEFED), inverseOnSurface = Color(0xFF202124),
        error = Color(0xFFF3ABA7), onError = Color(0xFF202124),
        errorContainer = Color(0xFF482725), onErrorContainer = Color(0xFFF3ABA7),
    )
    val success: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9DD8B5) else Color(0xFF28634A)
    val warning: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE6C27A) else Color(0xFF805718)
    val disabledContent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8D949B) else Color(0xFF767A80)
    val disabledSurface: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF30353A) else Color(0xFFE6E5E1)
}
