package com.fabrice.pdfbox

import com.fabrice.pdfbox.core.ocr.InvoiceParser
import com.fabrice.pdfbox.core.pdf.TableExtractor
import com.fabrice.pdfbox.core.pdf.WordBox
import com.fabrice.pdfbox.core.util.formatSize
import com.fabrice.pdfbox.core.util.sanitizeFileName
import com.fabrice.pdfbox.core.xml.InvoiceXml
import com.fabrice.pdfbox.core.util.PageRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {

    @Test
    fun `plages et pages isolees`() {
        assertEquals(listOf(0, 1, 2, 6), PageRanges.parse("1-3, 7", 10))
    }

    @Test
    fun `borne ouverte va jusqu a la fin`() {
        assertEquals(listOf(7, 8, 9), PageRanges.parse("8-", 10))
    }

    @Test
    fun `l ordre saisi est conserve`() {
        assertEquals(listOf(4, 0, 1), PageRanges.parse("5, 1-2", 6))
    }

    @Test
    fun `hors bornes ignore sans erreur`() {
        assertEquals(listOf(0), PageRanges.parse("1, 99", 3))
        assertEquals(emptyList<Int>(), PageRanges.parse("42", 3))
    }

    @Test
    fun `formatage compacte les suites`() {
        assertEquals("1-3, 7, 9-10", PageRanges.format(listOf(0, 1, 2, 6, 8, 9)))
    }

    @Test
    fun `aller-retour stable`() {
        val pages = listOf(0, 1, 2, 5, 9)
        assertEquals(pages, PageRanges.parse(PageRanges.format(pages), 12))
    }
}

class InvoiceValidationTest {

    @Test
    fun `luhn accepte un siret valide`() {
        // SIRET de démonstration : 732 829 320 00074 (clé de Luhn correcte).
        assertTrue(InvoiceParser.luhn("73282932000074"))
    }

    @Test
    fun `luhn rejette un chiffre altere`() {
        assertFalse(InvoiceParser.luhn("73282932000075"))
    }

    @Test
    fun `cle tva francaise verifiee`() {
        assertTrue(InvoiceParser.frenchVatValid("FR44732829320"))
        assertFalse(InvoiceParser.frenchVatValid("FR45732829320"))
        assertFalse(InvoiceParser.frenchVatValid("FR4473282932"))
    }

    @Test
    fun `iban modulo 97`() {
        assertTrue(InvoiceParser.ibanValid("FR1420041010050500013M02606"))
        assertTrue(InvoiceParser.ibanValid("FR14 2004 1010 0505 0001 3M02 606"))
        assertFalse(InvoiceParser.ibanValid("FR1420041010050500013M02607"))
    }

    @Test
    fun `montants etiquetes reconnus`() {
        val text = """
            SARL Exemple
            Facture n° F-2025-018
            Date : 12/03/2025
            SIRET : 732 829 320 00074
            Prestation de conseil        1 000,00 €
            Total HT                     1 000,00 €
            TVA 20 %                       200,00 €
            Total TTC                    1 200,00 €
        """.trimIndent()
        val fields = InvoiceParser.parse(text)
        assertEquals(1000.0, fields.totalHt!!, 0.001)
        assertEquals(200.0, fields.totalVat!!, 0.001)
        assertEquals(1200.0, fields.totalTtc!!, 0.001)
        assertEquals("12/03/2025", fields.date)
        assertEquals("73282932000074", fields.siret)
        assertTrue(fields.totalsConsistent == true)
    }

    @Test
    fun `un siret invalide n est pas retenu`() {
        val fields = InvoiceParser.parse("SIRET : 12345678901234")
        assertNull(fields.siret)
    }
}

class AmountParsingTest {

    @Test
    fun `formats francais et anglo-saxons`() {
        assertEquals(1234.56, InvoiceXml.parseAmount("1 234,56")!!, 0.001)
        assertEquals(1234.56, InvoiceXml.parseAmount("1.234,56")!!, 0.001)
        assertEquals(1234.56, InvoiceXml.parseAmount("1234.56")!!, 0.001)
        assertEquals(-42.0, InvoiceXml.parseAmount("-42")!!, 0.001)
        assertNull(InvoiceXml.parseAmount(""))
        assertNull(InvoiceXml.parseAmount(null))
    }

    @Test
    fun `dates normalisees`() {
        assertEquals("12/03/2025", InvoiceXml.normalizeDate("20250312"))
        assertEquals("12/03/2025", InvoiceXml.normalizeDate("2025-03-12"))
        assertEquals("12/03/2025", InvoiceXml.normalizeDate("2025-03-12T10:00:00"))
    }
}

class TableExtractorTest {

    /** Trois colonnes séparées par de vrais blancs, quatre lignes. */
    private fun grid(): List<WordBox> {
        val columns = listOf(0.05f to 0.28f, 0.40f to 0.60f, 0.72f to 0.95f)
        val rows = listOf(
            listOf("Désignation", "Quantité", "Montant"),
            listOf("Conseil", "2", "1000,00"),
            listOf("Formation", "1", "500,00"),
            listOf("Support", "3", "300,00")
        )
        val out = mutableListOf<WordBox>()
        rows.forEachIndexed { rowIndex, cells ->
            val top = 0.10f + rowIndex * 0.08f
            cells.forEachIndexed { columnIndex, cell ->
                val (left, right) = columns[columnIndex]
                out += WordBox(cell, left, top, right, top + 0.03f)
            }
        }
        return out
    }

    @Test
    fun `la grille est retrouvee`() {
        val tables = TableExtractor.extract(grid(), page = 0)
        assertEquals(1, tables.size)
        val table = tables.first()
        assertEquals(4, table.rowCount)
        assertEquals(3, table.rows.first().size)
        assertEquals("Désignation", table.rows[0][0])
        assertEquals("1000,00", table.rows[1][2])
    }

    @Test
    fun `un paragraphe ne produit pas de tableau`() {
        val words = (0 until 30).map { index ->
            val row = index / 6
            val column = index % 6
            WordBox(
                "mot$index",
                0.05f + column * 0.15f,
                0.1f + row * 0.06f,
                0.05f + column * 0.15f + 0.14f,
                0.13f + row * 0.06f
            )
        }
        // Les mots se touchent presque : aucun blanc vertical ne traverse.
        assertTrue(TableExtractor.extract(words, page = 0).isEmpty())
    }

    @Test
    fun `csv echappe les separateurs et les guillemets`() {
        val table = TableExtractor.Table(
            page = 0,
            rows = listOf(listOf("a;b", "c\"d"), listOf("e", "f")),
            columnCount = 2
        )
        val csv = TableExtractor.toCsv(listOf(table), TableExtractor.Separator.SEMICOLON, includeBom = false)
        assertEquals("\"a;b\";\"c\"\"d\"\r\ne;f\r\n", csv)
    }

    @Test
    fun `le bom est present pour excel`() {
        val table = TableExtractor.Table(0, listOf(listOf("é")), 1)
        val csv = TableExtractor.toCsv(listOf(table), TableExtractor.Separator.COMMA)
        assertEquals('\uFEFF', csv.first())
    }
}

class FormattingTest {

    @Test
    fun `tailles lisibles`() {
        assertEquals("512 o", formatSize(512))
        assertTrue(formatSize(1_500_000).endsWith("Mo"))
        assertTrue(formatSize(2_400_000_000).endsWith("Go"))
    }

    @Test
    fun `noms de fichiers assainis`() {
        assertEquals("facture_2025", sanitizeFileName("facture/2025"))
        assertEquals("document", sanitizeFileName("   "))
        assertTrue(sanitizeFileName("a".repeat(300)).length <= 120)
    }
}
