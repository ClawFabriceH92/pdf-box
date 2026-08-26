package com.fabrice.pdfbox.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Annot
import com.fabrice.pdfbox.core.data.AnnotationType
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.EmptyState
import com.fabrice.pdfbox.feature.common.HighlightColors
import com.fabrice.pdfbox.feature.common.LoadingBox
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    app: AppViewModel,
    reader: ReaderViewModel,
    doc: Doc?,
    onGoToLibrary: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(doc?.id) {
        if (doc != null) reader.open(context, doc) else reader.close()
    }

    if (doc == null) {
        EmptyState(
            title = "Aucun document ouvert",
            message = "Choisissez un document dans la bibliothèque, ou importez-en un.",
            actionLabel = "Ouvrir la bibliothèque",
            onAction = onGoToLibrary
        )
        return
    }

    if (reader.needsPassword) {
        PasswordPrompt(
            title = doc.title,
            error = reader.error,
            onSubmit = { reader.unlock(context, it) },
            onCancel = onGoToLibrary
        )
        return
    }

    if (reader.loading) {
        LoadingBox("Ouverture de « ${doc.title} »…")
        return
    }

    reader.error?.let { message ->
        EmptyState(
            title = "Document illisible",
            message = message,
            actionLabel = "Retour à la bibliothèque",
            onAction = { reader.clearError(); onGoToLibrary() }
        )
        return
    }

    var searchVisible by remember(doc.id) { mutableStateOf(false) }
    var noteTarget by remember(doc.id) { mutableStateOf<Pair<Int, Offset>?>(null) }
    var signatureTarget by remember(doc.id) { mutableStateOf<Pair<Int, Offset>?>(null) }

    var scale by remember(doc.id) { mutableFloatStateOf(1f) }
    var offset by remember(doc.id) { mutableStateOf(Offset.Zero) }
    var containerWidth by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val annotationsByPage = remember(reader.annotations) { reader.annotations.groupBy { it.page } }
    val hitsByPage = remember(reader.searchHits) { reader.searchHits.groupBy { it.page } }
    val activeHit = reader.searchHits.getOrNull(reader.searchIndex)

    LaunchedEffect(reader.currentPage) {
        if (reader.currentPage in 0 until reader.pageCount) {
            listState.animateScrollToItem(reader.currentPage)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!reader.fullscreen) {
            ReaderToolbar(
                doc = doc,
                reader = reader,
                searchVisible = searchVisible,
                onToggleSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) reader.clearSearch()
                },
                onResetZoom = { scale = 1f; offset = Offset.Zero }
            )
            if (searchVisible) {
                SearchRow(reader = reader, onClose = { searchVisible = false; reader.clearSearch() })
            }
            ModeRow(reader)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { containerWidth = it.width }
                // Le zoom n'intercepte que les gestes à deux doigts, en passe
                // « Initial » : un doigt continue de faire défiler la liste des
                // pages, ce qui reste le geste le plus fréquent.
                .pointerInput(doc.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    val next = (scale * zoom).coerceIn(1f, 6f)
                                    scale = next
                                    offset = if (next <= 1.001f) Offset.Zero else offset + pan
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(reader.pageCount) { index ->
                    val ratio = reader.pageRatios.getOrElse(index) { 1.414f }
                    PdfPageView(
                        reader = reader,
                        index = index,
                        ratio = ratio,
                        widthPx = ((containerWidth.takeIf { it > 0 } ?: 1080) * scale).roundToInt(),
                        annotations = annotationsByPage[index].orEmpty(),
                        searchHits = hitsByPage[index].orEmpty(),
                        activeHit = activeHit,
                        mode = reader.mode,
                        highlightColor = reader.highlightColor,
                        onRectDrawn = { rect ->
                            handleRect(reader, scope, doc.id, index, rect) { page, offsetIn ->
                                signatureTarget = page to offsetIn
                            }
                        },
                        onTap = { x, y ->
                            when (reader.mode) {
                                ReaderMode.NOTE -> noteTarget = index to Offset(x, y)
                                ReaderMode.SIGN -> signatureTarget = index to Offset(x, y)
                                ReaderMode.HIGHLIGHT -> scope.launch {
                                    val boxes = reader.selectionBetween(index, x to y, x to y)
                                    boxes.forEach { box ->
                                        reader.addAnnotation(
                                            Annot(
                                                docId = doc.id, page = index,
                                                type = AnnotationType.HIGHLIGHT,
                                                x0 = box.left, y0 = box.top,
                                                x1 = box.right, y1 = box.bottom,
                                                color = reader.highlightColor,
                                                text = box.text
                                            )
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        },
                        modifier = Modifier.border(1.dp, Color(0x22000000))
                    )
                }
            }

            // `firstVisibleItemIndex` change à chaque pixel de défilement :
            // le lire directement recomposerait tout le lecteur en continu.
            val visiblePage by remember {
                derivedStateOf { listState.firstVisibleItemIndex + 1 }
            }
            PageBadge(
                current = visiblePage,
                total = reader.pageCount,
                scale = scale,
                fullscreen = reader.fullscreen,
                onToggleFullscreen = { reader.fullscreen = !reader.fullscreen },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            )
        }

        if (reader.showThumbnails && !reader.fullscreen) {
            ThumbnailStrip(reader) { page -> reader.currentPage = page }
        }
    }

    noteTarget?.let { (page, position) ->
        NoteDialog(
            onDismiss = { noteTarget = null },
            onConfirm = { text ->
                reader.addAnnotation(
                    Annot(
                        docId = doc.id, page = page, type = AnnotationType.NOTE,
                        x0 = position.x, y0 = position.y,
                        x1 = position.x + 0.03f, y1 = position.y + 0.03f,
                        color = reader.highlightColor, text = text
                    )
                )
                noteTarget = null
            }
        )
    }

    signatureTarget?.let { (page, position) ->
        val target = remember(page, position) {
            File(Storage.workDir(context), "signature-${System.nanoTime()}.png")
        }
        SignatureDialog(
            targetFile = target,
            onDismiss = { signatureTarget = null },
            onSigned = { file ->
                val width = 0.34f
                val height = width * 0.4f
                reader.addAnnotation(
                    Annot(
                        docId = doc.id, page = page, type = AnnotationType.SIGNATURE,
                        x0 = position.x.coerceIn(0f, 1f - width),
                        y0 = position.y.coerceIn(0f, 1f - height),
                        x1 = (position.x + width).coerceAtMost(1f),
                        y1 = (position.y + height).coerceAtMost(1f),
                        payload = file.absolutePath,
                        text = "Signature"
                    )
                )
                signatureTarget = null
                app.say("Signature posée. « Exporter annoté » l'inscrira dans le PDF.")
            }
        )
    }
}

private fun handleRect(
    reader: ReaderViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    docId: Long,
    page: Int,
    rect: NormRect,
    onSignature: (Int, Offset) -> Unit
) {
    when (reader.mode) {
        ReaderMode.HIGHLIGHT -> scope.launch {
            // On tente d'abord de coller aux mots : un surlignage qui épouse le
            // texte vaut mieux qu'un rectangle approximatif. Sans couche texte
            // (document scanné), on retombe sur le rectangle tracé.
            val boxes = reader.selectionBetween(
                page,
                rect.left to (rect.top + rect.bottom) / 2f,
                rect.right to (rect.top + rect.bottom) / 2f
            ).filter { it.top < rect.bottom && it.bottom > rect.top }
            val toAdd = if (boxes.isNotEmpty()) {
                boxes.map { Annot(
                    docId = docId, page = page, type = AnnotationType.HIGHLIGHT,
                    x0 = it.left, y0 = it.top, x1 = it.right, y1 = it.bottom,
                    color = reader.highlightColor, text = it.text
                ) }
            } else {
                listOf(Annot(
                    docId = docId, page = page, type = AnnotationType.HIGHLIGHT,
                    x0 = rect.left, y0 = rect.top, x1 = rect.right, y1 = rect.bottom,
                    color = reader.highlightColor
                ))
            }
            toAdd.forEach { reader.addAnnotation(it) }
        }
        ReaderMode.REDACT -> reader.addAnnotation(
            Annot(
                docId = docId, page = page, type = AnnotationType.REDACT,
                x0 = rect.left, y0 = rect.top, x1 = rect.right, y1 = rect.bottom,
                color = 0xFF000000.toInt()
            )
        )
        ReaderMode.SIGN -> onSignature(page, Offset(rect.left, rect.top))
        else -> Unit
    }
}

@Composable
private fun ReaderToolbar(
    doc: Doc,
    reader: ReaderViewModel,
    searchVisible: Boolean,
    onToggleSearch: () -> Unit,
    onResetZoom: () -> Unit
) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "${reader.pageCount} page(s)" +
                        (if (reader.annotations.isNotEmpty()) " · ${reader.annotations.size} annotation(s)" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { reader.showThumbnails = !reader.showThumbnails }) {
                Icon(Icons.Default.List, contentDescription = "Miniatures")
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchVisible) Icons.Default.Clear else Icons.Default.Search,
                    contentDescription = "Rechercher dans le document"
                )
            }
            IconButton(onClick = {
                onResetZoom()
                reader.sourceFile?.let { file ->
                    Sharing.shareFile(context, file, "application/pdf", "Partager le PDF")
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = "Partager")
            }
        }
    }
}

@Composable
private fun SearchRow(reader: ReaderViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(reader.searchQuery) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                reader.search(context, it)
            },
            placeholder = { Text("Rechercher dans le document") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            supportingText = {
                Text(
                    when {
                        reader.searching -> "Recherche…"
                        text.length < 2 -> "Au moins deux caractères"
                        reader.searchHits.isEmpty() -> "Aucune occurrence"
                        else -> "${reader.searchIndex + 1} / ${reader.searchHits.size}"
                    }
                )
            }
        )
        IconButton(onClick = { reader.previousHit() }, enabled = reader.searchHits.isNotEmpty()) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Occurrence précédente")
        }
        IconButton(onClick = { reader.nextHit() }, enabled = reader.searchHits.isNotEmpty()) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Occurrence suivante")
        }
        TextButton(
            onClick = { reader.highlightAllHits() },
            enabled = reader.searchHits.isNotEmpty()
        ) { Text("Surligner") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeRow(reader: ReaderViewModel) {
    Column {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ReaderMode.entries.size) { index ->
                val mode = ReaderMode.entries[index]
                FilterChip(
                    selected = reader.mode == mode,
                    onClick = { reader.mode = mode },
                    label = { Text(mode.label) }
                )
            }
        }
        if (reader.mode == ReaderMode.HIGHLIGHT || reader.mode == ReaderMode.NOTE) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(HighlightColors.size) { index ->
                    val color = HighlightColors[index].second
                    val argb = color.toArgb()
                    val selected = reader.highlightColor == argb
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(color, RoundedCornerShape(8.dp))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { reader.highlightColor = argb }
                    )
                }
            }
        }
        if (reader.mode != ReaderMode.READ) {
            Text(
                when (reader.mode) {
                    ReaderMode.HIGHLIGHT -> "Touchez un mot ou glissez sur une ligne pour surligner."
                    ReaderMode.NOTE -> "Touchez l'endroit où poser la note."
                    ReaderMode.REDACT -> "Encadrez la zone à masquer, puis passez par Outils ▸ Masquer pour exporter."
                    ReaderMode.SIGN -> "Touchez l'endroit où poser la signature."
                    ReaderMode.READ -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PageBadge(
    current: Int,
    total: Int,
    scale: Float,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        modifier = modifier.clickable(onClick = onToggleFullscreen)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$current / $total" + if (scale > 1.05f) "  ×${"%.1f".format(scale)}" else "",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (fullscreen) "Quitter le plein écran" else "Plein écran",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ThumbnailStrip(reader: ReaderViewModel, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        LazyRow(
            Modifier.fillMaxWidth().height(126.dp).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(reader.pageCount) { index ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .width(74.dp)
                            .height(96.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                            .border(
                                if (reader.currentPage == index) 2.dp else 1.dp,
                                if (reader.currentPage == index) MaterialTheme.colorScheme.primary
                                else Color(0x33000000),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onSelect(index) }
                    ) {
                        var thumb by remember(index) {
                            mutableStateOf<android.graphics.Bitmap?>(null)
                        }
                        LaunchedEffect(index) { thumb = reader.bitmap(index, 256) }
                        thumb?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Texte de la note") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text("Ajouter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun PasswordPrompt(
    title: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("« $title » est protégé", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Saisissez le mot de passe d'ouverture. Il n'est ni enregistré ni transmis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            isError = error != null,
            supportingText = { if (error != null) Text(error) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) { Text("Annuler") }
            Button(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) {
                Text("Ouvrir")
            }
        }
    }
}

/** Écran Annotations (onglet 3) : la liste de tout ce qui a été posé. */
@Composable
fun AnnotationsScreen(
    app: AppViewModel,
    reader: ReaderViewModel,
    doc: Doc?,
    onGoToReader: () -> Unit
) {
    if (doc == null) {
        EmptyState(
            title = "Aucun document ouvert",
            message = "Les annotations s'affichent pour le document en cours de lecture.",
            actionLabel = "Aller au lecteur",
            onAction = onGoToReader
        )
        return
    }
    val annotations = reader.annotations
    if (annotations.isEmpty()) {
        EmptyState(
            title = "Aucune annotation",
            message = "Dans le lecteur, choisissez « Surligner », « Note », « Masquer » ou « Signer », " +
                "puis touchez la page.",
            actionLabel = "Aller au lecteur",
            onAction = onGoToReader
        )
        return
    }

    var editing by remember { mutableStateOf<Annot?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(annotations.size) { index ->
            val annot = annotations[index]
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    reader.currentPage = annot.page
                    onGoToReader()
                }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(Color(annot.color), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${labelOf(annot.type)} — page ${annot.page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            annot.text?.takeIf { it.isNotBlank() } ?: "(zone sans texte)",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3
                        )
                    }
                    if (annot.type == AnnotationType.NOTE) {
                        IconButton(onClick = { editing = annot }) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifier")
                        }
                    }
                    IconButton(onClick = { reader.deleteAnnotation(annot.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { reader.clearAnnotations(); app.say("Annotations effacées.") }) {
                Text("Tout effacer")
            }
        }
    }

    editing?.let { annot ->
        var text by remember(annot.id) { mutableStateOf(annot.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier la note") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    reader.updateNote(annot.id, text.trim())
                    editing = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } }
        )
    }
}

private fun labelOf(type: AnnotationType): String = when (type) {
    AnnotationType.HIGHLIGHT -> "Surlignage"
    AnnotationType.NOTE -> "Note"
    AnnotationType.REDACT -> "Zone à masquer"
    AnnotationType.SIGNATURE -> "Signature"
}
