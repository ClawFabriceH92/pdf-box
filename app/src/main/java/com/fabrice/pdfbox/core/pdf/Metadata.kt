package com.fabrice.pdfbox.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.Calendar

/** P11F — lecture et modification des métadonnées du document. */
object Metadata {

    fun read(file: File, password: String? = null): PdfInfo = PdfDoc.use(file, password) { doc ->
        read(doc)
    }

    fun read(doc: PDDocument): PdfInfo {
        val info = doc.documentInformation
        val acroForm = runCatching { doc.documentCatalog?.acroForm }.getOrNull()
        return PdfInfo(
            pageCount = doc.numberOfPages,
            encrypted = doc.isEncrypted,
            title = info?.title?.takeIf { it.isNotBlank() },
            author = info?.author?.takeIf { it.isNotBlank() },
            subject = info?.subject?.takeIf { it.isNotBlank() },
            keywords = info?.keywords?.takeIf { it.isNotBlank() },
            creator = info?.creator?.takeIf { it.isNotBlank() },
            producer = info?.producer?.takeIf { it.isNotBlank() },
            creationDate = info?.creationDate?.timeInMillis ?: 0L,
            modificationDate = info?.modificationDate?.timeInMillis ?: 0L,
            version = doc.version,
            hasAcroForm = acroForm != null && acroForm.fields.isNotEmpty(),
            fieldCount = acroForm?.fields?.size ?: 0,
            hasExtractableText = runCatching { PdfText.hasExtractableText(doc) }.getOrDefault(false),
            pageSizes = (0 until doc.numberOfPages).map { PdfGeometry.displaySize(doc.getPage(it)) }
        )
    }

    data class Edit(
        val title: String? = null,
        val author: String? = null,
        val subject: String? = null,
        val keywords: String? = null,
        val touchModificationDate: Boolean = true
    )

    fun write(source: File, target: File, edit: Edit, password: String? = null): File {
        PdfDoc.use(source, password) { doc ->
            PdfDoc.decryptForWrite(doc)
            val info = doc.documentInformation
            info.title = edit.title.orEmpty().ifBlank { null }
            info.author = edit.author.orEmpty().ifBlank { null }
            info.subject = edit.subject.orEmpty().ifBlank { null }
            info.keywords = edit.keywords.orEmpty().ifBlank { null }
            info.producer = "PDF Box"
            if (edit.touchModificationDate) info.modificationDate = Calendar.getInstance()
            doc.save(target)
        }
        return target
    }
}
