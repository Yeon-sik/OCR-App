package com.pricetrace.receiptocr.publishing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pricetrace.receiptocr.publishing.evidence.SessionPublicationEvidenceResolver
import com.pricetrace.receiptocr.publishing.generator.PublicationAssetStore
import com.pricetrace.receiptocr.publishing.naver.NaverBlogPackageGenerator
import com.pricetrace.receiptocr.publishing.share.*
import com.pricetrace.receiptscanner.domain.ReceiptPage
import com.pricetrace.receiptscanner.nutrition.NutritionLabelDraft
import com.pricetrace.receiptscanner.publishing.adapter.NutritionPublicationAdapter
import com.pricetrace.receiptscanner.publishing.adapter.PublicationAdaptation
import com.pricetrace.receiptscanner.publishing.model.PublicationPackage
import com.pricetrace.receiptscanner.publishing.model.PublicationSource
import java.io.File
import kotlinx.coroutines.CancellationException

/** Integration surface. Only the current verified general draft is supplied by the caller. */
@Composable
fun NutritionPublicationEntry(
    draft: NutritionLabelDraft,
    pages: List<ReceiptPage>,
    resolvePageFile: (String) -> File,
    isBusy: Boolean = false,
) {
    val adaptation = remember(draft) { NutritionPublicationAdapter.adapt(draft) }
    var sourceSnapshot by remember(draft.documentId) { mutableStateOf<PublicationSource?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { sourceSnapshot = (adaptation as? PublicationAdaptation.Ready)?.source },
            enabled = adaptation is PublicationAdaptation.Ready && !isBusy,
            modifier = Modifier.fillMaxWidth().testTag("open_blog_publication"),
        ) { Text("블로그용 게시물 생성") }
        Text(
            when (adaptation) {
                is PublicationAdaptation.Ready -> "검수한 영양정보로 제목·본문·표 이미지를 준비합니다."
                is PublicationAdaptation.Blocked -> adaptation.errors.firstOrNull().orEmpty()
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    sourceSnapshot?.let { source ->
        Dialog(
            onDismissRequest = { sourceSnapshot = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PublicationPreviewHost(source, pages, resolvePageFile) { sourceSnapshot = null }
            }
        }
    }
}

@Composable
private fun PublicationPreviewHost(
    source: PublicationSource,
    pages: List<ReceiptPage>,
    resolvePageFile: (String) -> File,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember(source) { mutableIntStateOf(0) }
    var publication by remember(source) { mutableStateOf<PublicationPackage?>(null) }
    var failure by remember(source) { mutableStateOf<String?>(null) }
    var actionMessage by remember(source) { mutableStateOf<String?>(null) }
    val clipboard = remember(context) { PublicationClipboardExporter(context) }
    val share = remember(context) { PublicationShareExporter(context) }
    LaunchedEffect(source, attempt) {
        publication = null
        failure = null
        actionMessage = null
        try {
            val appContext = context.applicationContext
            publication = NaverBlogPackageGenerator(
                evidenceResolver = SessionPublicationEvidenceResolver(appContext, pages, resolvePageFile),
                assetStore = PublicationAssetStore(appContext),
            ).generate(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = "게시물을 준비하지 못했습니다. 다시 시도하세요."
        }
    }
    val ready = publication
    if (ready == null) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onClose) { Text("닫기") }
            if (failure == null) {
                CircularProgressIndicator()
                Text("검수한 영양정보로 게시물을 준비하고 있습니다.")
            } else {
                Text(failure.orEmpty())
                Button(onClick = { attempt++ }) { Text("다시 생성") }
            }
        }
    } else {
        PublicationPreviewScreen(
            publication = ready,
            onBack = onClose,
            onCopy = { content ->
                actionMessage = when (val result = clipboard.copy(ready.draft, content)) {
                    PublicationExportResult.Completed -> "복사했습니다."
                    is PublicationExportResult.Failed -> result.message
                }
            },
            onShare = { content ->
                actionMessage = when (val result = share.share(ready.draft, content)) {
                    PublicationExportResult.Completed -> "공유 창을 열었습니다. 선택한 앱에서 최종 발행하세요."
                    is PublicationExportResult.Failed -> result.message
                }
            },
            actionMessage = actionMessage,
            onRegenerate = { attempt++ },
        )
    }
}
