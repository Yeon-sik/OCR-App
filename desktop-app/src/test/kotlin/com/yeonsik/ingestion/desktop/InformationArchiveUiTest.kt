package com.yeonsik.ingestion.desktop

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.pricetrace.receiptocr.fitness.*
import com.pricetrace.receiptscanner.information.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalTestApi::class)
class InformationArchiveUiTest {
    @Test fun `direct delivery requires account approval and persists inbox receipt`() = runDesktopComposeUiTest(width = 800, height = 700) {
        val store = DesktopInformationStore(Files.createTempDirectory("information-delivery-ui"))
        val record = InformationRecordCodec.decode(newInformationDraft(InformationOwner.FITNESS, null))
        store.save(record); store.markReviewed(record.id)
        val userId = "88888888-8888-4888-8888-888888888888"
        val requests = java.util.concurrent.CopyOnWriteArrayList<NutritionHttpRequest>()
        val transport = NutritionHttpTransport { request ->
            requests += request
            NutritionHttpResponse(200, when {
                request.url.endsWith("/auth/v1/user") -> "{\"id\":\"$userId\",\"email\":\"fixture@example.test\"}"
                request.method == "POST" -> "{\"record_id\":\"${record.id}\",\"duplicate\":false,\"content_fingerprint\":\"${record.fingerprint}\"}"
                else -> buildJsonArray { add(buildJsonObject { put("payload_text", record.canonical) }) }.toString()
            })
        }
        val config = DesktopRuntimeConfig.load(mapOf("NUTRITION_SUPABASE_URL" to "https://fixture.supabase.co",
            "NUTRITION_SUPABASE_PUBLISHABLE_KEY" to "sb_publishable_fixture_123456789", "NUTRITION_USER_ID" to userId,
            "NUTRITION_EMAIL" to "fixture@example.test", "NUTRITION_ACCESS_TOKEN" to "fixture-access", "NUTRITION_REFRESH_TOKEN" to "fixture-refresh"),
            store.directory.resolve("missing.env"))
        setContent { CollectorTheme { InformationArchiveScreen(store, delivery = DesktopInformationDelivery(config, transport)) } }
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("기록 1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("기록 1").performClick()
        onNodeWithText("Fitness로 직접 전송").performScrollTo().performClick()
        onNodeWithText("수신 계정 확인").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("확인한 계정으로 전송").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("확인한 계정으로 전송").performScrollTo().assertIsNotEnabled()
        assertFalse(requests.any { it.method == "POST" })
        capture("information-800-delivery-review")
        onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        onNodeWithText("확인한 계정으로 전송").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("검토함 도착을 확인했습니다. Fitness에서 내용을 검토하고 실제 기록 반영을 승인하세요.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(InformationDeliveryState.DELIVERED, InformationDeliveryJournal(store.directory.resolve("deliveries").toFile()).attempts(record.id).single().state)
        assertEquals(record.canonical, store.snapshot().records.single().record.canonical)
        capture("information-800-delivery-arrived")
    }
    @Test fun `source capture and owner reviewed persistence are reachable at compact size`() = runDesktopComposeUiTest(width = 800, height = 700) {
        val store = DesktopInformationStore(Files.createTempDirectory("information-ui"))
        setContent { CollectorTheme { InformationArchiveScreen(store) } }
        onNodeWithText("텍스트 원문").performTextInput("활동 기록: 산책 7.5분. 아직 Fitness에 보내지 않았음.")
        onNodeWithText("텍스트 보관").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("이 원문으로 구조화").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("이 원문으로 구조화").performScrollTo().performClick()
        onNodeWithText("새 기록 양식").performScrollTo().performClick()
        onNodeWithText("검토하고 저장").performScrollTo().assertIsNotEnabled()
        capture("information-800-draft")
        onAllNodes(isToggleable()).onLast().performScrollTo().performClick()
        onNodeWithText("검토하고 저장").performScrollTo().assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5000) { onAllNodesWithText("기록 1").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.snapshot().records.single().reviewed)
        onNodeWithText("기록 1").performClick()
        onNodeWithText("Fitness용 내보내기").performScrollTo().assertIsEnabled()
        capture("information-800-records")
    }

    private fun ComposeUiTest.capture(name: String) {
        waitForIdle()
        val bitmap = onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, bitmap[x, y].toArgb())
        val directory = java.io.File("build/reports/information-ui").apply { mkdirs() }
        ImageIO.write(image, "png", directory.resolve("$name.png"))
    }
}
