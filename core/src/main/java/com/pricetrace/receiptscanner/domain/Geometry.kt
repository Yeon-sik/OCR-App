package com.pricetrace.receiptscanner.domain

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right must be greater than or equal to left" }
        require(bottom >= top) { "bottom must be greater than or equal to top" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
