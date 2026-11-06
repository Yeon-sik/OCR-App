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

@OptIn(ExperimentalTestApi::class)
class InformationArchiveUiTest {
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
