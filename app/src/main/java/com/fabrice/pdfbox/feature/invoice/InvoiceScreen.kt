package com.fabrice.pdfbox.feature.invoice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.data.Attachment
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.Library
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.formatAmount
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.xml.InvoiceData
import com.fabrice.pdfbox.core.xml.InvoiceXml
import com.fabrice.pdfbox.core.xml.XmlNode
import com.fabrice.pdfbox.core.xml.XmlParseResult
import com.fabrice.pdfbox.core.xml.XmlTree
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.EmptyState
import com.fabrice.pdfbox.feature.common.InfoRow
import com.fabrice.pdfbox.feature.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Écran « Facture » (X1-X6). Une facture électronique est un couple : un PDF
 * lisible par un humain, et un XML lisible par une machine. L'écran les
 * présente ensemble, parce que c'est ainsi qu'ils circulent.
 */
@Composable
fun InvoiceScreen(
    app: AppViewModel,
    doc: Doc?,
    onOpenPdf: (Doc) -> Unit,
    onGoToLibrary: () -> Unit
) {
    val context = LocalContext.current
    if (doc == null) {
        EmptyState(
            title = "Aucune facture ouverte",
            message = "Importez une facture .zip (PDF + XML) ou un XML seul, puis ouvrez-la ici.",
            actionLabel = "Ouvrir la bibliothèque",
            onAction = onGoToLibrary
        )
        return
    }

    var attachments by remember(doc.id) { mutableStateOf<List<Attachment>>(emptyList()) }
    var keyData by remember(doc.id) { mutableStateOf<Pair<InvoiceData, String>?>(null) }
    var opened by remember(doc.id) { mutableStateOf<Attachment?>(null) }
    var loading by remember(doc.id) { mutableStateOf(true) }

    LaunchedEffect(doc.id) {
        loading = true
        val list = Library.attachmentsOf(doc.id)
        attachments = list
        keyData = withContext(Dispatchers.IO) {
            list.asSequence()
                .filter { it.mime.contains("xml") || it.name.endsWith(".xml", true) }
                .mapNotNull { att ->
                    when (val parsed = XmlTree.parse(Library.attachmentFile(att))) {
                        is XmlParseResult.Ok -> InvoiceXml.extract(parsed.root) to att.name
                        is XmlParseResult.Unreadable -> null
                    }
                }
                .firstOrNull { it.first.hasAnything }
        }
        loading = false
    }

    val current = opened
    if (current != null) {
        XmlViewerScreen(
            app = app,
            attachment = current,
            file = Library.attachmentFile(current),
            onBack = { opened = null }
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = doc.title) {
                Text(
                    buildString {
                        append(formatSize(doc.sizeBytes))
                        if (doc.pageCount > 0) append(" · ${doc.pageCount} page(s) PDF")
                        else append(" · XML seul, sans PDF")
                        append(" · ${attachments.size} fichier(s) joint(s)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (doc.pageCount > 0) {
                        TextButton(onClick = { onOpenPdf(doc) }) { Text("Ouvrir le PDF") }
                    }
                    TextButton(onClick = {
                        val file = File(doc.parsedUri.path ?: return@TextButton)
                        if (file.exists()) {
                            Sharing.shareFile(
                                context, file,
                                if (doc.pageCount > 0) "application/pdf" else "text/xml",
                                "Partager"
                            )
                        }
                    }) { Text("Partager") }
                }
            }
        }

        keyData?.let { (data, sourceName) ->
            item { KeyFieldsCard(data, sourceName) }
        }

        item {
            Text(
                "Fichiers attachés",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (loading) {
            item { Text("Lecture des pièces jointes…", style = MaterialTheme.typography.bodySmall) }
        } else if (attachments.isEmpty()) {
            item {
                Text(
                    "Aucun fichier joint. Une facture importée en .zip apparaît ici avec ses XML.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(attachments.size) { index ->
                val att = attachments[index]
                AttachmentRow(
                    attachment = att,
                    onOpen = { opened = att },
                    onShare = {
                        val file = Library.attachmentFile(att)
                        val name = keyData?.let { (data, _) ->
                            InvoiceXml.suggestedFileName(data, att.name)
                        } ?: att.name
                        val shared = File(file.parentFile, name)
                        // X4 — renommage normalisé pour l'envoi, sans toucher
                        // au fichier conservé dans la bibliothèque.
                        val toShare = if (name != att.name && file.copyTo(shared, overwrite = true).exists()) {
                            shared
                        } else file
                        Sharing.shareFile(context, toShare, att.mime, "Partager ${att.name}")
                    }
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun KeyFieldsCard(data: InvoiceData, sourceName: String) {
    SectionCard(
        title = "Champs clés",
        subtitle = "${data.format} — lus dans $sourceName"
    ) {
        InfoRow("N° de facture", data.number ?: "—")
        InfoRow("Date d'émission", data.issueDate ?: "—")
        data.dueDate?.let { InfoRow("Échéance", it) }
        InfoRow("Émetteur", data.sellerName ?: "—")
        InfoRow("SIRET / identifiant", data.sellerSiret ?: "—")
        InfoRow("N° TVA", data.sellerVat ?: "—")
        InfoRow("Destinataire", data.buyerName ?: "—")
        InfoRow("Total HT", data.totalHt?.let { formatAmount(it, data.currency) } ?: "—")
        InfoRow("TVA", data.totalVat?.let { formatAmount(it, data.currency) } ?: "—")
        InfoRow("Total TTC", data.totalTtc?.let { formatAmount(it, data.currency) } ?: "—")
        data.payable?.let { InfoRow("Net à payer", formatAmount(it, data.currency)) }
        data.totalsConsistent?.let { consistent ->
            Text(
                if (consistent) "HT + TVA = TTC : cohérent."
                else "HT + TVA ≠ TTC : le XML est incohérent, à signaler à l'émetteur.",
                style = MaterialTheme.typography.bodySmall,
                color = if (consistent) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
        }
        if (data.lines.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${data.lines.size} ligne(s) de facturation",
                style = MaterialTheme.typography.labelMedium
            )
            data.lines.take(6).forEach { line ->
                Text(
                    "• ${line.label}" +
                        (line.quantity?.let { " × $it" } ?: "") +
                        (line.total?.let { " — ${formatAmount(it, data.currency)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(attachment: Attachment, onOpen: () -> Unit, onShare: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    "${attachment.mime} · ${formatSize(attachment.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                attachment.problem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onShare) { Text("Partager") }
        }
    }
}

/** Nœuds visibles après repli, pour l'affichage en liste. */
internal fun flatten(
    node: XmlNode,
    collapsed: Set<String>,
    path: String = "0",
    into: MutableList<Pair<String, XmlNode>> = mutableListOf()
): MutableList<Pair<String, XmlNode>> {
    into += path to node
    if (path in collapsed) return into
    node.children.forEachIndexed { index, child ->
        flatten(child, collapsed, "$path.$index", into)
    }
    return into
}
