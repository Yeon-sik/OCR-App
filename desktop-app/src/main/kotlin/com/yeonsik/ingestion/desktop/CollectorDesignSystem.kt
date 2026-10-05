package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * One visual language for the Windows collector. Values are intentionally modest: this is a
 * dense review tool, not a collection of floating cards.
 */
object CollectorTokens {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 24.dp
    val panePadding = 16.dp
    val paneGap = 12.dp
    val compactControlHeight = 44.dp
    val standardRadius = 14.dp
    val compactRadius = 10.dp
    val controlShape = RoundedCornerShape(compactRadius)
    val panelShape = RoundedCornerShape(standardRadius)
    val focusWidth = 2.dp
}

@Composable
fun CollectorTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) OcrSemanticTokens.dark else OcrSemanticTokens.light
    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(
            displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = androidx.compose.material3.Shapes(
            extraSmall = CollectorTokens.controlShape,
            small = CollectorTokens.controlShape,
            medium = CollectorTokens.panelShape,
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}

/** A high-contrast focus ring applied consistently to desktop controls. */
fun Modifier.collectorFocusOutline(shape: Shape = CollectorTokens.controlShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .then(
            if (focused) {
                Modifier.border(CollectorTokens.focusWidth, MaterialTheme.colorScheme.secondary, shape)
            } else {
                Modifier
            },
        )
}

@Composable
fun CollectorSection(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(CollectorTokens.space2),
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        subtitle?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        content()
    }
}

enum class CollectorStatusKind(val icon: String, val label: String) {
    REVIEW("!", "검토 필요"),
    COMPLETE("✓", "완료"),
    ERROR("×", "오류"),
    PROCESSING("…", "처리 중"),
    NEUTRAL("•", "대기"),
}

@Composable
fun StatusBadge(
    kind: CollectorStatusKind,
    label: String = kind.label,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content, border) = when (kind) {
        CollectorStatusKind.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, colors.error)
        CollectorStatusKind.COMPLETE -> Triple(colors.surface, OcrSemanticTokens.success, OcrSemanticTokens.success)
        CollectorStatusKind.REVIEW -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
        CollectorStatusKind.PROCESSING -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
        CollectorStatusKind.NEUTRAL -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
    }
    Surface(
        modifier = modifier.semantics {
            contentDescription = listOfNotNull(label, detail).joinToString(": ")
        },
        color = container,
        contentColor = content,
        shape = CollectorTokens.controlShape,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CollectorTokens.space2, vertical = CollectorTokens.space1),
            horizontalArrangement = Arrangement.spacedBy(CollectorTokens.space1),
        ) {
            Text(kind.icon, modifier = Modifier.clearAndSetSemantics { }, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun CollectorButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
    contentDescription: String = label,
) {
    val base = modifier
        .heightIn(min = CollectorTokens.compactControlHeight)
        .collectorFocusOutline()
        .semantics { this.contentDescription = contentDescription }
    if (emphasized) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = base,
            shape = CollectorTokens.controlShape,
            contentPadding = PaddingValues(horizontal = CollectorTokens.space3, vertical = CollectorTokens.space2),
            colors = ButtonDefaults.buttonColors(),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = base,
            shape = CollectorTokens.controlShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = CollectorTokens.space3, vertical = CollectorTokens.space2),
        ) { Text(label) }
    }
}

@Composable
fun CollectorIconButton(
    symbol: String,
    accessibleName: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = CollectorTokens.compactControlHeight)
            .collectorFocusOutline()
            .semantics {
                role = Role.Button
                contentDescription = accessibleName
            },
    ) {
        Text(symbol, modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium)
    }
}

fun destinationAccent(destination: String, colors: ColorScheme = OcrSemanticTokens.light): Color = when (destination) {
    "PriceTrace" -> Color(0xFF16803A)
    "CashOS" -> Color(0xFF8A6800)
    "Fitness" -> Color(0xFF176C89)
    else -> colors.outline
}
