package com.pricetrace.receiptocr.publishing.naver

import com.pricetrace.receiptocr.publishing.generator.NutritionTableRenderer
import com.pricetrace.receiptocr.publishing.generator.NutritionTableRenderResult
import com.pricetrace.receiptocr.publishing.generator.PublicationAssetStore
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolution
import com.pricetrace.receiptscanner.publishing.evidence.PublicationEvidenceResolver
import com.pricetrace.receiptscanner.publishing.generator.BlogDraftGenerator
import com.pricetrace.receiptscanner.publishing.model.PublicationPackage
import com.pricetrace.receiptscanner.publishing.model.PublicationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Prepares exports; opening the share sheet and final Naver publishing remain explicit actions. */
class NaverBlogPackageGenerator(
    private val evidenceResolver: PublicationEvidenceResolver,
    private val assetStore: PublicationAssetStore,
    private val renderer: NutritionTableRenderer = NutritionTableRenderer(),
) {
    suspend fun generate(source: PublicationSource): PublicationPackage = withContext(Dispatchers.IO) {
        val draft = BlogDraftGenerator().generate(source)
        val evidence = try {
            evidenceResolver.resolve(source.documentId, source.evidenceAttachmentIds, source.evidencePageIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PublicationEvidenceResolution(notices = listOf(
                "원본 이미지 연결에 실패했습니다. 제목·본문·생성 표는 사용할 수 있습니다.",
            ))
        }
        currentCoroutineContext().ensureActive()
        val notices = evidence.notices.toMutableList()
        val generated = when (val result = renderer.render(source)) {
            is NutritionTableRenderResult.Failed -> {
                notices += result.message
                emptyList()
            }
            is NutritionTableRenderResult.Ready -> {
                try {
                    currentCoroutineContext().ensureActive()
                    listOf(assetStore.saveTable(result.bitmap))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: OutOfMemoryError) {
                    notices += "생성 표를 저장할 메모리가 부족합니다. 텍스트 복사는 사용할 수 있습니다."
                    emptyList()
                } catch (_: Exception) {
                    notices += "생성 표를 공유용 파일로 저장하지 못했습니다. 텍스트 복사는 사용할 수 있습니다."
                    emptyList()
                } finally {
                    result.bitmap.recycle()
                }
            }
        }
        PublicationPackage(draft.copy(evidenceImages = evidence.images, generatedImages = generated),
            notices.distinct())
    }
}
