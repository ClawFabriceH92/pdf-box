package com.fabrice.pdfbox.core.pdf

import com.fabrice.pdfbox.core.ocr.OcrPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.File

/**
 * T3 — « PDF recherchable » : une couche de texte **invisible** posée sur les
 * pages scannées, au millimètre près là où l'OCR a lu quelque chose.
 *
 * La page d'origine n'est pas touchée : on ajoute un flux de contenu par-dessus,
 * en mode de rendu « ni trait ni remplissage » (Tr 3). L'aspect du document est
 * donc rigoureusement identique, mais la recherche, la sélection et le
 * copier-coller fonctionnent — y compris dans les autres lecteurs de PDF.
 */
object SearchablePdf {

    fun apply(
        source: File,
        target: File,
        ocr: List<OcrPage>,
        password: String? = null
    ): File {
        require(ocr.any { it.lines.isNotEmpty() }) {
            "L'OCR n'a rien reconnu : il n'y a pas de couche texte à ajouter."
        }
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val font: PDFont = PDType1Font.HELVETICA
            ocr.forEach { page ->
                if (page.page !in 0 until doc.numberOfPages || page.lines.isEmpty()) return@forEach
                val pdPage = doc.getPage(page.page)
                val size = PdfGeometry.displaySize(pdPage)
                PDPageContentStream(
                    doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.setRenderingMode(RenderingMode.NEITHER)
                    page.lines.forEach { line ->
                        val text = PdfText.winAnsiSafe(line.text).trim()
                        if (text.isEmpty()) return@forEach
                        val boxWidth = line.width * size.widthPt
                        val boxHeight = line.height * size.heightPt
                        if (boxWidth <= 1f || boxHeight <= 1f) return@forEach
                        val fontSize = (boxHeight * 0.82f).coerceIn(2f, 200f)
                        val natural = runCatching { font.getStringWidth(text) / 1000f * fontSize }
                            .getOrDefault(0f)
                        if (natural <= 0f) return@forEach
                        // Le texte doit occuper exactement la largeur lue, sinon
                        // la sélection à l'écran se décale du mot affiché.
                        val horizontalScale = (boxWidth / natural * 100f).coerceIn(10f, 400f)
                        PdfGeometry.beginPlacement(
                            cs, pdPage,
                            line.left * size.widthPt,
                            line.top * size.heightPt,
                            boxWidth, boxHeight
                        )
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setHorizontalScaling(horizontalScale)
                        cs.newLineAtOffset(0f, boxHeight * 0.18f)
                        cs.showText(text)
                        cs.endText()
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(target)
        }
        return target
    }
}
