package com.fabrice.pdfbox.core.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Emplacements internes. Tout ce que l'application produit reste sous
 * `filesDir`, hors de portée des autres applications ; rien n'est écrit dans
 * le stockage partagé sans une action explicite de l'utilisateur (SAF).
 */
object Storage {

    fun libraryDir(context: Context): File =
        File(context.filesDir, "library").apply { mkdirs() }

    fun attachmentsDir(context: Context): File =
        File(context.filesDir, "attachments").apply { mkdirs() }

    fun exportsDir(context: Context): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    fun workDir(context: Context): File =
        File(context.cacheDir, "work").apply { mkdirs() }

    /** Fichier de sortie au nom unique : « rapport (2).pdf » plutôt qu'un écrasement. */
    fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val base = baseName(name)
        val ext = extensionOf(name).let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(dir, "$base$ext")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    /** Purge des fichiers de travail plus vieux que 24 h. */
    fun pruneWorkDir(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        workDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }
}

/** Nom affichable d'un `content://`, avec repli sur le dernier segment du chemin. */
fun ContentResolver.displayName(uri: Uri): String {
    if (uri.scheme == "file") return uri.lastPathSegment ?: "document"
    var cursor: Cursor? = null
    try {
        cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
    } catch (_: Exception) {
        // Certains fournisseurs refusent la requête : le repli suffit.
    } finally {
        cursor?.close()
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
}

fun ContentResolver.sizeOf(uri: Uri): Long {
    if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
    var cursor: Cursor? = null
    try {
        cursor = query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
    } catch (_: Exception) {
    } finally {
        cursor?.close()
    }
    return try {
        openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun ContentResolver.copyTo(uri: Uri, target: File): File {
    openInputStream(uri).use { input ->
        requireNotNull(input) { "Flux illisible : $uri" }
        FileOutputStream(target).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
    }
    return target
}

fun InputStream.copyToFile(target: File): File {
    FileOutputStream(target).use { out -> copyTo(out, DEFAULT_BUFFER_SIZE) }
    return target
}

/**
 * Conserve durablement l'accès à un document choisi par l'utilisateur.
 * Sans cela, l'URI redevient illisible au prochain lancement.
 */
fun Context.persistUriPermission(uri: Uri): Boolean = try {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    true
} catch (_: SecurityException) {
    false
}

fun Context.canStillRead(uri: Uri): Boolean = try {
    if (uri.scheme == "file") File(uri.path!!).canRead()
    else contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
} catch (_: Exception) {
    false
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
