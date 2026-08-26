package com.fabrice.pdfbox.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fabrice.pdfbox.core.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Accès unique à la bibliothèque. Un objet plutôt qu'une injection : il n'y a
 * qu'une base, qu'un cycle de vie (celui du processus) et aucun test qui
 * gagnerait à en substituer une autre.
 */
object Library {

    private lateinit var appContext: Context
    private lateinit var helper: LibraryDb

    private val _docs = MutableStateFlow<List<Doc>>(emptyList())
    val docs: StateFlow<List<Doc>> = _docs.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        helper = LibraryDb(appContext)
        Storage.pruneWorkDir(appContext)
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- documents

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _docs.value = queryDocs()
    }

    private fun queryDocs(): List<Doc> {
        val out = mutableListOf<Doc>()
        db.rawQuery(
            """
            SELECT d.*, (SELECT COUNT(*) FROM attachments a WHERE a.doc_id = d.id) AS att
            FROM docs d ORDER BY d.added_at DESC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) out += c.toDoc()
        }
        return out
    }

    private fun android.database.Cursor.toDoc() = Doc(
        id = long("id"),
        title = str("title"),
        uri = str("uri"),
        managed = bool("managed"),
        sizeBytes = long("size_bytes"),
        pageCount = int("page_count"),
        addedAt = long("added_at"),
        modifiedAt = long("modified_at"),
        tag = strOrNull("tag"),
        sha256 = strOrNull("sha256"),
        encrypted = bool("encrypted"),
        indexedAt = long("indexed_at"),
        attachmentCount = runCatching { int("att") }.getOrDefault(0)
    )

    suspend fun doc(id: Long): Doc? = withContext(Dispatchers.IO) {
        db.rawQuery(
            """
            SELECT d.*, (SELECT COUNT(*) FROM attachments a WHERE a.doc_id = d.id) AS att
            FROM docs d WHERE d.id = ?
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.toDoc() else null }
    }

    suspend fun findByUri(uri: String): Doc? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT * FROM docs WHERE uri = ?", arrayOf(uri)).use { c ->
            if (c.moveToFirst()) c.toDoc() else null
        }
    }

    suspend fun findBySha(sha: String): List<Doc> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Doc>()
        db.rawQuery("SELECT * FROM docs WHERE sha256 = ?", arrayOf(sha)).use { c ->
            while (c.moveToNext()) out += c.toDoc()
        }
        out
    }

    suspend fun upsert(doc: Doc): Long = withContext(Dispatchers.IO) {
        val cv = values {
            put("title", doc.title)
            put("uri", doc.uri)
            put("managed", if (doc.managed) 1 else 0)
            put("size_bytes", doc.sizeBytes)
            put("page_count", doc.pageCount)
            put("added_at", doc.addedAt)
            put("modified_at", doc.modifiedAt)
            put("tag", doc.tag)
            put("sha256", doc.sha256)
            put("encrypted", if (doc.encrypted) 1 else 0)
            put("indexed_at", doc.indexedAt)
        }
        val id = if (doc.id == 0L) {
            db.insertWithOnConflict("docs", null, cv, SQLiteDatabase.CONFLICT_IGNORE).let { inserted ->
                if (inserted > 0) inserted
                // L'URI existe déjà : on met la fiche existante à jour plutôt que
                // de créer un doublon silencieux.
                else db.rawQuery("SELECT id FROM docs WHERE uri = ?", arrayOf(doc.uri)).use { c ->
                    if (c.moveToFirst()) {
                        val existing = c.getLong(0)
                        db.update("docs", cv, "id = ?", arrayOf(existing.toString()))
                        existing
                    } else -1L
                }
            }
        } else {
            db.update("docs", cv, "id = ?", arrayOf(doc.id.toString()))
            doc.id
        }
        _docs.value = queryDocs()
        id
    }

    suspend fun setTag(docId: Long, tag: String?) = withContext(Dispatchers.IO) {
        db.update("docs", values { put("tag", tag) }, "id = ?", arrayOf(docId.toString()))
        _docs.value = queryDocs()
    }

    suspend fun rename(docId: Long, title: String) = withContext(Dispatchers.IO) {
        db.update("docs", values { put("title", title) }, "id = ?", arrayOf(docId.toString()))
        _docs.value = queryDocs()
    }

    suspend fun allTags(): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT tag FROM docs WHERE tag IS NOT NULL AND tag <> '' ORDER BY tag",
            null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }

    /**
     * Supprime la fiche. Le fichier n'est effacé que s'il appartient à
     * l'application : un document référencé par SAF reste où l'utilisateur l'a
     * mis — l'application n'a pas à disposer de ses fichiers.
     */
    suspend fun delete(doc: Doc): Boolean = withContext(Dispatchers.IO) {
        attachmentsOf(doc.id).forEach { att ->
            File(Storage.attachmentsDir(appContext), att.relPath).delete()
        }
        db.delete("docs", "id = ?", arrayOf(doc.id.toString()))
        db.delete("doc_fts", "doc_id = ?", arrayOf(doc.id.toString()))
        var fileRemoved = false
        if (doc.managed) {
            val f = localFileOf(doc)
            if (f != null && f.exists()) fileRemoved = f.delete()
        }
        _docs.value = queryDocs()
        fileRemoved
    }

    /** Chemin réel d'un document géré par l'application, sinon `null`. */
    fun localFileOf(doc: Doc): File? {
        val uri = doc.parsedUri
        return if (uri.scheme == "file") uri.path?.let { File(it) } else null
    }

    // -------------------------------------------------------------- pièces jointes

    suspend fun addAttachment(att: Attachment): Long = withContext(Dispatchers.IO) {
        val id = db.insert("attachments", null, values {
            put("doc_id", att.docId)
            put("name", att.name)
            put("mime", att.mime)
            put("rel_path", att.relPath)
            put("size_bytes", att.sizeBytes)
            put("readable", if (att.readable) 1 else 0)
            put("problem", att.problem)
        })
        _docs.value = queryDocs()
        id
    }

    suspend fun attachmentsOf(docId: Long): List<Attachment> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Attachment>()
        db.rawQuery(
            "SELECT * FROM attachments WHERE doc_id = ? ORDER BY name",
            arrayOf(docId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Attachment(
                    id = c.long("id"),
                    docId = c.long("doc_id"),
                    name = c.str("name"),
                    mime = c.str("mime"),
                    relPath = c.str("rel_path"),
                    sizeBytes = c.long("size_bytes"),
                    readable = c.bool("readable"),
                    problem = c.strOrNull("problem")
                )
            }
        }
        out
    }

    fun attachmentFile(att: Attachment): File = File(Storage.attachmentsDir(appContext), att.relPath)

    // ---------------------------------------------------------------- annotations

    suspend fun annotations(docId: Long): List<Annot> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Annot>()
        db.rawQuery(
            "SELECT * FROM annots WHERE doc_id = ? ORDER BY page, created_at",
            arrayOf(docId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Annot(
                    id = c.long("id"),
                    docId = c.long("doc_id"),
                    page = c.int("page"),
                    type = runCatching { AnnotationType.valueOf(c.str("type")) }
                        .getOrDefault(AnnotationType.HIGHLIGHT),
                    x0 = c.float("x0"), y0 = c.float("y0"),
                    x1 = c.float("x1"), y1 = c.float("y1"),
                    color = c.int("color"),
                    text = c.strOrNull("text"),
                    payload = c.strOrNull("payload"),
                    createdAt = c.long("created_at")
                )
            }
        }
        out
    }

    suspend fun addAnnotation(a: Annot): Long = withContext(Dispatchers.IO) {
        db.insert("annots", null, values {
            put("doc_id", a.docId)
            put("page", a.page)
            put("type", a.type.name)
            put("x0", a.x0); put("y0", a.y0); put("x1", a.x1); put("y1", a.y1)
            put("color", a.color)
            put("text", a.text)
            put("payload", a.payload)
            put("created_at", a.createdAt)
        })
    }

    suspend fun updateAnnotationText(id: Long, text: String) = withContext(Dispatchers.IO) {
        db.update("annots", values { put("text", text) }, "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun deleteAnnotation(id: Long) = withContext(Dispatchers.IO) {
        db.delete("annots", "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun deleteAnnotations(docId: Long) = withContext(Dispatchers.IO) {
        db.delete("annots", "doc_id = ?", arrayOf(docId.toString()))
        Unit
    }

    // ------------------------------------------------------------ index plein texte

    suspend fun indexDocument(docId: Long, pages: List<String>) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.delete("doc_fts", "doc_id = ?", arrayOf(docId.toString()))
            pages.forEachIndexed { index, content ->
                if (content.isNotBlank()) {
                    db.insert("doc_fts", null, values {
                        put("doc_id", docId)
                        put("page", index)
                        put("content", content)
                    })
                }
            }
            db.update(
                "docs",
                values { put("indexed_at", System.currentTimeMillis()) },
                "id = ?", arrayOf(docId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _docs.value = queryDocs()
    }

    /**
     * Recherche plein texte. La requête utilisateur est transformée en préfixes
     * (`fact*`) pour que la frappe partielle trouve quelque chose, et les
     * caractères qui ont un sens pour FTS sont neutralisés.
     */
    suspend fun search(query: String, limit: Int = 200): List<SearchHit> = withContext(Dispatchers.IO) {
        val terms = query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("[\"*^:()\\-]"), "") }
            .filter { it.isNotBlank() }
        if (terms.isEmpty()) return@withContext emptyList()
        val match = terms.joinToString(" ") { "\"$it\"*" }
        val out = mutableListOf<SearchHit>()
        try {
            db.rawQuery(
                """
                SELECT doc_id, page, snippet(doc_fts, '[', ']', '…', 2, 24) AS snip
                FROM doc_fts WHERE doc_fts MATCH ? LIMIT ?
                """.trimIndent(),
                arrayOf(match, limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out += SearchHit(
                        docId = c.getLong(0),
                        page = c.getInt(1),
                        snippet = c.getString(2) ?: ""
                    )
                }
            }
        } catch (_: Exception) {
            // Requête FTS invalide (guillemets déséquilibrés…) : pas de résultat,
            // pas de plantage.
        }
        out
    }

    // ------------------------------------------------------------------ historique

    suspend fun addTextEntry(entry: TextEntry): Long = withContext(Dispatchers.IO) {
        val id = db.insert("text_history", null, values {
            put("doc_id", entry.docId)
            put("title", entry.title)
            put("excerpt", entry.excerpt)
            put("char_count", entry.charCount)
            put("rel_path", entry.relPath)
            put("created_at", entry.createdAt)
        })
        // Le cahier des charges borne l'historique à 50 entrées : on purge les
        // fichiers en même temps que les lignes, sinon ils s'accumulent.
        db.rawQuery(
            "SELECT id, rel_path FROM text_history ORDER BY created_at DESC LIMIT -1 OFFSET 50",
            null
        ).use { c ->
            while (c.moveToNext()) {
                File(Storage.exportsDir(appContext), c.getString(1)).delete()
                db.delete("text_history", "id = ?", arrayOf(c.getLong(0).toString()))
            }
        }
        id
    }

    suspend fun textHistory(): List<TextEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TextEntry>()
        db.rawQuery("SELECT * FROM text_history ORDER BY created_at DESC LIMIT 50", null).use { c ->
            while (c.moveToNext()) {
                out += TextEntry(
                    id = c.long("id"),
                    docId = c.long("doc_id"),
                    title = c.str("title"),
                    excerpt = c.str("excerpt"),
                    charCount = c.int("char_count"),
                    relPath = c.str("rel_path"),
                    createdAt = c.long("created_at")
                )
            }
        }
        out
    }

    suspend fun clearTextHistory() = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT rel_path FROM text_history", null).use { c ->
            while (c.moveToNext()) File(Storage.exportsDir(appContext), c.getString(0)).delete()
        }
        db.delete("text_history", null, null)
        Unit
    }

    // ------------------------------------------------------------------ statistiques

    suspend fun stats(): LibraryStats = withContext(Dispatchers.IO) {
        val docs = _docs.value.ifEmpty { queryDocs() }
        val indexed = docs.count { it.indexedAt > 0 }
        val annotated = db.rawQuery("SELECT COUNT(DISTINCT doc_id) FROM annots", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        LibraryStats(
            docCount = docs.size,
            totalBytes = docs.sumOf { it.sizeBytes },
            pageCount = docs.sumOf { it.pageCount },
            indexedCount = indexed,
            annotatedCount = annotated,
            withXmlCount = docs.count { it.hasAttachments },
            managedBytes = docs.filter { it.managed }.sumOf { it.sizeBytes },
            tags = docs.mapNotNull { it.tag }.distinct().size
        )
    }
}

data class LibraryStats(
    val docCount: Int,
    val totalBytes: Long,
    val pageCount: Int,
    val indexedCount: Int,
    val annotatedCount: Int,
    val withXmlCount: Int,
    val managedBytes: Long,
    val tags: Int
)
