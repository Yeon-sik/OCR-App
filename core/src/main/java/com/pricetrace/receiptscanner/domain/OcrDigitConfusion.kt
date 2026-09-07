package com.pricetrace.receiptscanner.domain

import kotlin.math.abs

/** How a printed amount could have turned into the recognized amount. */
enum class MisreadKind {
    /** One digit replaced by a digit thermal-print OCR is known to confuse it with. */
    CONFUSABLE_DIGIT,

    /** Two neighbouring digits swapped. */
    TRANSPOSED_DIGITS,

    /** The recognizer lost one printed digit. */
    DROPPED_DIGIT,

    /** The recognizer added a digit that was not printed. */
    INSERTED_DIGIT,

    /** One digit differs, but not through a known confusion pair. */
    SUBSTITUTED_DIGIT,

    /** The two amounts are not related by a single recognition slip. */
    NONE,
}

/**
 * Shape-based digit confusions seen on faded thermal receipts. This is deliberately small: it only
 * ranks a candidate the caller already derived from the amount arithmetic, and never invents a value.
 */
object OcrDigitConfusion {
    private val confusablePairs: Set<Pair<Char, Char>> = setOf(
        '0' to '6',
        '0' to '8',
        '0' to '9',
        '1' to '4',
        '1' to '7',
        '2' to '3',
        '2' to '7',
        '3' to '8',
        '3' to '9',
        '4' to '9',
        '5' to '6',
        '5' to '8',
        '6' to '8',
        '8' to '9',
    ).flatMap { (left, right) -> listOf(left to right, right to left) }.toSet()

    fun classify(recognized: Long, candidate: Long): MisreadKind {
        if (recognized == candidate) return MisreadKind.NONE
        if (!sameSign(recognized, candidate)) return MisreadKind.NONE
        val left = abs(recognized).toString()
        val right = abs(candidate).toString()
        return when {
            left.length == right.length -> classifySameLength(left, right)
            left.length == right.length + 1 && isSingleDeletionOf(left, right) -> MisreadKind.INSERTED_DIGIT
            right.length == left.length + 1 && isSingleDeletionOf(right, left) -> MisreadKind.DROPPED_DIGIT
            else -> MisreadKind.NONE
        }
    }

    private fun classifySameLength(left: String, right: String): MisreadKind {
        val differing = left.indices.filter { index -> left[index] != right[index] }
        return when {
            differing.size == 1 -> {
                val index = differing.single()
                if ((left[index] to right[index]) in confusablePairs) {
                    MisreadKind.CONFUSABLE_DIGIT
                } else {
                    MisreadKind.SUBSTITUTED_DIGIT
                }
            }
            differing.size == 2 &&
                differing[1] == differing[0] + 1 &&
                left[differing[0]] == right[differing[1]] &&
                left[differing[1]] == right[differing[0]] -> MisreadKind.TRANSPOSED_DIGITS
            else -> MisreadKind.NONE
        }
    }

    /** True when removing exactly one character from [longer] yields [shorter]. */
    private fun isSingleDeletionOf(longer: String, shorter: String): Boolean {
        var longerIndex = 0
        var shorterIndex = 0
        var skipped = false
        while (longerIndex < longer.length && shorterIndex < shorter.length) {
            if (longer[longerIndex] == shorter[shorterIndex]) {
                longerIndex++
                shorterIndex++
            } else {
                if (skipped) return false
                skipped = true
                longerIndex++
            }
        }
        return shorterIndex == shorter.length
    }

    private fun sameSign(left: Long, right: Long): Boolean = (left < 0) == (right < 0)
}
