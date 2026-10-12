package com.pricetrace.receiptocr.publishing.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptocr.publishing.generator.NutritionTableRenderResult
import com.pricetrace.receiptocr.publishing.generator.NutritionTableRenderer
import com.pricetrace.receiptocr.publishing.share.PublicationClipboardContent
import com.pricetrace.receiptocr.publishing.share.PublicationShareContent
import com.pricetrace.receiptscanner.publishing.generator.BlogDraftGenerator
import com.pricetrace.receiptscanner.publishing.model.PublicationPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stateless preview can render a mock package without the app ViewModel or navigation. */
@Composable
fun PublicationPreviewScreen(
    publication: PublicationPackage,
    onBack: () -> Unit,
    onCopy: (PublicationClipboardContent) -> Unit,
    onShare: (PublicationShareContent) -> Unit,
    actionMessage: String? = null,
    onRegenerate: (() -> Unit)? = null,
    sampleTable: ImageBitmap? = null,
) {
    val draft = publication.draft
    val imageCount = draft.evidenceImages.size + draft.generatedImages.size
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("publication_preview"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("게시물 미리보기", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onBack, modifier = Modifier.testTag("publication_close")) { Text("닫기") }
            }
            Text("복사하거나 공유한 뒤 네이버 블로그에서 내용을 확인하고 최종 발행하세요.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (publication.notices.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        publication.notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { Text("원본 이미지", style = MaterialTheme.typography.titleLarge) }
        if (draft.evidenceImages.isEmpty()) {
            item { Text("연결된 원본 이미지가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(draft.evidenceImages, key = { "original-" + it.id }) { image ->
            PublicationUriImage(image.uri, "원본 증거 이미지", "publication_original_" + image.id)
        }
        item { Text("영양성분표 이미지", style = MaterialTheme.typography.titleLarge) }
        if (sampleTable != null) {
            item {
                Image(sampleTable, "샘플 영양성분표", Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth)
            }
        } else if (draft.generatedImages.isEmpty()) {
            item {
                Text("영양성분표 이미지를 준비하지 못했습니다. 텍스트 복사는 사용할 수 있습니다.")
                onRegenerate?.let { retry ->
                    OutlinedButton(onClick = retry) { Text("이미지 다시 생성") }
                }
            }
        }
        items(draft.generatedImages, key = { "generated-" + it.id }) { image ->
            PublicationUriImage(image.uri, image.label, "publication_table")
        }
        item {
            Text("게시 제목", style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(draft.title, modifier = Modifier.testTag("publication_title")) }
        }
        item {
            Text("본문", style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(draft.body, modifier = Modifier.testTag("publication_body")) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCopy(PublicationClipboardContent.TITLE) },
                    modifier = Modifier.fillMaxWidth().testTag("publication_copy_title")) { Text("제목 복사") }
                OutlinedButton(onClick = { onCopy(PublicationClipboardContent.BODY) },
                    modifier = Modifier.fillMaxWidth().testTag("publication_copy_body")) { Text("본문 복사") }
                OutlinedButton(onClick = { onCopy(PublicationClipboardContent.ALL_TEXT) },
                    modifier = Modifier.fillMaxWidth().testTag("publication_copy_all")) { Text("전체 텍스트 복사") }
                OutlinedButton(onClick = { onShare(PublicationShareContent.TEXT) },
                    modifier = Modifier.fillMaxWidth().testTag("publication_share_text")) { Text("텍스트 공유") }
                OutlinedButton(onClick = { onShare(PublicationShareContent.IMAGES) }, enabled = imageCount > 0,
                    modifier = Modifier.fillMaxWidth().testTag("publication_share_images")) {
                    Text("이미지 공유 (" + imageCount + "개)")
                }
                Button(onClick = { onShare(PublicationShareContent.ALL) },
                    modifier = Modifier.fillMaxWidth().testTag("publication_share_all")) { Text("전체 공유") }
                actionMessage?.let { Text(it, modifier = Modifier.testTag("publication_action_message")) }
                Text("공유 앱마다 제목·본문·이미지 수신 방식이 다릅니다. 필요한 내용은 복사 기능도 이용하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class LoadedPublicationImage(val bitmap: Bitmap?)

@Composable
private fun PublicationUriImage(uri: String, label: String, tag: String) {
    val context = LocalContext.current
    val loaded by produceState<LoadedPublicationImage?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val parsed = Uri.parse(uri)
                check(parsed.scheme == "content")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                check(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1440) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                LoadedPublicationImage(context.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                LoadedPublicationImage(null)
            } catch (_: Exception) {
                LoadedPublicationImage(null)
            }
        }
    }
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        val image = loaded
        when {
            image == null -> CircularProgressIndicator(Modifier.padding(24.dp))
            image.bitmap == null -> Text("이미지를 표시할 수 없습니다.", Modifier.padding(16.dp))
            else -> Image(image.bitmap.asImageBitmap(), label,
                Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(12.dp),
                contentScale = ContentScale.Fit)
        }
    }
}

@Preview(name = "독립 Blog Publication 샘플", widthDp = 390, heightDp = 844)
@Composable
private fun PublicationPreviewSample() {
    val source = remember { PublicationSamples.source() }
    val publication = remember { PublicationPackage(BlogDraftGenerator().generate(source),
        listOf("샘플 데이터입니다. 실제 원본 이미지는 연결하지 않았습니다.")) }
    val table = remember { (NutritionTableRenderer().render(source) as? NutritionTableRenderResult.Ready)
        ?.bitmap?.asImageBitmap() }
    MaterialTheme {
        Surface {
            PublicationPreviewScreen(publication, {}, {}, {}, sampleTable = table)
        }
    }
}
