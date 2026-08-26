package com.fabrice.pdfbox.core.data

import android.content.Context
import android.net.Uri
import com.fabrice.pdfbox.core.pdf.PdfDoc
import com.fabrice.pdfbox.core.pdf.PdfProtectedException
import com.fabrice.pdfbox.core.pdf.PdfSource
import com.fabrice.pdfbox.core.pdf.PdfText
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.copyTo
import com.fabrice.pdfbox.core.util.displayName
import com.fabrice.pdfbox.core.util.extensionOf
import com.fabrice.pdfbox.core.util.humanMessage
import com.fabrice.pdfbox.core.util.persistUriPermission
import com.fabrice.pdfbox.core.util.sanitizeFileName
import com.fabrice.pdfbox.core.util.sha256
import com.fabrice.pdfbox.core.util.sizeOf
import com.fabrice.pdfbox.core.xml.ZipImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed interface ImportResult {
    data class Added(val doc: Doc, val note: String? = null) : ImportResult
    data class Bundle(val doc: Doc, val xmlCount: Int, val skipped: List<String>) : ImportResult
    data class Duplicate(val existing: Doc) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * Point d'entrée unique de tout ce qui rentre dans l'application : sélecteur
 * de fichiers, partage depuis une autre application, ouverture d'une pièce
 * jointe, archive de facture.
 */
object Importer {

    /**
     * @param persistable vrai quand l'URI vient du sélecteur système et peut
     * être mémorisée durablement. Dans ce cas le fichier **reste chez
     * l'utilisateur** ; sinon on en fait une copie, faute de quoi le document
     * deviendrait illisible dès la fin du partage.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        persistable: Boolean,
        suggestedName: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val name = sanitizeFileName(suggestedName ?: context.contentResolver.displayName(uri))
            val head = readHead(context, uri)
            when {
                ZipImport.looksLikeZip(head) && extensionOf(name) != "pdf" ->
                    importZip(context, uri, name)
                looksLikeXml(head, name) -> importXml(context, uri, name)
                looksLikeImage(context, uri, name) -> ImportResult.Failed(
                    "Les images passent par « Outils ▸ Images → PDF »."
                )
                else -> importPdf(context, uri, name, persistable)
            }
        } catch (e: Exception) {
            ImportResult.Failed("Import impossible : ${humanMessage(e)}")
        }
    }

    // ------------------------------------------------------------------- PDF

    private suspend fun importPdf(
        context: Context,
        uri: Uri,
        name: String,
        persistable: Boolean
    ): ImportResult {
        val persisted = persistable && context.persistUriPermission(uri)
        val fileName = if (extensionOf(name) == "pdf") name else "$name.pdf"

        val (storedUri, managed, localFile) = if (persisted) {
            Triple(uri.toString(), false, PdfSource.localFile(context, uri))
        } else {
            val target = Storage.uniqueFile(Storage.libraryDir(context), fileName)
            context.contentResolver.copyTo(uri, target)
            Triple(Uri.fromFile(target).toString(), true, target)
        }

        Library.findByUri(storedUri)?.let { existing ->
            if (managed) localFile.delete()
            return ImportResult.Duplicate(existing)
        }

        val probe = probe(localFile)
        val hash = if (localFile.length() in 1..(100L * 1024 * 1024)) {
            runCatching { localFile.sha256() }.getOrNull()
        } else null

        if (hash != null) {
            Library.findBySha(hash).firstOrNull()?.let { existing ->
                if (managed) localFile.delete()
                return ImportResult.Duplicate(existing)
            }
        }

        val doc = Doc(
            title = com.fabrice.pdfbox.core.util.baseName(fileName),
            uri = storedUri,
            managed = managed,
            sizeBytes = if (managed) localFile.length() else context.contentResolver.sizeOf(uri),
            pageCount = probe.pageCount,
            modifiedAt = localFile.lastModified(),
            sha256 = hash,
            encrypted = probe.encrypted
        )
        val id = Library.upsert(doc)
        val saved = Library.doc(id) ?: doc.copy(id = id)
        if (!probe.encrypted) indexInBackground(saved, localFile)
        val note = when {
            probe.encrypted -> "Document protégé : le mot de passe sera demandé à l'ouverture."
            probe.pageCount == 0 -> "Aucune page lisible dans ce fichier."
            else -> null
        }
        return ImportResult.Added(saved, note)
    }

    // ------------------------------------------------------------- facture zip

    private suspend fun importZip(context: Context, uri: Uri, name: String): ImportResult {
        val workDir = File(Storage.workDir(context), "zip-${System.nanoTime()}").apply { mkdirs() }
        val extracted = context.contentResolver.openInputStream(uri)?.use {
            ZipImport.extract(it, workDir)
        } ?: return ImportResult.Failed("Archive illisible.")

        if (extracted.total == 0) {
            workDir.deleteRecursively()
            return ImportResult.Failed("Archive vide : ni PDF ni XML à l'intérieur.")
        }

        val bundleName = com.fabrice.pdfbox.core.util.baseName(name)
        val pdf = extracted.pdfs.firstOrNull()
        val docFile: File
        val pageCount: Int
        val encrypted: Boolean

        if (pdf != null) {
            docFile = Storage.uniqueFile(Storage.libraryDir(context), "$bundleName.pdf")
            pdf.copyTo(docFile, overwrite = true)
            val probe = probe(docFile)
            pageCount = probe.pageCount
            encrypted = probe.encrypted
        } else {
            // Facture sans PDF (dépôt EDI pur) : la fiche existe quand même,
            // pour que les XML aient un endroit où vivre.
            val firstXml = extracted.xmls.firstOrNull()
                ?: return ImportResult.Failed("Archive sans PDF ni XML exploitable.").also {
                    workDir.deleteRecursively()
                }
            docFile = Storage.uniqueFile(Storage.attachmentsDir(context), firstXml.name)
            firstXml.copyTo(docFile, overwrite = true)
            pageCount = 0
            encrypted = false
        }

        val doc = Doc(
            title = bundleName,
            uri = Uri.fromFile(docFile).toString(),
            managed = true,
            sizeBytes = docFile.length(),
            pageCount = pageCount,
            modifiedAt = System.currentTimeMillis(),
            sha256 = runCatching { docFile.sha256() }.getOrNull(),
            encrypted = encrypted,
            tag = "facture"
        )
        val id = Library.upsert(doc)

        var attached = 0
        (extracted.xmls + extracted.others).forEach { file ->
            val stored = Storage.uniqueFile(Storage.attachmentsDir(context), file.name)
            file.copyTo(stored, overwrite = true)
            Library.addAttachment(
                Attachment(
                    docId = id,
                    name = file.name,
                    mime = mimeOf(file.name),
                    relPath = stored.name,
                    sizeBytes = stored.length()
                )
            )
            attached++
        }
        // Les PDF supplémentaires de l'archive rejoignent la bibliothèque
        // comme documents à part entière : ce sont des factures distinctes.
        extracted.pdfs.drop(1).forEach { extra ->
            val target = Storage.uniqueFile(Storage.libraryDir(context), extra.name)
            extra.copyTo(target, overwrite = true)
            val probe = probe(target)
            Library.upsert(
                Doc(
                    title = com.fabrice.pdfbox.core.util.baseName(extra.name),
                    uri = Uri.fromFile(target).toString(),
                    managed = true,
                    sizeBytes = target.length(),
                    pageCount = probe.pageCount,
                    encrypted = probe.encrypted,
                    tag = "facture"
                )
            )
        }

        workDir.deleteRecursively()
        val saved = Library.doc(id) ?: doc.copy(id = id)
        if (pageCount > 0 && !encrypted) indexInBackground(saved, docFile)
        return ImportResult.Bundle(saved, attached, extracted.skipped)
    }

    // ------------------------------------------------------------------- XML

    private suspend fun importXml(context: Context, uri: Uri, name: String): ImportResult {
        val fileName = if (extensionOf(name) == "xml") name else "$name.xml"
        val stored = Storage.uniqueFile(Storage.attachmentsDir(context), fileName)
        context.contentResolver.copyTo(uri, stored)
        val doc = Doc(
            title = com.fabrice.pdfbox.core.util.baseName(fileName),
            uri = Uri.fromFile(stored).toString(),
            managed = true,
            sizeBytes = stored.length(),
            pageCount = 0,
            modifiedAt = stored.lastModified(),
            tag = "facture"
        )
        val id = Library.upsert(doc)
        Library.addAttachment(
            Attachment(
                docId = id,
                name = fileName,
                mime = "text/xml",
                relPath = stored.name,
                sizeBytes = stored.length()
            )
        )
        return ImportResult.Added(
            Library.doc(id) ?: doc.copy(id = id),
            "XML sans PDF associé : il s'ouvre dans la visionneuse XML."
        )
    }

    // ------------------------------------------------------------------ outils

    /** Enregistre un fichier produit par l'application (export, conversion). */
    suspend fun adoptProducedFile(
        context: Context,
        file: File,
        title: String,
        tag: String? = null
    ): Doc = withContext(Dispatchers.IO) {
        val target = if (file.parentFile == Storage.libraryDir(context)) file else {
            Storage.uniqueFile(Storage.libraryDir(context), file.name).also {
                file.copyTo(it, overwrite = true)
            }
        }
        val probe = probe(target)
        val doc = Doc(
            title = title,
            uri = Uri.fromFile(target).toString(),
            managed = true,
            sizeBytes = target.length(),
            pageCount = probe.pageCount,
            modifiedAt = target.lastModified(),
            sha256 = runCatching { target.sha256() }.getOrNull(),
            encrypted = probe.encrypted,
            tag = tag
        )
        val id = Library.upsert(doc)
        val saved = Library.doc(id) ?: doc.copy(id = id)
        if (!probe.encrypted && probe.pageCount > 0) indexInBackground(saved, target)
        saved
    }

    data class Probe(val pageCount: Int, val encrypted: Boolean)

    fun probe(file: File): Probe = try {
        PdfDoc.use(file) { Probe(it.numberOfPages, it.isEncrypted) }
    } catch (_: PdfProtectedException) {
        Probe(0, true)
    } catch (_: Exception) {
        Probe(0, false)
    }

    /**
     * Indexation plein texte (B2). Bornée : au-delà de 400 pages, l'extraction
     * bloquerait l'import plusieurs dizaines de secondes pour un gain marginal.
     */
    suspend fun indexInBackground(doc: Doc, file: File, maxPages: Int = 400) {
        if (doc.pageCount <= 0 || doc.pageCount > maxPages) return
        runCatching {
            val pages = PdfText.extractAll(file)
            if (pages.any { it.isNotBlank() }) Library.indexDocument(doc.id, pages)
        }
    }

    // ------------------------------------------------------------------ formats

    private fun readHead(context: Context, uri: Uri): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(512)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOfRange(0, read)
        } ?: ByteArray(0)
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun looksLikeXml(head: ByteArray, name: String): Boolean {
        if (extensionOf(name) == "xml") return true
        if (head.size < 5) return false
        val prefix = String(head, 0, minOf(head.size, 64)).trim().removePrefix("\uFEFF")
        return prefix.startsWith("<?xml", true)
    }

    private fun looksLikeImage(context: Context, uri: Uri, name: String): Boolean {
        val extension = extensionOf(name)
        if (extension in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")) return true
        val type = context.contentResolver.getType(uri).orEmpty()
        return type.startsWith("image/")
    }

    private fun mimeOf(name: String): String = when (extensionOf(name)) {
        "xml", "cii", "ubl" -> "text/xml"
        "pdf" -> "application/pdf"
        "csv" -> "text/csv"
        "txt", "edi" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }.lowercase(Locale.ROOT)
}
