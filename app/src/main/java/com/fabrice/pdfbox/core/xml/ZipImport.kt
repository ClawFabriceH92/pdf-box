package com.fabrice.pdfbox.core.xml

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * X1 — import d'une facture livrée en archive : un PDF et un ou plusieurs XML
 * (Chorus Pro, PDP, EDIFACT, UBL), parfois accompagnés de pièces jointes.
 *
 * L'extraction refuse les chemins qui remontent hors du dossier cible
 * (« Zip Slip ») : une archive n'a pas à décider où l'application écrit.
 */
object ZipImport {

    data class Extracted(
        val pdfs: List<File>,
        val xmls: List<File>,
        val others: List<File>,
        val skipped: List<String>
    ) {
        val isInvoiceBundle: Boolean get() = pdfs.isNotEmpty() && xmls.isNotEmpty()
        val total: Int get() = pdfs.size + xmls.size + others.size
    }

    private const val MAX_ENTRY_BYTES = 80L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    private const val MAX_ENTRIES = 400

    fun extract(input: InputStream, targetDir: File): Extracted {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalPath
        val pdfs = mutableListOf<File>()
        val xmls = mutableListOf<File>()
        val others = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        var totalBytes = 0L
        var count = 0

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val current = entry
                if (++count > MAX_ENTRIES) {
                    skipped += "archive tronquée après $MAX_ENTRIES fichiers"
                    break
                }
                if (!current.isDirectory) {
                    val name = current.name.substringAfterLast('/').substringAfterLast('\\')
                    val destination = File(targetDir, sanitizeEntryName(name))
                    if (!destination.canonicalPath.startsWith(canonicalTarget)) {
                        skipped += "${current.name} (chemin hors du dossier d'import)"
                    } else {
                        val written = runCatching { copyBounded(zip, destination, MAX_ENTRY_BYTES) }
                            .getOrElse { -1L }
                        when {
                            written < 0 -> {
                                destination.delete()
                                skipped += "${current.name} (illisible)"
                            }
                            else -> {
                                totalBytes += written
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    destination.delete()
                                    skipped += "archive trop volumineuse, extraction interrompue"
                                    return@use
                                }
                                when (classify(destination)) {
                                    Kind.PDF -> pdfs += destination
                                    Kind.XML -> xmls += destination
                                    Kind.OTHER -> others += destination
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Extracted(pdfs, xmls, others, skipped)
    }

    private enum class Kind { PDF, XML, OTHER }

    /**
     * Le classement se fait sur le contenu, pas sur l'extension : les dépôts
     * Chorus livrent régulièrement un XML nommé `.txt` ou sans extension.
     */
    private fun classify(file: File): Kind {
        val head = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read >= 4 &&
            head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x44.toByte() && head[3] == 0x46.toByte()
        ) return Kind.PDF

        val extension = file.extension.lowercase(Locale.ROOT)
        if (extension == "pdf") return Kind.PDF
        if (extension in setOf("xml", "cii", "ubl", "edi")) return Kind.XML

        val prefix = String(head, 0, read.coerceAtLeast(0)).trim()
        if (prefix.startsWith("<?xml", true) || prefix.startsWith("<")) return Kind.XML
        return Kind.OTHER
    }

    private fun sanitizeEntryName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "piece-${System.nanoTime()}" else cleaned.take(120)
    }

    private fun copyBounded(input: InputStream, target: File, limit: Long): Long {
        var written = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                written += read
                // Une archive « zip bomb » se déclare petite et se décompresse
                // sans fin : on borne chaque entrée.
                if (written > limit) throw IllegalStateException("Fichier décompressé trop volumineux.")
                output.write(buffer, 0, read)
            }
        }
        return written
    }

    fun looksLikeZip(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
}
