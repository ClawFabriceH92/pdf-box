package com.fabrice.pdfbox.core.pdf

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * T7 — détection de tableaux et export CSV.
 *
 * Un PDF ne contient pas de tableaux : il contient du texte à des coordonnées.
 * La grille se retrouve par **projection des blancs** — l'algorithme classique
 * de la mise en page : on cherche les bandes verticales qu'aucun mot ne
 * traverse sur la plupart des lignes ; ce sont les séparateurs de colonnes.
 * Les filets tracés ne sont pas utilisés : beaucoup de tableaux n'en ont pas,
 * et ceux qui en ont ont aussi les blancs.
 */
object TableExtractor {

    data class Table(
        val page: Int,
        val rows: List<List<String>>,
        val columnCount: Int
    ) {
        val rowCount: Int get() = rows.size
        val nonEmptyCells: Int get() = rows.sumOf { row -> row.count { it.isNotBlank() } }
        fun preview(maxRows: Int = 4): List<List<String>> = rows.take(maxRows)
    }

    data class Options(
        val minRows: Int = 3,
        val minColumns: Int = 2,
        /** Largeur minimale d'un blanc pour compter comme séparateur (fraction de page). */
        val minGap: Float = 0.012f,
        /** Part des lignes qui doivent présenter le blanc au même endroit. */
        val gapAgreement: Float = 0.75f
    )

    fun extract(words: List<WordBox>, page: Int, options: Options = Options()): List<Table> {
        if (words.size < 6) return emptyList()
        val rows = groupIntoRows(words)
        if (rows.size < options.minRows) return emptyList()
        val separators = findSeparators(rows, options)
        if (separators.isEmpty()) return emptyList()
        val bounds = buildColumnBounds(separators)
        if (bounds.size < options.minColumns) return emptyList()

        val grid = rows.map { row -> toCells(row, bounds) }
        return splitIntoBlocks(grid, options).map { block ->
            Table(page = page, rows = trimEmptyColumns(block), columnCount = bounds.size)
        }.filter { it.rowCount >= options.minRows && it.columnCount >= options.minColumns }
    }

    fun extractFromFile(
        file: File,
        pages: List<Int>,
        password: String? = null,
        options: Options = Options()
    ): List<Table> = PdfDoc.use(file, password) { doc ->
        pages.flatMap { index ->
            val words = PdfText.words(doc, index)
            extract(words, index, options)
        }
    }

    // --------------------------------------------------------------- découpage

    private fun groupIntoRows(words: List<WordBox>): List<List<WordBox>> {
        val sorted = words.sortedWith(compareBy({ it.top }, { it.left }))
        val medianHeight = sorted.map { it.bottom - it.top }.sorted()
            .let { if (it.isEmpty()) 0.01f else it[it.size / 2] }
            .coerceAtLeast(0.004f)
        val tolerance = medianHeight * 0.6f

        val rows = mutableListOf<MutableList<WordBox>>()
        var currentCenter = Float.NaN
        for (word in sorted) {
            val center = (word.top + word.bottom) / 2f
            if (rows.isEmpty() || abs(center - currentCenter) > tolerance) {
                rows += mutableListOf(word)
                currentCenter = center
            } else {
                rows.last().add(word)
                val line = rows.last()
                currentCenter = line.sumOf { ((it.top + it.bottom) / 2f).toDouble() }.toFloat() / line.size
            }
        }
        return rows.map { it.sortedBy { w -> w.left } }
    }

    /**
     * Position des séparateurs, par vote des lignes. Une tranche verticale est
     * un séparateur si elle est libre sur assez de lignes ; on ne compte que
     * les lignes qui s'étendent de part et d'autre, sinon un titre court
     * créerait des colonnes fantômes.
     */
    private fun findSeparators(rows: List<List<WordBox>>, options: Options): List<ClosedFloatingPointRange<Float>> {
        val slices = 400
        val votes = IntArray(slices)
        val eligible = IntArray(slices)

        for (row in rows) {
            if (row.size < 2) continue
            val rowLeft = row.first().left
            val rowRight = row.last().right
            for (s in 0 until slices) {
                val x = (s + 0.5f) / slices
                if (x <= rowLeft || x >= rowRight) continue
                eligible[s]++
                val covered = row.any { x >= it.left && x <= it.right }
                if (!covered) votes[s]++
            }
        }

        val bands = mutableListOf<ClosedFloatingPointRange<Float>>()
        var start = -1
        for (s in 0 until slices) {
            val enough = eligible[s] >= max(2, (rows.size * 0.4f).roundToInt())
            val free = enough && votes[s] >= (eligible[s] * options.gapAgreement)
            if (free && start < 0) start = s
            if (!free && start >= 0) {
                addBand(bands, start, s, slices, options.minGap)
                start = -1
            }
        }
        if (start >= 0) addBand(bands, start, slices, slices, options.minGap)
        return bands
    }

    private fun addBand(
        into: MutableList<ClosedFloatingPointRange<Float>>,
        startSlice: Int,
        endSlice: Int,
        slices: Int,
        minGap: Float
    ) {
        val from = startSlice.toFloat() / slices
        val to = endSlice.toFloat() / slices
        if (to - from >= minGap) into += from..to
    }

    private fun buildColumnBounds(
        separators: List<ClosedFloatingPointRange<Float>>
    ): List<ClosedFloatingPointRange<Float>> {
        val cuts = separators.map { (it.start + it.endInclusive) / 2f }.sorted()
        val bounds = mutableListOf<ClosedFloatingPointRange<Float>>()
        var previous = 0f
        for (cut in cuts) {
            bounds += previous..cut
            previous = cut
        }
        bounds += previous..1f
        return bounds.filter { it.endInclusive - it.start > 0.005f }
    }

    private fun toCells(
        row: List<WordBox>,
        bounds: List<ClosedFloatingPointRange<Float>>
    ): List<String> {
        val cells = Array(bounds.size) { StringBuilder() }
        for (word in row) {
            val center = (word.left + word.right) / 2f
            var index = bounds.indexOfFirst { center >= it.start && center <= it.endInclusive }
            if (index < 0) index = if (center < bounds.first().start) 0 else bounds.lastIndex
            if (cells[index].isNotEmpty()) cells[index].append(' ')
            cells[index].append(word.text)
        }
        return cells.map { it.toString().trim() }
    }

    /**
     * Sépare les blocs tabulaires du texte courant : une ligne qui ne remplit
     * qu'une seule cellule est du paragraphe, pas du tableau. Deux telles
     * lignes de suite closent le bloc.
     */
    private fun splitIntoBlocks(grid: List<List<String>>, options: Options): List<List<List<String>>> {
        val blocks = mutableListOf<List<List<String>>>()
        var current = mutableListOf<List<String>>()
        var loneRows = 0
        for (row in grid) {
            val filled = row.count { it.isNotBlank() }
            if (filled >= options.minColumns) {
                current.add(row)
                loneRows = 0
            } else {
                loneRows++
                if (loneRows >= 2 || filled == 0) {
                    if (current.size >= options.minRows) blocks += current.toList()
                    current = mutableListOf()
                    loneRows = 0
                } else if (current.isNotEmpty()) {
                    current.add(row)
                }
            }
        }
        if (current.size >= options.minRows) blocks += current.toList()
        return blocks
    }

    private fun trimEmptyColumns(rows: List<List<String>>): List<List<String>> {
        if (rows.isEmpty()) return rows
        val width = rows.maxOf { it.size }
        val keep = (0 until width).filter { column ->
            rows.any { row -> row.getOrNull(column)?.isNotBlank() == true }
        }
        if (keep.isEmpty()) return rows
        return rows.map { row -> keep.map { row.getOrNull(it).orEmpty() } }
    }

    // ------------------------------------------------------------------- CSV

    enum class Separator(val label: String, val char: Char) {
        SEMICOLON("Point-virgule (Excel FR)", ';'),
        COMMA("Virgule (standard CSV)", ','),
        TAB("Tabulation", '\t')
    }

    /**
     * Le BOM n'est pas une coquetterie : sans lui, Excel lit un CSV UTF-8 en
     * ANSI et affiche « TrÃ©sorerie ». Les autres tableurs l'ignorent.
     */
    fun toCsv(tables: List<Table>, separator: Separator, includeBom: Boolean = true): String {
        val sb = StringBuilder()
        if (includeBom) sb.append('\uFEFF')
        tables.forEachIndexed { index, table ->
            if (tables.size > 1) {
                if (index > 0) sb.append("\r\n")
                sb.append(escape("Tableau ${index + 1} — page ${table.page + 1}", separator.char))
                sb.append("\r\n")
            }
            table.rows.forEach { row ->
                sb.append(row.joinToString(separator.char.toString()) { escape(it, separator.char) })
                sb.append("\r\n")
            }
        }
        return sb.toString()
    }

    private fun escape(value: String, separator: Char): String {
        val needsQuotes = value.contains(separator) || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun writeCsv(tables: List<Table>, separator: Separator, target: File): File {
        target.writeText(toCsv(tables, separator), Charsets.UTF_8)
        return target
    }
}
