package com.fabrice.pdfbox.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Formatage en unités décimales (Mo = 10^6), comme l'affiche Android. */
fun formatSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes o"
    val unit = 1000.0
    val exp = (ln(bytes.toDouble()) / ln(unit)).toInt().coerceIn(1, 4)
    val units = arrayOf("ko", "Mo", "Go", "To")
    val value = bytes / unit.pow(exp.toDouble())
    val decimals = if (value < 10) 1 else 0
    return String.format(Locale.FRANCE, "%.${decimals}f %s", value, units[exp - 1])
}

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.FRANCE)
private val dayTimeFormat = SimpleDateFormat("d MMM yyyy 'à' HH:mm", Locale.FRANCE)

fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0L) "—" else dayFormat.format(Date(epochMillis))

fun formatDateTime(epochMillis: Long): String =
    if (epochMillis <= 0L) "—" else dayTimeFormat.format(Date(epochMillis))

/** « il y a 3 jours », pour la bibliothèque. */
fun formatRelative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "—"
    val delta = now - epochMillis
    if (delta < 0) return formatDate(epochMillis)
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "à l'instant"
        minutes < 60 -> "il y a $minutes min"
        hours < 24 -> "il y a $hours h"
        days < 7 -> if (days <= 1L) "hier" else "il y a $days jours"
        else -> formatDate(epochMillis)
    }
}

fun formatDuration(millis: Long): String {
    val s = abs(millis) / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** Montants « 1 234,56 € » pour les champs de facture. */
fun formatAmount(value: Double, currency: String = "EUR"): String {
    val symbol = when (currency.uppercase(Locale.ROOT)) {
        "EUR" -> "€"
        "USD" -> "$"
        "GBP" -> "£"
        else -> currency
    }
    return String.format(Locale.FRANCE, "%,.2f %s", value, symbol)
}

/** Nom de fichier sûr sur toutes les partitions (FAT32 incluse). */
fun sanitizeFileName(raw: String, fallback: String = "document"): String {
    val cleaned = raw.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim('.', ' ')
    val safe = if (cleaned.isBlank()) fallback else cleaned
    return if (safe.length <= 120) safe else safe.take(120)
}

fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
