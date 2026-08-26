package com.fabrice.pdfbox.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.pdfbox.core.data.Annot
import com.fabrice.pdfbox.core.data.AnnotationType
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.data.Library
import com.fabrice.pdfbox.core.pdf.PdfPageRenderer
import com.fabrice.pdfbox.core.pdf.PdfProtectedException
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.pdf.PdfText
import com.fabrice.pdfbox.core.pdf.Security
import com.fabrice.pdfbox.core.pdf.WordBox
import com.fabrice.pdfbox.core.util.humanMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class ReaderMode(val label: String) {
    READ("Lecture"),
    HIGHLIGHT("Surligner"),
    NOTE("Note"),
    REDACT("Masquer"),
    SIGN("Signer")
}

data class DocSearchHit(val page: Int, val box: WordBox)

/**
 * État du document ouvert : rendu des pages, texte, annotations, recherche.
 *
 * Conservé à l'échelle de l'activité : passer sur l'onglet Annotations ou
 * Outils puis revenir ne referme pas le document et ne relance pas le rendu.
 */
class ReaderViewModel : ViewModel() {

    var doc by mutableStateOf<Doc?>(null)
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    var pageRatios by mutableStateOf<List<Float>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var needsPassword by mutableStateOf(false)
        private set
    var password by mutableStateOf<String?>(null)
        private set

    var annotations by mutableStateOf<List<Annot>>(emptyList())
        private set
    var mode by mutableStateOf(ReaderMode.READ)
    var highlightColor by mutableIntStateOf(0xFFFFEB3B.toInt())
    var currentPage by mutableIntStateOf(0)
    var fullscreen by mutableStateOf(false)
    var showThumbnails by mutableStateOf(false)

    var searchQuery by mutableStateOf("")
        private set
    var searchHits by mutableStateOf<List<DocSearchHit>>(emptyList())
        private set
    var searchIndex by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set

    /** Fichier réellement lisible : copie déchiffrée si le document est protégé. */
    var renderFile by mutableStateOf<File?>(null)
        private set
    var sourceFile by mutableStateOf<File?>(null)
        private set

    private var renderer: PdfPageRenderer? = null
    private val renderLock = Mutex()
    private val words = HashMap<Int, List<WordBox>>()

    // Un huitième de la mémoire allouée à l'application : au-delà, le rendu
    // d'un document long finit par déclencher le ramasse-miettes en boucle.
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8).coerceIn(8_192, 160_000)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    val isOpen: Boolean get() = renderer != null

    fun open(context: Context, target: Doc, withPassword: String? = null) {
        if (doc?.id == target.id && renderer != null && withPassword == null) {
            return
        }
        closeInternal()
        doc = target
        loading = true
        error = null
        needsPassword = false
        password = withPassword
        currentPage = 0
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { openBlocking(context, target, withPassword) }
            loading = false
            result.fold(
                onSuccess = { state ->
                    renderer = state.renderer
                    sourceFile = state.source
                    renderFile = state.render
                    pageCount = state.renderer.pageCount
                    pageRatios = state.ratios
                },
                onFailure = { failure ->
                    if (failure is PdfProtectedException) {
                        needsPassword = true
                    } else {
                        error = humanMessage(failure)
                    }
                }
            )
            reloadAnnotations()
        }
    }

    private class OpenState(
        val renderer: PdfPageRenderer,
        val source: File,
        val render: File,
        val ratios: List<Float>
    )

    private fun openBlocking(context: Context, target: Doc, pass: String?): Result<OpenState> = try {
        val source = PdfSource.localFile(context, target)
        val render = if (pass.isNullOrEmpty()) source else Security.tempDecrypted(context, source, pass)
        val opened = PdfPageRenderer.open(render)
        val ratios = (0 until opened.pageCount).map { opened.pageSize(it).ratio }
        Result.success(OpenState(opened, source, render, ratios))
    } catch (e: Throwable) {
        Result.failure(e)
    }

    fun unlock(context: Context, candidate: String) {
        val target = doc ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val source = PdfSource.localFile(context, target)
                Security.passwordWorks(source, candidate)
            }
            if (ok) open(context, target, candidate)
            else error = "Mot de passe refusé."
        }
    }

    fun clearError() { error = null }

    fun close() {
        closeInternal()
        doc = null
        pageCount = 0
        pageRatios = emptyList()
        annotations = emptyList()
        searchHits = emptyList()
        searchQuery = ""
        searchIndex = -1
    }

    private fun closeInternal() {
        renderer?.close()
        renderer = null
        cache.evictAll()
        words.clear()
        // La copie déchiffrée ne doit pas survivre à la fermeture du document.
        renderFile?.let { file -> if (file != sourceFile) file.delete() }
        renderFile = null
        sourceFile = null
    }

    override fun onCleared() {
        closeInternal()
        super.onCleared()
    }

    // -------------------------------------------------------------------- rendu

    suspend fun bitmap(page: Int, widthPx: Int): Bitmap? {
        val engine = renderer ?: return null
        // Le pas d'arrondi évite de re-rendre la page à chaque pixel de zoom.
        val bucket = ((widthPx + 255) / 256) * 256
        val key = "$page@$bucket"
        cache.get(key)?.let { return it }
        return renderLock.withLock {
            cache.get(key) ?: withContext(Dispatchers.IO) {
                runCatching { engine.render(page, bucket) }.getOrNull()
            }?.also { cache.put(key, it) }
        }
    }

    suspend fun wordsOf(page: Int): List<WordBox> {
        words[page]?.let { return it }
        val file = sourceFile ?: return emptyList()
        val extracted = withContext(Dispatchers.IO) {
            runCatching { PdfText.words(file, page, password) }.getOrDefault(emptyList())
        }
        words[page] = extracted
        return extracted
    }

    // --------------------------------------------------------------- recherche

    fun search(context: Context, raw: String) {
        searchQuery = raw
        searchIndex = -1
        if (raw.trim().length < 2) {
            searchHits = emptyList()
            return
        }
        searching = true
        viewModelScope.launch {
            val hits = mutableListOf<DocSearchHit>()
            for (page in 0 until pageCount) {
                val pageWords = wordsOf(page)
                if (pageWords.isEmpty()) continue
                PdfText.findOccurrences(pageWords, raw).forEach { range ->
                    PdfText.boxesOf(pageWords, range).forEach { box -> hits += DocSearchHit(page, box) }
                }
                if (hits.size > 500) break
            }
            searchHits = hits
            searching = false
            if (hits.isNotEmpty()) {
                searchIndex = 0
                currentPage = hits[0].page
            }
        }
    }

    fun nextHit() {
        if (searchHits.isEmpty()) return
        searchIndex = (searchIndex + 1) % searchHits.size
        currentPage = searchHits[searchIndex].page
    }

    fun previousHit() {
        if (searchHits.isEmpty()) return
        searchIndex = if (searchIndex <= 0) searchHits.lastIndex else searchIndex - 1
        currentPage = searchHits[searchIndex].page
    }

    fun clearSearch() {
        searchQuery = ""
        searchHits = emptyList()
        searchIndex = -1
    }

    /** Surligne toutes les occurrences trouvées, d'un coup (A1 + L3). */
    fun highlightAllHits() {
        val target = doc ?: return
        val hits = searchHits
        if (hits.isEmpty()) return
        viewModelScope.launch {
            hits.forEach { hit ->
                Library.addAnnotation(
                    Annot(
                        docId = target.id,
                        page = hit.page,
                        type = AnnotationType.HIGHLIGHT,
                        x0 = hit.box.left, y0 = hit.box.top,
                        x1 = hit.box.right, y1 = hit.box.bottom,
                        color = highlightColor,
                        text = hit.box.text
                    )
                )
            }
            reloadAnnotations()
        }
    }

    // -------------------------------------------------------------- annotations

    fun reloadAnnotations() {
        val target = doc ?: return
        viewModelScope.launch { annotations = Library.annotations(target.id) }
    }

    fun addAnnotation(annot: Annot, then: (() -> Unit)? = null) {
        viewModelScope.launch {
            Library.addAnnotation(annot)
            annotations = Library.annotations(annot.docId)
            then?.invoke()
        }
    }

    fun deleteAnnotation(id: Long) {
        val target = doc ?: return
        viewModelScope.launch {
            Library.deleteAnnotation(id)
            annotations = Library.annotations(target.id)
        }
    }

    fun updateNote(id: Long, text: String) {
        val target = doc ?: return
        viewModelScope.launch {
            Library.updateAnnotationText(id, text)
            annotations = Library.annotations(target.id)
        }
    }

    fun clearAnnotations() {
        val target = doc ?: return
        viewModelScope.launch {
            Library.deleteAnnotations(target.id)
            annotations = emptyList()
        }
    }

    /**
     * Sélection d'un mot par appui, d'un passage par glissement. Le texte
     * couvert est retourné pour être copié ou stocké avec l'annotation.
     */
    suspend fun selectionBetween(page: Int, from: Pair<Float, Float>, to: Pair<Float, Float>): List<WordBox> {
        val pageWords = wordsOf(page)
        if (pageWords.isEmpty()) return emptyList()
        val startIndex = nearestWord(pageWords, from.first, from.second)
        val endIndex = nearestWord(pageWords, to.first, to.second)
        if (startIndex < 0 || endIndex < 0) return emptyList()
        val range = if (startIndex <= endIndex) startIndex..endIndex else endIndex..startIndex
        return PdfText.boxesOf(pageWords, range)
    }

    private fun nearestWord(pageWords: List<WordBox>, x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        pageWords.forEachIndexed { index, word ->
            if (x in word.left..word.right && y in word.top..word.bottom) {
                best = index
                bestDistance = 0f
                return@forEachIndexed
            }
            val cx = (word.left + word.right) / 2f
            val cy = (word.top + word.bottom) / 2f
            // La distance verticale pèse plus : deux lignes voisines sont bien
            // plus proches en pixels qu'elles ne le sont pour le lecteur.
            val distance = (x - cx) * (x - cx) + 4f * (y - cy) * (y - cy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }
}
