package com.fabrice.pdfbox.core.xml

import java.util.Locale

data class InvoiceLine(
    val label: String,
    val quantity: String?,
    val unitPrice: Double?,
    val total: Double?
)

/**
 * X3 — champs clés d'une facture électronique, quels que soient la norme et le
 * préfixe de namespace employés.
 *
 * Deux formats couvrent l'essentiel de la facturation électronique française :
 * **CII** (UN/CEFACT, ce qu'utilisent Factur-X et Chorus Pro) et **UBL**
 * (OASIS). Les deux décrivent la même facture avec des noms différents ; on
 * cherche donc par nom local, sans se soucier des namespaces, en essayant les
 * deux vocabulaires.
 */
data class InvoiceData(
    val format: String,
    val number: String? = null,
    val typeCode: String? = null,
    val issueDate: String? = null,
    val dueDate: String? = null,
    val sellerName: String? = null,
    val sellerSiret: String? = null,
    val sellerVat: String? = null,
    val buyerName: String? = null,
    val buyerSiret: String? = null,
    val currency: String = "EUR",
    val totalHt: Double? = null,
    val totalVat: Double? = null,
    val totalTtc: Double? = null,
    val payable: Double? = null,
    val lines: List<InvoiceLine> = emptyList()
) {
    val hasAnything: Boolean
        get() = listOf(number, issueDate, sellerName, sellerSiret, sellerVat).any { !it.isNullOrBlank() } ||
            listOf(totalHt, totalVat, totalTtc).any { it != null }

    /** Cohérence arithmétique : HT + TVA = TTC, à un centime près. */
    val totalsConsistent: Boolean?
        get() {
            val ht = totalHt ?: return null
            val vat = totalVat ?: return null
            val ttc = totalTtc ?: return null
            return kotlin.math.abs(ht + vat - ttc) < 0.015
        }
}

object InvoiceXml {

    fun detectFormat(root: XmlNode): String = when {
        root.name.equals("CrossIndustryInvoice", true) -> "CII — Factur-X / Chorus Pro"
        root.name.equals("CrossIndustryDocument", true) -> "CII — ZUGFeRD 1.0"
        root.name.equals("Invoice", true) -> "UBL — Facture"
        root.name.equals("CreditNote", true) -> "UBL — Avoir"
        root.name.contains("Facture", true) -> "Format français propriétaire"
        else -> "XML — format non reconnu"
    }

    fun extract(root: XmlNode): InvoiceData {
        val format = detectFormat(root)
        val isUbl = format.startsWith("UBL")

        val number = firstText(root, if (isUbl) listOf("ID") else listOf("ID"), scope = if (isUbl) null else "ExchangedDocument")
            ?: firstText(root, listOf("InvoiceNumber", "NumeroFacture"))
        val issue = normalizeDate(
            firstText(root, listOf("IssueDate")) ?: firstText(root, listOf("DateTimeString"))
        )
        val due = normalizeDate(
            firstText(root, listOf("DueDate")) ?: firstText(root, listOf("DueDateDateTime", "DateTimeString"), scope = "SpecifiedTradePaymentTerms")
        )

        val sellerScope = root.find("SellerTradeParty", "AccountingSupplierParty")
        val buyerScope = root.find("BuyerTradeParty", "AccountingCustomerParty")

        return InvoiceData(
            format = format,
            number = number,
            typeCode = firstText(root, listOf("TypeCode", "InvoiceTypeCode")),
            issueDate = issue,
            dueDate = due,
            sellerName = sellerScope?.let { partyName(it) },
            sellerSiret = sellerScope?.let { legalId(it) },
            sellerVat = sellerScope?.let { vatId(it) },
            buyerName = buyerScope?.let { partyName(it) },
            buyerSiret = buyerScope?.let { legalId(it) },
            currency = firstText(root, listOf("InvoiceCurrencyCode", "DocumentCurrencyCode"))
                ?.uppercase(Locale.ROOT) ?: "EUR",
            totalHt = amount(root, "TaxBasisTotalAmount", "TaxExclusiveAmount"),
            totalVat = amount(root, "TaxTotalAmount", "TaxAmount"),
            totalTtc = amount(root, "GrandTotalAmount", "TaxInclusiveAmount"),
            payable = amount(root, "DuePayableAmount", "PayableAmount"),
            lines = lines(root)
        )
    }

    // --------------------------------------------------------------- fragments

    private fun partyName(party: XmlNode): String? =
        party.find("Name")?.text?.takeIf { it.isNotBlank() }
            ?: party.find("PartyName")?.find("Name")?.text?.takeIf { it.isNotBlank() }
            ?: party.find("RegistrationName")?.text

    /** SIRET/SIREN : `SpecifiedLegalOrganization/ID` en CII, `CompanyID` en UBL. */
    private fun legalId(party: XmlNode): String? {
        val cii = party.find("SpecifiedLegalOrganization")?.find("ID")?.text
        if (!cii.isNullOrBlank()) return cii.trim()
        val ubl = party.find("PartyLegalEntity")?.find("CompanyID")?.text
        if (!ubl.isNullOrBlank()) return ubl.trim()
        return party.findAll("ID", "CompanyID")
            .map { it.text.orEmpty().filter(Char::isDigit) }
            .firstOrNull { it.length == 9 || it.length == 14 }
    }

    /** Numéro de TVA intracommunautaire : « FR » suivi de 11 caractères. */
    private fun vatId(party: XmlNode): String? {
        val candidates = party.findAll("ID", "CompanyID", "EndpointID")
            .mapNotNull { node ->
                val schemeIsVat = node.attributes.any { (key, value) ->
                    key.endsWith("schemeID", true) && value.contains("VA", true)
                }
                val text = node.text?.trim().orEmpty()
                when {
                    text.matches(Regex("(?i)[A-Z]{2}[0-9A-Z]{8,13}")) -> text.uppercase(Locale.ROOT)
                    schemeIsVat && text.isNotBlank() -> text
                    else -> null
                }
            }
        return candidates.firstOrNull()
    }

    private fun amount(root: XmlNode, ciiName: String, ublName: String): Double? =
        parseAmount(firstText(root, listOf(ciiName)) ?: firstText(root, listOf(ublName)))

    private fun firstText(root: XmlNode, names: List<String>, scope: String? = null): String? {
        val base = if (scope != null) root.find(scope) ?: root else root
        for (name in names) {
            val node = base.find(name)
            val text = node?.text
            if (!text.isNullOrBlank()) return text.trim()
        }
        return null
    }

    private fun lines(root: XmlNode): List<InvoiceLine> {
        val items = root.findAll("IncludedSupplyChainTradeLineItem", "InvoiceLine", "CreditNoteLine")
        return items.take(200).mapNotNull { item ->
            val label = item.find("Name", "Description", "Item")?.let { node ->
                node.text ?: node.find("Name", "Description")?.text
            }?.trim()
            val quantity = item.find("BilledQuantity", "InvoicedQuantity", "ChargeableUnitQuantity")?.text?.trim()
            val unitPrice = parseAmount(
                item.find("ChargeAmount", "PriceAmount")?.text
            )
            val total = parseAmount(
                item.find("LineTotalAmount", "LineExtensionAmount")?.text
            )
            if (label.isNullOrBlank() && quantity == null && total == null) null
            else InvoiceLine(label ?: "(sans libellé)", quantity, unitPrice, total)
        }
    }

    fun parseAmount(raw: String?): Double? {
        val text = raw?.trim()?.replace(" ", "")?.replace("\u00A0", "") ?: return null
        if (text.isEmpty()) return null
        val normalized = when {
            // « 1.234,56 » : le point sépare les milliers.
            text.contains(',') && text.contains('.') && text.lastIndexOf(',') > text.lastIndexOf('.') ->
                text.replace(".", "").replace(',', '.')
            text.contains(',') && !text.contains('.') -> text.replace(',', '.')
            else -> text
        }
        return normalized.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()
    }

    /** « 20250312 » (format CII 102) ou « 2025-03-12 » (UBL) → « 12/03/2025 ». */
    fun normalizeDate(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(text)?.let { m ->
            return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(text)?.let { m ->
            return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
        }
        return text
    }

    /** X4 — nom de partage normalisé : `facture-2025-03-12-ACME.xml`. */
    fun suggestedFileName(data: InvoiceData, fallback: String): String {
        val date = data.issueDate?.replace('/', '-')?.split('-')?.reversed()?.joinToString("-")
        val issuer = data.sellerName
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            ?.trim('-')
            ?.take(28)
        val parts = listOfNotNull("facture", date, issuer).filter { it.isNotBlank() }
        return if (parts.size <= 1) fallback else parts.joinToString("-") + ".xml"
    }
}
