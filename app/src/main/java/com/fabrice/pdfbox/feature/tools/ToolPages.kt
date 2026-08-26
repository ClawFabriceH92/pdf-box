package com.fabrice.pdfbox.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.AnnotationType
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.pdf.AnnotationExport
import com.fabrice.pdfbox.core.pdf.Anchor
import com.fabrice.pdfbox.core.pdf.ImageIo
import com.fabrice.pdfbox.core.pdf.PageOps
import com.fabrice.pdfbox.core.pdf.Redaction
import com.fabrice.pdfbox.core.pdf.Watermark
import com.fabrice.pdfbox.core.pdf.WatermarkSpec
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.HighlightColors
import com.fabrice.pdfbox.feature.common.LabeledSlider
import com.fabrice.pdfbox.feature.common.PageRangeField
import com.fabrice.pdfbox.core.util.PageRanges
import com.fabrice.pdfbox.feature.reader.ReaderViewModel
import kotlin.math.roundToInt

/** Coque commune : titre, explication, contenu défilant, deux boutons. */
@Composable
internal fun ToolDialog(
    title: String,
    explanation: String? = null,
    confirmLabel: String = "Lancer",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (explanation != null) {
                    Text(
                        explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ------------------------------------------------------------------ A6 fusion

@Composable
fun MergeDialog(app: AppViewModel, allDocs: List<Doc>, onClose: () -> Unit) {
    val context = LocalContext.current
    val candidates = remember(allDocs) { allDocs.filter { it.pageCount > 0 && !it.encrypted } }
    val order = remember { mutableStateListOf<Long>() }

    ToolDialog(
        title = "Fusionner",
        explanation = "Cochez les documents, puis réglez leur ordre avec les flèches. " +
            "Le résultat est un nouveau PDF ; les originaux ne bougent pas.",
        confirmLabel = "Fusionner (${order.size})",
        confirmEnabled = order.size >= 2,
        onConfirm = {
            val files = order.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
            val title = "Fusion de ${files.size} documents"
            app.runTool(
                context = context,
                label = "Fusion",
                adoptTitle = title,
                work = { progress ->
                    val sources = files.map { sourceOf(context, it) }
                    PageOps.merge(sources, outputFile(context, null, "fusion"), progress)
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        if (candidates.isEmpty()) {
            Text("Aucun document fusionnable dans la bibliothèque.")
            return@ToolDialog
        }
        order.forEachIndexed { index, id ->
            val doc = candidates.firstOrNull { it.id == id } ?: return@forEachIndexed
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}.", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    doc.title, maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = index > 0,
                    onClick = { val v = order.removeAt(index); order.add(index - 1, v) }
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter") }
                IconButton(
                    enabled = index < order.lastIndex,
                    onClick = { val v = order.removeAt(index); order.add(index + 1, v) }
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre") }
                IconButton(onClick = { order.removeAt(index) }) {
                    Icon(Icons.Default.Clear, contentDescription = "Retirer")
                }
            }
        }
        if (order.isNotEmpty()) {
            Text(
                "Total : ${order.mapNotNull { id -> candidates.firstOrNull { it.id == id } }.sumOf { it.pageCount }} pages",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
        }
        candidates.forEach { doc ->
            val selected = order.contains(doc.id)
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (selected) order.remove(doc.id) else order.add(doc.id)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "${doc.pageCount} p. · ${formatSize(doc.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------- A4 / A5 / A7 : pages, rotation

enum class PageToolMode(val title: String, val verb: String) {
    EXTRACT("Extraire des pages", "Extraire"),
    DELETE("Supprimer des pages", "Supprimer"),
    ROTATE("Pivoter des pages", "Pivoter")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageToolDialog(app: AppViewModel, doc: Doc?, mode: PageToolMode, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var range by remember { mutableStateOf("") }
    var degrees by remember { mutableIntStateOf(90) }
    val pages = PageRanges.parse(range, doc.pageCount)

    ToolDialog(
        title = mode.title,
        explanation = when (mode) {
            PageToolMode.EXTRACT -> "L'ordre saisi est l'ordre du résultat : « 5, 1-3 » produit un PDF " +
                "commençant par la page 5."
            PageToolMode.DELETE -> "Les pages listées sont retirées ; il doit en rester au moins une."
            PageToolMode.ROTATE -> "La rotation s'ajoute à celle déjà enregistrée dans la page."
        },
        confirmLabel = mode.verb,
        confirmEnabled = pages.isNotEmpty() || (mode == PageToolMode.ROTATE && range.isBlank()),
        onConfirm = {
            val suffix = when (mode) {
                PageToolMode.EXTRACT -> "extrait"
                PageToolMode.DELETE -> "pages retirées"
                PageToolMode.ROTATE -> "pivoté"
            }
            app.runTool(
                context = context,
                label = mode.title,
                adoptTitle = "${doc.title} ($suffix)",
                tag = doc.tag,
                work = {
                    val source = sourceOf(context, doc)
                    val target = outputFile(context, doc, suffix)
                    when (mode) {
                        PageToolMode.EXTRACT -> PageOps.extract(source, pages, target)
                        PageToolMode.DELETE -> PageOps.deletePages(source, pages.toSet(), target)
                        PageToolMode.ROTATE -> PageOps.rotate(source, pages.toSet(), degrees, target)
                    }
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        PageRangeField(value = range, pageCount = doc.pageCount, onValueChange = { range = it })
        if (mode == PageToolMode.ROTATE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90, 180, 270).forEach { value ->
                    FilterChip(
                        selected = degrees == value,
                        onClick = { degrees = value },
                        label = { Text("$value°") }
                    )
                }
            }
            Text(
                "Vide = toutes les pages.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --------------------------------------------------------------- A9 réordonner

@Composable
fun ReorderDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    val order = remember(doc.id) { mutableStateListOf<Int>().apply { addAll(0 until doc.pageCount) } }

    ToolDialog(
        title = "Déplacer des pages",
        explanation = "Remontez ou descendez les pages ; le nouveau PDF suit cet ordre. " +
            "Le contenu de chaque page est intact.",
        confirmLabel = "Enregistrer l'ordre",
        confirmEnabled = order.toList() != (0 until doc.pageCount).toList(),
        onConfirm = {
            val target = order.toList()
            app.runTool(
                context = context,
                label = "Réordonnancement",
                adoptTitle = "${doc.title} (réordonné)",
                tag = doc.tag,
                work = {
                    PageOps.reorder(sourceOf(context, doc), target, outputFile(context, doc, "réordonné"))
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Text(
            "Ordre : " + order.joinToString(", ") { "${it + 1}" },
            style = MaterialTheme.typography.labelMedium
        )
        order.forEachIndexed { index, page ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("${page + 1}", style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Position ${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = index > 0,
                    onClick = { val v = order.removeAt(index); order.add(index - 1, v) }
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter") }
                IconButton(
                    enabled = index < order.lastIndex,
                    onClick = { val v = order.removeAt(index); order.add(index + 1, v) }
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre") }
            }
        }
    }
}

// ------------------------------------------------------------- P8F numérotation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberPagesDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var position by remember { mutableStateOf(PageOps.NumberPosition.BOTTOM_CENTER) }
    var showTotal by remember { mutableStateOf(true) }
    var skipFirst by remember { mutableStateOf(false) }
    var startAt by remember { mutableStateOf("1") }

    ToolDialog(
        title = "Numéroter les pages",
        confirmLabel = "Numéroter",
        onConfirm = {
            val start = startAt.toIntOrNull() ?: 1
            app.runTool(
                context = context,
                label = "Numérotation",
                adoptTitle = "${doc.title} (numéroté)",
                tag = doc.tag,
                work = {
                    PageOps.numberPages(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "numéroté"),
                        position = position,
                        showTotal = showTotal,
                        startAt = start,
                        skipFirst = skipFirst
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageOps.NumberPosition.entries.forEach { value ->
                FilterChip(
                    selected = position == value,
                    onClick = { position = value },
                    label = { Text(value.label) }
                )
            }
        }
        SwitchRow("Afficher le total (« p. 3/12 »)", showTotal) { showTotal = it }
        SwitchRow("Ne pas numéroter la 1re page", skipFirst) { skipFirst = it }
        OutlinedTextField(
            value = startAt,
            onValueChange = { startAt = it.filter(Char::isDigit).take(4) },
            label = { Text("Commencer à") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- A10 filigrane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var text by remember { mutableStateOf("CONFIDENTIEL") }
    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var anchor by remember { mutableStateOf(Anchor.CENTER) }
    var rotation by remember { mutableFloatStateOf(45f) }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    var scale by remember { mutableFloatStateOf(0.6f) }
    var tiled by remember { mutableStateOf(false) }
    var colorIndex by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("") }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    val pages = PageRanges.parse(range, doc.pageCount)

    ToolDialog(
        title = "Filigrane",
        explanation = "Texte ou logo, posé sur les pages choisies. Le résultat est un nouveau PDF.",
        confirmLabel = "Appliquer",
        confirmEnabled = text.isNotBlank() || imageUri != null,
        onConfirm = {
            val chosenColor = HighlightColors.getOrElse(colorIndex) { "Gris" to Color(0xFF9E9E9E) }.second
            val uri = imageUri
            app.runTool(
                context = context,
                label = "Filigrane",
                adoptTitle = "${doc.title} (filigrane)",
                tag = doc.tag,
                work = {
                    val bitmap = uri?.let { ImageIo.decodeOriented(context, it, 1600) }
                    Watermark.apply(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "filigrane"),
                        spec = WatermarkSpec(
                            text = if (bitmap != null) null else text,
                            image = bitmap,
                            anchor = anchor,
                            rotationDegrees = rotation,
                            opacity = opacity,
                            scale = scale,
                            colorArgb = chosenColor.toArgb(),
                            tiled = tiled,
                            pages = pages.toSet()
                        )
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Texte") },
            singleLine = true,
            enabled = imageUri == null,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { imagePicker.launch("image/*") }) {
                Text(if (imageUri == null) "Choisir un logo…" else "Changer de logo")
            }
            if (imageUri != null) {
                TextButton(onClick = { imageUri = null }) { Text("Retirer") }
            }
        }
        if (imageUri != null) {
            Text(
                "Logo sélectionné : le texte est ignoré.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (imageUri == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColors.forEachIndexed { index, pair ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .background(pair.second, RoundedCornerShape(6.dp))
                            .clickable { colorIndex = index }
                            .then(
                                if (colorIndex == index) Modifier.padding(2.dp) else Modifier
                            )
                    )
                }
            }
        }
        LabeledSlider("Rotation", rotation, -90f..90f, format = { "${it.roundToInt()}°" }) { rotation = it }
        LabeledSlider("Opacité", opacity, 0.05f..1f, format = { "${(it * 100).roundToInt()} %" }) { opacity = it }
        LabeledSlider("Taille", scale, 0.15f..1f, format = { "${(it * 100).roundToInt()} % de la largeur" }) { scale = it }
        SwitchRow("Répéter en mosaïque", tiled) { tiled = it }
        Column {
            Text("Position", style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Anchor.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(
                                selected = anchor == value,
                                onClick = { anchor = value },
                                label = { Text(value.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }
        PageRangeField(value = range, pageCount = doc.pageCount, onValueChange = { range = it })
    }
}

// -------------------------------------------------------------- P1F masquage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactDialog(app: AppViewModel, reader: ReaderViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    val zones = reader.annotations.filter { it.type == AnnotationType.REDACT }
    var mode by remember { mutableStateOf(Redaction.Mode.FLATTEN) }

    ToolDialog(
        title = "Masquer des zones",
        explanation = if (zones.isEmpty())
            "Aucune zone définie. Dans le lecteur, choisissez le mode « Masquer » et " +
                "encadrez ce qui doit disparaître, puis revenez ici."
        else "${zones.size} zone(s) sur ${zones.map { it.page }.distinct().size} page(s).",
        confirmLabel = "Exporter masqué",
        confirmEnabled = zones.isNotEmpty(),
        onConfirm = {
            val chosen = mode
            app.runTool(
                context = context,
                label = "Masquage",
                adoptTitle = "${doc.title} (masqué)",
                tag = doc.tag,
                work = {
                    Redaction.apply(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "masqué"),
                        boxes = zones,
                        mode = chosen
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Redaction.Mode.entries.forEach { value ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { mode = value }
                    .background(
                        if (mode == value) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(value.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    value.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------- A3 export des annotations

@Composable
fun AnnotatedExportDialog(app: AppViewModel, reader: ReaderViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    val annotations = reader.annotations.filter { it.type != AnnotationType.REDACT }
    var includeRedactions by remember { mutableStateOf(false) }
    val redactions = reader.annotations.filter { it.type == AnnotationType.REDACT }

    ToolDialog(
        title = "Exporter annoté",
        explanation = "Les surlignages sont dessinés dans la page (visibles partout), " +
            "les notes deviennent de vrais pense-bêtes PDF, la signature est incrustée.",
        confirmLabel = "Exporter",
        confirmEnabled = annotations.isNotEmpty() || (includeRedactions && redactions.isNotEmpty()),
        onConfirm = {
            val all = if (includeRedactions) annotations + redactions else annotations
            val withRedactions = includeRedactions
            app.runTool(
                context = context,
                label = "Export annoté",
                adoptTitle = "${doc.title} (annoté)",
                tag = doc.tag,
                work = {
                    AnnotationExport.apply(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "annoté"),
                        annotations = all,
                        includeRedactions = withRedactions
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Text(
            "${annotations.count { it.type == AnnotationType.HIGHLIGHT }} surlignage(s), " +
                "${annotations.count { it.type == AnnotationType.NOTE }} note(s), " +
                "${annotations.count { it.type == AnnotationType.SIGNATURE }} signature(s).",
            style = MaterialTheme.typography.bodyMedium
        )
        if (redactions.isNotEmpty()) {
            SwitchRow(
                "Inclure les ${redactions.size} zone(s) à masquer (rectangle noir seulement)",
                includeRedactions
            ) { includeRedactions = it }
            Text(
                "Pour un masquage qui retire vraiment le texte, utilisez « Masquer des zones ».",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun SignInfoDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Signer") },
        text = {
            Text(
                "Ouvrez le document dans le lecteur, choisissez le mode « Signer », " +
                    "touchez l'endroit où poser la signature et tracez-la.\n\n" +
                    "Il s'agit d'une signature manuscrite incrustée dans la page, non d'une " +
                    "signature électronique qualifiée : elle n'a pas de valeur probante au " +
                    "sens du règlement eIDAS."
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Compris") } }
    )
}
