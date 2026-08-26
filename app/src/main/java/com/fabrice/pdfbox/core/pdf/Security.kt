package com.fabrice.pdfbox.core.pdf

import com.fabrice.pdfbox.core.util.Storage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File

/**
 * P5F — protection par mot de passe et déverrouillage.
 *
 * Deux mots de passe existent dans le format PDF et la confusion est courante :
 * celui de l'**utilisateur** conditionne l'ouverture, celui du **propriétaire**
 * conditionne les droits (impression, copie, modification). Laisser le second
 * vide reviendrait à publier les droits ; on le renseigne toujours.
 */
object Security {

    enum class Strength(val label: String, val bits: Int) {
        AES_128("AES 128 bits", 128),
        AES_256("AES 256 bits (recommandé)", 256)
    }

    data class Permissions(
        val canPrint: Boolean = true,
        val canExtractContent: Boolean = false,
        val canModify: Boolean = false,
        val canModifyAnnotations: Boolean = false,
        val canFillInForm: Boolean = true,
        val canAssembleDocument: Boolean = false
    )

    fun protect(
        source: File,
        target: File,
        userPassword: String,
        ownerPassword: String = userPassword,
        strength: Strength = Strength.AES_256,
        permissions: Permissions = Permissions(),
        currentPassword: String? = null
    ): File {
        require(userPassword.isNotEmpty()) { "Le mot de passe ne peut pas être vide." }
        PdfDoc.use(source, currentPassword) { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val access = AccessPermission().apply {
                setCanPrint(permissions.canPrint)
                setCanPrintDegraded(permissions.canPrint)
                setCanExtractContent(permissions.canExtractContent)
                setCanExtractForAccessibility(true)
                setCanModify(permissions.canModify)
                setCanModifyAnnotations(permissions.canModifyAnnotations)
                setCanFillInForm(permissions.canFillInForm)
                setCanAssembleDocument(permissions.canAssembleDocument)
            }
            val policy = StandardProtectionPolicy(
                ownerPassword.ifEmpty { userPassword },
                userPassword,
                access
            ).apply {
                encryptionKeyLength = strength.bits
                isPreferAES = true
            }
            doc.isAllSecurityToBeRemoved = false
            doc.protect(policy)
            doc.save(target)
        }
        return target
    }

    /** Retire la protection d'un document dont on connaît le mot de passe. */
    fun unlock(source: File, target: File, password: String): File {
        PdfDoc.use(source, password) { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(target)
        }
        return target
    }

    /**
     * Copie déchiffrée temporaire : `PdfRenderer` (comme tout moteur de rendu
     * système) refuse un document protégé, il faut lui donner un fichier clair.
     */
    fun decryptToTemp(source: File, password: String): File {
        val out = File(source.parentFile ?: source, "dec-${System.nanoTime()}.pdf")
        return unlock(source, out, password)
    }

    fun tempDecrypted(context: android.content.Context, source: File, password: String): File {
        val out = File(Storage.workDir(context), "dec-${System.nanoTime()}.pdf")
        return unlock(source, out, password)
    }

    /** Vérifie un mot de passe sans rien écrire. */
    fun passwordWorks(source: File, password: String): Boolean = try {
        PdfDoc.use(source, password) { it.numberOfPages >= 0 }
    } catch (_: Exception) {
        false
    }
}
