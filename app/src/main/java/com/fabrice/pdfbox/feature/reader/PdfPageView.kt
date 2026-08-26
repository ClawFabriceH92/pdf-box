package com.fabrice.pdfbox.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Annot
import com.fabrice.pdfbox.core.data.AnnotationType

/** Rectangle en coordonnées normalisées, tel que le doigt vient de le tracer. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized() = NormRect(
        minOf(left, right), minOf(top, bottom),
        maxOf(left, right), maxOf(top, bottom)
    )

    val isMeaningful: Boolean
        get() = (right - left) > 0.004f && (bottom - top) > 0.004f
}

/**
 * Une page : image rendue, annotations superposées, gestes de sélection.
 *
 * Le rendu est demandé à la largeur réelle du composant, arrondie par paliers
 * dans le ViewModel : zoomer ne redemande donc pas une image à chaque pixel.
 */
@Composable
fun PdfPageView(
    reader: ReaderViewModel,
    index: Int,
    ratio: Float,
    widthPx: Int,
    annotations: List<Annot>,
    searchHits: List<DocSearchHit>,
    activeHit: DocSearchHit?,
    mode: ReaderMode,
    highlightColor: Int,
    onRectDrawn: (NormRect) -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Rendu asynchrone. `produceState` ferait la même chose en une ligne, mais
    // son contrôle lint ne reconnaît pas l'affectation de `value` et signale une
    // erreur ; l'écriture explicite dit de toute façon plus clairement que la
    // page repart de zéro quand l'index ou la largeur changent.
    var bitmap by remember(index, widthPx) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        bitmap = reader.bitmap(index, widthPx)
    }

    var dragStart by remember(index) { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember(index) { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f / ratio.coerceAtLeast(0.05f))
            .background(Color.White)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (mode == ReaderMode.READ) {
                        Modifier
                    } else {
                        Modifier.pointerInput(mode, index) {
                            detectTapGestures { position ->
                                onTap(position.x / size.width, position.y / size.height)
                            }
                        }.pointerInput(mode, index) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragStart = start
                                    dragCurrent = start
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    dragCurrent = change.position
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragCurrent
                                    if (start != null && end != null) {
                                        val rect = NormRect(
                                            start.x / size.width, start.y / size.height,
                                            end.x / size.width, end.y / size.height
                                        ).normalized()
                                        if (rect.isMeaningful) onRectDrawn(rect)
                                    }
                                    dragStart = null
                                    dragCurrent = null
                                },
                                onDragCancel = {
                                    dragStart = null
                                    dragCurrent = null
                                }
                            )
                        }
                    }
                )
        ) {
            val w = size.width
            val h = size.height

            annotations.forEach { annot ->
                val rect = Rect(
                    Offset(annot.left * w, annot.top * h),
                    Size((annot.right - annot.left) * w, (annot.bottom - annot.top) * h)
                )
                when (annot.type) {
                    AnnotationType.HIGHLIGHT -> drawRect(
                        color = Color(annot.color).copy(alpha = 0.42f),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        blendMode = BlendMode.Multiply
                    )
                    AnnotationType.REDACT -> drawRect(
                        color = Color.Black,
                        topLeft = rect.topLeft,
                        size = rect.size
                    )
                    AnnotationType.NOTE -> {
                        drawRect(
                            color = Color(annot.color),
                            topLeft = rect.topLeft,
                            size = Size(w * 0.028f, w * 0.028f)
                        )
                        drawRect(
                            color = Color.Black.copy(alpha = 0.45f),
                            topLeft = rect.topLeft,
                            size = Size(w * 0.028f, w * 0.028f),
                            style = Stroke(width = 2f)
                        )
                    }
                    AnnotationType.SIGNATURE -> drawRect(
                        color = Color(0xFF3F6FB5).copy(alpha = 0.25f),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 3f)
                    )
                }
            }

            searchHits.forEach { hit ->
                val isActive = hit === activeHit
                drawRect(
                    color = if (isActive) Color(0xFFFF9800).copy(alpha = 0.55f)
                    else Color(0xFF4CAF50).copy(alpha = 0.32f),
                    topLeft = Offset(hit.box.left * w, hit.box.top * h),
                    size = Size((hit.box.right - hit.box.left) * w, (hit.box.bottom - hit.box.top) * h)
                )
            }

            val start = dragStart
            val current = dragCurrent
            if (start != null && current != null) {
                val left = minOf(start.x, current.x)
                val top = minOf(start.y, current.y)
                val color = when (mode) {
                    ReaderMode.REDACT -> Color.Black
                    ReaderMode.SIGN -> Color(0xFF3F6FB5)
                    else -> Color(highlightColor)
                }
                drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(left, top),
                    size = Size(kotlin.math.abs(current.x - start.x), kotlin.math.abs(current.y - start.y))
                )
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(kotlin.math.abs(current.x - start.x), kotlin.math.abs(current.y - start.y)),
                    style = Stroke(width = 3f)
                )
            }
        }

        if (bitmap == null) {
            Text(
                "Page ${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
