package com.fabrice.pdfbox.core.data

import android.net.Uri

/**
 * Un document de la bibliothèque.
 *
 * Deux régimes cohabitent, et la distinction compte pour l'utilisateur :
 *  - `managed = true`  : le fichier a été **copié** dans l'espace privé de
 *    l'application (import par partage, export conservé). Supprimer le document
 *    supprime le fichier.
 *  - `managed = false` : le document est **référencé** là où l'utilisateur l'a
 *    choisi (SAF, permission persistée). Supprimer le document ne retire que la
 *    fiche ; le fichier d'origine n'est pas touché.
 */
data class Doc(
    val id: Long = 0L,
    val title: String,
    val uri: String,
    val managed: Boolean,
    val sizeBytes: Long = 0L,
    val pageCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = 0L,
    val tag: String? = null,
    val sha256: String? = null,
    val encrypted: Boolean = false,
    val indexedAt: Long = 0L,
    val attachmentCount: Int = 0
) {
    val parsedUri: Uri get() = Uri.parse(uri)
    val hasAttachments: Boolean get() = attachmentCount > 0
}

/** Fichier joint à une facture : XML Chorus/Factur-X/UBL, ou toute pièce du .zip. */
data class Attachment(
    val id: Long = 0L,
    val docId: Long,
    val name: String,
    val mime: String,
    val relPath: String,
    val sizeBytes: Long,
    val readable: Boolean = true,
    val problem: String? = null
)

enum class AnnotationType { HIGHLIGHT, NOTE, REDACT, SIGNATURE }

/**
 * Annotation posée sur une page. Les coordonnées sont **normalisées** (0..1)
 * dans l'espace d'affichage de la page : elles restent valides quelle que soit
 * la résolution de rendu, et se retraduisent en points PDF à l'export.
 */
data class Annot(
    val id: Long = 0L,
    val docId: Long,
    val page: Int,
    val type: AnnotationType,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val color: Int = 0xFFFFEB3B.toInt(),
    val text: String? = null,
    val payload: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val left get() = minOf(x0, x1)
    val top get() = minOf(y0, y1)
    val right get() = maxOf(x0, x1)
    val bottom get() = maxOf(y0, y1)
}

/** Entrée d'historique d'extraction de texte (T5). */
data class TextEntry(
    val id: Long = 0L,
    val docId: Long,
    val title: String,
    val excerpt: String,
    val charCount: Int,
    val relPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Résultat d'une recherche plein texte dans la bibliothèque (B2). */
data class SearchHit(
    val docId: Long,
    val page: Int,
    val snippet: String
)

enum class SortOrder(val label: String) {
    RECENT("Ajout récent"),
    NAME("Nom"),
    SIZE("Taille"),
    PAGES("Pages")
}
