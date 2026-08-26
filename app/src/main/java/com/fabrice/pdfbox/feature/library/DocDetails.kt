package com.fabrice.pdfbox.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.Library
import com.fabrice.pdfbox.core.data.TextEntry
import com.fabrice.pdfbox.core.pdf.Metadata
import com.fabrice.pdfbox.core.pdf.PdfInfo
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.pdf.PdfText
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.formatDateTime
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.util.sanitizeFileName
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.InfoRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Écran « Métadonnées » du cahier des charges : ce que le fichier dit de
 * lui-même, ce que la bibliothèque en sait, et les deux actions qui en
 * découlent — corriger les métadonnées, extraire tout le texte.
 */
@Composable
fun DocDetailsDialog(app: AppViewModel, doc: Doc, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember(doc.id) { mutableStateOf<PdfInfo?>(null) }
    var editing by remember(doc.id) { mutableStateOf(false) }

    LaunchedEffect(doc.id) {
        info = withContext(Dispatchers.IO) {
            runCatching {
                Metadata.read(PdfSource.localFile(context, doc))
            }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.title, maxLines = 2) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                InfoRow("Taille", formatSize(doc.sizeBytes))
                InfoRow("Pages", if (doc.pageCount > 0) "${doc.pageCount}" else "—")
                InfoRow("Ajouté le", formatDateTime(doc.addedAt))
                InfoRow("Étiquette", doc.tag ?: "aucune")
                InfoRow(
                    "Emplacement",
                    if (doc.managed) "Espace privé de PDF Box" else "Dossier choisi par vous (SAF)"
                )
                InfoRow("Indexé", if (doc.indexedAt > 0) formatDateTime(doc.indexedAt) else "non")
                doc.sha256?.let { InfoRow("SHA-256", it.take(16) + "…") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                val current = info
                if (current == null) {
                    Text("Lecture des métadonnées…", style = MaterialTheme.typography.bodySmall)
                } else {
                    InfoRow("Version PDF", "%.1f".format(current.version))
                    InfoRow("Titre", current.title ?: "—")
                    InfoRow("Auteur", current.author ?: "—")
                    InfoRow("Sujet", current.subject ?: "—")
                    InfoRow("Mots-clés", current.keywords ?: "—")
                    InfoRow("Producteur", current.producer ?: "—")
                    InfoRow(
                        "Créé le",
                        if (current.creationDate > 0) formatDateTime(current.creationDate) else "—"
                    )
                    InfoRow("Chiffré", if (current.encrypted) "oui" else "non")
                    InfoRow(
                        "Formulaire",
                        if (current.hasAcroForm) "${current.fieldCount} champ(s)" else "non"
                    )
                    InfoRow(
                        "Couche texte",
                        if (current.hasExtractableText) "présente"
                        else "absente — document scanné, l'OCR est nécessaire"
                    )
                    current.pageSizes.firstOrNull()?.let { size ->
                        InfoRow(
                            "Format de la 1re page",
                            "%.0f × %.0f pt (%.0f × %.0f mm)".format(
                                size.widthPt, size.heightPt,
                                size.widthPt / 72f * 25.4f, size.heightPt / 72f * 25.4f
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    extractText(app, context, doc)
                    onDismiss()
                }) { Text("Extraire le texte") }
                TextButton(onClick = { editing = true }) { Text("Modifier") }
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        }
    )

    if (editing) {
        MetadataEditor(
            app = app,
            doc = doc,
            info = info,
            onDismiss = { editing = false; onDismiss() }
        )
    }
}

@Composable
private fun MetadataEditor(app: AppViewModel, doc: Doc, info: PdfInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(info?.title ?: doc.title) }
    var author by remember { mutableStateOf(info?.author.orEmpty()) }
    var subject by remember { mutableStateOf(info?.subject.orEmpty()) }
    var keywords by remember { mutableStateOf(info?.keywords.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Métadonnées") },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Un nouveau document est produit ; l'original n'est pas modifié.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(title, { title = it }, label = { Text("Titre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, { author = it }, label = { Text("Auteur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subject, { subject = it }, label = { Text("Sujet") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Mots-clés") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = sanitizeFileName("${doc.title} (métadonnées).pdf")
                app.runTool(
                    context = context,
                    label = "Métadonnées",
                    adoptTitle = "${doc.title} (métadonnées)",
                    tag = doc.tag,
                    work = {
                        val source = PdfSource.localFile(context, doc)
                        Metadata.write(
                            source,
                            Storage.uniqueFile(Storage.exportsDir(context), name),
                            Metadata.Edit(title, author, subject, keywords)
                        )
                    }
                )
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** T1 + T4 + T5 — extraction du texte entier, partage et historique. */
fun extractText(app: AppViewModel, context: android.content.Context, doc: Doc) {
    app.runAnalysis(
        context = context,
        label = "Extraction du texte",
        work = { progress ->
            val file = PdfSource.localFile(context, doc)
            val pages = PdfText.extractAll(file)
            progress.onProgress(pages.size, pages.size, "assemblage")
            val text = pages.mapIndexed { index, content ->
                if (content.isBlank()) "" else "— page ${index + 1} —\n$content"
            }.filter { it.isNotBlank() }.joinToString("\n\n")
            val target = Storage.uniqueFile(
                Storage.exportsDir(context),
                sanitizeFileName("${doc.title}.txt")
            )
            target.writeText(text)
            Library.indexDocument(doc.id, pages)
            Library.addTextEntry(
                TextEntry(
                    docId = doc.id,
                    title = doc.title,
                    excerpt = text.take(180).replace('\n', ' '),
                    charCount = text.length,
                    relPath = target.name
                )
            )
            text to target
        },
        onResult = { (text, file) ->
            if (text.isBlank()) {
                app.fail(
                    "Aucun texte à extraire : ce PDF est probablement un scan. " +
                        "Passez par Outils ▸ OCR."
                )
            } else {
                app.say("${text.length} caractères extraits — le fichier est prêt à partager.")
                Sharing.shareFile(context, file, "text/plain", "Partager le texte extrait")
            }
        }
    )
}
