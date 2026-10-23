package com.pricetrace.receiptocr
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductBrandResolutionUiTest {
    @get:Rule val compose=createComposeRule()
    private val id="00000000-0000-4000-8000-000000000001"
    private val other="00000000-0000-4000-8000-000000000002"
    private fun product()=ProductCandidate("p","Observed product",brand="Observed A",subBrand="Observed sub",evidence=listOf(ProductCandidateEvidence(sourceAttachmentIds=listOf("photo"),observedValue="Observed product")))
    private fun match(key:String=id,name:String="Canonical A")=PriceTraceBrandMatch(key,name,other,"Parent", "Observed alias","alias",null,null)
    @Test fun explicitAliasSelectionChangeClearAndRawEditKeepObservedFacts() {
        var p by mutableStateOf(product()); var drafts by mutableStateOf(emptyList<ProductBrandResolutionDraft>())
        val changes=mutableListOf<ProductBrandResolutionDraft>(); val queries=mutableListOf<String>(); val changeChoice=java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ProductBrandResolutionEditor(p,drafts,true,false,{q->synchronized(queries){queries+=q};listOf(if(changeChoice.get()) match(other,"Changed") else match())},{d->changes+=d;drafts=ProductBrandResolutionJson.decode(ProductBrandResolutionJson.encode(listOf(d)))}) } } }
        compose.onNodeWithText("브랜드 관측값: Observed A").assertExists()
        compose.onNodeWithText("서브브랜드 관측값: Observed sub").assertExists()
        compose.onNodeWithTag("pt_brand_search_p_brand").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pt_brand_result_${id}_brand").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Observed A",synchronized(queries){queries.first()}); assertTrue(changes.isEmpty())
        compose.onNodeWithText("일치한 별칭: Observed alias").assertExists()
        capture("pt-brand-search")
        compose.onNodeWithTag("pt_brand_result_${id}_brand").performClick()
        assertEquals(id,changes.single().brandId)
        compose.onNodeWithText("Parent > Canonical A").assertExists()
        capture("pt-brand-selected")
        changeChoice.set(true)
        compose.onNodeWithTag("pt_brand_search_p_brand").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pt_brand_result_${other}_brand").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pt_brand_result_${other}_brand").performClick()
        assertEquals(other,changes.last().brandId)
        compose.onNodeWithTag("pt_brand_clear_brand").performClick()
        assertEquals(ProductBrandSelectionIntent.CLEAR,changes.last().intent)
        assertEquals("Observed A",p.brand)
        compose.runOnIdle { drafts=listOf(match().select(p,ProductBrandFactField.BRAND));p=p.copy(brand="Edited raw brand") }
        compose.onNodeWithText("브랜드 관측값: Edited raw brand").assertExists()
        compose.onNodeWithText("Parent > Canonical A").assertDoesNotExist()
    }
    @Test fun cancelledOldSearchCannotReplaceCurrentAndDoesNotAutoSelect() {
        val gate=CompletableDeferred<List<PriceTraceBrandMatch>>(); val queried=java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.setContent { MaterialTheme { ProductBrandResolutionEditor(product(),emptyList(),true,false,{q->queried+=q;if(q=="Observed A") gate.await() else listOf(match(other,"Latest"))},{error("automatic selection")}) } }
        compose.onNodeWithTag("pt_brand_search_p_brand").performClick()
        compose.waitUntil(5000) { "Observed A" in queried }
        compose.onNodeWithText("브랜드 검색 중…").assertExists()
        compose.onNodeWithTag("pt_brand_query_brand").performTextReplacement("latest")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pt_brand_result_${other}_brand").fetchSemanticsNodes().isNotEmpty() }
        gate.complete(listOf(match()))
        compose.waitForIdle()
        compose.onNodeWithTag("pt_brand_result_${id}_brand").assertDoesNotExist()
        compose.onNodeWithTag("pt_brand_result_${other}_brand").assertExists()
    }
    @Test fun failureRetryEmptyAndNoConnectionPreserveOrdinaryReview() {
        var attempts=0
        compose.setContent { MaterialTheme { ProductBrandResolutionEditor(product(),emptyList(),true,false,{if(attempts++==0) error("offline") else emptyList()},{}) } }
        compose.onNodeWithTag("pt_brand_search_p_brand").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("다시 시도").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("검색 결과가 없습니다. 관측값은 그대로 유지됩니다.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2,attempts)
    }
    @Test fun missingConnectionDoesNotHideRawFacts() {
        compose.setContent { MaterialTheme { ProductBrandResolutionEditor(product(),emptyList(),false,false,{error("must not search")},{}) } }
        compose.onAllNodesWithText("PriceTrace 연결 설정 필요").assertCountEquals(2)
        compose.onNodeWithText("브랜드 관측값: Observed A").assertExists()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
