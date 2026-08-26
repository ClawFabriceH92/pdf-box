package com.fabrice.pdfbox.feature.common

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.ImportResult
import com.fabrice.pdfbox.core.data.Importer
import com.fabrice.pdfbox.core.data.Library
import com.fabrice.pdfbox.core.data.LibraryStats
import com.fabrice.pdfbox.core.data.SearchHit
import com.fabrice.pdfbox.core.data.SortOrder
import com.fabrice.pdfbox.core.data.TextEntry
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.util.ProgressSink
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.TaskCenter
import com.fabrice.pdfbox.core.util.TaskResult
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.util.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiMessage(val text: String, val isError: Boolean = false)

/**
 * État partagé par les quatre onglets : bibliothèque, document courant,
 * messages, tâches longues. Un seul ViewModel à l'échelle de l'activité — ce
 * qui permet de changer d'onglet sans perdre le document ouvert ni la
 * progression d'un OCR.
 */
class AppViewModel : ViewModel() {

    val docs = Library.docs
    val running = TaskCenter.running

    var message by mutableStateOf<UiMessage?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var sort by mutableStateOf(SortOrder.RECENT)
        private set
    var tagFilter by mutableStateOf<String?>(null)
        private set
    var fullTextHits by mutableStateOf<Map<Long, SearchHit>>(emptyMap())
        private set
    var tags by mutableStateOf<List<String>>(emptyList())
        private set
    var currentDocId by mutableStateOf<Long?>(null)
        private set
    var stats by mutableStateOf<LibraryStats?>(null)
        private set
    var history by mutableStateOf<List<TextEntry>>(emptyList())
        private set
    var duplicates by mutableStateOf<List<List<Doc>>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            Library.refresh()
            tags = Library.allTags()
        }
    }

    // ------------------------------------------------------------------ messages

    fun say(text: String) { message = UiMessage(text, isError = false) }
    fun fail(text: String) { message = UiMessage(text, isError = true) }
    fun dismissMessage() { message = null }

    // --------------------------------------------------------------- navigation

    fun openDoc(id: Long?) { currentDocId = id }

    suspend fun doc(id: Long): Doc? = Library.doc(id)

    fun currentDoc(all: List<Doc>): Doc? = currentDocId?.let { id -> all.firstOrNull { it.id == id } }

    // ---------------------------------------------------------------- recherche

    fun setQuery(value: String) {
        query = value
        viewModelScope.launch {
            fullTextHits = if (value.length < 2) emptyMap()
            else Library.search(value).associateBy { it.docId }
        }
    }

    fun setSort(value: SortOrder) { sort = value }
    fun setTagFilter(value: String?) { tagFilter = value }

    /**
     * U6 — la barre de recherche filtre à la fois sur le nom et sur le contenu
     * indexé : chercher « facture » doit trouver le document dont le *texte*
     * contient le mot, pas seulement celui qui s'appelle ainsi.
     */
    fun visibleDocs(all: List<Doc>): List<Doc> {
        val needle = query.trim().lowercase()
        val filtered = all.asSequence()
            .filter { tagFilter == null || it.tag == tagFilter }
            .filter {
                needle.isEmpty() ||
                    it.title.lowercase().contains(needle) ||
                    it.tag?.lowercase()?.contains(needle) == true ||
                    fullTextHits.containsKey(it.id)
            }
        return when (sort) {
            SortOrder.RECENT -> filtered.sortedByDescending { it.addedAt }
            SortOrder.NAME -> filtered.sortedBy { it.title.lowercase() }
            SortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            SortOrder.PAGES -> filtered.sortedByDescending { it.pageCount }
        }.toList()
    }

    // ------------------------------------------------------------------- import

    fun importUris(context: Context, uris: List<Uri>, persistable: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var duplicates = 0
            val notes = mutableListOf<String>()
            uris.forEach { uri ->
                when (val result = Importer.import(context, uri, persistable)) {
                    is ImportResult.Added -> {
                        added++
                        currentDocId = result.doc.id
                        result.note?.let { notes += it }
                    }
                    is ImportResult.Bundle -> {
                        added++
                        currentDocId = result.doc.id
                        notes += "Facture importée : ${result.xmlCount} fichier(s) joint(s)."
                        if (result.skipped.isNotEmpty()) {
                            notes += "Ignoré : ${result.skipped.joinToString(", ")}"
                        }
                    }
                    is ImportResult.Duplicate -> {
                        duplicates++
                        currentDocId = result.existing.id
                    }
                    is ImportResult.Failed -> notes += result.message
                }
            }
            Library.refresh()
            tags = Library.allTags()
            val summary = buildString {
                if (added > 0) append("$added document(s) importé(s). ")
                if (duplicates > 0) append("$duplicates déjà présent(s). ")
                notes.take(3).forEach { append(it).append(' ') }
            }.trim()
            if (summary.isNotEmpty()) {
                message = UiMessage(summary, isError = added == 0 && duplicates == 0)
            }
        }
    }

    // ------------------------------------------------------------- bibliothèque

    fun rename(doc: Doc, title: String) {
        viewModelScope.launch {
            Library.rename(doc.id, title.trim().ifBlank { doc.title })
            say("Renommé.")
        }
    }

    fun setTag(doc: Doc, tag: String?) {
        viewModelScope.launch {
            Library.setTag(doc.id, tag?.trim()?.takeIf { it.isNotBlank() })
            tags = Library.allTags()
        }
    }

    fun delete(doc: Doc) {
        viewModelScope.launch {
            val removed = Library.delete(doc)
            if (currentDocId == doc.id) currentDocId = null
            tags = Library.allTags()
            say(
                if (removed) "« ${doc.title} » supprimé (fiche et fichier)."
                else "« ${doc.title} » retiré de la bibliothèque. Le fichier d'origine n'a pas été touché."
            )
        }
    }

    fun reindex(context: Context, doc: Doc) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { PdfSource.localFile(context, doc) }
            Importer.indexInBackground(doc, file)
            Library.refresh()
            say("Texte réindexé pour la recherche.")
        }
    }

    fun refreshStats() {
        viewModelScope.launch { stats = Library.stats() }
    }

    fun refreshHistory() {
        viewModelScope.launch { history = Library.textHistory() }
    }

    fun clearHistory() {
        viewModelScope.launch {
            Library.clearTextHistory()
            history = emptyList()
            say("Historique effacé.")
        }
    }

    /**
     * P10F — doublons. Le nom ne suffit pas et la taille non plus : deux PDF de
     * même taille diffèrent souvent d'un octet. Seule l'empreinte tranche, et on
     * ne la calcule que sur les groupes déjà suspects.
     */
    fun findDuplicates(context: Context) {
        val label = "Recherche de doublons"
        val started = TaskCenter.launch(
            context = context,
            label = label,
            notifyOnFinish = false,
            work = { progress -> computeDuplicates(context, progress) },
            onResult = { result ->
                when (result) {
                    is TaskResult.Ok -> {
                        duplicates = result.value
                        say(
                            if (result.value.isEmpty()) "Aucun doublon."
                            else "${result.value.size} groupe(s) de doublons."
                        )
                    }
                    is TaskResult.Failed -> fail(result.message)
                }
            }
        )
        if (!started) fail("Une autre tâche est déjà en cours.")
    }

    private suspend fun computeDuplicates(
        context: Context,
        progress: ProgressSink
    ): List<List<Doc>> = withContext(Dispatchers.IO) {
        val all = Library.docs.value.filter { it.sizeBytes > 0 }
        val bySize = all.groupBy { it.sizeBytes }.filterValues { it.size > 1 }
        val groups = mutableListOf<List<Doc>>()
        var index = 0
        bySize.values.forEach { candidates ->
            progress.onProgress(index++, bySize.size, "${candidates.size} candidats")
            val byHash = candidates.groupBy { doc ->
                doc.sha256 ?: runCatching { PdfSource.localFile(context, doc).sha256() }.getOrNull()
                ?: "?${doc.id}"
            }
            byHash.values.filter { it.size > 1 }.forEach { groups += it }
        }
        groups
    }

    // ----------------------------------------------------------------- outillage

    /**
     * Exécute un outil produisant un fichier, l'enregistre dans la bibliothèque
     * et le désigne comme document courant. Les outils ne se soucient donc ni de
     * la progression, ni des erreurs, ni du rangement.
     */
    fun runTool(
        context: Context,
        label: String,
        adoptTitle: String? = null,
        tag: String? = null,
        work: suspend (ProgressSink) -> File,
        onDone: ((File) -> Unit)? = null
    ) {
        val started = TaskCenter.launch(
            context = context,
            label = label,
            work = work,
            onResult = { result ->
                when (result) {
                    is TaskResult.Ok -> {
                        val file = result.value
                        if (adoptTitle != null) {
                            val doc = Importer.adoptProducedFile(context, file, adoptTitle, tag)
                            currentDocId = doc.id
                            Library.refresh()
                            tags = Library.allTags()
                            say("$label : « ${doc.title} » (${formatSize(file.length())}) ajouté à la bibliothèque.")
                        } else {
                            say("$label : terminé (${formatSize(file.length())}).")
                        }
                        onDone?.invoke(file)
                    }
                    is TaskResult.Failed -> fail(result.message)
                }
            }
        )
        if (!started) fail("Une autre tâche est déjà en cours ; attendez qu'elle finisse.")
    }

    /** Variante sans fichier produit : OCR, extraction de texte, analyse. */
    fun <T> runAnalysis(
        context: Context,
        label: String,
        work: suspend (ProgressSink) -> T,
        onResult: (T) -> Unit
    ) {
        val started = TaskCenter.launch(
            context = context,
            label = label,
            work = work,
            onResult = { result ->
                when (result) {
                    is TaskResult.Ok -> onResult(result.value)
                    is TaskResult.Failed -> fail(result.message)
                }
            }
        )
        if (!started) fail("Une autre tâche est déjà en cours ; attendez qu'elle finisse.")
    }

    fun cancelTask() = TaskCenter.cancel()

    fun exportFile(context: Context, name: String): File =
        Storage.uniqueFile(Storage.exportsDir(context), name)
}
