package com.fabrice.pdfbox.core.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fabrice.pdfbox.core.pdf.PdfPageRenderer
import com.fabrice.pdfbox.core.util.NoProgress
import com.fabrice.pdfbox.core.util.ProgressSink
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Une ligne reconnue, en coordonnées d'affichage normalisées (0..1). */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.0001f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.0001f)
}

data class OcrPage(val page: Int, val text: String, val lines: List<OcrLine>) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * T2 — reconnaissance de texte **sur l'appareil**.
 *
 * Le modèle latin est embarqué dans l'APK (`text-recognition` et non
 * `text-recognition-*-unbundled`) : rien n'est téléchargé au premier usage,
 * rien ne sort du téléphone, et la reconnaissance fonctionne en mode avion.
 * Le latin couvre le français et l'anglais, seules langues du périmètre.
 */
object Ocr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): Pair<String, List<OcrLine>> =
        withContext(Dispatchers.Default) {
            val width = bitmap.width.toFloat().coerceAtLeast(1f)
            val height = bitmap.height.toFloat().coerceAtLeast(1f)
            val result = awaitRecognition(bitmap)
            val lines = mutableListOf<OcrLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box: Rect = line.boundingBox ?: continue
                    lines += OcrLine(
                        text = line.text,
                        left = (box.left / width).coerceIn(0f, 1f),
                        top = (box.top / height).coerceIn(0f, 1f),
                        right = (box.right / width).coerceIn(0f, 1f),
                        bottom = (box.bottom / height).coerceIn(0f, 1f)
                    )
                }
            }
            result.text to lines
        }

    private suspend fun awaitRecognition(bitmap: Bitmap) =
        suspendCancellableCoroutine { continuation: CancellableContinuation<com.google.mlkit.vision.text.Text> ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    /**
     * OCR d'un document entier. Le rendu se fait à 200 dpi : en dessous, les
     * petits caractères d'une facture ne passent pas ; au-dessus, le gain est
     * marginal et la mémoire devient le facteur limitant.
     */
    suspend fun recognizeDocument(
        file: File,
        pages: List<Int>,
        dpi: Int = 200,
        progress: ProgressSink = NoProgress
    ): List<OcrPage> = withContext(Dispatchers.Default) {
        val out = mutableListOf<OcrPage>()
        PdfPageRenderer.open(file).use { renderer ->
            val targets = pages.ifEmpty { (0 until renderer.pageCount).toList() }
            targets.forEachIndexed { position, index ->
                if (index !in 0 until renderer.pageCount) return@forEachIndexed
                progress.onProgress(position, targets.size, "page ${index + 1}")
                val size = renderer.pageSize(index)
                val widthPx = ((size.widthPt / 72f) * dpi).roundToInt().coerceIn(600, 3000)
                val bitmap = renderer.render(index, widthPx)
                val (text, lines) = recognize(bitmap)
                bitmap.recycle()
                out += OcrPage(index, text, lines)
            }
            progress.onProgress(targets.size, targets.size, "terminé")
        }
        out
    }
}
