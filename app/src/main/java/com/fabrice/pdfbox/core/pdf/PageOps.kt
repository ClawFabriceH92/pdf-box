package com.fabrice.pdfbox.core.pdf

import com.fabrice.pdfbox.core.util.NoProgress
import com.fabrice.pdfbox.core.util.ProgressSink
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * Opérations sur la pagination : extraire, supprimer, réordonner, pivoter,
 * fusionner, numéroter.
 *
 * Toutes travaillent **dans le document chargé** plutôt que de recopier les
 * pages vers un document neuf. Recopier casse en silence ce qui n'est pas la
 * page elle-même : liens, signets, champs de formulaire. En retirant les pages
 * non voulues puis en enregistrant, PDFBox n'écrit que les objets encore
 * atteignables, et le reste survit intact.
 */
object PageOps {

    fun extract(source: File, pages: List<Int>, target: File, password: String? = null): File {
        require(pages.isNotEmpty()) { "Aucune page sélectionnée." }
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val keep = pages.filter { it in 0 until doc.numberOfPages }.distinct().sorted()
            require(keep.isNotEmpty()) { "Les pages demandées n'existent pas dans ce document." }
            val ordered = pages.filter { it in keep }.distinct()
            reorderInPlace(doc, ordered)
            doc.save(target)
        }
        return target
    }

    fun deletePages(source: File, pages: Set<Int>, target: File, password: String? = null): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val keep = (0 until doc.numberOfPages).filterNot { it in pages }
            require(keep.isNotEmpty()) { "Il doit rester au moins une page." }
            reorderInPlace(doc, keep)
            doc.save(target)
        }
        return target
    }

    fun reorder(source: File, order: List<Int>, target: File, password: String? = null): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            require(order.sorted() == (0 until doc.numberOfPages).toList()) {
                "L'ordre demandé ne couvre pas exactement les pages du document."
            }
            reorderInPlace(doc, order)
            doc.save(target)
        }
        return target
    }

    fun rotate(
        source: File,
        pages: Set<Int>,
        degrees: Int,
        target: File,
        password: String? = null
    ): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val targets = if (pages.isEmpty()) (0 until doc.numberOfPages).toSet() else pages
            targets.forEach { index ->
                if (index in 0 until doc.numberOfPages) {
                    val page = doc.getPage(index)
                    page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
                }
            }
            doc.save(target)
        }
        return target
    }

    fun merge(
        sources: List<File>,
        target: File,
        progress: ProgressSink = NoProgress
    ): File {
        require(sources.size >= 2) { "Il faut au moins deux documents à fusionner." }
        val merger = PDFMergerUtility()
        merger.destinationFileName = target.absolutePath
        sources.forEachIndexed { index, file ->
            progress.onProgress(index, sources.size, file.name)
            merger.addSource(file)
        }
        // `MemoryUsageSetting` par défaut = tout en mémoire. Sur un téléphone,
        // fusionner plusieurs scans y suffit rarement : on bascule sur un
        // fichier temporaire au-delà de 32 Mo.
        merger.mergeDocuments(
            com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(32L * 1024 * 1024)
        )
        progress.onProgress(sources.size, sources.size, "terminé")
        return target
    }

    enum class NumberPosition(val label: String) {
        BOTTOM_CENTER("En bas, centré"),
        BOTTOM_RIGHT("En bas, à droite"),
        BOTTOM_LEFT("En bas, à gauche"),
        TOP_RIGHT("En haut, à droite")
    }

    /** Numérotation « p. 3/12 » ou « 3 », posée dans l'espace d'affichage. */
    fun numberPages(
        source: File,
        target: File,
        position: NumberPosition = NumberPosition.BOTTOM_CENTER,
        showTotal: Boolean = true,
        startAt: Int = 1,
        skipFirst: Boolean = false,
        fontSize: Float = 10f,
        password: String? = null
    ): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val font: PDFont = PDType1Font.HELVETICA
            val total = doc.numberOfPages
            for (index in 0 until total) {
                if (skipFirst && index == 0) continue
                val page = doc.getPage(index)
                val label = if (showTotal) "p. ${index + startAt}/${total + startAt - 1}" else "${index + startAt}"
                val text = PdfText.winAnsiSafe(label)
                val width = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(40f)
                val size = PdfGeometry.displaySize(page)
                val margin = 24f
                val x = when (position) {
                    NumberPosition.BOTTOM_CENTER -> (size.widthPt - width) / 2f
                    NumberPosition.BOTTOM_RIGHT, NumberPosition.TOP_RIGHT -> size.widthPt - width - margin
                    NumberPosition.BOTTOM_LEFT -> margin
                }
                val y = if (position == NumberPosition.TOP_RIGHT) margin else size.heightPt - margin - fontSize
                PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    PdfGeometry.beginPlacement(cs, page, x, y, width.coerceAtLeast(1f), fontSize)
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(0.25f, 0.25f, 0.25f)
                    cs.newLineAtOffset(0f, fontSize * 0.2f)
                    cs.showText(text)
                    cs.endText()
                    cs.restoreGraphicsState()
                }
            }
            doc.save(target)
        }
        return target
    }

    /**
     * Réordonne dans le document ouvert. Les pages retirées de l'arbre et non
     * réinsérées disparaissent de l'enregistrement : c'est ce qui sert aussi à
     * extraire et à supprimer.
     */
    private fun reorderInPlace(doc: PDDocument, order: List<Int>) {
        val original = ArrayList<PDPage>(doc.numberOfPages)
        for (page in doc.pages) original.add(page)
        original.forEach { doc.pages.remove(it) }
        order.forEach { index -> doc.pages.add(original[index]) }
    }
}
