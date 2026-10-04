package com.yeonsik.ingestion.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.pricetrace.receiptscanner.review.CanonicalEditableField
import com.pricetrace.receiptscanner.review.CanonicalFieldType
import org.junit.Rule
import org.junit.Test

class DesktopWorkspaceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsavedFieldSurvivesResizeAndSourceRoundTrip() {
        var width by mutableStateOf(1440)
        var pane by mutableStateOf(CompactCollectorPane.REVIEW)
        var imports = 0
        compose.setContent {
            CollectorTheme {
                Box(Modifier.requiredSize(width.dp, 900.dp)) {
                    AppShell(pane, { pane = it }, inbox = { Text("작업 목록 내용") }, review = {
                        AccessibleField(CanonicalEditableField("receipt.merchant.name", "판매처", CanonicalFieldType.TEXT, false, value = "원래 값"),
                            false, null, null, false, {})
                    }, evidence = { Text("원본 내용") }, onOpen = { imports++ })
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("미적용 편집값")
        listOf(1040, 800, 1360, 1600).forEach { next ->
            compose.runOnIdle { width = next }
            compose.onNode(hasSetTextAction()).assertTextContains("미적용 편집값")
        }
        compose.runOnIdle { width = 800; pane = CompactCollectorPane.EVIDENCE }
        compose.onNodeWithText("원본 내용").assertExists()
        compose.runOnIdle { pane = CompactCollectorPane.REVIEW }
        compose.onNode(hasSetTextAction()).assertTextContains("미적용 편집값")
        compose.onNode(hasSetTextAction()).performClick().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.O); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, imports) }
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.F6) }
        compose.onNodeWithText("원본 내용").assertExists()
        compose.onRoot().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.F6); keyUp(Key.ShiftLeft) }
        compose.onNode(hasSetTextAction()).assertTextContains("미적용 편집값")
    }
}
