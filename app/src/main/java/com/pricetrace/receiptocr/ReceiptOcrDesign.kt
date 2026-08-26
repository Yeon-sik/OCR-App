package com.pricetrace.receiptocr

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalContentColor

/**
 * A deliberately monochrome variant of FitnessApp's hologram treatment.
 * It is reserved for the single judgment surface on a screen so decoration never competes with work.
 */
@Composable
internal fun MonochromeHologramHero(
    eyebrow: String,
    title: String,
    description: String,
    footer: String,
    modifier: Modifier = Modifier,
) {
    val inspectionMode = LocalInspectionMode.current
    val transition = rememberInfiniteTransition(label = "monochrome_hologram")
    val animatedPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_400, easing = LinearEasing),
        ),
        label = "monochrome_hologram_phase",
    )
    val shape = RoundedCornerShape(24.dp)
    val radius = 24.dp
    val stroke = 2.dp

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .shadow(elevation = 12.dp, shape = shape, clip = false)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF050506),
                            Color(0xFF2D2E32),
                            Color(0xFF111114),
                            Color(0xFF050506),
                        ),
                    ),
                )
                .drawWithContent {
                    drawContent()
                    val phase = if (inspectionMode) 0.52f else animatedPhase.value
                    val radiusPx = radius.toPx()
                    val travel = (-1f + phase * 3f) * size.width
                    val border = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF080809),
                            Color(0xFF777A80),
                            Color.White,
                            Color(0xFF777A80),
                            Color(0xFF080809),
                        ),
                        start = Offset(travel - size.width, 0f),
                        end = Offset(travel + size.width, size.height),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.14f),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawRoundRect(
                        brush = border,
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = stroke.toPx()),
                    )
                }
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Text(
                eyebrow,
                color = Color.White.copy(alpha = 0.64f),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
            )
            Text(
                title,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                description,
                modifier = Modifier.padding(top = 10.dp),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                footer,
                modifier = Modifier.padding(top = 18.dp),
                color = Color.White.copy(alpha = 0.64f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
