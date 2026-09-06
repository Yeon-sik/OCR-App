package com.pricetrace.receiptscanner.domain

data class ReceiptEvaluationSample(
    val boundaryDetectedAutomatically: Boolean,
    val boundaryCorrectedManually: Boolean,
    val expectedText: String,
    val recognizedText: String,
    val expectedMerchant: String?,
    val parsedMerchant: String?,
    val expectedDate: String?,
    val parsedDate: String?,
    val expectedGrandTotalMinor: Long?,
    val parsedGrandTotalMinor: Long?,
    val expectedLineCount: Int,
    val parsedLineCount: Int,
    val matchedLineCount: Int,
    val expectedSkuCount: Int,
    val matchedSkuCount: Int,
    val reconciliationSucceeded: Boolean,
    val processingTimeMillis: Long,
    val userModifiedFieldCount: Int,
) {
    init {
        require(expectedLineCount >= 0 && parsedLineCount >= 0 && matchedLineCount >= 0)
        require(matchedLineCount <= expectedLineCount && matchedLineCount <= parsedLineCount)
        require(expectedSkuCount >= 0 && matchedSkuCount in 0..expectedSkuCount)
        require(processingTimeMillis >= 0 && userModifiedFieldCount >= 0)
    }
}

data class ReceiptEvaluationSummary(
    val sampleCount: Int,
    val automaticBoundaryDetectionRate: Double,
    val manualBoundaryCorrectionRate: Double,
    val characterErrorRate: Double?,
    val merchantAccuracy: Double?,
    val dateAccuracy: Double?,
    val grandTotalAccuracy: Double?,
    val linePrecision: Double?,
    val lineRecall: Double?,
    val skuAccuracy: Double?,
    val reconciliationSuccessRate: Double,
    val averageProcessingTimeMillis: Double,
    val averageUserModifiedFieldCount: Double,
)

object ReceiptEvaluationCalculator {
    fun summarize(samples: List<ReceiptEvaluationSample>): ReceiptEvaluationSummary {
        require(samples.isNotEmpty()) { "At least one evaluation sample is required" }
        val expectedCharacters = samples.sumOf { it.expectedText.length.toLong() }
        val characterEdits = samples.sumOf {
            levenshteinDistance(it.expectedText, it.recognizedText).toLong()
        }
        val expectedLines = samples.sumOf { it.expectedLineCount.toLong() }
        val parsedLines = samples.sumOf { it.parsedLineCount.toLong() }
        val matchedLines = samples.sumOf { it.matchedLineCount.toLong() }
        val expectedSkus = samples.sumOf { it.expectedSkuCount.toLong() }
        val matchedSkus = samples.sumOf { it.matchedSkuCount.toLong() }
        return ReceiptEvaluationSummary(
            sampleCount = samples.size,
            automaticBoundaryDetectionRate = rate(samples.count { it.boundaryDetectedAutomatically }, samples.size),
            manualBoundaryCorrectionRate = rate(samples.count { it.boundaryCorrectedManually }, samples.size),
            characterErrorRate = ratio(characterEdits, expectedCharacters),
            merchantAccuracy = nullableExactAccuracy(samples) { expectedMerchant to parsedMerchant },
            dateAccuracy = nullableExactAccuracy(samples) { expectedDate to parsedDate },
            grandTotalAccuracy = nullableExactAccuracy(samples) { expectedGrandTotalMinor to parsedGrandTotalMinor },
            linePrecision = ratio(matchedLines, parsedLines),
            lineRecall = ratio(matchedLines, expectedLines),
            skuAccuracy = ratio(matchedSkus, expectedSkus),
            reconciliationSuccessRate = rate(samples.count { it.reconciliationSucceeded }, samples.size),
            averageProcessingTimeMillis = samples.map { it.processingTimeMillis }.average(),
            averageUserModifiedFieldCount = samples.map { it.userModifiedFieldCount }.average(),
        )
    }

    fun levenshteinDistance(expected: String, actual: String): Int {
        if (expected.isEmpty()) return actual.length
        if (actual.isEmpty()) return expected.length
        var previous = IntArray(actual.length + 1) { it }
        expected.forEachIndexed { expectedIndex, expectedCharacter ->
            val current = IntArray(actual.length + 1)
            current[0] = expectedIndex + 1
            actual.forEachIndexed { actualIndex, actualCharacter ->
                val substitution = previous[actualIndex] + if (expectedCharacter == actualCharacter) 0 else 1
                current[actualIndex + 1] = minOf(
                    current[actualIndex] + 1,
                    previous[actualIndex + 1] + 1,
                    substitution,
                )
            }
            previous = current
        }
        return previous.last()
    }

    private fun <T> nullableExactAccuracy(
        samples: List<ReceiptEvaluationSample>,
        values: ReceiptEvaluationSample.() -> Pair<T?, T?>,
    ): Double? {
        val comparable = samples.map(values).filter { (expected, _) -> expected != null }
        if (comparable.isEmpty()) return null
        return rate(comparable.count { (expected, actual) -> expected == actual }, comparable.size)
    }

    private fun rate(numerator: Int, denominator: Int): Double = numerator.toDouble() / denominator
    private fun ratio(numerator: Long, denominator: Long): Double? =
        denominator.takeIf { it > 0 }?.let { numerator.toDouble() / it }
}
