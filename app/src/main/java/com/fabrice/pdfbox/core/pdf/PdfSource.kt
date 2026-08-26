package com.fabrice.pdfbox.core.pdf

import android.content.Context
import android.net.Uri
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.core.util.Storage
import com.fabrice.pdfbox.core.util.copyTo
import com.fabrice.pdfbox.core.util.sizeOf
import java.io.File

/**
 * Ramène n'importe quelle source à un fichier local **seekable**.
 *
 * `PdfRenderer` comme PDFBox veulent un accès aléatoire : un `content://`
 * servi par un fournisseur distant (Drive, messagerie) n'en offre pas
 * toujours. On matérialise donc une copie de travail, réutilisée tant que la
 * taille de la source ne change pas.
 */
object PdfSource {

    fun localFile(context: Context, uri: Uri): File {
        if (uri.scheme == "file") {
            val f = File(requireNotNull(uri.path) { "URI file:// sans chemin" })
            if (f.exists()) return f
        }
        val size = context.contentResolver.sizeOf(uri)
        val key = "%08x".format(uri.toString().hashCode()) + "-" + size
        val cached = File(Storage.workDir(context), "src-$key.pdf")
        if (cached.exists() && cached.length() > 0L && (size <= 0L || cached.length() == size)) {
            return cached
        }
        val tmp = File(cached.parentFile, cached.name + ".part")
        context.contentResolver.copyTo(uri, tmp)
        if (cached.exists()) cached.delete()
        if (!tmp.renameTo(cached)) return tmp
        return cached
    }

    fun localFile(context: Context, doc: Doc): File = localFile(context, doc.parsedUri)

    /** Copie de travail modifiable : les outils n'écrivent jamais sur la source. */
    fun scratchCopy(context: Context, source: File, suffix: String): File {
        val out = File(Storage.workDir(context), "tmp-${System.nanoTime()}-$suffix")
        source.copyTo(out, overwrite = true)
        return out
    }
}
