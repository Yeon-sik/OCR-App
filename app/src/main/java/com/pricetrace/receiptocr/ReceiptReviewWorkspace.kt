package com.pricetrace.receiptocr

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pricetrace.receiptscanner.domain.BoundingBox
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.domain.TranscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class ReviewSourceSelection(
    val label: String = "검수 내용", val value: String = "", val pageId: String? = null,
    val boxes: List<BoundingBox> = emptyList(), val recognizedText: String? = null,
)
internal val LocalReviewSource = staticCompositionLocalOf<(ReviewSourceSelection, Boolean) -> Unit> { { _, _ -> } }
internal val LocalReviewContext = staticCompositionLocalOf { ReviewSourceSelection() }
internal val reviewWorkspaceScreens = setOf(AppScreen.FIELD_REVIEW, AppScreen.ITEM_REVIEW, AppScreen.RECONCILIATION,
    AppScreen.AI_CORRECTION, AppScreen.MERCHANT_REVIEW, AppScreen.PRODUCT_CANDIDATE_REVIEW,
    AppScreen.NUTRITION_REVIEW, AppScreen.CONSUMPTION_REVIEW, AppScreen.CANONICAL_JSON_VALIDATOR)

@Composable
internal fun ReviewWorkspaceBar(state: ReceiptAppUiState, source: ReviewSourceSelection,
    hasSource: Boolean, onSource: () -> Unit, onFields: () -> Unit, onItems: () -> Unit, onTotals: () -> Unit,
    onAttention: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("review_workspace")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(state.receipt?.merchant?.name ?: state.nutritionDraft?.productName ?: "검수 작업",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    val verified = state.receipt?.document?.source?.transcriptionStatus == TranscriptionStatus.USER_VERIFIED
                    Text(if (verified) "영수증 검수 확정" else "원본과 비교해 확인하세요", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onSource, modifier = Modifier.heightIn(min = 48.dp).testTag("review_source_button")) {
                    Text(if (hasSource) "원본 보기" else "원본 확인")
                }
            }
            if (source.value.isNotBlank()) Text("현재 값 · ${source.label}: ${source.value}", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, modifier = Modifier.testTag("review_current_value"))
            if (state.receipt != null && state.screen in setOf(AppScreen.FIELD_REVIEW, AppScreen.ITEM_REVIEW, AppScreen.RECONCILIATION, AppScreen.AI_CORRECTION)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onFields) { Text("기본 정보") }
                    TextButton(onClick = onItems) { Text("항목") }
                    TextButton(onClick = onTotals) { Text("합계·확정") }
                    state.progress?.takeIf { it.attentionLineItemCount > 0 }?.let {
                        TextButton(onClick = onAttention, modifier = Modifier.testTag("workspace_attention")) {
                            Text("확인 필요 ${it.attentionLineItemCount}행", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReviewSourceDialog(pages: List<ReceiptPage>, resolve: (String) -> File,
    bundleFiles: Map<String, String>, selection: ReviewSourceSelection, onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().testTag("review_source_dialog"), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("원본 대조", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("source_return")) { Text("검수로 돌아가기") }
                }
                Text("${selection.label} · 현재 값: ${selection.value.ifBlank { "미확인" }}", Modifier.padding(horizontal = 16.dp))
                Text(if (selection.boxes.isEmpty()) "정확한 위치 정보 없음 · 원본 전체를 확인하세요" else "연결된 인식 영역을 표시합니다",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val orderedPages = pages.sortedBy { if (it.id == selection.pageId) 0 else 1 }
                    items(orderedPages, key = { it.id }) { page ->
                        EvidenceImage(page, resolve(page.storageKey), if (page.id == selection.pageId) selection.boxes else emptyList(), true, 420)
                    }
                    items(bundleFiles.entries.toList(), key = { it.key }) { (_, path) -> ReviewFileImage(File(path)) }
                    if (pages.isEmpty() && bundleFiles.isEmpty()) item {
                        Text("연결된 원본 이미지가 없습니다. 원본이 필요한 작업은 원본을 첨부한 뒤 확정하세요.", Modifier.testTag("source_unavailable"))
                    }
                    selection.recognizedText?.let { text -> item { Text("인식된 글자 (원본과 대조 필요)\n$text") } }
                }
            }
        }
    }
}

@Composable
private fun ReviewFileImage(file: File) {
    var loading by remember(file) { mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeSampledBitmap(file)?.asImageBitmap() }.getOrNull() }
        loading = false
    }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var pan by remember(file) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, delta, _ -> scale = (scale * zoom).coerceIn(1f, 5f); pan = if (scale == 1f) Offset.Zero else pan + delta }
    Column {
        Text(file.name, style = MaterialTheme.typography.bodySmall)
        Row { TextButton(onClick = { scale = 1f; pan = Offset.Zero }) { Text("화면 맞춤") } }
        Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds()) {
            bitmap?.let { Image(it, "원본 자료: ${file.name}", Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y).transformable(transform), contentScale = ContentScale.Fit) }
                ?: Text(if (loading) "원본을 불러오는 중" else "이 원본은 미리볼 수 없습니다. 연결된 자료는 유지됩니다.")
        }
    }
}
