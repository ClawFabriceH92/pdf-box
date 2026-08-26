package com.fabrice.pdfbox.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/**
 * Ouverture d'un PDF avec PDFBox, en distinguant les deux échecs que
 * l'utilisateur peut corriger : le mot de passe manquant et le fichier abîmé.
 */
object PdfDoc {

    fun open(file: File, password: String? = null): PDDocument = try {
        if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)
    } catch (e: InvalidPasswordException) {
        throw PdfProtectedException(e)
    } catch (e: java.io.IOException) {
        val message = e.message.orEmpty()
        if (message.contains("password", true)) throw PdfProtectedException(e)
        throw PdfDamagedException(e)
    }

    inline fun <T> use(file: File, password: String? = null, block: (PDDocument) -> T): T =
        open(file, password).use(block)

    /**
     * Retire la protection en mémoire. PDFBox refuse d'écrire un document
     * déchiffré tant que le drapeau est posé ; c'est l'appel qu'oublient la
     * plupart des intégrations, et l'export échoue alors sans explication.
     */
    fun decryptForWrite(doc: PDDocument) {
        if (doc.isEncrypted) {
            doc.isAllSecurityToBeRemoved = true
        }
    }

    fun isEncrypted(file: File): Boolean = try {
        PDDocument.load(file).use { it.isEncrypted }
    } catch (_: InvalidPasswordException) {
        true
    } catch (_: Exception) {
        false
    }
}

class PdfDamagedException(cause: Throwable? = null) :
    Exception("Ce fichier n'est pas un PDF exploitable (structure illisible).", cause)

/** Fiche descriptive d'un document, telle que l'affiche l'écran Métadonnées. */
data class PdfInfo(
    val pageCount: Int,
    val encrypted: Boolean,
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creator: String?,
    val producer: String?,
    val creationDate: Long,
    val modificationDate: Long,
    val version: Float,
    val hasAcroForm: Boolean,
    val fieldCount: Int,
    val hasExtractableText: Boolean,
    val pageSizes: List<PageSize>
) {
    val looksScanned: Boolean get() = pageCount > 0 && !hasExtractableText
}
