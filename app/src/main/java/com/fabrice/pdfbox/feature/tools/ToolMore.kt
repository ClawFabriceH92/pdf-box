package com.fabrice.pdfbox.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.Library
import com.fabrice.pdfbox.core.data.TextEntry
import com.fabrice.pdfbox.core.ocr.InvoiceFields
import com.fabrice.pdfbox.core.ocr.InvoiceParser
import com.fabrice.pdfbox.core.ocr.Ocr
import com.fabrice.pdfbox.core.pdf.Compress
import com.fabrice.pdfbox.core.pdf.Forms
import com.fabrice.pdfbox.core.pdf.ImageIo
import com.fabrice.pdfbox.core.pdf.PdfText
import com.fabrice.pdfbox.core.pdf.Printing
import com.fabrice.pdfbox.core.pdf.SearchablePdf
import com.fabrice.pdfbox.core.pdf.Security
import com.fabrice.pdfbox.core.pdf.TableExtractor
import com.fabrice.pdfbox.core.pdf.Watermark
import com.fabrice.pdfbox.core.pdf.WatermarkSpec
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.formatAmount
import com.fabrice.pdfbox.core.util.formatDateTime
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.util.sanitizeFileName
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.InfoRow
import com.fabrice.pdfbox.feature.common.LabeledSlider
import com.fabrice.pdfbox.feature.common.PageRangeField
import com.fabrice.pdfbox.core.util.PageRanges
import kotlin.math.roundToInt

// ------------------------------------------------------------- P4F compression

@Composable
fun CompressDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var profile by remember { mutableStateOf(Compress.Profile.STANDARD) }

    ToolDialog(
        title = "Compresser",
        explanation = "Taille actuelle : ${formatSize(doc.sizeBytes)}.",
        confirmLabel = "Compresser",
        onConfirm = {
            val chosen = profile
            app.runTool(
                context = context,
                label = "Compression",
                adoptTitle = "${doc.title} (compressé)",
                tag = doc.tag,
                work = { progress ->
                    val result = Compress.compress(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "compressé"),
                        profile = chosen,
                        progress = progress
                    )
                    result.file
                },
                onDone = { file ->
                    val ratio = if (doc.sizeBytes > 0) file.length() * 100 / doc.sizeBytes else 100
                    if (file.length() >= doc.sizeBytes) {
                        app.say(
                            "Le fichier n'a pas pu être allégé (${formatSize(file.length())}) : " +
                                "il est déjà optimisé. L'original reste le meilleur choix."
                        )
                    } else {
                        app.say("Réduit à $ratio % — ${formatSize(file.length())}.")
                    }
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Compress.Profile.entries.forEach { value ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { profile = value }
                    .background(
                        if (profile == value) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
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

// ---------------------------------------------------------- P5F mot de passe

@Composable
fun PasswordDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf(Security.Strength.AES_256) }
    var allowPrint by remember { mutableStateOf(true) }
    var allowCopy by remember { mutableStateOf(false) }

    val mismatch = confirm.isNotEmpty() && confirm != password

    ToolDialog(
        title = "Protéger par mot de passe",
        explanation = "Le mot de passe n'est enregistré nulle part : perdu, le document " +
            "est définitivement inaccessible, y compris pour vous.",
        confirmLabel = "Protéger",
        confirmEnabled = password.length >= 4 && password == confirm,
        onConfirm = {
            val pass = password
            val chosen = strength
            val permissions = Security.Permissions(
                canPrint = allowPrint,
                canExtractContent = allowCopy
            )
            app.runTool(
                context = context,
                label = "Protection",
                adoptTitle = "${doc.title} (protégé)",
                tag = doc.tag,
                work = {
                    Security.protect(
                        source = sourceOf(context, doc),
                        target = outputFile(context, doc, "protégé"),
                        userPassword = pass,
                        strength = chosen,
                        permissions = permissions
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Au moins 4 caractères ; 12 recommandés.") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirmer") },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { if (mismatch) Text("Les deux saisies diffèrent.") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Security.Strength.entries.forEach { value ->
                FilterChip(
                    selected = strength == value,
                    onClick = { strength = value },
                    label = { Text(value.label) }
                )
            }
        }
        SwitchRow("Autoriser l'impression", allowPrint) { allowPrint = it }
        SwitchRow("Autoriser la copie du texte", allowCopy) { allowCopy = it }
    }
}

@Composable
fun UnlockDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var password by remember { mutableStateOf("") }

    ToolDialog(
        title = "Retirer le mot de passe",
        explanation = "Produit une copie non chiffrée, à condition de connaître le mot de passe.",
        confirmLabel = "Déverrouiller",
        confirmEnabled = password.isNotEmpty(),
        onConfirm = {
            val pass = password
            app.runTool(
                context = context,
                label = "Déverrouillage",
                adoptTitle = "${doc.title} (déverrouillé)",
                tag = doc.tag,
                work = {
                    Security.unlock(
                        sourceOf(context, doc),
                        outputFile(context, doc, "déverrouillé"),
                        pass
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe actuel") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ------------------------------------------------------------------ P6F images

@Composable
fun ToImageDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var range by remember { mutableStateOf("1") }
    var dpi by remember { mutableStateOf(200f) }
    var format by remember { mutableStateOf(ImageIo.Format.PNG) }
    val pages = PageRanges.parse(range, doc.pageCount)

    ToolDialog(
        title = "PDF → image",
        confirmLabel = "Exporter",
        confirmEnabled = pages.isNotEmpty(),
        onConfirm = {
            val chosen = format
            val resolution = dpi.roundToInt()
            app.runTool(
                context = context,
                label = "Export image",
                work = { progress ->
                    val files = ImageIo.pagesToImages(
                        context = context,
                        source = sourceOf(context, doc),
                        pages = pages,
                        dpi = resolution,
                        format = chosen,
                        baseName = sanitizeFileName(doc.title),
                        progress = progress
                    )
                    Sharing.shareFiles(context, files, chosen.mime, "Partager les images")
                    files.first()
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        PageRangeField(value = range, pageCount = doc.pageCount, onValueChange = { range = it })
        LabeledSlider("Résolution", dpi, 72f..400f, format = { "${it.roundToInt()} dpi" }) { dpi = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageIo.Format.entries.forEach { value ->
                FilterChip(
                    selected = format == value,
                    onClick = { format = value },
                    label = { Text(value.label) }
                )
            }
        }
    }
}

@Composable
fun FromImagesDialog(app: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val picked = remember { mutableStateListOf<android.net.Uri>() }
    var quality by remember { mutableStateOf(0.85f) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> picked.addAll(uris) }

    // `TakePicturePreview` ne rend qu'une vignette : illisible pour un reçu.
    // On passe donc par un fichier et une URI FileProvider, seule voie vers la
    // pleine résolution du capteur.
    var pendingPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken -> if (taken) pendingPhoto?.let { picked.add(it) } }

    ToolDialog(
        title = "Images → PDF",
        explanation = "Une image par page, centrée sur un A4. L'orientation des photos est " +
            "corrigée d'après leurs métadonnées EXIF.",
        confirmLabel = "Créer le PDF (${picked.size})",
        confirmEnabled = picked.isNotEmpty(),
        onConfirm = {
            val images = picked.toList()
            val jpeg = quality
            app.runTool(
                context = context,
                label = "Images → PDF",
                adoptTitle = "Document ${formatDateTime(System.currentTimeMillis())}",
                work = { progress ->
                    ImageIo.imagesToPdf(
                        context = context,
                        images = images,
                        target = Storage.uniqueFile(Storage.exportsDir(context), "images.pdf"),
                        jpegQuality = jpeg,
                        progress = progress
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choisir des images") }
            TextButton(onClick = {
                val file = Storage.uniqueFile(Storage.workDir(context), "photo-${System.nanoTime()}.jpg")
                val uri = Sharing.uriFor(context, file)
                pendingPhoto = uri
                camera.launch(uri)
            }) { Text("Photographier") }
        }
        if (picked.isEmpty()) {
            Text("Aucune image sélectionnée.", style = MaterialTheme.typography.bodySmall)
        } else {
            picked.forEachIndexed { index, uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}. ${uri.lastPathSegment?.takeLast(30) ?: "image"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { picked.removeAt(index) }) { Text("Retirer") }
                }
            }
        }
        LabeledSlider("Qualité JPEG", quality, 0.4f..1f, format = { "${(it * 100).roundToInt()} %" }) {
            quality = it
        }
    }
}

@Composable
fun PrintDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Imprimer") },
        text = {
            Text(
                "Le service d'impression d'Android prend le relais : choix de l'imprimante, " +
                    "aperçu, recto-verso. « Enregistrer au format PDF » y figure aussi."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                app.runAnalysis(
                    context = context,
                    label = "Préparation de l'impression",
                    work = { sourceOf(context, doc) },
                    onResult = { file -> Printing.print(context, file, doc.title) }
                )
                onClose()
            }) { Text("Imprimer") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } }
    )
}

// ------------------------------------------------------------------ T2 / T3 OCR

@Composable
fun OcrDialog(app: AppViewModel, doc: Doc?, searchable: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var range by remember { mutableStateOf("") }
    var dpi by remember { mutableStateOf(200f) }
    val pages = PageRanges.parse(range, doc.pageCount)

    ToolDialog(
        title = if (searchable) "Rendre recherchable" else "OCR",
        explanation = if (searchable)
            "Une couche de texte invisible est posée sur les pages : l'aspect ne change pas, " +
                "mais la recherche et le copier-coller fonctionnent, ici comme dans les autres lecteurs."
        else
            "Reconnaissance sur l'appareil, sans réseau. Comptez quelques secondes par page.",
        confirmLabel = if (searchable) "Créer le PDF recherchable" else "Lancer l'OCR",
        onConfirm = {
            val resolution = dpi.roundToInt()
            val targets = pages
            if (searchable) {
                app.runTool(
                    context = context,
                    label = "PDF recherchable",
                    adoptTitle = "${doc.title} (recherchable)",
                    tag = doc.tag,
                    work = { progress ->
                        val source = sourceOf(context, doc)
                        val ocr = Ocr.recognizeDocument(source, targets, resolution, progress)
                        val out = SearchablePdf.apply(
                            source, outputFile(context, doc, "recherchable"), ocr
                        )
                        Library.indexDocument(doc.id, ocr.map { it.text })
                        out
                    }
                )
            } else {
                app.runAnalysis(
                    context = context,
                    label = "OCR",
                    work = { progress ->
                        val source = sourceOf(context, doc)
                        val ocr = Ocr.recognizeDocument(source, targets, resolution, progress)
                        val text = ocr.joinToString("\n\n") { page ->
                            if (page.text.isBlank()) "" else "— page ${page.page + 1} —\n${page.text}"
                        }.trim()
                        Library.indexDocument(doc.id, ocr.map { it.text })
                        val file = Storage.uniqueFile(
                            Storage.exportsDir(context),
                            sanitizeFileName("${doc.title} (OCR).txt")
                        )
                        file.writeText(text)
                        Library.addTextEntry(
                            TextEntry(
                                docId = doc.id,
                                title = "${doc.title} (OCR)",
                                excerpt = text.take(180).replace('\n', ' '),
                                charCount = text.length,
                                relPath = file.name
                            )
                        )
                        text to file
                    },
                    onResult = { (text, file) ->
                        if (text.isBlank()) {
                            app.fail("Aucun texte reconnu sur les pages traitées.")
                        } else {
                            app.say("${text.length} caractères reconnus — texte prêt à partager.")
                            Sharing.shareFile(context, file, "text/plain", "Partager le texte OCR")
                        }
                    }
                )
            }
            onClose()
        },
        onDismiss = onClose
    ) {
        PageRangeField(value = range, pageCount = doc.pageCount, onValueChange = { range = it })
        LabeledSlider("Résolution du rendu", dpi, 120f..300f, format = { "${it.roundToInt()} dpi" }) {
            dpi = it
        }
        Text(
            "En dessous de 150 dpi, les petits caractères d'une facture passent mal ; " +
                "au-delà de 250, le gain est marginal et la mémoire devient limitante.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -------------------------------------------------------------- T7 tableau CSV

@Composable
fun TableDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var range by remember { mutableStateOf("") }
    var separator by remember { mutableStateOf(TableExtractor.Separator.SEMICOLON) }
    val pages = PageRanges.parse(range, doc.pageCount)

    ToolDialog(
        title = "Tableau → CSV",
        explanation = "Les colonnes sont retrouvées par la géométrie du texte : les blancs " +
            "verticaux que traversent toutes les lignes. Un tableau sans alignement régulier " +
            "ne sera pas détecté.",
        confirmLabel = "Extraire",
        onConfirm = {
            val chosen = separator
            val targets = pages.ifEmpty { (0 until doc.pageCount).toList() }
            app.runAnalysis(
                context = context,
                label = "Extraction des tableaux",
                work = { progress ->
                    progress.onProgress(0, targets.size, "analyse")
                    val tables = TableExtractor.extractFromFile(sourceOf(context, doc), targets)
                    progress.onProgress(targets.size, targets.size, "écriture")
                    val file = Storage.uniqueFile(
                        Storage.exportsDir(context),
                        sanitizeFileName("${doc.title}.csv")
                    )
                    if (tables.isNotEmpty()) TableExtractor.writeCsv(tables, chosen, file)
                    tables to file
                },
                onResult = { (tables, file) ->
                    if (tables.isEmpty()) {
                        app.fail(
                            "Aucun tableau détecté. Si le document est un scan, lancez d'abord " +
                                "l'OCR : sans couche texte il n'y a rien à aligner."
                        )
                    } else {
                        val rows = tables.sumOf { it.rowCount }
                        app.say("${tables.size} tableau(x), $rows lignes — CSV prêt.")
                        Sharing.shareFile(context, file, "text/csv", "Partager le CSV")
                    }
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        PageRangeField(value = range, pageCount = doc.pageCount, onValueChange = { range = it })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TableExtractor.Separator.entries.forEach { value ->
                FilterChip(
                    selected = separator == value,
                    onClick = { separator = value },
                    label = { Text(value.label) }
                )
            }
        }
    }
}

// ------------------------------------------------------------- P2F formulaires

@Composable
fun FormsDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var fields by remember(doc.id) { mutableStateOf<List<Forms.Field>?>(null) }
    val values = remember(doc.id) { mutableStateMapOf<String, String>() }
    var flatten by remember { mutableStateOf(true) }

    LaunchedEffect(doc.id) {
        val loaded = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Forms.read(sourceOf(context, doc))
            }
        }.getOrDefault(emptyList())
        loaded.forEach { values[it.name] = it.value }
        fields = loaded
    }

    val loaded = fields
    ToolDialog(
        title = "Formulaire",
        explanation = when {
            loaded == null -> "Lecture des champs…"
            loaded.isEmpty() -> "Ce document ne contient pas de formulaire AcroForm."
            else -> "${loaded.size} champ(s). « Figer » transforme les valeurs en contenu " +
                "définitif : plus personne ne pourra les modifier."
        },
        confirmLabel = "Enregistrer",
        confirmEnabled = !loaded.isNullOrEmpty(),
        onConfirm = {
            val snapshot = values.toMap()
            val doFlatten = flatten
            app.runTool(
                context = context,
                label = "Formulaire",
                adoptTitle = "${doc.title} (rempli)",
                tag = doc.tag,
                work = {
                    Forms.fill(
                        sourceOf(context, doc),
                        outputFile(context, doc, "rempli"),
                        snapshot,
                        doFlatten
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        loaded?.forEach { field ->
            when (field.kind) {
                Forms.FieldKind.CHECKBOX -> {
                    val checked = values[field.name]?.let { it.isNotEmpty() && it != "Off" && it != "false" } ?: false
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !field.readOnly) {
                            values[field.name] = (!checked).toString()
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !field.readOnly,
                            onCheckedChange = { values[field.name] = it.toString() }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(field.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Forms.FieldKind.CHOICE, Forms.FieldKind.RADIO -> {
                    Column {
                        Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            field.options.take(6).forEach { option ->
                                FilterChip(
                                    selected = values[field.name] == option,
                                    onClick = { values[field.name] = option },
                                    label = { Text(option, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
                Forms.FieldKind.SIGNATURE, Forms.FieldKind.BUTTON -> {
                    InfoRow(field.label, "champ non modifiable")
                }
                else -> {
                    OutlinedTextField(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values[field.name] = it },
                        label = { Text(field.label + if (field.required) " *" else "") },
                        enabled = !field.readOnly,
                        singleLine = field.kind != Forms.FieldKind.MULTILINE,
                        minLines = if (field.kind == Forms.FieldKind.MULTILINE) 2 else 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (!loaded.isNullOrEmpty()) {
            SwitchRow("Figer les valeurs (non modifiables)", flatten) { flatten = it }
        }
    }
}

// -------------------------------------------------------- P3F facture (texte)

@Composable
fun InvoiceFieldsDialog(app: AppViewModel, doc: Doc?, onClose: () -> Unit) {
    val context = LocalContext.current
    if (doc == null) return
    var parsed by remember(doc.id) { mutableStateOf<InvoiceFields?>(null) }
    var status by remember(doc.id) { mutableStateOf("Lecture du document…") }

    LaunchedEffect(doc.id) {
        val text = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PdfText.extractAll(sourceOf(context, doc)).joinToString("\n")
            }
        }.getOrDefault("")
        if (text.isBlank()) {
            status = "Pas de couche texte : lancez l'OCR, puis revenez ici."
        } else {
            parsed = InvoiceParser.parse(text)
            status = ""
        }
    }

    val fields = parsed
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Champs de la facture") },
        text = {
            Column(
                Modifier.height(390.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (fields == null) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "${fields.found} champ(s) reconnus. Les identifiants sont vérifiés " +
                            "(clé de Luhn du SIRET, clé TVA, modulo 97 de l'IBAN) : un champ " +
                            "absent vaut mieux qu'un champ faux.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Émetteur", fields.issuer ?: "—")
                    InfoRow("N° de facture", fields.number ?: "—")
                    InfoRow("Date", fields.date ?: "—")
                    InfoRow("Échéance", fields.dueDate ?: "—")
                    InfoRow("SIRET", fields.siret ?: "—")
                    InfoRow("SIREN", fields.siren ?: "—")
                    InfoRow("N° TVA", fields.vatNumber ?: "—")
                    InfoRow("IBAN", fields.iban ?: "—")
                    InfoRow("BIC", fields.bic ?: "—")
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    InfoRow("Total HT", fields.totalHt?.let { formatAmount(it) } ?: "—")
                    InfoRow("TVA", fields.totalVat?.let { formatAmount(it) } ?: "—")
                    InfoRow("Total TTC", fields.totalTtc?.let { formatAmount(it) } ?: "—")
                    if (fields.vatRates.isNotEmpty()) {
                        InfoRow("Taux relevés", fields.vatRates.joinToString(", ") { "$it %" })
                    }
                    fields.totalsConsistent?.let { consistent ->
                        Text(
                            if (consistent) "HT + TVA = TTC : cohérent."
                            else "HT + TVA ≠ TTC : vérifiez, un montant a pu être mal lu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (consistent) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (fields != null) {
                    TextButton(onClick = {
                        Sharing.shareText(context, summaryOf(doc, fields), "Facture — ${doc.title}")
                    }) { Text("Partager") }
                }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    )
}

private fun summaryOf(doc: Doc, fields: InvoiceFields): String = buildString {
    appendLine("Facture — ${doc.title}")
    fields.issuer?.let { appendLine("Émetteur : $it") }
    fields.number?.let { appendLine("N° : $it") }
    fields.date?.let { appendLine("Date : $it") }
    fields.siret?.let { appendLine("SIRET : $it") }
    fields.vatNumber?.let { appendLine("TVA : $it") }
    fields.iban?.let { appendLine("IBAN : $it") }
    fields.totalHt?.let { appendLine("Total HT : ${formatAmount(it)}") }
    fields.totalVat?.let { appendLine("TVA : ${formatAmount(it)}") }
    fields.totalTtc?.let { appendLine("Total TTC : ${formatAmount(it)}") }
    appendLine()
    append("Extrait par PDF Box — vérifiez les montants avant usage comptable.")
}

// ---------------------------------------------------------------- T5 historique

@Composable
fun HistoryDialog(app: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { app.refreshHistory() }
    val entries = app.history

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Historique des extractions") },
        text = {
            Column(Modifier.height(380.dp)) {
                if (entries.isEmpty()) {
                    Text("Aucune extraction pour l'instant.")
                } else {
                    entries.forEach { entry ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val file = java.io.File(Storage.exportsDir(context), entry.relPath)
                                    if (file.exists()) {
                                        Sharing.shareFile(context, file, "text/plain", "Partager le texte")
                                    } else {
                                        app.fail("Le fichier de cette extraction a été purgé.")
                                    }
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${entry.charCount} caractères · ${formatDateTime(entry.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                entry.excerpt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { app.clearHistory() }) { Text("Effacer") }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    )
}

// -------------------------------------------------------------------- P9F lot

@Composable
fun BatchDialog(app: AppViewModel, allDocs: List<Doc>, onClose: () -> Unit) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<Long>() }
    var compress by remember { mutableStateOf(true) }
    var watermarkText by remember { mutableStateOf("") }
    val candidates = remember(allDocs) { allDocs.filter { it.pageCount > 0 && !it.encrypted } }

    ToolDialog(
        title = "Traitement en lot",
        explanation = "La même opération sur plusieurs documents. Chacun produit un nouveau " +
            "fichier ; les originaux restent intacts.",
        confirmLabel = "Traiter (${selected.size})",
        confirmEnabled = selected.isNotEmpty() && (compress || watermarkText.isNotBlank()),
        onConfirm = {
            val docs = selected.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
            val doCompress = compress
            val mark = watermarkText.trim()
            app.runAnalysis(
                context = context,
                label = "Traitement en lot",
                work = { progress ->
                    val produced = mutableListOf<Pair<Doc, java.io.File>>()
                    docs.forEachIndexed { index, item ->
                        progress.onProgress(index, docs.size, item.title)
                        var current = sourceOf(context, item)
                        if (mark.isNotBlank()) {
                            current = Watermark.apply(
                                current,
                                outputFile(context, item, "filigrane"),
                                WatermarkSpec(text = mark)
                            )
                        }
                        if (doCompress) {
                            current = Compress.compress(
                                current,
                                outputFile(context, item, "compressé"),
                                Compress.Profile.STANDARD
                            ).file
                        }
                        produced += item to current
                    }
                    progress.onProgress(docs.size, docs.size, "enregistrement")
                    produced
                },
                onResult = { produced ->
                    app.runAnalysis(
                        context = context,
                        label = "Ajout à la bibliothèque",
                        work = {
                            produced.forEach { (item, file) ->
                                com.fabrice.pdfbox.core.data.Importer.adoptProducedFile(
                                    context, file, "${item.title} (lot)", item.tag
                                )
                            }
                            produced.size
                        },
                        onResult = { count -> app.say("$count document(s) traités et ajoutés.") }
                    )
                }
            )
            onClose()
        },
        onDismiss = onClose
    ) {
        SwitchRow("Compresser (profil standard)", compress) { compress = it }
        OutlinedTextField(
            value = watermarkText,
            onValueChange = { watermarkText = it },
            label = { Text("Filigrane (laisser vide pour aucun)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        candidates.forEach { item ->
            val isSelected = selected.contains(item.id)
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (isSelected) selected.remove(item.id) else selected.add(item.id)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = isSelected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}
