package com.pricetrace.receiptocr

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pricetrace.receiptscanner.ingestion.IngestionNutrition
import com.pricetrace.receiptscanner.ingestion.IngestionProjection
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrEnvelope
import com.pricetrace.receiptscanner.ingestion.YeonsikOcrV2Json
import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.LinkedProductReview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LinkedProductReviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameNameAndBrandHaveOneEditableControlAndNoNutritionDuplicate() {
        val review = render(fixture())
        compose.onAllNodes(hasSetTextAction() and hasText("Test cereal")).assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction() and hasText("Test brand")).assertCountEquals(1)
        compose.onNodeWithTag("product_facts_match_product-1").assertExists()
        capture("product-information")
        compose.runOnIdle { review.screen(AppScreen.NUTRITION_REVIEW) }
        compose.onNodeWithTag("nutrition_product_context").assertIsDisplayed()
        capture("nutrition-context")
        compose.onNodeWithTag("nutrition_product_name").assertDoesNotExist()
        compose.onNodeWithTag("nutrition_brand").assertDoesNotExist()
        compose.onNodeWithTag("nutrition_review").performScrollToNode(hasTestTag("nutrition_basis_amount"))
        compose.onNodeWithTag("nutrition_basis_amount").assertIsDisplayed()
    }

    @Test fun differentNameRequiresExplicitChoiceAndOneUndoRestoresConflict() {
        val original = fixture()
        val label = label(original)
        val review = render(original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(productName = "Other cereal")))))
        compose.onNodeWithTag("product_fact_product-1_NAME_resolve").assertIsNotEnabled()
        capture("product-conflict")
        scroll("confirm_product_candidates")
        compose.onNodeWithTag("confirm_product_candidates").assertIsNotEnabled()
        scroll("product_fact_product-1_NAME_source_1")
        compose.onNodeWithTag("product_fact_product-1_NAME_source_1").performClick()
        compose.onNodeWithTag("product_fact_product-1_NAME_resolve").performClick()
        compose.runOnIdle {
            assertEquals("Other cereal", review.controller.state.value.envelope.productCandidates.single().productName)
            assertFalse(LinkedProductReview.hasConflicts(review.controller.state.value.envelope))
            assertEquals(1, review.controller.state.value.edits.size)
        }
        scroll("product_review_undo")
        compose.onNodeWithTag("product_review_undo").performClick()
        compose.runOnIdle { assertTrue(LinkedProductReview.hasConflicts(review.controller.state.value.envelope)) }
        compose.onNodeWithTag("product_review_redo").performClick()
        compose.runOnIdle { assertFalse(LinkedProductReview.hasConflicts(review.controller.state.value.envelope)) }
    }

    @Test fun conflictingBrandShowsBothSourcesAndNeverChangesOnOpening() {
        val original = fixture()
        val label = label(original)
        val review = render(original.copy(nutrition = listOf(label.copy(draft = label.draft.copy(brand = "Different brand")))))
        scroll("product_fact_product-1_BRAND_source_0")
        compose.onNodeWithTag("product_fact_product-1_BRAND_source_0").assertTextContains("Test brand", substring = true)
        scroll("product_fact_product-1_BRAND_source_1")
        compose.onNodeWithTag("product_fact_product-1_BRAND_source_1").assertTextContains("Different brand", substring = true)
        compose.runOnIdle {
            assertEquals("Test brand", review.controller.state.value.envelope.productCandidates.single().brand)
            assertEquals("Different brand", label(review.controller.state.value.envelope).draft.brand)
            assertTrue(review.controller.state.value.edits.isEmpty())
        }
        compose.onNodeWithTag("product_fact_product-1_BRAND_manual").performClick()
        compose.onNodeWithTag("product_fact_product-1_BRAND_input").performTextReplacement("Chosen brand")
        compose.onNodeWithTag("product_fact_product-1_BRAND_input").assertTextContains("Chosen brand")
        // IME insets can still move the button after text replacement in a full-suite run.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("product_fact_product-1_BRAND_resolve").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Chosen brand", review.controller.state.value.envelope.productCandidates.single().brand)
            assertEquals("Chosen brand", label(review.controller.state.value.envelope).draft.brand)
            assertEquals(2, review.controller.state.value.edits.size)
        }
    }

    @Test fun unlinkedLabelRetainsIndependentNameAndBrandInputs() {
        val original = fixture()
        render(original.copy(nutrition = listOf(label(original).copy(productClientKey = null))), AppScreen.NUTRITION_REVIEW)
        compose.onNodeWithTag("nutrition_review").performScrollToNode(hasTestTag("nutrition_product_name"))
        compose.onNodeWithTag("nutrition_product_name").assertExists().assert(hasSetTextAction())
        compose.onNodeWithTag("nutrition_review").performScrollToNode(hasTestTag("nutrition_brand"))
        compose.onNodeWithTag("nutrition_brand").assertExists().assert(hasSetTextAction())
        compose.onNodeWithTag("nutrition_product_context").assertDoesNotExist()
    }

    @Test fun multipleProductsAndSameStringsKeepTheirOwnMutationPaths() {
        val original = fixture()
        val product = original.productCandidates.single()
        val label = label(original)
        val review = render(original.copy(productCandidates = listOf(product, product.copy(clientKey = "product-2")),
            nutrition = listOf(label, label.copy(clientKey = "label-2", productClientKey = "product-2",
                draft = label.draft.copy(documentId = "draft-2")))))
        scroll("product_fact_product-2_NAME_input")
        compose.onNodeWithTag("product_fact_product-2_NAME_input").performTextReplacement("Second product")
        compose.onNodeWithTag("product_fact_product-2_NAME_apply").performScrollTo().performClick()
        compose.runOnIdle {
            val envelope = review.controller.state.value.envelope
            assertEquals(listOf("Test cereal", "Second product"), envelope.productCandidates.map { it.productName })
            assertEquals(listOf("Test cereal", "Second product"), envelope.nutrition.map { (it as IngestionNutrition.ProductLabel).draft.productName })
        }
    }

    @Test fun unifiedBrandEditPropagatesThroughAuditAndUndoRedo() {
        val review = render(fixture())
        scroll("product_fact_product-1_BRAND_input")
        compose.onNodeWithTag("product_fact_product-1_BRAND_input").performTextReplacement("Edited brand")
        compose.onNodeWithTag("product_fact_product-1_BRAND_apply").performClick()
        compose.runOnIdle {
            assertEquals("Edited brand", review.controller.state.value.envelope.productCandidates.single().brand)
            assertEquals("Edited brand", label(review.controller.state.value.envelope).draft.brand)
            assertEquals(2, review.controller.state.value.edits.size)
        }
        scroll("product_review_undo")
        compose.onNodeWithTag("product_review_undo").performClick()
        compose.runOnIdle { assertEquals("Test brand", label(review.controller.state.value.envelope).draft.brand) }
        compose.onNodeWithTag("product_review_redo").performClick()
        compose.runOnIdle { assertEquals("Edited brand", label(review.controller.state.value.envelope).draft.brand) }
    }

    @Test fun oneSidedBrandShowsMissingSourceWithoutAConflictOrCopy() {
        val original = fixture()
        val review = render(original.copy(nutrition = listOf(label(original).let { it.copy(draft = it.draft.copy(brand = null)) })))
        scroll("product_fact_product-1_BRAND_input")
        compose.onNodeWithTag("product_fact_product-1_BRAND_source_0").assertDoesNotExist()
        compose.onNodeWithTag("product_facts_match_product-1").assertDoesNotExist()
        compose.runOnIdle { assertNull(label(review.controller.state.value.envelope).draft.brand) }
    }

    @Test fun unsavedProductEditBlocksConfirmationUntilApplied() {
        render(fixture())
        compose.onNodeWithTag("product_fact_product-1_NAME_input").performTextReplacement("Pending name")
        scroll("confirm_product_candidates")
        compose.onNodeWithTag("confirm_product_candidates").assertIsNotEnabled()
        scroll("product_fact_product-1_NAME_apply")
        compose.onNodeWithTag("product_fact_product-1_NAME_apply").performClick()
        scroll("confirm_product_candidates")
        compose.onNodeWithTag("confirm_product_candidates").assertIsEnabled()
    }

    @Test fun canonicalTypedReviewAlsoExposesOneLinkedNameAndBrand() {
        val envelope = fixture()
        compose.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(uiState = ReceiptAppUiState(screen = AppScreen.CANONICAL_JSON_VALIDATOR),
                    canonicalJsonValidatorState = AndroidCanonicalJsonValidatorState(envelope = envelope,
                        plan = com.pricetrace.receiptscanner.ingestion.CanonicalProjectionPlanner.plan(envelope)))
            }
        }
        compose.onNodeWithTag("canonical_json_validator").performScrollToNode(hasTestTag("product_fact_product-1_NAME_input"))
        compose.onAllNodes(hasSetTextAction() and hasText("Test cereal")).assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction() and hasText("Test brand")).assertCountEquals(1)
        compose.onNodeWithTag("canonical_review_field_nutrition_label-1_.product_name").assertDoesNotExist()
        compose.onNodeWithTag("canonical_review_field_nutrition_label-1_.brand").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scroll(tag: String) = compose.onNodeWithTag("product_candidate_review").performScrollToNode(hasTestTag(tag))

    private class Review(val controller: CanonicalReviewController, initialScreen: AppScreen) {
        var state by mutableStateOf(ReceiptAppUiState())
        init { refresh(initialScreen) }
        fun screen(screen: AppScreen) { refresh(screen) }
        fun edit(mutation: (CanonicalReviewController) -> Boolean) { assertTrue(mutation(controller)); refresh(state.screen) }
        private fun refresh(screen: AppScreen) {
            val reviewed = controller.state.value
            val envelope = reviewed.envelope
            state = state.copy(screen = screen, isCanonicalIngestion = true, currentDocumentId = "review-ui",
                productCandidates = envelope.productCandidates, canonicalNutritionArtifacts = envelope.nutrition,
                canonicalNutritionCount = envelope.nutrition.size,
                nutritionDraft = (envelope.nutrition.first() as IngestionNutrition.ProductLabel).draft,
                productReviewCanUndo = reviewed.canUndo, productReviewCanRedo = reviewed.canRedo,
                productReviewFieldErrors = reviewed.fieldErrors)
        }
    }

    private fun render(envelope: YeonsikOcrEnvelope, screen: AppScreen = AppScreen.PRODUCT_CANDIDATE_REVIEW): Review {
        val review = Review(CanonicalReviewController(envelope), screen)
        compose.setContent {
            ReceiptOcrTheme {
                ReceiptOcrContent(uiState = review.state, productCandidates = review.state.productCandidates,
                    canonicalNutritionCount = review.state.canonicalNutritionCount,
                    onProductReviewFieldChanged = { path, value -> review.edit { it.updateField(path, value) } },
                    onResolveProductFact = { key, field, value -> review.edit { it.resolveLinkedProductFact(key, field, value) } },
                    onProductReviewUndo = { review.edit { it.undo() } }, onProductReviewRedo = { review.edit { it.redo() } })
            }
        }
        return review
    }

    private fun fixture(): YeonsikOcrEnvelope {
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("linked-product-review.json").bufferedReader().use { it.readText() }
        val envelope = YeonsikOcrV2Json.decode(json, "review-ui")
        return envelope.copy(schemaVersion = com.pricetrace.receiptscanner.ingestion.YEONSIK_OCR_V3_SCHEMA, nutrition = listOf(label(envelope).copy(clientKey = "label-1", productClientKey = "product-1")),
            consumption = emptyList(), targets = envelope.targets - IngestionProjection.FITNESS_MEAL)
    }
    private fun label(envelope: YeonsikOcrEnvelope) = envelope.nutrition.first() as IngestionNutrition.ProductLabel
}
