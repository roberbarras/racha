package io.github.roberbarras.racha.desktop.resource

import androidx.compose.ui.graphics.toAwtImage
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfBoxDocumentTest {
    private fun twoPagePdf() = Files.createTempFile("racha", ".pdf").also { file ->
        PDDocument().use { doc ->
            listOf(PDRectangle.A4, PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)).forEach { size ->
                val page = PDPage(size).also(doc::addPage)
                PDPageContentStream(doc, page).use {
                    it.beginText(); it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    it.newLineAtOffset(72f, 72f); it.showText("Racha"); it.endText()
                }
            }
            doc.save(file.toFile())
        }
    }

    @Test
    fun opensPagesAndRendersAtTheRequestedWidth() = runBlocking {
        val file = twoPagePdf()
        PdfBoxDocument.open(file.toString())!!.use { pdf ->
            assertEquals(2, pdf.pageCount)
            assertTrue(abs(pdf.pageAspect(0) - 210f / 297f) < 0.01f, "portrait A4")
            assertTrue(abs(pdf.pageAspect(1) - 297f / 210f) < 0.01f, "landscape A4")
            val bitmap = pdf.render(0, 600)
            assertTrue(abs(bitmap.width - 600) <= 1, "rendered width ${bitmap.width}")
            assertTrue(abs(bitmap.height - 849) <= 2, "rendered height ${bitmap.height}")
        }
        Files.delete(file)
    }

    @Test
    fun drawsTheContentOfTheSampleDocument() = runBlocking {
        PdfBoxDocument.open(java.nio.file.Path.of("../testdata/documents/sample.pdf").toAbsolutePath().toString())!!.use { pdf ->
            val page = pdf.render(0, 595).toAwtImage()
            // Blue box of page 1: x 72..372, y 400..600 from the bottom (1 px per point at this width).
            val inside = java.awt.Color(page.getRGB(200, page.height - 500))
            assertTrue(inside.blue > 150 && inside.red < 60, "box colour $inside")
            assertEquals(java.awt.Color.WHITE.rgb, page.getRGB(5, 5))
        }
    }

    @Test
    fun aBrokenFileIsNull() = runBlocking {
        val file = Files.createTempFile("racha", ".pdf").also { Files.writeString(it, "not a pdf") }
        assertNull(PdfBoxDocument.open(file.toString()))
        Files.delete(file)
    }
}
