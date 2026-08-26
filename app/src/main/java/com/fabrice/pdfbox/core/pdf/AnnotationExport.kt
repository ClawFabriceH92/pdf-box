package com.fabrice.pdfbox.core.pdf

import android.graphics.BitmapFactory
import com.fabrice.pdfbox.core.data.Annot
import com.fabrice.pdfbox.core.data.AnnotationType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File

/**
 * A3 — export d'une copie annotée.
 *
 * Les surlignages sont **dessinés** dans le flux de la page, en mode de fusion
 * « produit » : la couleur assombrit le fond au lieu de le recouvrir, donc le
 * texte reste lisible dessous, exactement comme un surligneur. Un lecteur qui
 * ne gère pas les annotations interactives les affiche quand même.
 *
 * Les notes deviennent en revanche de vraies annotations PDF (« pense-bête ») :
 * elles restent alors dépliables, modifiables et supprimables dans n'importe
 * quel lecteur, ce qu'un dessin ne permettrait pas.
 */
object AnnotationExport {

    fun apply(
        source: File,
        target: File,
        annotations: List<Annot>,
        password: String? = null,
        includeRedactions: Boolean = false
    ): File {
        require(annotations.isNotEmpty()) { "Ce document ne porte aucune annotation." }
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            annotations.groupBy { it.page }.forEach { (index, items) ->
                if (index !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(index)
                drawOnPage(doc, page, items, includeRedactions)
            }
            doc.save(target)
        }
        return target
    }

    private fun drawOnPage(
        doc: PDDocument,
        page: PDPage,
        items: List<Annot>,
        includeRedactions: Boolean
    ) {
        val drawn = items.filter {
            it.type == AnnotationType.HIGHLIGHT ||
                it.type == AnnotationType.SIGNATURE ||
                (it.type == AnnotationType.REDACT && includeRedactions)
        }
        if (drawn.isNotEmpty()) {
            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                drawn.forEach { annot ->
                    when (annot.type) {
                        AnnotationType.HIGHLIGHT -> drawHighlight(cs, page, annot)
                        AnnotationType.REDACT -> drawRedaction(cs, page, annot)
                        AnnotationType.SIGNATURE -> drawSignature(doc, cs, page, annot)
                        else -> Unit
                    }
                }
            }
        }
        items.filter { it.type == AnnotationType.NOTE }.forEach { note ->
            addStickyNote(page, note)
        }
    }

    private fun drawHighlight(cs: PDPageContentStream, page: PDPage, annot: Annot) {
        val state = PDExtendedGraphicsState().apply {
            blendMode = BlendMode.MULTIPLY
            nonStrokingAlphaConstant = 1f
        }
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(state)
        cs.setNonStrokingColor(
            ((annot.color shr 16) and 0xFF) / 255f,
            ((annot.color shr 8) and 0xFF) / 255f,
            (annot.color and 0xFF) / 255f
        )
        val u = PdfGeometry.rectToUser(page, annot.left, annot.top, annot.right, annot.bottom)
        cs.addRect(u[0], u[1], u[2], u[3])
        cs.fill()
        cs.restoreGraphicsState()
    }

    private fun drawRedaction(cs: PDPageContentStream, page: PDPage, annot: Annot) {
        cs.saveGraphicsState()
        cs.setNonStrokingColor(0f, 0f, 0f)
        val u = PdfGeometry.rectToUser(page, annot.left, annot.top, annot.right, annot.bottom)
        cs.addRect(u[0], u[1], u[2], u[3])
        cs.fill()
        cs.restoreGraphicsState()
    }

    private fun drawSignature(doc: PDDocument, cs: PDPageContentStream, page: PDPage, annot: Annot) {
        val path = annot.payload ?: return
        val file = File(path)
        if (!file.exists()) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val image = LosslessFactory.createFromImage(doc, bitmap)
        val size = PdfGeometry.displaySize(page)
        val x = annot.left * size.widthPt
        val y = annot.top * size.heightPt
        val width = (annot.right - annot.left) * size.widthPt
        val height = (annot.bottom - annot.top) * size.heightPt
        PdfGeometry.beginPlacement(cs, page, x, y, width, height)
        cs.drawImage(image, 0f, 0f, width, height)
        cs.restoreGraphicsState()
        bitmap.recycle()
    }

    private fun addStickyNote(page: PDPage, annot: Annot) {
        val size = PdfGeometry.displaySize(page)
        val anchor = PdfGeometry.toUser(page, annot.left * size.widthPt, annot.top * size.heightPt)
        val note = PDAnnotationText().apply {
            contents = annot.text.orEmpty()
            name = PDAnnotationText.NAME_NOTE
            rectangle = PDRectangle(anchor[0], anchor[1] - 20f, 20f, 20f)
            color = PDColor(
                floatArrayOf(
                    ((annot.color shr 16) and 0xFF) / 255f,
                    ((annot.color shr 8) and 0xFF) / 255f,
                    (annot.color and 0xFF) / 255f
                ),
                PDDeviceRGB.INSTANCE
            )
            isPrinted = true
        }
        runCatching { page.annotations.add(note) }
    }
}
