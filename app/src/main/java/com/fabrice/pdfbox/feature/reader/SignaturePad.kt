package com.fabrice.pdfbox.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream

/**
 * A8 — signature manuscrite.
 *
 * Les traits sont mémorisés en coordonnées **normalisées**, puis rejoués à la
 * résolution d'export : une signature tracée sur un écran de téléphone reste
 * nette une fois posée sur un A4 à 300 dpi, ce qu'une capture d'écran du tracé
 * ne permettrait pas.
 */
@Composable
fun SignatureDialog(
    targetFile: File,
    onDismiss: () -> Unit,
    onSigned: (File) -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tracez votre signature dans le cadre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    current = listOf(
                                        Offset(start.x / size.width, start.y / size.height)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = current + Offset(
                                        change.position.x / size.width,
                                        change.position.y / size.height
                                    )
                                },
                                onDragEnd = {
                                    if (current.size > 1) strokes.add(current)
                                    current = emptyList()
                                },
                                onDragCancel = { current = emptyList() }
                            )
                        }
                ) {
                    (strokes + listOf(current)).forEach { stroke ->
                        if (stroke.size < 2) return@forEach
                        val path = Path()
                        path.moveTo(stroke[0].x * size.width, stroke[0].y * size.height)
                        stroke.drop(1).forEach { point ->
                            path.lineTo(point.x * size.width, point.y * size.height)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF10233D),
                            style = Stroke(
                                width = size.width * 0.008f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Effacer") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = strokes.isNotEmpty(),
                onClick = {
                    val file = renderSignature(strokes.toList(), targetFile)
                    if (file != null) onSigned(file)
                }
            ) { Text("Placer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Rejoue les traits sur un bitmap transparent haute résolution. */
private fun renderSignature(strokes: List<List<Offset>>, target: File): File? {
    if (strokes.isEmpty()) return null
    val width = 1200
    val height = 480
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.TRANSPARENT)
    val paint = Paint().apply {
        isAntiAlias = true
        color = AndroidColor.rgb(16, 35, 61)
        style = Paint.Style.STROKE
        strokeWidth = width * 0.009f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.size < 2) return@forEach
        val path = AndroidPath()
        path.moveTo(stroke[0].x * width, stroke[0].y * height)
        stroke.drop(1).forEach { point -> path.lineTo(point.x * width, point.y * height) }
        canvas.drawPath(path, paint)
    }
    return try {
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        target
    } catch (_: Exception) {
        bitmap.recycle()
        null
    }
}
