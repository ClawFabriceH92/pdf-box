package com.fabrice.pdfbox.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix

/**
 * Conversion entre l'**espace d'affichage** (celui que voit l'utilisateur :
 * origine en haut à gauche, rotation `/Rotate` appliquée) et l'**espace
 * utilisateur** du PDF (origine en bas à gauche, rotation ignorée).
 *
 * Toutes les annotations sont mémorisées en coordonnées d'affichage
 * normalisées : c'est ce que l'utilisateur a désigné du doigt. La traduction
 * n'a lieu qu'au moment d'écrire dans le fichier.
 */
object PdfGeometry {

    fun cropBox(page: PDPage): PDRectangle = page.cropBox ?: page.mediaBox ?: PDRectangle.A4

    fun normalizedRotation(page: PDPage): Int {
        val r = page.rotation % 360
        return if (r < 0) r + 360 else r
    }

    /** Dimensions de la page telles qu'affichées, en points. */
    fun displaySize(page: PDPage): PageSize {
        val box = cropBox(page)
        return if (normalizedRotation(page) % 180 == 0) {
            PageSize(box.width, box.height)
        } else {
            PageSize(box.height, box.width)
        }
    }

    /**
     * Matrice affichage → utilisateur. Son déterminant vaut −1 (l'axe des
     * ordonnées est inversé) : elle convient aux rectangles, pas au texte ni
     * aux images, qui seraient rendus en miroir. Pour ceux-là, voir [placement].
     */
    private fun displayToUser(page: PDPage): FloatArray {
        val box = cropBox(page)
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val urx = box.upperRightX
        val ury = box.upperRightY
        return when (normalizedRotation(page)) {
            90 -> floatArrayOf(0f, 1f, 1f, 0f, llx, lly)
            180 -> floatArrayOf(-1f, 0f, 0f, 1f, urx, lly)
            270 -> floatArrayOf(0f, -1f, -1f, 0f, urx, ury)
            else -> floatArrayOf(1f, 0f, 0f, -1f, llx, ury)
        }
    }

    /** Un point d'affichage (en points, origine haut-gauche) en espace utilisateur. */
    fun toUser(page: PDPage, dx: Float, dy: Float): FloatArray {
        val m = displayToUser(page)
        return floatArrayOf(m[0] * dx + m[2] * dy + m[4], m[1] * dx + m[3] * dy + m[5])
    }

    /**
     * Rectangle d'affichage **normalisé** (0..1) traduit en rectangle utilisateur.
     * Retourne [x, y, largeur, hauteur] prêt pour `addRect`.
     */
    fun rectToUser(page: PDPage, l: Float, t: Float, r: Float, b: Float): FloatArray {
        val size = displaySize(page)
        val x0 = l * size.widthPt
        val y0 = t * size.heightPt
        val x1 = r * size.widthPt
        val y1 = b * size.heightPt
        val p0 = toUser(page, x0, y0)
        val p1 = toUser(page, x1, y1)
        val minX = minOf(p0[0], p1[0])
        val minY = minOf(p0[1], p1[1])
        val maxX = maxOf(p0[0], p1[0])
        val maxY = maxOf(p0[1], p1[1])
        return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * Matrice à appliquer pour dessiner du contenu **droit** (texte, image) dans
     * un rectangle d'affichage. Le contenu se dessine ensuite dans un repère
     * local (0..w, 0..h), ordonnées vers le haut, comme un PDF ordinaire.
     */
    fun placement(page: PDPage, displayX: Float, displayY: Float, width: Float, height: Float): Matrix {
        val m = displayToUser(page)
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]
        val top = displayY + height
        return Matrix(a, b, -c, -d, a * displayX + c * top + e, b * displayX + d * top + f)
    }

    /**
     * Prépare le flux pour dessiner droit dans un rectangle d'affichage, avec
     * une rotation propre autour du centre du rectangle. À refermer par
     * `restoreGraphicsState()`.
     */
    fun beginPlacement(
        stream: PDPageContentStream,
        page: PDPage,
        displayX: Float,
        displayY: Float,
        width: Float,
        height: Float,
        rotationDegrees: Float = 0f
    ) {
        stream.saveGraphicsState()
        stream.transform(placement(page, displayX, displayY, width, height))
        if (rotationDegrees != 0f) {
            val cx = width / 2f
            val cy = height / 2f
            stream.transform(Matrix.getTranslateInstance(cx, cy))
            stream.transform(Matrix.getRotateInstance(Math.toRadians(rotationDegrees.toDouble()), 0f, 0f))
            stream.transform(Matrix.getTranslateInstance(-cx, -cy))
        }
    }
}
