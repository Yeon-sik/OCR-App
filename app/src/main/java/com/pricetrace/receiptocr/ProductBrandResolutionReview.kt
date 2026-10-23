package com.pricetrace.receiptocr
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.*

@Composable
internal fun ProductBrandResolutionEditor(product: ProductCandidate, drafts: List<ProductBrandResolutionDraft>,
    configured: Boolean, busy: Boolean, search: suspend (String) -> List<PriceTraceBrandMatch>,
    onChange: (ProductBrandResolutionDraft) -> Unit, metadata: String? = null) {
    Column(Modifier.fillMaxWidth().testTag("pt_brand_${product.clientKey}"), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("PriceTrace 브랜드 연결",style=MaterialTheme.typography.titleSmall)
        Text("관측값은 그대로 보존하며, 선택한 연결은 PriceTrace 제출 시 저장됩니다.",style=MaterialTheme.typography.bodySmall)
        productBrandReviewMessages(metadata,product.clientKey).forEach { Text(it,modifier=Modifier.testTag("pt_brand_semantic_review")) }
        ProductBrandFactField.entries.forEach { field ->
            val observed=field.observed(product)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            key(product.clientKey,field,observed) {
                var open by rememberSaveable { mutableStateOf(false) }
                var query by rememberSaveable { mutableStateOf(observed) }
                var retry by remember { mutableStateOf(0) }
                var loading by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var matches by remember { mutableStateOf(emptyList<PriceTraceBrandMatch>()) }
                val draft=drafts.singleOrNull { it.sourceField==field && it.matches(product) }
                LaunchedEffect(open,query,retry) {
                    if(!open || !configured) return@LaunchedEffect
                    loading=true; error=null; matches=emptyList()
                    val requested=query
                    try {
                        delay(250)
                        val result=withContext(Dispatchers.IO) { search(requested) }
                        currentCoroutineContext().ensureActive()
                        if(query==requested) matches=result
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(failure: Exception) { error=failure.message ?: "브랜드 검색에 실패했습니다." }
                    finally { if(currentCoroutineContext().isActive) loading=false }
                }
                Text("${field.label} 관측값: $observed")
                if(draft?.intent==ProductBrandSelectionIntent.SELECTED) {
                    Text(listOfNotNull(draft.parentCanonicalName,draft.canonicalName).joinToString(" > "))
                    if(draft.parentBrandId!=null && draft.parentCanonicalName==null) Text("상위 브랜드 공개 정보 없음",style=MaterialTheme.typography.bodySmall)
                }
                if(draft?.intent==ProductBrandSelectionIntent.CLEAR) Text("연결 해제 예정",style=MaterialTheme.typography.bodySmall)
                if(!configured) Text("PriceTrace 연결 설정 필요",style=MaterialTheme.typography.bodySmall)
                else {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({open=!open},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("pt_brand_search_${product.clientKey}_${field.wire}")) { Text(if(open) "검색 닫기" else "${field.label} 검색/변경") }
                        if(draft?.intent==ProductBrandSelectionIntent.SELECTED) TextButton({
                            onChange(ProductBrandResolutionDraft(product.clientKey,field,observed,ProductBrandSelectionIntent.CLEAR))
                        },enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("pt_brand_clear_${field.wire}")) { Text("연결 해제") }
                    }
                    if(open) {
                        OutlinedTextField(query,{query=it},label={Text("${field.label} 검색어")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("pt_brand_query_${field.wire}"))
                        when {
                            loading -> Text("브랜드 검색 중…")
                            error!=null -> { Text(error!!,color=MaterialTheme.colorScheme.error); TextButton({retry++}) { Text("다시 시도") } }
                            matches.isEmpty() -> Text("검색 결과가 없습니다. 관측값은 그대로 유지됩니다.")
                            else -> matches.forEach { match ->
                                OutlinedButton({onChange(match.select(product,field));open=false},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("pt_brand_result_${match.brandId}_${field.wire}")) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(match.canonicalName)
                                        if(match.parentBrandId!=null) Text("상위: ${match.parentCanonicalName ?: "공개 정보 없음"}",style=MaterialTheme.typography.bodySmall)
                                        if(match.matchKind=="alias") Text("일치한 별칭: ${match.matchedName}",style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
internal data class ProductBrandReviewActions(val drafts: List<ProductBrandResolutionDraft>, val configured: Boolean,
    val search: suspend (String) -> List<PriceTraceBrandMatch>, val change: (ProductBrandResolutionDraft) -> Unit, val metadata: String? = null)
internal val LocalProductBrandReview = staticCompositionLocalOf<ProductBrandReviewActions?> { null }