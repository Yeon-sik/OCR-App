package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.requiredSize
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

class PurchaseIdentityReviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ambiguousChoicesRequireClickAndSearchAndDeferUseTheSharedCommand() {
        val choices = listOf(PurchaseIdentityChoice("00000000-0000-4000-8000-000000000001","한성대학교 학식당","본관"),
            PurchaseIdentityChoice("00000000-0000-4000-8000-000000000002","한성대학교 학식당","창의관"))
        val command = PurchaseIdentityCommand("purchase-1","line-1",PurchaseIdentityEntity.RESTAURANT,PurchaseIdentityAction.SEARCH,"한성대 학식당")
        val card = PurchaseIdentityReviewCard(command,"한성대 학식당",PurchaseIdentityReviewState("purchase-1","line-1",command.entity,command.query,"needs_selection",choices),null,null)
        val actions = mutableListOf<PurchaseIdentityCommand>()
        compose.setContent { CollectorTheme { Surface(Modifier.requiredSize(420.dp,660.dp)) { PurchaseIdentityCard(card,true,actions::add) } } }
        compose.onNodeWithText("관측값: 한성대 학식당").assertIsDisplayed()
        compose.onNodeWithText("새 식당 등록").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("기존 식당에 연결 · ${choices[1].label}").performClick()
        assertEquals(choices[1].id,actions.single().selectedId)
        assertEquals(PurchaseIdentityAction.SELECT,actions.single().action)
        compose.onNodeWithText("다시 검색").performClick()
        assertEquals(PurchaseIdentityAction.SEARCH,actions.last().action)
        compose.onNodeWithText("보류").performClick()
        assertEquals(PurchaseIdentityAction.DEFER,actions.last().action)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width,pixels.height,BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for(x in 0 until pixels.width) image.setRGB(x,y,pixels[x,y].toArgb())
        File("build/ui-evidence").mkdirs()
        ImageIO.write(image,"png",File("build/ui-evidence/purchase-identity-review.png"))
    }
}
