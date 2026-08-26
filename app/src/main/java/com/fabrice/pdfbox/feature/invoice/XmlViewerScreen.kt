package com.fabrice.pdfbox.feature.invoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabrice.pdfbox.core.data.Attachment
import com.fabrice.pdfbox.core.util.Sharing
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.xml.XmlNode
import com.fabrice.pdfbox.core.xml.XmlParseResult
import com.fabrice.pdfbox.core.xml.XmlTree
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.LoadingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Palette de coloration : lisible sur fond clair comme sur fond sombre, sans
// dépendre du thème — un XML se lit toujours de la même façon.
private val TagColor = Color(0xFF4F8FE0)
private val AttrNameColor = Color(0xFFB07CD6)
private val AttrValueColor = Color(0xFFCE9178)
private val ValueColor = Color(0xFF6BBF8A)

/**
 * X3 — visionneuse XML : arborescence repliable, coloration syntaxique,
 * recherche, copie d'un nœud, partage.
 *
 * Le fichier est présenté comme un arbre et non comme du texte brut : un XML
 * Chorus tient sur une seule ligne de 40 000 caractères, illisible tel quel.
 */
@Composable
fun XmlViewerScreen(
    app: AppViewModel,
    attachment: Attachment,
    file: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var parsed by remember(file.path) { mutableStateOf<XmlParseResult?>(null) }
    val collapsed = remember(file.path) { mutableStateOf(setOf<String>()) }
    var query by remember(file.path) { mutableStateOf("") }
    var searchVisible by remember(file.path) { mutableStateOf(false) }
    var matchIndex by remember(file.path) { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(file.path) {
        parsed = withContext(Dispatchers.IO) { XmlTree.parse(file) }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        when (val state = parsed) {
                            is XmlParseResult.Ok ->
                                "${state.nodeCount} nœuds · ${state.encoding} · ${formatSize(attachment.sizeBytes)}"
                            is XmlParseResult.Unreadable -> "illisible"
                            null -> "lecture…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) {
                    Icon(
                        if (searchVisible) Icons.Default.Clear else Icons.Default.Search,
                        contentDescription = "Rechercher"
                    )
                }
                IconButton(onClick = {
                    Sharing.shareFile(context, file, attachment.mime, "Partager ${attachment.name}")
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Partager")
                }
            }
        }

        when (val state = parsed) {
            null -> LoadingBox("Analyse du XML…")

            is XmlParseResult.Unreadable -> UnreadableCard(state, attachment, file, app)

            is XmlParseResult.Ok -> {
                val rows = remember(state, collapsed.value) { flatten(state.root, collapsed.value) }
                val matches = remember(rows, query) {
                    if (query.length < 2) emptyList()
                    else rows.withIndex().filter { (_, entry) ->
                        val node = entry.second
                        node.displayName.contains(query, true) ||
                            node.text?.contains(query, true) == true ||
                            node.attributes.any { it.second.contains(query, true) }
                    }.map { it.index }
                }

                if (searchVisible) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; matchIndex = 0 },
                            placeholder = { Text("Chercher un nœud ou une valeur") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            supportingText = {
                                Text(
                                    when {
                                        query.length < 2 -> "Au moins deux caractères"
                                        matches.isEmpty() -> "Aucun résultat"
                                        else -> "${matchIndex + 1} / ${matches.size}"
                                    }
                                )
                            }
                        )
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { matchIndex = if (matchIndex <= 0) matches.lastIndex else matchIndex - 1 }
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Précédent") }
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { matchIndex = (matchIndex + 1) % matches.size }
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Suivant") }
                    }
                    LaunchedEffect(matchIndex, matches) {
                        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it) }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { collapsed.value = emptySet() }) { Text("Tout déplier") }
                    TextButton(onClick = {
                        collapsed.value = flatten(state.root, emptySet())
                            .filter { it.second.children.isNotEmpty() && it.first != "0" }
                            .map { it.first }.toSet()
                    }) { Text("Tout replier") }
                    TextButton(onClick = {
                        Sharing.copyToClipboard(context, "XML", XmlTree.prettyPrint(state.root))
                    }) { Text("Copier") }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    items(rows.size) { index ->
                        val (path, node) = rows[index]
                        XmlRow(
                            node = node,
                            path = path,
                            highlighted = matches.getOrNull(matchIndex) == index,
                            collapsed = path in collapsed.value,
                            query = query,
                            onToggle = {
                                collapsed.value = if (path in collapsed.value) {
                                    collapsed.value - path
                                } else {
                                    collapsed.value + path
                                }
                            },
                            onCopy = {
                                Sharing.copyToClipboard(context, node.displayName, XmlTree.prettyPrint(node))
                            }
                        )
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }
    }
}

@Composable
private fun XmlRow(
    node: XmlNode,
    path: String,
    highlighted: Boolean,
    collapsed: Boolean,
    query: String,
    onToggle: () -> Unit,
    onCopy: () -> Unit
) {
    val indent = (node.depth * 12).coerceAtMost(140)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = if (node.children.isNotEmpty()) onToggle else onCopy)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Spacer(Modifier.width(indent.dp))
        Box(Modifier.width(20.dp), contentAlignment = Alignment.TopCenter) {
            if (node.children.isNotEmpty()) {
                Icon(
                    if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = renderNode(node, query),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            if (collapsed && node.children.isNotEmpty()) {
                Text(
                    "… ${node.children.size} nœud(s) replié(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun renderNode(node: XmlNode, query: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = TagColor, fontWeight = FontWeight.Medium)) {
        append("<")
        append(node.displayName)
    }
    node.attributes.take(6).forEach { (name, value) ->
        append(" ")
        withStyle(SpanStyle(color = AttrNameColor)) { append(name) }
        append("=")
        withStyle(SpanStyle(color = AttrValueColor)) { append("\"" + value.take(48) + "\"") }
    }
    withStyle(SpanStyle(color = TagColor)) { append(">") }
    val text = node.text
    if (!text.isNullOrBlank()) {
        append(" ")
        withStyle(
            SpanStyle(
                color = ValueColor,
                fontWeight = if (query.length >= 2 && text.contains(query, true)) FontWeight.Bold
                else FontWeight.Normal
            )
        ) {
            append(text.take(160))
            if (text.length > 160) append("…")
        }
    }
}

@Composable
private fun UnreadableCard(
    state: XmlParseResult.Unreadable,
    attachment: Attachment,
    file: File,
    app: AppViewModel
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Ce fichier ne peut pas être affiché",
            style = MaterialTheme.typography.titleMedium
        )
        Text(state.reason, style = MaterialTheme.typography.bodyMedium)
        state.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.hexPreview?.let {
            Text(
                "Premiers octets : $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
        Text(
            "Le fichier reste intact et partageable : une autre application saura " +
                "peut-être l'ouvrir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            Sharing.shareFile(context, file, attachment.mime, "Partager ${attachment.name}")
        }) { Text("Partager le fichier") }
    }
}
