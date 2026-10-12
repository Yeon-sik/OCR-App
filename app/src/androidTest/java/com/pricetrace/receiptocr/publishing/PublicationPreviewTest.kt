package com.pricetrace.receiptocr.publishing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pricetrace.receiptocr.publishing.share.PublicationClipboardContent
import com.pricetrace.receiptocr.publishing.share.PublicationShareContent
import com.pricetrace.receiptocr.publishing.ui.NutritionPublicationEntry
import com.pricetrace.receiptocr.publishing.ui.PublicationPreviewScreen
import com.pricetrace.receiptocr.publishing.ui.PublicationSamples
import com.pricetrace.receiptscanner.nutrition.NutritionDraftStatus
import com.pricetrace.receiptscanner.publishing.generator.BlogDraftGenerator
import com.pricetrace.receiptscanner.publishing.model.PublicationPackage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublicationPreviewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun mockPreviewShowsFactsAndDispatchesExplicitCopyAndShareActions() {
        val copied = mutableListOf<PublicationClipboardContent>()
        val shared = mutableListOf<PublicationShareContent>()
        val publication = PublicationPackage(BlogDraftGenerator().generate(PublicationSamples.source()))
        composeRule.setContent {
            MaterialTheme { PublicationPreviewScreen(publication, {}, { copied += it }, { shared += it }) }
        }
        composeRule.onNodeWithTag("publication_preview").assertIsDisplayed()
        for ((tag, expected) in listOf(
            "publication_copy_title" to PublicationClipboardContent.TITLE,
            "publication_copy_body" to PublicationClipboardContent.BODY,
            "publication_copy_all" to PublicationClipboardContent.ALL_TEXT,
        )) {
            composeRule.onNodeWithTag("publication_preview").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(expected, copied.last()) }
        }
        composeRule.onNodeWithTag("publication_share_images").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("publication_share_text").performScrollTo().performClick()
        composeRule.onNodeWithTag("publication_share_all").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(PublicationShareContent.TEXT, PublicationShareContent.ALL), shared)
        }
    }

    @Test fun onlyVerifiedValidAndIdleDraftCanEnterPublication() {
        var draft by mutableStateOf(PublicationSamples.nutritionDraft().copy(status = NutritionDraftStatus.PARSED))
        var busy by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme { NutritionPublicationEntry(draft, emptyList(), ::File, isBusy = busy) }
        }
        composeRule.onNodeWithTag("open_blog_publication").assertIsNotEnabled()
        composeRule.runOnIdle { draft = PublicationSamples.nutritionDraft() }
        composeRule.onNodeWithTag("open_blog_publication").assertIsEnabled()
        composeRule.runOnIdle { busy = true }
        composeRule.onNodeWithTag("open_blog_publication").assertIsNotEnabled()
        composeRule.runOnIdle { busy = false; draft = draft.copy(basisAmount = null) }
        composeRule.onNodeWithTag("open_blog_publication").assertIsNotEnabled()
    }
}
