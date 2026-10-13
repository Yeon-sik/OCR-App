package com.pricetrace.receiptocr.publishing.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.pricetrace.receiptscanner.publishing.generator.PublicationText
import com.pricetrace.receiptscanner.publishing.model.PublicationSource
import kotlin.math.max

sealed interface NutritionTableRenderResult {
    data class Ready(val bitmap: Bitmap) : NutritionTableRenderResult
    data class Failed(val message: String) : NutritionTableRenderResult
}

/** Mobile blog image, with layout independent of Compose and the app theme. */
class NutritionTableRenderer(
    private val createBitmap: (Int, Int) -> Bitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    },
) {
    fun render(source: PublicationSource): NutritionTableRenderResult {
        var bitmap: Bitmap? = null
        return try {
            val heading = text("영양정보", 38f, ACCENT, bold = true)
            val name = text(PublicationText.displayName(source), 48f, INK, bold = true, maxLines = 4)
            val rows = buildList {
                PublicationText.basis(source)?.let { add(Triple("기준량", it, 0)) }
                PublicationText.nutrientRows(source).forEach { row ->
                    val nutrient = row.nutrient
                    add(Triple(row.label, PublicationText.number(nutrient.value) + " " + nutrient.unit, row.depth))
                }
            }.map { (label, value, depth) ->
                val indent = depth * 32
                val left = text(label, 34f, INK, width = LABEL_WIDTH - indent)
                val right = text(value, 38f, INK, bold = true, width = VALUE_WIDTH,
                    alignment = Layout.Alignment.ALIGN_OPPOSITE)
                TableRow(left, right, max(left.height, right.height) + ROW_PADDING * 2, indent)
            }
            val footer = text("출처: " + PublicationText.sourceLabel(source), 28f, MUTED)
            val date = PublicationText.confirmedDate(source)?.let {
                text("확인일: " + it, 28f, MUTED)
            }
            val rowsTop = PADDING + heading.height + 24 + name.height + 36
            val footerTop = rowsTop + rows.sumOf { it.height } + 36
            val height = footerTop + footer.height + (date?.let { it.height + 12 } ?: 0) + PADDING
            require(height in 1..MAX_HEIGHT) { "Nutrition table exceeds bitmap size limit" }
            val image = createBitmap(WIDTH, height)
            bitmap = image
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            draw(canvas, heading, PADDING, PADDING)
            draw(canvas, name, PADDING, PADDING + heading.height + 24)
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE; strokeWidth = 2f }
            var y = rowsTop
            rows.forEachIndexed { index, row ->
                if (index % 2 == 0) {
                    canvas.drawRect(PADDING.toFloat(), y.toFloat(), (WIDTH - PADDING).toFloat(),
                        (y + row.height).toFloat(), Paint().apply { color = TINT })
                }
                draw(canvas, row.left, PADDING + 18 + row.indent, y + ROW_PADDING)
                draw(canvas, row.right, WIDTH - PADDING - VALUE_WIDTH - 18, y + ROW_PADDING)
                canvas.drawLine(PADDING.toFloat(), (y + row.height).toFloat(),
                    (WIDTH - PADDING).toFloat(), (y + row.height).toFloat(), rule)
                y += row.height
            }
            draw(canvas, footer, PADDING, footerTop)
            date?.let { draw(canvas, it, PADDING, footerTop + footer.height + 12) }
            NutritionTableRenderResult.Ready(image)
        } catch (_: OutOfMemoryError) {
            bitmap?.recycle()
            NutritionTableRenderResult.Failed("영양성분표 이미지를 만들 메모리가 부족합니다.")
        } catch (_: Exception) {
            bitmap?.recycle()
            NutritionTableRenderResult.Failed("영양성분표 이미지를 생성하지 못했습니다.")
        }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        width: Int = WIDTH - PADDING * 2,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        maxLines: Int = Int.MAX_VALUE,
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(alignment).setIncludePad(false).setLineSpacing(6f, 1f)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    private fun draw(canvas: Canvas, layout: StaticLayout, x: Int, y: Int) {
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private data class TableRow(val left: StaticLayout, val right: StaticLayout, val height: Int, val indent: Int)

    private companion object {
        const val WIDTH = 1080
        const val PADDING = 64
        const val LABEL_WIDTH = 420
        const val VALUE_WIDTH = 460
        const val ROW_PADDING = 26
        const val MAX_HEIGHT = 16384
        val ACCENT = Color.rgb(22, 102, 88)
        val INK = Color.rgb(23, 34, 42)
        val MUTED = Color.rgb(82, 99, 111)
        val RULE = Color.rgb(219, 229, 231)
        val TINT = Color.rgb(243, 248, 247)
    }
}
