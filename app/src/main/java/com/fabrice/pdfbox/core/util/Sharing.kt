package com.fabrice.pdfbox.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Partage et ouverture de fichiers. Tout passe par un `FileProvider` : aucune
 * autre application ne reçoit de chemin brut, seulement une URI temporaire et
 * révocable sur le fichier concerné.
 */
object Sharing {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareFile(context: Context, file: File, mime: String, title: String = "Partager") {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, title))
    }

    fun shareFiles(context: Context, files: List<File>, mime: String, title: String = "Partager") {
        if (files.size == 1) return shareFile(context, files[0], mime, title)
        val uris = ArrayList<Uri>(files.map { uriFor(context, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, title))
    }

    fun shareText(context: Context, text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        launch(context, Intent.createChooser(intent, "Partager le texte"))
    }

    fun openFile(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launch(context, intent)
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Aucune application ne sait ouvrir ce fichier", Toast.LENGTH_LONG).show()
        }
    }
}
