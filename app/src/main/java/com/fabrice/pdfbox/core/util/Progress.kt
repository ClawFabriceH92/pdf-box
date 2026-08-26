package com.fabrice.pdfbox.core.util

/** Rapport d'avancement d'une opération longue, consommé par l'interface. */
fun interface ProgressSink {
    fun onProgress(done: Int, total: Int, label: String)
}

val NoProgress = ProgressSink { _, _, _ -> }

/**
 * Résultat d'une opération de traitement : soit un fichier produit, soit une
 * erreur *expliquée*. Les écrans n'affichent jamais une trace Java brute.
 */
sealed interface TaskResult<out T> {
    data class Ok<T>(val value: T) : TaskResult<T>
    data class Failed(val message: String, val cause: Throwable? = null) : TaskResult<Nothing>
}

inline fun <T> runTask(errorPrefix: String, block: () -> T): TaskResult<T> = try {
    TaskResult.Ok(block())
} catch (e: OutOfMemoryError) {
    TaskResult.Failed("$errorPrefix : document trop volumineux pour la mémoire disponible.")
} catch (e: Exception) {
    TaskResult.Failed("$errorPrefix : ${humanMessage(e)}", e)
}

fun humanMessage(e: Throwable): String {
    val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    return when {
        raw.contains("password", true) || raw.contains("decrypt", true) ->
            "le document est protégé par un mot de passe."
        raw.contains("ENOSPC", true) || raw.contains("No space", true) ->
            "plus assez d'espace libre sur l'appareil."
        raw.contains("EACCES", true) || raw.contains("Permission denied", true) ->
            "accès refusé à ce fichier (autorisation expirée ?)."
        raw.contains("FileNotFound", true) || raw.contains("ENOENT", true) ->
            "fichier introuvable — il a peut-être été déplacé ou supprimé."
        else -> raw
    }
}
