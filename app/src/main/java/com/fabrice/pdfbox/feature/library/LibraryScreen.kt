package com.fabrice.pdfbox.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.SortOrder
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.formatRelative
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.ConfirmDialog
import com.fabrice.pdfbox.feature.common.EmptyState
import com.fabrice.pdfbox.feature.common.InfoRow
import com.fabrice.pdfbox.feature.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/zip",
    "application/x-zip-compressed",
    "text/xml",
    "application/xml",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    app: AppViewModel,
    onOpenDoc: (Doc) -> Unit,
    onOpenInvoice: (Doc) -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val allDocs by app.docs.collectAsState()
    val docs = app.visibleDocs(allDocs)

    var menuFor by remember { mutableStateOf<Doc?>(null) }
    var renaming by remember { mutableStateOf<Doc?>(null) }
    var tagging by remember { mutableStateOf<Doc?>(null) }
    var deleting by remember { mutableStateOf<Doc?>(null) }
    var details by remember { mutableStateOf<Doc?>(null) }
    var showStats by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> app.importUris(context, uris, persistable = true) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(IMPORT_MIME_TYPES) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Importer") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchHeader(app)
            FilterRow(
                app = app,
                onStats = { showStats = true; app.refreshStats() },
                onDuplicates = { app.findDuplicates(context) }
            )

            if (app.duplicates.isNotEmpty()) {
                DuplicatesBanner(app)
            }

            if (docs.isEmpty()) {
                EmptyState(
                    title = if (allDocs.isEmpty()) "Bibliothèque vide" else "Aucun résultat",
                    message = if (allDocs.isEmpty())
                        "Importez un PDF, une facture .zip ou un XML. " +
                            "Vous pouvez aussi partager un fichier depuis une autre application vers PDF Box."
                    else "Aucun document ne correspond à « ${app.query} ».",
                    actionLabel = if (allDocs.isEmpty()) "Choisir un fichier" else null,
                    onAction = if (allDocs.isEmpty()) ({ picker.launch(IMPORT_MIME_TYPES) }) else null
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(docs.size) { index ->
                        val doc = docs[index]
                        DocRow(
                            doc = doc,
                            snippet = app.fullTextHits[doc.id]?.snippet,
                            onClick = {
                                app.openDoc(doc.id)
                                if (doc.pageCount == 0 && doc.hasAttachments) onOpenInvoice(doc)
                                else onOpenDoc(doc)
                            },
                            onMenu = { menuFor = doc }
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { doc ->
        DocMenu(
            doc = doc,
            onDismiss = { menuFor = null },
            onOpen = { menuFor = null; app.openDoc(doc.id); onOpenDoc(doc) },
            onInvoice = { menuFor = null; app.openDoc(doc.id); onOpenInvoice(doc) },
            onRename = { menuFor = null; renaming = doc },
            onTag = { menuFor = null; tagging = doc },
            onDetails = { menuFor = null; details = doc },
            onShare = {
                menuFor = null
                scope.launch {
                    val file = withContext(Dispatchers.IO) { PdfSource.localFile(context, doc) }
                    Sharing.shareFile(context, file, "application/pdf", "Partager « ${doc.title} »")
                }
            },
            onReindex = { menuFor = null; app.reindex(context, doc) },
            onDelete = { menuFor = null; deleting = doc }
        )
    }

    renaming?.let { doc ->
        var title by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { app.rename(doc, title); renaming = null }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } }
        )
    }

    tagging?.let { doc ->
        var tag by remember(doc.id) { mutableStateOf(doc.tag.orEmpty()) }
        AlertDialog(
            onDismissRequest = { tagging = null },
            title = { Text("Étiquette") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Une seule étiquette par document : « facture », « frais », « contrat »…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (app.tags.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(app.tags.size) { i ->
                                AssistChip(onClick = { tag = app.tags[i] }, label = { Text(app.tags[i]) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { app.setTag(doc, tag); tagging = null }) { Text("Appliquer") }
            },
            dismissButton = {
                TextButton(onClick = { app.setTag(doc, null); tagging = null }) { Text("Retirer") }
            }
        )
    }

    deleting?.let { doc ->
        ConfirmDialog(
            title = "Supprimer « ${doc.title} » ?",
            message = if (doc.managed)
                "Le fichier est stocké par PDF Box : il sera définitivement supprimé, " +
                    "avec ses annotations et ses pièces jointes. Il n'y a pas de corbeille."
            else
                "Le document sera retiré de la bibliothèque avec ses annotations. " +
                    "Le fichier d'origine, lui, restera où vous l'avez rangé.",
            confirmLabel = "Supprimer",
            destructive = true,
            onConfirm = { app.delete(doc); deleting = null },
            onDismiss = { deleting = null }
        )
    }

    details?.let { doc ->
        DocDetailsDialog(app = app, doc = doc, onDismiss = { details = null })
    }

    if (showStats) {
        StatsDialog(app = app, onDismiss = { showStats = false })
    }
}

@Composable
private fun SearchHeader(app: AppViewModel) {
    OutlinedTextField(
        value = app.query,
        onValueChange = { app.setQuery(it) },
        placeholder = { Text("Rechercher : nom, étiquette, contenu…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (app.query.isNotEmpty()) {
                IconButton(onClick = { app.setQuery("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Effacer")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(app: AppViewModel, onStats: () -> Unit, onDuplicates: () -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AssistChip(onClick = { sortMenu = true }, label = { Text("Tri : ${app.sort.label}") })
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = { app.setSort(order); sortMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(app.tags.size) { index ->
                val tag = app.tags[index]
                FilterChip(
                    selected = app.tagFilter == tag,
                    onClick = { app.setTagFilter(if (app.tagFilter == tag) null else tag) },
                    label = { Text(tag) }
                )
            }
        }
        IconButton(onClick = onDuplicates) {
            Icon(Icons.Default.Refresh, contentDescription = "Chercher les doublons")
        }
        IconButton(onClick = onStats) {
            Icon(Icons.Default.Info, contentDescription = "Statistiques")
        }
    }
}

@Composable
private fun DuplicatesBanner(app: AppViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${app.duplicates.size} groupe(s) de doublons (même empreinte SHA-256)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            app.duplicates.take(4).forEach { group ->
                Text(
                    "• " + group.joinToString(", ") { it.title } + " — " + formatSize(group.first().sizeBytes),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DocRow(doc: Doc, snippet: String?, onClick: () -> Unit, onMenu: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (doc.pageCount > 0) "${doc.pageCount}" else "XML",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(formatSize(doc.sizeBytes))
                            if (doc.pageCount > 0) append(" · ${doc.pageCount} p.")
                            append(" · ${formatRelative(doc.addedAt)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (doc.hasAttachments) Badge("XML attaché", MaterialTheme.colorScheme.tertiary)
                    if (doc.encrypted) Badge("protégé", MaterialTheme.colorScheme.error)
                    if (!doc.managed) Badge("référencé", MaterialTheme.colorScheme.outline)
                    doc.tag?.let { Badge(it, MaterialTheme.colorScheme.primary) }
                }
                if (snippet != null) {
                    Text(
                        snippet.replace('\n', ' ').take(140),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DocMenu(
    doc: Doc,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onInvoice: () -> Unit,
    onRename: () -> Unit,
    onTag: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onReindex: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(doc.title, maxLines = 2) },
        text = {
            Column {
                MenuLine("Ouvrir dans le lecteur", Icons.Default.Search, onOpen)
                if (doc.hasAttachments) MenuLine("Facture et XML attachés", Icons.Default.Info, onInvoice)
                MenuLine("Renommer", Icons.Default.Edit, onRename)
                MenuLine("Étiquette", Icons.Default.Add, onTag)
                MenuLine("Détails et métadonnées", Icons.Default.Info, onDetails)
                MenuLine("Partager le fichier", Icons.Default.Share, onShare)
                MenuLine("Réindexer le texte", Icons.Default.Refresh, onReindex)
                MenuLine("Supprimer", Icons.Default.Delete, onDelete, destructive = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun MenuLine(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatsDialog(app: AppViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { app.refreshStats() }
    val stats = app.stats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bibliothèque") },
        text = {
            if (stats == null) {
                Text("Calcul en cours…")
            } else {
                Column {
                    InfoRow("Documents", "${stats.docCount}")
                    InfoRow("Pages au total", "${stats.pageCount}")
                    InfoRow("Taille cumulée", formatSize(stats.totalBytes))
                    InfoRow("Stockés par l'application", formatSize(stats.managedBytes))
                    InfoRow("Indexés pour la recherche", "${stats.indexedCount}")
                    InfoRow("Documents annotés", "${stats.annotatedCount}")
                    InfoRow("Factures avec XML", "${stats.withXmlCount}")
                    InfoRow("Étiquettes", "${stats.tags}")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "« Stockés par l'application » compte les fichiers copiés dans " +
                            "PDF Box ; les documents référencés restent chez vous et ne " +
                            "consomment rien de plus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
