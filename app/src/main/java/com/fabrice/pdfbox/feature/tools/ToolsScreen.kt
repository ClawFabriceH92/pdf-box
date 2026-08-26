package com.fabrice.pdfbox.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.R
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.IconTile
import com.fabrice.pdfbox.feature.common.SectionCard
import com.fabrice.pdfbox.feature.reader.ReaderViewModel

enum class Tool {
    MERGE, EXTRACT, DELETE_PAGES, REORDER, ROTATE, NUMBER,
    WATERMARK, REDACT, EXPORT_ANNOTATED, SIGN_INFO,
    COMPRESS, PASSWORD, UNLOCK, TO_IMAGE, FROM_IMAGE, PRINT,
    OCR, SEARCHABLE, TABLE_CSV, TEXT_HISTORY, FORMS, INVOICE_FIELDS, BATCH
}

/**
 * U3 — l'écran « Outils ».
 *
 * Toutes les opérations produisent un **nouveau** document : aucune n'écrase
 * l'original. C'est la seule garantie qui permet d'essayer un filigrane ou une
 * compression sans peur, sur un téléphone où il n'y a pas d'annulation.
 */
@Composable
fun ToolsScreen(
    app: AppViewModel,
    reader: ReaderViewModel,
    doc: Doc?,
    onOpenLibrary: () -> Unit
) {
    val allDocs by app.docs.collectAsState()
    var active by remember { mutableStateOf<Tool?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "Document courant",
                subtitle = if (doc == null)
                    "Aucun document sélectionné : les outils qui portent sur un document " +
                        "resteront inactifs."
                else null
            ) {
                if (doc != null) {
                    Text(doc.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        buildString {
                            append(formatSize(doc.sizeBytes))
                            if (doc.pageCount > 0) append(" · ${doc.pageCount} page(s)")
                            if (reader.annotations.isNotEmpty()) {
                                append(" · ${reader.annotations.size} annotation(s)")
                            }
                            if (doc.encrypted) append(" · protégé")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    androidx.compose.material3.TextButton(onClick = onOpenLibrary) {
                        Text("Ouvrir la bibliothèque")
                    }
                }
            }
        }

        toolSection(
            "Pages",
            listOf(
                Entry(Tool.MERGE, "Fusionner", "Assembler plusieurs PDF, ordre réglable", needsDoc = false),
                Entry(Tool.EXTRACT, "Extraire des pages", "Un nouveau PDF avec la sélection"),
                Entry(Tool.DELETE_PAGES, "Supprimer des pages", "Retirer les pages inutiles"),
                Entry(Tool.REORDER, "Déplacer des pages", "Réordonner par glissement"),
                Entry(Tool.ROTATE, "Pivoter", "Redresser des pages couchées"),
                Entry(Tool.NUMBER, "Numéroter", "Pied de page « p. 3/12 »")
            ),
            doc
        ) { active = it }

        toolSection(
            "Marquage",
            listOf(
                Entry(Tool.WATERMARK, "Filigrane", "Texte ou logo, position, rotation, opacité"),
                Entry(Tool.REDACT, "Masquer des zones", "Noircir un IBAN, un SIRET, une mention"),
                Entry(Tool.EXPORT_ANNOTATED, "Exporter annoté", "Surlignages, notes et signature dans le PDF"),
                Entry(Tool.SIGN_INFO, "Signer", "Poser une signature manuscrite")
            ),
            doc
        ) { active = it }

        toolSection(
            "Envoyer",
            listOf(
                Entry(Tool.COMPRESS, "Compresser", "Alléger avant un envoi par courriel"),
                Entry(Tool.PASSWORD, "Protéger par mot de passe", "Chiffrement AES 128 ou 256 bits"),
                Entry(Tool.UNLOCK, "Retirer le mot de passe", "Sur un document dont vous avez la clé"),
                Entry(Tool.TO_IMAGE, "PDF → image", "Une page en PNG ou JPEG"),
                Entry(Tool.FROM_IMAGE, "Images → PDF", "Photos ou scans en un document", needsDoc = false),
                Entry(Tool.PRINT, "Imprimer", "Via le service d'impression Android")
            ),
            doc
        ) { active = it }

        toolSection(
            "Texte et données",
            listOf(
                Entry(Tool.OCR, "OCR", "Lire un PDF scanné, en local"),
                Entry(Tool.SEARCHABLE, "Rendre recherchable", "Couche texte invisible sur un scan"),
                Entry(Tool.TABLE_CSV, "Tableau → CSV", "Export vers un tableur"),
                Entry(Tool.FORMS, "Formulaire", "Remplir les champs d'un AcroForm"),
                Entry(Tool.INVOICE_FIELDS, "Lire une facture", "Émetteur, SIRET, HT / TVA / TTC"),
                Entry(Tool.TEXT_HISTORY, "Historique des extractions", "Les 50 derniers textes", needsDoc = false),
                Entry(Tool.BATCH, "Traitement en lot", "Filigrane ou compression sur plusieurs PDF", needsDoc = false)
            ),
            doc
        ) { active = it }

        item { Spacer(Modifier.height(40.dp)) }
    }

    ToolHost(
        app = app,
        reader = reader,
        doc = doc,
        allDocs = allDocs,
        active = active,
        onClose = { active = null },
        onOpenLibrary = onOpenLibrary
    )
}

data class Entry(
    val tool: Tool,
    val title: String,
    val subtitle: String,
    val needsDoc: Boolean = true
)

private fun androidx.compose.foundation.lazy.LazyListScope.toolSection(
    title: String,
    entries: List<Entry>,
    doc: Doc?,
    onSelect: (Tool) -> Unit
) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
        )
    }
    items(entries.size) { index ->
        val entry = entries[index]
        val enabled = !entry.needsDoc || doc != null
        IconTile(
            painter = painterResource(iconOf(entry.tool)),
            title = entry.title,
            subtitle = if (enabled) entry.subtitle else "Ouvrez d'abord un document",
            enabled = enabled,
            onClick = { onSelect(entry.tool) }
        )
    }
}

private fun iconOf(tool: Tool): Int = when (tool) {
    Tool.MERGE, Tool.EXTRACT, Tool.DELETE_PAGES, Tool.REORDER, Tool.ROTATE, Tool.NUMBER ->
        R.drawable.ic_merge
    Tool.TABLE_CSV -> R.drawable.ic_table
    Tool.OCR, Tool.SEARCHABLE -> R.drawable.ic_ocr
    Tool.INVOICE_FIELDS, Tool.FORMS -> R.drawable.ic_xml
    Tool.WATERMARK, Tool.REDACT, Tool.EXPORT_ANNOTATED, Tool.SIGN_INFO -> R.drawable.ic_tab_annotate
    else -> R.drawable.ic_tab_tools
}
