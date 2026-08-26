package com.fabrice.pdfbox.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fabrice.pdfbox.core.util.NoProgress
import com.fabrice.pdfbox.core.util.ProgressSink
import com.fabrice.pdfbox.core.util.Storage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** P6F — export d'une page en image, et le chemin inverse : images → PDF. */
object ImageIo {

    enum class Format(val label: String, val extension: String, val mime: String) {
        PNG("PNG (sans perte)", "png", "image/png"),
        JPEG("JPEG (plus léger)", "jpg", "image/jpeg")
    }

    fun pageToImage(
        context: Context,
        source: File,
        pageIndex: Int,
        dpi: Int,
        format: Format,
        baseName: String,
        password: String? = null
    ): File {
        val renderSource = if (password.isNullOrEmpty()) source else Security.tempDecrypted(context, source, password)
        try {
            PdfPageRenderer.open(renderSource).use { renderer ->
                val size = renderer.pageSize(pageIndex)
                val widthPx = ((size.widthPt / 72f) * dpi).roundToInt().coerceIn(200, 4096)
                val bitmap = renderer.render(pageIndex, widthPx)
                val out = Storage.uniqueFile(
                    Storage.exportsDir(context),
                    "$baseName-p${pageIndex + 1}.${format.extension}"
                )
                FileOutputStream(out).use { stream ->
                    val codec = if (format == Format.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bitmap.compress(codec, if (format == Format.PNG) 100 else 92, stream)
                }
                bitmap.recycle()
                return out
            }
        } finally {
            if (renderSource != source) renderSource.delete()
        }
    }

    fun pagesToImages(
        context: Context,
        source: File,
        pages: List<Int>,
        dpi: Int,
        format: Format,
        baseName: String,
        password: String? = null,
        progress: ProgressSink = NoProgress
    ): List<File> = pages.mapIndexed { i, page ->
        progress.onProgress(i, pages.size, "page ${page + 1}")
        pageToImage(context, source, page, dpi, format, baseName, password)
    }

    /**
     * T6 / import photo — une page par image, mise à l'échelle d'un A4 en
     * conservant les proportions. L'orientation EXIF est appliquée : sans quoi
     * une photo prise en portrait ressort couchée.
     */
    fun imagesToPdf(
        context: Context,
        images: List<Uri>,
        target: File,
        jpegQuality: Float = 0.85f,
        maxDimension: Int = 2200,
        progress: ProgressSink = NoProgress
    ): File {
        require(images.isNotEmpty()) { "Aucune image sélectionnée." }
        PDDocument().use { doc ->
            images.forEachIndexed { index, uri ->
                progress.onProgress(index, images.size, "image ${index + 1}")
                val bitmap = decodeOriented(context, uri, maxDimension) ?: return@forEachIndexed
                val a4 = PDRectangle.A4
                val scale = min(a4.width / bitmap.width, a4.height / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val page = PDPage(a4)
                doc.addPage(page)
                val image = JPEGFactory.createFromImage(doc, bitmap, jpegQuality)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(
                        image,
                        (a4.width - width) / 2f,
                        (a4.height - height) / 2f,
                        width,
                        height
                    )
                }
                bitmap.recycle()
            }
            require(doc.numberOfPages > 0) { "Aucune image lisible dans la sélection." }
            progress.onProgress(images.size, images.size, "enregistrement")
            doc.save(target)
        }
        return target
    }

    fun decodeOriented(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }
}
