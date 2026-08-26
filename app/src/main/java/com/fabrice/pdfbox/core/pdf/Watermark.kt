package com.fabrice.pdfbox.core.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File

/** Ancrage du filigrane dans la page, en coordonnées d'affichage. */
enum class Anchor(val label: String, val fx: Float, val fy: Float) {
    TOP_LEFT("Haut gauche", 0f, 0f),
    TOP_CENTER("Haut centre", 0.5f, 0f),
    TOP_RIGHT("Haut droite", 1f, 0f),
    MIDDLE_LEFT("Milieu gauche", 0f, 0.5f),
    CENTER("Centre", 0.5f, 0.5f),
    MIDDLE_RIGHT("Milieu droite", 1f, 0.5f),
    BOTTOM_LEFT("Bas gauche", 0f, 1f),
    BOTTOM_CENTER("Bas centre", 0.5f, 1f),
    BOTTOM_RIGHT("Bas droite", 1f, 1f)
}

data class WatermarkSpec(
    val text: String? = null,
    val image: Bitmap? = null,
    val anchor: Anchor = Anchor.CENTER,
    val rotationDegrees: Float = 45f,
    val opacity: Float = 0.25f,
    /** Part de la largeur de page occupée (0.1 … 1.0). */
    val scale: Float = 0.6f,
    val colorArgb: Int = 0xFF9E9E9E.toInt(),
    val tiled: Boolean = false,
    val marginPt: Float = 24f,
    /** Pages visées ; vide = toutes. */
    val pages: Set<Int> = emptySet(),
    val bold: Boolean = true
)

/** A10 — filigrane texte ou image, sur tout ou partie du document. */
object Watermark {

    fun apply(source: File, target: File, spec: WatermarkSpec, password: String? = null): File {
        require(!spec.text.isNullOrBlank() || spec.image != null) {
            "Indiquez un texte ou choisissez une image."
        }
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val image: PDImageXObject? = spec.image?.let { LosslessFactory.createFromImage(doc, it) }
            val font: PDFont = if (spec.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            val graphics = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = spec.opacity.coerceIn(0.02f, 1f)
                strokingAlphaConstant = spec.opacity.coerceIn(0.02f, 1f)
            }

            val targets = if (spec.pages.isEmpty()) (0 until doc.numberOfPages).toSet() else spec.pages
            for (index in targets.sorted()) {
                if (index !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(index)
                val size = PdfGeometry.displaySize(page)
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.setGraphicsStateParameters(graphics)
                    if (spec.tiled) {
                        drawTiled(cs, doc, page, size, spec, font, image)
                    } else {
                        drawSingle(cs, page, size, spec, font, image)
                    }
                }
            }
            doc.save(target)
        }
        return target
    }

    private fun drawSingle(
        cs: PDPageContentStream,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        size: PageSize,
        spec: WatermarkSpec,
        font: PDFont,
        image: PDImageXObject?
    ) {
        val (w, h) = contentSize(size, spec, font, image)
        val usable = spec.marginPt
        val x = usable + spec.anchor.fx * (size.widthPt - 2 * usable - w)
        val y = usable + spec.anchor.fy * (size.heightPt - 2 * usable - h)
        PdfGeometry.beginPlacement(cs, page, x, y, w, h, spec.rotationDegrees)
        drawContent(cs, spec, font, image, w, h)
        cs.restoreGraphicsState()
    }

    private fun drawTiled(
        cs: PDPageContentStream,
        doc: com.tom_roush.pdfbox.pdmodel.PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        size: PageSize,
        spec: WatermarkSpec,
        font: PDFont,
        image: PDImageXObject?
    ) {
        val tileSpec = spec.copy(scale = spec.scale.coerceAtMost(0.42f))
        val (w, h) = contentSize(size, tileSpec, font, image)
        val stepX = (w * 1.35f).coerceAtLeast(48f)
        val stepY = (h * 2.4f).coerceAtLeast(48f)
        var y = -h
        while (y < size.heightPt) {
            var x = -w * 0.5f
            while (x < size.widthPt) {
                PdfGeometry.beginPlacement(cs, page, x, y, w, h, spec.rotationDegrees)
                drawContent(cs, tileSpec, font, image, w, h)
                cs.restoreGraphicsState()
                x += stepX
            }
            y += stepY
        }
    }

    private fun contentSize(
        size: PageSize,
        spec: WatermarkSpec,
        font: PDFont,
        image: PDImageXObject?
    ): Pair<Float, Float> {
        val available = size.widthPt * spec.scale.coerceIn(0.05f, 1f)
        return if (image != null) {
            val ratio = image.height.toFloat() / image.width.toFloat().coerceAtLeast(1f)
            available to (available * ratio)
        } else {
            val text = PdfText.winAnsiSafe(spec.text.orEmpty())
            val unitWidth = runCatching { font.getStringWidth(text) / 1000f }.getOrDefault(0.5f * text.length.toFloat())
            val fontSize = if (unitWidth <= 0f) 24f else available / unitWidth
            (unitWidth * fontSize) to (fontSize * 0.95f)
        }
    }

    private fun drawContent(
        cs: PDPageContentStream,
        spec: WatermarkSpec,
        font: PDFont,
        image: PDImageXObject?,
        w: Float,
        h: Float
    ) {
        if (image != null) {
            cs.drawImage(image, 0f, 0f, w, h)
            return
        }
        val text = PdfText.winAnsiSafe(spec.text.orEmpty())
        if (text.isBlank()) return
        val fontSize = h / 0.95f
        cs.beginText()
        cs.setFont(font, fontSize)
        cs.setNonStrokingColor(
            ((spec.colorArgb shr 16) and 0xFF) / 255f,
            ((spec.colorArgb shr 8) and 0xFF) / 255f,
            (spec.colorArgb and 0xFF) / 255f
        )
        cs.newLineAtOffset(0f, h * 0.22f)
        cs.showText(text)
        cs.endText()
    }
}
