package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.CanonicalEditableField

internal fun reviewFieldPriority(field: CanonicalEditableField, errors: Set<String>, drafts: Set<String>): Int = when {
    field.path in errors -> 0
    field.path in drafts -> 1
    !field.nullable && field.value.isNullOrBlank() -> 2
    field.path.substringAfterLast('.') in setOf("product_name", "brand_name", "brand", "basis_amount", "basis_unit") -> 3
    field.path.startsWith("nutrition[") && field.path.contains("nutrients") -> 4
    !field.nullable -> 5
    else -> 6
}

/** Draft text stays outside the canonical document until the normal audited edit succeeds. */
internal class DesktopReviewDraftActions(
    val values: Map<String, String>,
    val change: (String, String) -> Unit,
    val apply: (String, (CanonicalReviewController) -> Boolean) -> Unit,
    val discard: (String) -> Unit,
    val applyAll: () -> Unit = {},
    val discardAll: () -> Unit = {},
)

internal val LocalReviewDraftActions = staticCompositionLocalOf<DesktopReviewDraftActions?> { null }

internal class ReviewNavigation {
    var requestedPath by mutableStateOf<String?>(null)
        private set
    var requestNumber by mutableIntStateOf(0)
        private set

    fun next(paths: List<String>) {
        if (paths.isEmpty()) return
        requestedPath = paths[(paths.indexOf(requestedPath) + 1) % paths.size]
        requestNumber++
    }
}

internal val LocalReviewNavigation = staticCompositionLocalOf<ReviewNavigation?> { null }

@Composable
internal fun ReviewTarget(path: String, inputFocus: FocusRequester? = null, onFocused: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val navigation = LocalReviewNavigation.current
    val bringIntoView = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(navigation?.requestNumber) {
        if (navigation?.requestedPath == path) {
            bringIntoView.bringIntoView()
            (inputFocus ?: focus).requestFocus()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("review-target:$path")
        .bringIntoViewRequester(bringIntoView).onFocusChanged { if (it.hasFocus) onFocused() }
        .focusRequester(focus).collectorFocusOutline().focusable(),
        content = content)
}
