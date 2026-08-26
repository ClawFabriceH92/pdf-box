package com.fabrice.pdfbox.core.ocr

import com.fabrice.pdfbox.core.pdf.PdfText
import com.fabrice.pdfbox.core.xml.InvoiceXml
import java.util.Locale

/**
 * P3F — lecture structurée d'une facture à partir de son **texte** (couche
 * texte du PDF ou sortie de l'OCR).
 *
 * Aucun modèle statistique : des motifs explicites, contrôlés par les règles de
 * validation françaises (clé de Luhn du SIRET, clé du numéro de TVA). Un champ
 * n'est retenu que s'il est vérifiable ou explicitement étiqueté dans le
 * document — mieux vaut un champ vide qu'un champ faux sur une facture.
 */
data class InvoiceFields(
    val number: String? = null,
    val date: String? = null,
    val dueDate: String? = null,
    val siret: String? = null,
    val siren: String? = null,
    val vatNumber: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val totalHt: Double? = null,
    val totalVat: Double? = null,
    val totalTtc: Double? = null,
    val vatRates: List<Double> = emptyList(),
    val issuer: String? = null
) {
    val found: Int
        get() = listOf<Any?>(number, date, siret, vatNumber, iban, totalHt, totalVat, totalTtc)
            .count { it != null }

    val totalsConsistent: Boolean?
        get() {
            val ht = totalHt ?: return null
            val vat = totalVat ?: return null
            val ttc = totalTtc ?: return null
            return kotlin.math.abs(ht + vat - ttc) < 0.02
        }
}

object InvoiceParser {

    private val NUMBER = Regex(
        "(?i)\\b(?:facture|invoice|n[°ºo]\\s*de\\s*facture|numéro\\s+de\\s+facture)\\b[\\s:n°ºo.\\-]{0,12}([A-Z0-9][A-Z0-9\\-/_]{2,24})"
    )
    private val DATE = Regex("\\b(\\d{1,2})[/.\\- ](\\d{1,2}|\\p{L}{3,9})[/.\\- ](\\d{2,4})\\b")
    private val ISO_DATE = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val DUE = Regex("(?i)(échéance|date limite de (?:règlement|paiement)|payable (?:avant|le)|due date)[^\\n]{0,40}")
    private val SIRET = Regex("(?i)\\bsiret\\b[^0-9]{0,12}((?:\\d[ .]?){14})")
    private val SIREN = Regex("(?i)\\bsiren\\b[^0-9]{0,12}((?:\\d[ .]?){9})")
    private val VAT = Regex("(?i)\\b(FR[ ]?[0-9A-Z]{2}[ ]?\\d{3}[ ]?\\d{3}[ ]?\\d{3})\\b")
    private val IBAN = Regex("\\b([A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?)\\b")
    private val BIC = Regex("(?i)\\bbic\\b[^A-Z0-9]{0,10}([A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\\b")
    private val RATE = Regex("(?i)\\btva\\b[^0-9%]{0,20}(\\d{1,2}(?:[.,]\\d{1,2})?)\\s*%")

    private val AMOUNT_LABELS = mapOf(
        "ht" to listOf(
            "total ht", "montant ht", "total h.t", "sous-total", "total hors taxe",
            "base ht", "total net ht", "net ht", "total excl"
        ),
        "tva" to listOf(
            "total tva", "montant tva", "dont tva", "tva ", "t.v.a", "vat amount"
        ),
        "ttc" to listOf(
            "total ttc", "montant ttc", "net à payer", "net a payer", "total t.t.c",
            "total à payer", "total a payer", "montant dû", "montant du", "total incl"
        )
    )

    fun parse(text: String): InvoiceFields {
        if (text.isBlank()) return InvoiceFields()
        val lines = text.lines()

        val siretRaw = SIRET.find(text)?.groupValues?.get(1)?.filter(Char::isDigit)
        val siret = siretRaw?.takeIf { it.length == 14 && luhn(it) }
        val sirenRaw = SIREN.find(text)?.groupValues?.get(1)?.filter(Char::isDigit)
        val siren = sirenRaw?.takeIf { it.length == 9 && luhn(it) }
            ?: siret?.take(9)?.takeIf { luhn(it) }

        val vat = VAT.find(text)?.groupValues?.get(1)
            ?.replace(" ", "")?.uppercase(Locale.ROOT)
            ?.takeIf { frenchVatValid(it) }

        val iban = IBAN.findAll(text)
            .map { it.groupValues[1].replace(" ", "").uppercase(Locale.ROOT) }
            .firstOrNull { ibanValid(it) }

        return InvoiceFields(
            number = NUMBER.find(text)?.groupValues?.get(1)?.trim('-', '.', '/', '_'),
            date = firstDate(text),
            dueDate = DUE.find(text)?.value?.let { firstDate(it) },
            siret = siret,
            siren = siren,
            vatNumber = vat,
            iban = iban,
            bic = BIC.find(text)?.groupValues?.get(1)?.uppercase(Locale.ROOT),
            totalHt = labelledAmount(lines, AMOUNT_LABELS.getValue("ht")),
            totalVat = labelledAmount(lines, AMOUNT_LABELS.getValue("tva")),
            totalTtc = labelledAmount(lines, AMOUNT_LABELS.getValue("ttc")),
            vatRates = RATE.findAll(text)
                .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .distinct().sorted().toList(),
            issuer = guessIssuer(lines)
        )
    }

    private fun firstDate(text: String): String? {
        ISO_DATE.find(text)?.let { m ->
            return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
        }
        val m = DATE.find(text) ?: return null
        val day = m.groupValues[1].padStart(2, '0')
        val rawMonth = m.groupValues[2]
        val month = rawMonth.toIntOrNull()?.toString()?.padStart(2, '0') ?: monthFromName(rawMonth)
        val year = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
        return if (month == null) null else "$day/$month/$year"
    }

    private fun monthFromName(name: String): String? {
        val key = PdfText.foldAccents(name)
        return when {
            key.startsWith("janv") -> "01"
            key.startsWith("fev") -> "02"
            key.startsWith("mar") -> "03"
            key.startsWith("avr") -> "04"
            key.startsWith("mai") -> "05"
            key.startsWith("juin") -> "06"
            key.startsWith("juil") -> "07"
            key.startsWith("aou") -> "08"
            key.startsWith("sep") -> "09"
            key.startsWith("oct") -> "10"
            key.startsWith("nov") -> "11"
            key.startsWith("dec") -> "12"
            else -> null
        }
    }

    /**
     * Un montant n'est retenu que s'il est sur la même ligne que son étiquette,
     * ou sur la suivante : c'est ce qui distingue « Total TTC 1 200,00 » d'un
     * nombre qui traîne ailleurs dans la page.
     */
    private fun labelledAmount(lines: List<String>, labels: List<String>): Double? {
        for ((index, line) in lines.withIndex()) {
            val lower = line.lowercase(Locale.FRANCE)
            val label = labels.firstOrNull { lower.contains(it) } ?: continue
            val after = line.substring(
                (lower.indexOf(label) + label.length).coerceAtMost(line.length)
            )
            amountIn(after)?.let { return it }
            lines.getOrNull(index + 1)?.let { next -> amountIn(next)?.let { return it } }
        }
        return null
    }

    private val AMOUNT = Regex("(-?\\d{1,3}(?:[ .\\u00A0]\\d{3})*(?:[.,]\\d{1,2})?|-?\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|EUR)?")

    private fun amountIn(fragment: String): Double? {
        val candidates = AMOUNT.findAll(fragment)
            .map { it.groupValues[1] }
            .mapNotNull { InvoiceXml.parseAmount(it) }
            .filter { kotlin.math.abs(it) >= 0.01 }
            .toList()
        return candidates.lastOrNull()
    }

    /** L'émetteur est presque toujours la première ligne substantielle de la page. */
    private fun guessIssuer(lines: List<String>): String? = lines
        .asSequence()
        .map { it.trim() }
        .filter { it.length in 3..60 }
        .filterNot { it.contains("facture", true) || it.contains("invoice", true) }
        .filterNot { it.count { ch -> ch.isDigit() } > it.length / 2 }
        .firstOrNull()

    // ------------------------------------------------------------- validations

    /** Clé de Luhn — un SIRET saisi de travers ne passe pas. */
    fun luhn(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var value = digits[i] - '0'
            if (double) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }

    /** Clé du numéro de TVA français : `(12 + 3 × (SIREN mod 97)) mod 97`. */
    fun frenchVatValid(vat: String): Boolean {
        val normalized = vat.replace(" ", "").uppercase(Locale.ROOT)
        if (!normalized.startsWith("FR") || normalized.length != 13) return false
        val key = normalized.substring(2, 4)
        val siren = normalized.substring(4)
        if (siren.any { !it.isDigit() }) return false
        if (!luhn(siren)) return false
        if (key.any { !it.isDigit() }) return true // clés alphanumériques : non vérifiables
        val expected = (12 + 3 * (siren.toLong() % 97)) % 97
        return key.toInt().toLong() == expected
    }

    /** Contrôle IBAN modulo 97 (ISO 13616). */
    fun ibanValid(iban: String): Boolean {
        val normalized = iban.replace(" ", "").uppercase(Locale.ROOT)
        if (normalized.length !in 15..34) return false
        if (!normalized.all { it.isLetterOrDigit() }) return false
        val rearranged = normalized.substring(4) + normalized.substring(0, 4)
        var remainder = 0L
        for (ch in rearranged) {
            val value = if (ch.isDigit()) ch - '0' else ch - 'A' + 10
            remainder = if (value > 9) (remainder * 100 + value) % 97 else (remainder * 10 + value) % 97
        }
        return remainder == 1L
    }
}
