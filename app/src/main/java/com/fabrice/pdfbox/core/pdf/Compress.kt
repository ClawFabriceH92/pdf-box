package com.fabrice.pdfbox.core.pdf

import android.graphics.Bitmap
import com.fabrice.pdfbox.core.util.NoProgress
import com.fabrice.pdfbox.core.util.ProgressSink
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.roundToInt

/**
 * P4F — réduction de la taille d'un PDF, typiquement avant un envoi par courriel.
 *
 * Les trois profils ne diffèrent pas par un curseur mais par ce qu'ils
 * acceptent de perdre, ce qui est la seule question qui se pose vraiment.
 */
object Compress {

    enum class Profile(
        val label: String,
        val explanation: String,
        val dpi: Int,
        val jpegQuality: Float,
        val rasterize: Boolean
    ) {
        SAFE(
            "Sûr — texte préservé",
            "Seules les images incluses sont ré-encodées. Le texte, les liens et " +
                "la sélection restent intacts.",
            dpi = 200, jpegQuality = 0.82f, rasterize = false
        ),
        STANDARD(
            "Standard",
            "Images ré-échantillonnées à 150 dpi. Bon compromis pour un envoi " +
                "par courriel ; le texte reste sélectionnable.",
            dpi = 150, jpegQuality = 0.7f, rasterize = false
        ),
        SMALL(
            "Maximal — pages aplaties",
            "Chaque page devient une image de 110 dpi. Le gain est le plus fort, " +
                "mais le document perd son texte : plus de recherche ni de copie.",
            dpi = 110, jpegQuality = 0.6f, rasterize = true
        )
    }

    data class Result(val file: File, val beforeBytes: Long, val afterBytes: Long) {
        val ratio: Float get() = if (beforeBytes <= 0) 1f else afterBytes.toFloat() / beforeBytes
        val savedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0L)
        val effective: Boolean get() = afterBytes < beforeBytes
    }

    fun compress(
        source: File,
        target: File,
        profile: Profile = Profile.STANDARD,
        password: String? = null,
        progress: ProgressSink = NoProgress
    ): Result {
        val before = source.length()
        if (profile.rasterize) {
            rasterize(source, target, profile, password, progress)
        } else {
            recompressImages(source, target, profile, password, progress)
        }
        return Result(target, before, target.length())
    }

    /** Ré-encode les images incluses, sans toucher au reste de la structure. */
    private fun recompressImages(
        source: File,
        target: File,
        profile: Profile,
        password: String?,
        progress: ProgressSink
    ) {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val total = doc.numberOfPages
            for (index in 0 until total) {
                progress.onProgress(index, total, "page ${index + 1}")
                val page = doc.getPage(index)
                val resources: PDResources = page.resources ?: continue
                val pageWidthPt = PdfGeometry.displaySize(page).widthPt.coerceAtLeast(1f)
                val maxWidthPx = ((pageWidthPt / 72f) * profile.dpi).roundToInt().coerceAtLeast(320)
                val names = resources.xObjectNames?.toList() ?: emptyList()
                for (name in names) {
                    val xObject = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                    if (xObject !is PDImageXObject) continue
                    // Masques et transparences : le JPEG les perdrait, on passe.
                    if (xObject.isStencil || xObject.softMask != null) continue
                    if (xObject.width < 64 || xObject.height < 64) continue
                    if (xObject.width <= maxWidthPx && "jpg".equals(xObject.suffix, true)) continue
                    val bitmap = runCatching { xObject.image }.getOrNull() ?: continue
                    val scaled = downscale(bitmap, maxWidthPx)
                    val replacement = runCatching {
                        JPEGFactory.createFromImage(doc, scaled, profile.jpegQuality)
                    }.getOrNull()
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    if (replacement != null) resources.put(name, replacement)
                }
            }
            progress.onProgress(total, total, "enregistrement")
            doc.save(target)
        }
    }

    /** Aplatit chaque page en une image : le gain maximal, au prix du texte. */
    private fun rasterize(
        source: File,
        target: File,
        profile: Profile,
        password: String?,
        progress: ProgressSink
    ) {
        val renderSource = if (password.isNullOrEmpty()) source else Security.decryptToTemp(source, password)
        try {
            PdfPageRenderer.open(renderSource).use { renderer ->
                PDDocument().use { out ->
                    val total = renderer.pageCount
                    for (index in 0 until total) {
                        progress.onProgress(index, total, "page ${index + 1}")
                        val size = renderer.pageSize(index)
                        val widthPx = ((size.widthPt / 72f) * profile.dpi).roundToInt().coerceIn(200, 2400)
                        val bitmap = renderer.render(index, widthPx)
                        val page = PDPage(PDRectangle(size.widthPt, size.heightPt))
                        out.addPage(page)
                        val image = JPEGFactory.createFromImage(out, bitmap, profile.jpegQuality)
                        PDPageContentStream(out, page).use { cs ->
                            cs.drawImage(image, 0f, 0f, size.widthPt, size.heightPt)
                        }
                        bitmap.recycle()
                    }
                    progress.onProgress(total, total, "enregistrement")
                    out.save(target)
                }
            }
        } finally {
            if (renderSource != source) renderSource.delete()
        }
    }

    private fun downscale(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val width = maxWidth
        val height = (width * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
