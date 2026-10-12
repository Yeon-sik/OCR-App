package com.pricetrace.receiptscanner.publishing

import com.pricetrace.receiptscanner.publishing.adapter.NutritionPublicationAdapter
import com.pricetrace.receiptscanner.publishing.adapter.PublicationAdaptation
import com.pricetrace.receiptscanner.publishing.generator.BlogDraftGenerator
import com.pricetrace.receiptscanner.publishing.generator.PublicationText
import org.junit.Assert.*
import org.junit.Test

class BlogDraftGeneratorTest {
    private val source = (NutritionPublicationAdapter.adapt(
        NutritionPublicationAdapterTest.verifiedDraft(),
    ) as PublicationAdaptation.Ready).source
    private val generator = BlogDraftGenerator()

    @Test fun titleAndBodyUseOnlyProvidedFacts() {
        val draft = generator.generate(source)
        assertEquals("오리온 닥터유 단백질바 50 g 영양정보 | 칼로리·단백질·당류·나트륨", draft.title)
        assertTrue(draft.body.contains("열량: 249 kcal"))
        assertTrue(draft.body.contains("나트륨: 180 mg"))
        assertTrue(draft.body.contains("확인일: 2026-10-12"))
        assertTrue(draft.body.contains("제품 포장 영양성분표"))
        assertFalse(draft.body.contains("식이섬유"))
        assertTrue(draft.evidenceImages.isEmpty())
    }

    @Test fun realZeroIsPrintedAndUnknownNutrientsAreOmitted() {
        val draft = generator.generate(source)
        assertTrue(draft.body.contains("당류: 0 g"))
        assertFalse(draft.body.contains("첨가당:"))
        assertFalse(draft.body.contains("콜레스테롤:"))
    }

    @Test fun missingBrandAndBasisDoNotProduceNullOrInventedServing() {
        val draft = generator.generate(source.copy(brand = null, basisAmount = null))
        assertTrue(draft.title.startsWith("닥터유 단백질바"))
        assertFalse(draft.body.contains("기준량:"))
        assertFalse(draft.fullText.contains("null"))
    }

    @Test fun existingBrandPrefixIsNotRepeated() {
        assertFalse(generator.generate(source.copy(
            productName = "오리온 닥터유 단백질바",
        )).title.startsWith("오리온 오리온"))
    }

    @Test fun longUnicodeProductNameIsKeptInBodyAndTitleIsAbbreviatedSafely() {
        val product = "한글 🥗 ".repeat(80)
        val draft = generator.generate(source.copy(productName = product))
        assertTrue(draft.title.contains("…"))
        assertTrue(draft.title.length < 160)
        assertTrue(draft.body.contains(product.trim()))
        assertFalse(draft.title.contains("\uFFFD"))
    }

    @Test fun specialCharactersRemainPlainTextAndWhitespaceDoesNotBreakTitle() {
        val draft = generator.generate(source.copy(productName = "단백질 & <견과> \"바\"\n한정판"))
        assertTrue(draft.body.contains("단백질 & <견과> \"바\" 한정판"))
        assertFalse(draft.title.contains("\n"))
    }

    @Test fun decimalValuesAndMixedUnitsAreLocaleIndependent() {
        val draft = generator.generate(source.copy(
            nutrients = source.nutrients.map {
                it.copy(value = if (it.unit == "mg") 123.45 else 1.25)
            },
        ))
        assertTrue(draft.body.contains("단백질: 1.25 g"))
        assertTrue(draft.body.contains("나트륨: 123.45 mg"))
        assertEquals("0", PublicationText.number(-0.0))
    }

    @Test fun invalidOrMissingDateIsNotReplacedWithToday() {
        assertFalse(generator.generate(source.copy(confirmedAt = null)).body.contains("확인일:"))
        assertFalse(generator.generate(source.copy(confirmedAt = "unknown")).body.contains("확인일:"))
    }

    @Test fun externalSourceIsAttributedAsExternalRatherThanProductPackaging() {
        val draft = generator.generate(source.copy(
            sourceType = "external_reference",
            sourceReference = "https://example.org/nutrition",
        ))
        assertTrue(draft.body.contains("외부 영양정보 자료"))
        assertTrue(draft.body.contains("https://example.org/nutrition"))
        assertFalse(draft.body.contains("제품 포장"))
    }

    @Test fun noHealthClaimsOrEstimatedValuesAreAdded() {
        val draft = generator.generate(source.copy(nutrients = emptyList()))
        assertFalse(draft.body.contains("열량:"))
        assertFalse(draft.title.contains("칼로리"))
        assertFalse(draft.body.contains("다이어트"))
    }
}
