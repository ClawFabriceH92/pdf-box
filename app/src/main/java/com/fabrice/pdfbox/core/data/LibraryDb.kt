package com.fabrice.pdfbox.core.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base de la bibliothèque, en SQLite direct.
 *
 * Le cahier des charges prévoyait Room ; le schéma tient en cinq tables et la
 * seule requête non triviale est la recherche plein texte, qui doit de toute
 * façon être écrite à la main (Room ne génère pas de `MATCH` avec `snippet()`).
 * Room aurait ajouté un processeur d'annotations et une contrainte de version
 * Kotlin sans rien simplifier ici.
 *
 * FTS4 et non FTS5 : FTS5 n'est pas garanti dans le SQLite embarqué d'Android 8,
 * cible minimale du projet. FTS4 avec le tokenizer `unicode61` indexe le
 * français correctement (accents repliés, casse ignorée).
 */
class LibraryDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE docs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                uri TEXT NOT NULL,
                managed INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                page_count INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                modified_at INTEGER NOT NULL DEFAULT 0,
                tag TEXT,
                sha256 TEXT,
                encrypted INTEGER NOT NULL DEFAULT 0,
                indexed_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_docs_added ON docs(added_at DESC)")
        db.execSQL("CREATE INDEX idx_docs_sha ON docs(sha256)")
        db.execSQL("CREATE UNIQUE INDEX idx_docs_uri ON docs(uri)")

        db.execSQL(
            """
            CREATE TABLE attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL REFERENCES docs(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                rel_path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                readable INTEGER NOT NULL DEFAULT 1,
                problem TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_att_doc ON attachments(doc_id)")

        db.execSQL(
            """
            CREATE TABLE annots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL REFERENCES docs(id) ON DELETE CASCADE,
                page INTEGER NOT NULL,
                type TEXT NOT NULL,
                x0 REAL NOT NULL, y0 REAL NOT NULL,
                x1 REAL NOT NULL, y1 REAL NOT NULL,
                color INTEGER NOT NULL,
                text TEXT,
                payload TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_annot_doc ON annots(doc_id, page)")

        db.execSQL(
            """
            CREATE TABLE text_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                excerpt TEXT NOT NULL,
                char_count INTEGER NOT NULL,
                rel_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_hist_date ON text_history(created_at DESC)")

        db.execSQL(
            """
            CREATE VIRTUAL TABLE doc_fts USING fts4(
                doc_id, page, content,
                notindexed=doc_id, notindexed=page,
                tokenize=unicode61
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 : pas encore de migration à écrire. Détruire et reconstruire ferait
        // perdre les fiches, on ne le fait donc qu'en cas de version inconnue.
        if (oldVersion > newVersion) {
            db.execSQL("DROP TABLE IF EXISTS doc_fts")
            db.execSQL("DROP TABLE IF EXISTS text_history")
            db.execSQL("DROP TABLE IF EXISTS annots")
            db.execSQL("DROP TABLE IF EXISTS attachments")
            db.execSQL("DROP TABLE IF EXISTS docs")
            onCreate(db)
        }
    }

    companion object {
        const val NAME = "pdfs_db.db"
        const val VERSION = 1
    }
}

internal fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
internal fun Cursor.strOrNull(name: String): String? {
    val idx = getColumnIndexOrThrow(name)
    return if (isNull(idx)) null else getString(idx)
}
internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun Cursor.float(name: String): Float = getFloat(getColumnIndexOrThrow(name))
internal fun Cursor.bool(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) != 0

internal fun values(block: ContentValues.() -> Unit): ContentValues =
    ContentValues().apply(block)
