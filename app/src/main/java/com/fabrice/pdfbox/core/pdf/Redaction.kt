package com.fabrice.pdfbox.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.fabrice.pdfbox.core.data.Annot
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * P1F — masquage de zones confidentielles (IBAN, SIRET, mentions personnelles).
 *
 * Deux modes, parce que la différence est celle qui compte réellement et qu'un
 * seul bouton « noircir » induirait en erreur :
 *
 *  - [Mode.FLATTEN] : la page est **rasterisée**, les zones peintes en noir, et
 *    la page d'origine remplacée par cette image. Le texte masqué n'existe plus
 *    dans le fichier — c'est le seul mode qui résiste au copier-coller et à
 *    `pdftotext`. Contrepartie : la page entière perd sa couche texte.
 *  - [Mode.OVERLAY] : un rectangle noir est dessiné par-dessus. Rapide, le
 *    reste du document garde son texte, **mais le texte masqué est toujours
 *    dans le fichier** et se récupère en le sélectionnant. Réservé au confort
 *    de lecture, jamais à la confidentialité.
 */
object Redaction {

    enum class Mode(val label: String, val explanation: String) {
        FLATTEN(
            "Masquage sûr",
            "La page est aplatie en image : le texte masqué disparaît du fichier. " +
                "La page perd sa couche texte (plus de recherche ni de copie)."
        ),
        OVERLAY(
            "Masquage visuel",
            "Un rectangle noir est posé par-dessus. Rapide et sans perte, mais le " +
                "texte reste présent sous le noir et peut être récupéré."
        )
    }

    fun apply(
        source: File,
        target: File,
        boxes: List<Annot>,
        mode: Mode = Mode.FLATTEN,
        dpi: Int = 200,
        password: String? = null
    ): File {
        require(boxes.isNotEmpty()) { "Aucune zone à masquer." }
        val byPage = boxes.groupBy { it.page }
        when (mode) {
            Mode.OVERLAY -> applyOverlay(source, target, byPage, password)
            Mode.FLATTEN -> applyFlatten(source, target, byPage, dpi, password)
        }
        return target
    }

    private fun applyOverlay(
        source: File,
        target: File,
        byPage: Map<Int, List<Annot>>,
        password: String?
    ) {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            byPage.forEach { (index, rects) ->
                if (index !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(index)
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    rects.forEach { r ->
                        val u = PdfGeometry.rectToUser(page, r.left, r.top, r.right, r.bottom)
                        cs.addRect(u[0], u[1], u[2], u[3])
                    }
                    cs.fill()
                }
            }
            doc.save(target)
        }
    }

    private fun applyFlatten(
        source: File,
        target: File,
        byPage: Map<Int, List<Annot>>,
        dpi: Int,
        password: String?
    ) {
        // Le rendu passe par le moteur système (PDFium) : plus fidèle et bien
        // plus rapide que le rasteriseur Java pour un document scanné.
        val renderSource = if (password.isNullOrEmpty()) source else Security.decryptToTemp(source, password)
        PdfPageRenderer.open(renderSource).use { renderer ->
            PdfDoc.use(source, password) { doc ->
                PdfDoc.decryptForWrite(doc)
                byPage.forEach { (index, rects) ->
                    if (index !in 0 until doc.numberOfPages) return@forEach
                    val page = doc.getPage(index)
                    val size = PdfGeometry.displaySize(page)
                    val widthPx = ((size.widthPt / 72f) * dpi).roundToInt().coerceIn(200, 3000)
                    val bitmap = renderer.render(index, widthPx)
                    paintBlack(bitmap, rects)

                    // La page devient l'image : rotation neutralisée et boîtes
                    // ramenées aux dimensions d'affichage, sinon le lecteur
                    // appliquerait la rotation une seconde fois.
                    page.rotation = 0
                    page.mediaBox = PDRectangle(size.widthPt, size.heightPt)
                    page.cropBox = PDRectangle(size.widthPt, size.heightPt)
                    page.setAnnotations(emptyList())
                    page.resources = PDResources()

                    val image = JPEGFactory.createFromImage(doc, bitmap, 0.86f)
                    PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, true
                    ).use { cs ->
                        cs.drawImage(image, 0f, 0f, size.widthPt, size.heightPt)
                    }
                    bitmap.recycle()
                }
                doc.save(target)
            }
        }
        if (renderSource != source) renderSource.delete()
    }

    private fun paintBlack(bitmap: Bitmap, rects: List<Annot>) {
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        rects.forEach { r ->
            canvas.drawRect(
                RectF(
                    r.left * bitmap.width,
                    r.top * bitmap.height,
                    r.right * bitmap.width,
                    r.bottom * bitmap.height
                ),
                paint
            )
        }
    }
}
