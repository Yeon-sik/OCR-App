package com.yeonsik.ingestion.desktop

import org.junit.Assert.*
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File

class DesktopDropFilesTest {
    @Test fun fileDropsPreserveMultipleFilesAndNormalizePaths() {
        val files = listOf(File("one.yeonsik"), File("folder/../two.yeonsik"))
        val transfer = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = files
        }
        assertEquals(files.map { it.toPath().toAbsolutePath().normalize() }, desktopDropFiles(transfer))
    }

    @Test fun textDropsAreRejected() {
        assertTrue(desktopDropFiles(StringSelection("not a file list")).isEmpty())
    }
}
