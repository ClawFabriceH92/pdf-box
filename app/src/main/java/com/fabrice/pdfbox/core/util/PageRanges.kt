package com.fabrice.pdfbox.core.util

/**
 * Sélection de pages sous forme « 1-3, 7, 10- ». C'est la notation des boîtes
 * de dialogue d'impression : elle est déjà connue, et bien plus rapide que de
 * cocher trente vignettes.
 */
object PageRanges {

    fun parse(input: String, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.equals("tout", true) || trimmed == "*") return (0 until pageCount).toList()
        val out = LinkedHashSet<Int>()
        trimmed.split(',', ';').forEach { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@forEach
            val dash = piece.indexOf('-')
            if (dash < 0) {
                piece.toIntOrNull()?.let { if (it in 1..pageCount) out += it - 1 }
            } else {
                val from = piece.substring(0, dash).trim().toIntOrNull() ?: 1
                val to = piece.substring(dash + 1).trim().toIntOrNull() ?: pageCount
                val start = from.coerceIn(1, pageCount)
                val end = to.coerceIn(1, pageCount)
                if (start <= end) (start..end).forEach { out += it - 1 }
                else (end..start).reversed().forEach { out += it - 1 }
            }
        }
        return out.toList()
    }

    fun format(pages: Collection<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.map { it + 1 }.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start
        for (value in sorted.drop(1)) {
            if (value == previous + 1) { previous = value; continue }
            parts += if (start == previous) "$start" else "$start-$previous"
            start = value
            previous = value
        }
        parts += if (start == previous) "$start" else "$start-$previous"
        return parts.joinToString(", ")
    }
}
