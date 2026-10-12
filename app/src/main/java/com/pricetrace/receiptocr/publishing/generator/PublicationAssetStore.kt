package com.pricetrace.receiptocr.publishing.generator

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.pricetrace.receiptscanner.publishing.model.PublicationGeneratedImage
import java.io.File
import java.util.UUID

/** Generated files have their own directory. Original document/page files are never rewritten. */
class PublicationAssetStore(private val context: Context) {
    fun saveTable(bitmap: Bitmap): PublicationGeneratedImage {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "receipt-scanner/publication-assets/" + id)
        check(directory.mkdirs()) { "Unable to create publication output directory" }
        val file = File(directory, "nutrition_table.png")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return PublicationGeneratedImage(id = id, uri = uri.toString())
    }
}
