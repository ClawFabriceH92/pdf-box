package com.fabrice.pdfbox.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Boîte d'un mot, en coordonnées **d'affichage normalisées** (0..1) : la même
 * convention que les annotations, donc directement superposable au rendu.
 */
data class WordBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val normalized: String by lazy { PdfText.foldAccents(text) }
}

/** Extraction de texte, positions des mots, recherche dans le document. */
object PdfText {

    /**
     * Les polices standard du PDF codent en WinAnsi : tout caractère hors de ce
     * jeu fait échouer `showText` au moment de l'écriture, pas à la saisie. On
     * remplace donc en amont, en gardant les équivalents lisibles.
     */
    fun winAnsiSafe(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val replacement = when (ch) {
                '\u2018', '\u2019', '\u02BC' -> '\''
                '\u201C', '\u201D' -> '"'
                '\u2013', '\u2014', '\u2212' -> '-'
                '\u00A0', '\u202F', '\u2009', '\t' -> ' '
                else -> ch
            }
            sb.append(
                when {
                    replacement.code in 32..126 -> replacement
                    WIN_ANSI_EXTRA.contains(replacement) -> replacement
                    replacement.code < 32 -> ' '
                    else -> stripAccent(replacement)
                }
            )
        }
        return sb.toString()
    }

    private val WIN_ANSI_EXTRA: Set<Char> = buildSet {
        // Plage Latin-1 imprimable, plus les caractères que WinAnsi ajoute
        // entre 0x80 et 0x9F et dont le français a l'usage.
        for (c in '\u00A1'..'\u00FF') add(c)
        addAll(
            listOf(
                '\u20AC', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
                '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u017D', '\u2018',
                '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC',
                '\u2122', '\u0161', '\u203A', '\u0153', '\u017E', '\u0178'
            )
        )
    }

    private fun stripAccent(ch: Char): Char {
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        val base = decomposed.firstOrNull { it.code in 32..126 }
        return base ?: '?'
    }

    /** Repli des accents et de la casse, pour comparer « Éléctricité » à « electricite ». */
    fun foldAccents(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036F]+"), "")
            .lowercase(Locale.FRANCE)

    fun extractAll(file: File, password: String? = null): List<String> =
        PdfDoc.use(file, password) { doc -> extractAll(doc) }

    fun extractAll(doc: PDDocument): List<String> {
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        return (1..doc.numberOfPages).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            runCatching { stripper.getText(doc) }.getOrDefault("")
        }
    }

    fun extractPage(file: File, pageIndex: Int, password: String? = null): String =
        PdfDoc.use(file, password) { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return@use ""
            PDFTextStripper().apply {
                sortByPosition = true
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.getText(doc)
        }

    /** Vrai si le document contient une couche texte exploitable (donc non scanné). */
    fun hasExtractableText(doc: PDDocument, sampleCount: Int = 3): Boolean {
        val stripper = PDFTextStripper().apply { sortByPosition = false }
        val pages = (1..doc.numberOfPages).take(sampleCount)
        for (page in pages) {
            stripper.startPage = page
            stripper.endPage = page
            val text = runCatching { stripper.getText(doc) }.getOrDefault("")
            if (text.replace(Regex("\\s"), "").length >= 24) return true
        }
        return false
    }

    fun words(file: File, pageIndex: Int, password: String? = null): List<WordBox> =
        PdfDoc.use(file, password) { doc -> words(doc, pageIndex) }

    fun words(doc: PDDocument, pageIndex: Int): List<WordBox> {
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        val collector = WordCollector().apply {
            sortByPosition = true
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }
        return runCatching {
            collector.getText(doc)
            collector.words
        }.getOrDefault(emptyList())
    }

    /**
     * Recherche une expression dans les mots d'une page. Retourne, pour chaque
     * occurrence, la suite de mots concernés — ce qui permet de surligner une
     * expression de plusieurs mots d'un seul tenant par ligne.
     */
    fun findOccurrences(words: List<WordBox>, query: String): List<IntRange> {
        val needles = foldAccents(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (needles.isEmpty() || words.isEmpty()) return emptyList()
        val hits = mutableListOf<IntRange>()
        if (needles.size == 1) {
            val needle = needles[0]
            words.forEachIndexed { index, word ->
                if (word.normalized.contains(needle)) hits += index..index
            }
            return hits
        }
        var i = 0
        while (i <= words.size - needles.size) {
            var matches = true
            for (k in needles.indices) {
                val word = words[i + k].normalized
                val ok = when (k) {
                    0 -> word.endsWith(needles[k]) || word == needles[k] || word.contains(needles[k])
                    needles.lastIndex -> word.startsWith(needles[k])
                    else -> word == needles[k]
                }
                if (!ok) { matches = false; break }
            }
            if (matches) {
                hits += i..(i + needles.lastIndex)
                i += needles.size
            } else {
                i++
            }
        }
        return hits
    }

    /** Réunit une suite de mots en rectangles, un par ligne de texte. */
    fun boxesOf(words: List<WordBox>, range: IntRange): List<WordBox> {
        val slice = words.slice(range)
        if (slice.isEmpty()) return emptyList()
        val lines = mutableListOf<MutableList<WordBox>>()
        for (word in slice) {
            val line = lines.lastOrNull()
            val sameLine = line != null && kotlin.math.abs(line.last().top - word.top) <
                (line.last().bottom - line.last().top) * 0.6f
            if (sameLine) line!!.add(word) else lines.add(mutableListOf(word))
        }
        return lines.map { line ->
            WordBox(
                text = line.joinToString(" ") { it.text },
                left = line.minOf { it.left },
                top = line.minOf { it.top },
                right = line.maxOf { it.right },
                bottom = line.maxOf { it.bottom }
            )
        }
    }
}

/**
 * Reconstruit des mots à partir des positions de caractères. PDFBox livre le
 * texte par fragments de flux, qui ne correspondent ni aux mots ni aux lignes ;
 * seule la position permet de retrouver les uns et les autres.
 */
private class WordCollector : PDFTextStripper() {

    val words = mutableListOf<WordBox>()
    private var pageWidth = 1f
    private var pageHeight = 1f

    private val buffer = StringBuilder()
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private var lastRight = 0f

    override fun startPage(page: PDPage) {
        val size = PdfGeometry.displaySize(page)
        pageWidth = if (size.widthPt > 0f) size.widthPt else 1f
        pageHeight = if (size.heightPt > 0f) size.heightPt else 1f
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (tp in textPositions) {
            val glyph = tp.unicode ?: ""
            if (glyph.isBlank()) { flush(); continue }
            val x0 = tp.xDirAdj
            val x1 = tp.xDirAdj + tp.widthDirAdj
            val y1 = tp.yDirAdj
            val y0 = tp.yDirAdj - tp.heightDir
            // Un blanc typographique n'existe pas toujours comme caractère :
            // un écart supérieur à un tiers de cadratin sépare deux mots.
            if (buffer.isNotEmpty() && x0 - lastRight > tp.widthOfSpace * 0.35f) flush()
            if (buffer.isEmpty()) {
                left = x0; top = y0; right = x1; bottom = y1
            } else {
                left = minOf(left, x0); top = minOf(top, y0)
                right = maxOf(right, x1); bottom = maxOf(bottom, y1)
            }
            buffer.append(glyph)
            lastRight = x1
        }
        flush()
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        words += WordBox(
            text = buffer.toString(),
            left = (left / pageWidth).coerceIn(0f, 1f),
            top = (top / pageHeight).coerceIn(0f, 1f),
            right = (right / pageWidth).coerceIn(0f, 1f),
            bottom = (bottom / pageHeight).coerceIn(0f, 1f)
        )
        buffer.setLength(0)
    }
}
