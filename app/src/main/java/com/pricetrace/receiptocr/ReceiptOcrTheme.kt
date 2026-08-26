package com.pricetrace.receiptocr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF15161A)
private val InkSecondary = Color(0xFF6A6E76)
private val InkTertiary = Color(0xFFA2A6AE)
private val Page = Color(0xFFECEEF1)
private val Raised = Color(0xFFFFFFFF)
private val Subtle = Color(0xFFF5F6F8)
private val Inverse = Color(0xFF111114)
private val Hairline = Color(0xFFE2E4E8)
private val Positive = Color(0xFF2E7D5B)
private val Negative = Color(0xFFC0453E)
private val Warning = Color(0xFFA8761F)

private val LightColors = lightColorScheme(
    primary = Inverse,
    onPrimary = Color.White,
    primaryContainer = Inverse,
    onPrimaryContainer = Color.White,
    secondary = InkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Subtle,
    onSecondaryContainer = Ink,
    tertiary = InkTertiary,
    onTertiary = Inverse,
    tertiaryContainer = Color(0xFFEAEBEE),
    onTertiaryContainer = Ink,
    background = Page,
    onBackground = Ink,
    surface = Raised,
    onSurface = Ink,
    surfaceVariant = Subtle,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = Raised,
    surfaceContainerLow = Raised,
    surfaceContainer = Subtle,
    surfaceContainerHigh = Color(0xFFF0F1F3),
    surfaceContainerHighest = Color(0xFFEAEBEE),
    inverseSurface = Inverse,
    inverseOnSurface = Color.White,
    inversePrimary = Color.White,
    outline = Color(0xFFBEC1C7),
    outlineVariant = Hairline,
    error = Negative,
    onError = Color.White,
    errorContainer = Color(0xFFFBEDEB),
    onErrorContainer = Color(0xFF7E2824),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Inverse,
    primaryContainer = Color.White,
    onPrimaryContainer = Inverse,
    secondary = Color(0xFFB9BBC1),
    onSecondary = Inverse,
    secondaryContainer = Color(0xFF242529),
    onSecondaryContainer = Color(0xFFF2F2F3),
    tertiary = Color(0xFF91949B),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF303136),
    onTertiaryContainer = Color.White,
    background = Color(0xFF050506),
    onBackground = Color(0xFFF4F4F5),
    surface = Inverse,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1B1C1F),
    onSurfaceVariant = Color(0xFFB2B4BA),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Inverse,
    surfaceContainerHigh = Color(0xFF1B1C1F),
    surfaceContainerHighest = Color(0xFF242529),
    inverseSurface = Color.White,
    inverseOnSurface = Inverse,
    inversePrimary = Inverse,
    outline = Color(0xFF5C5E64),
    outlineVariant = Color(0xFF303136),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1514),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

internal val ReceiptPositive = Positive
internal val ReceiptWarning = Warning

private val ReceiptShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val ReceiptTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun ReceiptOcrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ReceiptTypography,
        shapes = ReceiptShapes,
        content = content,
    )
}
