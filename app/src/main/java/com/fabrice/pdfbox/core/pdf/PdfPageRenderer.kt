package com.fabrice.pdfbox.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rendu des pages.
 *
 * Le cahier des charges prévoyait d'embarquer un wrapper PDFium. Android en
 * expose déjà un : `android.graphics.pdf.PdfRenderer` **est** PDFium, présent
 * dans le système depuis l'API 21. L'utiliser supprime une dépendance native de
 * plusieurs mégaoctets par architecture, et supprime aussi le risque de
 * packaging que le cahier des charges lui-même signalait comme premier risque
 * du projet. Ce que le moteur système ne sait pas faire — extraire du texte,
 * modifier la structure — n'était de toute façon pas son rôle : c'est PDFBox
 * qui s'en charge.
 *
 * `PdfRenderer` n'ouvre qu'une page à la fois et n'est pas réentrant : tous les
 * accès passent par le verrou de l'instance.
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /** Taille de la page en points PDF (1/72 pouce), rotation appliquée. */
    fun pageSize(index: Int): PageSize = synchronized(this) {
        renderer.openPage(index).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
    }

    /**
     * Rend une page dans une largeur cible, en bornant la surface : au-delà,
     * un A4 à 600 dpi dépasse la mémoire d'un appareil d'entrée de gamme.
     */
    fun render(index: Int, targetWidthPx: Int, maxPixels: Int = 8_000_000): Bitmap =
        synchronized(this) {
            renderer.openPage(index).use { page ->
                val ratio = page.height.toFloat() / page.width.toFloat()
                var width = max(1, min(targetWidthPx, 4096))
                var height = max(1, (width * ratio).roundToInt())
                val pixels = width.toLong() * height.toLong()
                if (pixels > maxPixels) {
                    val scale = Math.sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
                    width = max(1, (width * scale).roundToInt())
                    height = max(1, (height * scale).roundToInt())
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // PdfRenderer ne peint pas le fond : sans ce blanc, une page au
                // fond transparent s'affiche sur du noir en thème sombre.
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * @throws PdfProtectedException si le document est chiffré — `PdfRenderer`
         * refuse alors de l'ouvrir, et le message système n'est pas exploitable.
         */
        fun open(file: File): PdfPageRenderer {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfPageRenderer(pfd, PdfRenderer(pfd))
            } catch (e: SecurityException) {
                runCatching { pfd.close() }
                throw PdfProtectedException(e)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw e
            }
        }
    }
}

data class PageSize(val widthPt: Float, val heightPt: Float) {
    val ratio: Float get() = if (widthPt <= 0f) 1f else heightPt / widthPt
}

class PdfProtectedException(cause: Throwable? = null) :
    Exception("Ce PDF est protégé par un mot de passe.", cause)
