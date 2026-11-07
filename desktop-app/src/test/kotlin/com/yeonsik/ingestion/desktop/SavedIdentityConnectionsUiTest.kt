package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.ingestion.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class SavedIdentityConnectionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tooManySavedRecordsShowAnExplanationAndDisableLoading() {
        val metadata = kotlinx.serialization.json.buildJsonObject {
            put("sources", kotlinx.serialization.json.JsonArray((1..501).map { index ->
                kotlinx.serialization.json.buildJsonObject {
                    put("purchaseSourceId", kotlinx.serialization.json.JsonPrimitive("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"))
                }
            }))
        }.toString()
        val session = IngestionSession("large", "doc", "canonical", "fingerprint", IngestionReviewStatus.READY, "now", "now",
            listOf(ProjectionState(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, updatedAt = "now", metadataJson = metadata)))
        compose.setContent { CollectorTheme { SavedIdentityConnectionsPanel(session, true) { error("Over-limit requests must stay disabled") } } }
        compose.onNodeWithText("브랜드·가게·식당 연결").performClick()
        compose.onNodeWithText("연결 대상이 500개를 넘었습니다. 수집 기록을 나누어 확인하세요.").assertIsDisplayed()
        compose.onNodeWithText("이 수집 기록의 연결 정보 불러오기").assertIsNotEnabled()
    }

    @Test fun disabledPanelCannotLoadWhileAnotherOperationIsRunning() {
        val session = IngestionSession("busy", "doc", "canonical", "fingerprint", IngestionReviewStatus.READY, "now", "now",
            listOf(ProjectionState(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, updatedAt = "now",
                metadataJson = """{"purchaseSourceId":"00000000-0000-4000-8000-000000000001"}""")))
        compose.setContent { CollectorTheme { SavedIdentityConnectionsPanel(session, false) { error("Busy requests must stay disabled") } } }
        compose.onNodeWithText("브랜드·가게·식당 연결").performClick()
        compose.onNodeWithText("이 수집 기록의 연결 정보 불러오기").assertIsNotEnabled()
    }

    @Test fun choosingCandidateRequiresSeparateConfirmationAndChangingQueryClearsIt() {
        val source = "00000000-0000-4000-8000-000000000001"
        val target = "00000000-0000-4000-8000-000000000002"
        val metadata = """{"purchaseSourceId":"$source","identityConnections":{"inventory":{"schemaVersion":"identity-connections.v1","ownerId":"00000000-0000-4000-8000-000000000011","truncated":false,"sources":[{"entity":"restaurant","sourceId":"$source","name":"한성대학교 학식당","branchName":null,"context":null,"selected":null}]},"search":{"schemaVersion":"identity-search.v1","entity":"restaurant","sourceId":"$source","query":"한성대학교 학식당","choices":[{"id":"$target","name":"한성대학교 학생식당","branchName":null,"context":null,"similarity":0.8889,"matchKind":"similar"}]}}}"""
        val session = IngestionSession("test","doc","canonical","fingerprint",IngestionReviewStatus.READY,"now","now",
            listOf(ProjectionState(IngestionProjection.PRICETRACE_PRICE_OBSERVATION,updatedAt="now",metadataJson=metadata)))
        val commands = mutableListOf<SavedIdentityCommand>()
        compose.setContent { CollectorTheme { Surface(Modifier.requiredSize(420.dp,780.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                SavedIdentityConnectionsPanel(session,true,commands::add)
            }
        } } }
        compose.onNodeWithText("브랜드·가게·식당 연결").performClick()
        compose.onNodeWithText("한성대학교 학생식당 · 이름 유사도 89%").performScrollTo().performClick()
        assertTrue(commands.isEmpty())
        compose.onNodeWithText("같은 항목으로 확인하고 연결").performScrollTo().assertIsDisplayed()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_ARGB)
        for(y in 0 until pixels.height) for(x in 0 until pixels.width) image.setRGB(x,y,pixels[x,y].toArgb())
        File("build/ui-evidence").mkdirs()
        ImageIO.write(image,"png",File("build/ui-evidence/saved-identity-connections.png"))
        compose.onNodeWithText("같은 항목으로 확인하고 연결").performClick()
        assertEquals(SavedIdentityAction.CONNECT,commands.single().action)
        assertEquals(target,commands.single().targetId)
        compose.onNodeWithText("기존 식당 검색").performScrollTo().performTextReplacement("한성대학교")
        compose.onNodeWithText("같은 항목으로 확인하고 연결").assertDoesNotExist()
        compose.onNodeWithText("한성대학교 학생식당 · 이름 유사도 89%").assertDoesNotExist()
    }
}
