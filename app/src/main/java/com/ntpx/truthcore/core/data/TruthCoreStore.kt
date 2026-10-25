package com.ntpx.truthcore.core.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.memory.MemoryKind
import com.ntpx.truthcore.core.memory.MemoryRecord
import java.time.Instant

/**
 * SQLite implementation of [TruthCoreRepository].
 *
 * One instance is shared per process (see TruthCoreRuntime.get). Audit appends
 * and approval consumption run inside synchronized write transactions so the
 * hash chain cannot fork when the UI and hands-free service write concurrently.
 */
class TruthCoreStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), TruthCoreRepository {
    private val writeLock = Any()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE memory (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                trust REAL NOT NULL,
                importance REAL NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE knowledge (
                id TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                content TEXT NOT NULL,
                source_uri TEXT,
                independent_key TEXT NOT NULL,
                trust REAL NOT NULL,
                expires_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE audit (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                event TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                previous_hash TEXT NOT NULL,
                entry_hash TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE approvals (
                token TEXT PRIMARY KEY,
                action TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                used INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createIndexes(db)
            db.delete("approvals", "used=1 OR expires_at<=?", arrayOf(System.currentTimeMillis().toString()))
        }
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS memory_created_idx ON memory(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS knowledge_key_idx ON knowledge(independent_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS approvals_expiry_idx ON approvals(expires_at)")
    }

    override fun remember(record: MemoryRecord) {
        require(record.trust in 0.0..1.0)
        require(record.importance in 0.0..1.0)
        val values = ContentValues().apply {
            put("id", record.id)
            put("kind", record.kind.name)
            put("content", record.content)
            put("trust", record.trust)
            put("importance", record.importance)
            put("created_at", record.createdAt.toEpochMilli())
            record.expiresAt?.let { put("expires_at", it.toEpochMilli()) }
        }
        writableDatabase.insertWithOnConflict("memory", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun searchMemory(query: String, limit: Int): List<MemoryRecord> {
        val now = Instant.now()
        val terms = RepositorySupport.tokenize(query)
        val (selection, args) = likeFilter(terms, listOf("content"))
        readableDatabase.query("memory", null, selection, args, null, null, "created_at DESC").use { c ->
            val rows = mutableListOf<Pair<MemoryRecord, Int>>()
            while (c.moveToNext()) {
                val record = c.toMemory()
                if (!record.isUsable(now)) continue
                val score = RepositorySupport.score(terms, record.content)
                if (score > 0 || terms.isEmpty()) rows += record to score
            }
            return rows.sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenByDescending { it.first.createdAt }
            ).take(limit).map { it.first }
        }
    }

    override fun forgetMemory(query: String): Int = synchronized(writeLock) {
        val terms = RepositorySupport.tokenize(query)
        val ids = mutableListOf<String>()
        readableDatabase.query("memory", arrayOf("id", "content"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id == query.trim() || RepositorySupport.matchesAll(terms, c.getString(1))) ids += id
            }
        }
        ids.sumOf { writableDatabase.delete("memory", "id=?", arrayOf(it)) }
    }

    override fun memoryCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memory", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun upsertKnowledge(evidence: Evidence) {
        val values = ContentValues().apply {
            put("id", evidence.id)
            put("label", evidence.label)
            put("content", evidence.content)
            evidence.sourceUri?.let { put("source_uri", it) }
            put("independent_key", evidence.independentKey)
            put("trust", evidence.trust)
            evidence.expiresAt?.let { put("expires_at", it.toEpochMilli()) }
        }
        writableDatabase.insertWithOnConflict("knowledge", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun searchKnowledge(query: String, limit: Int): List<Evidence> {
        val terms = RepositorySupport.tokenize(query)
        val now = Instant.now()
        val (selection, args) = likeFilter(terms, listOf("label", "content"))
        readableDatabase.query("knowledge", null, selection, args, null, null, null).use { c ->
            val rows = mutableListOf<Pair<Evidence, Int>>()
            while (c.moveToNext()) {
                val evidence = c.toEvidence()
                if (evidence.effectiveStrength(now) <= 0.0) continue
                val score = RepositorySupport.score(terms, evidence.label + " " + evidence.content)
                if (score > 0 || terms.isEmpty()) rows += evidence to score
            }
            return rows.sortedWith(compareByDescending<Pair<Evidence, Int>> { it.second }.thenByDescending { it.first.trust })
                .take(limit).map { it.first }
        }
    }

    override fun forgetKnowledge(query: String): Int = synchronized(writeLock) {
        val terms = RepositorySupport.tokenize(query)
        val ids = mutableListOf<String>()
        readableDatabase.query("knowledge", arrayOf("id", "label", "content"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id == query.trim() || RepositorySupport.matchesAll(terms, c.getString(1) + " " + c.getString(2))) ids += id
            }
        }
        ids.sumOf { writableDatabase.delete("knowledge", "id=?", arrayOf(it)) }
    }

    override fun appendAudit(event: String, payload: String): String = synchronized(writeLock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val hash = appendAuditLocked(db, event, payload)
            db.setTransactionSuccessful()
            hash
        } finally {
            db.endTransaction()
        }
    }

    private fun appendAuditLocked(db: SQLiteDatabase, event: String, payload: String): String {
        val previous = db.rawQuery("SELECT entry_hash FROM audit ORDER BY seq DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else "GENESIS"
        }
        val at = System.currentTimeMillis()
        val payloadHash = RepositorySupport.sha256(payload)
        val entryHash = RepositorySupport.entryHash(previous, at, event, payloadHash)
        val values = ContentValues().apply {
            put("at", at)
            put("event", event)
            put("payload_hash", payloadHash)
            put("previous_hash", previous)
            put("entry_hash", entryHash)
        }
        db.insertOrThrow("audit", null, values)
        return entryHash
    }

    override fun verifyAuditChain(): Boolean {
        readableDatabase.rawQuery("SELECT at,event,payload_hash,previous_hash,entry_hash FROM audit ORDER BY seq ASC", null).use { c ->
            var previous = "GENESIS"
            while (c.moveToNext()) {
                val at = c.getLong(0)
                val event = c.getString(1)
                val payloadHash = c.getString(2)
                if (c.getString(3) != previous) return false
                if (RepositorySupport.entryHash(previous, at, event, payloadHash) != c.getString(4)) return false
                previous = c.getString(4)
            }
        }
        return true
    }

    override fun issueApproval(action: String, ttlSeconds: Long): String = synchronized(writeLock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            db.delete("approvals", "used=1 OR expires_at<=?", arrayOf(now.toString()))
            val token = RepositorySupport.newApprovalCode { candidate ->
                db.query("approvals", arrayOf("token"), "token=?", arrayOf(candidate), null, null, null).use { it.moveToFirst() }
            }
            val values = ContentValues().apply {
                put("token", token)
                put("action", action)
                put("expires_at", now + ttlSeconds * 1000)
                put("used", 0)
            }
            db.insertOrThrow("approvals", null, values)
            appendAuditLocked(db, "approval.issued", action)
            db.setTransactionSuccessful()
            token
        } finally {
            db.endTransaction()
        }
    }

    override fun consumeApproval(token: String, action: String): Boolean = synchronized(writeLock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val valid = db.query(
                "approvals", arrayOf("expires_at", "used", "action"), "token=?", arrayOf(token), null, null, null
            ).use { c ->
                c.moveToFirst() && c.getInt(1) == 0 && c.getString(2) == action && c.getLong(0) > System.currentTimeMillis()
            }
            if (!valid) return@synchronized false
            db.update("approvals", ContentValues().apply { put("used", 1) }, "token=?", arrayOf(token))
            appendAuditLocked(db, "approval.consumed", action)
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = super<SQLiteOpenHelper>.close()

    /** SQL prefilter: any term may match; final scoring happens in Kotlin. */
    private fun likeFilter(terms: Set<String>, columns: List<String>): Pair<String?, Array<String>?> {
        if (terms.isEmpty()) return null to null
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        terms.take(12).forEach { term ->
            columns.forEach { column ->
                clauses += "LOWER($column) LIKE ? ESCAPE '\\'"
                args += "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            }
        }
        return clauses.joinToString(" OR ") to args.toTypedArray()
    }

    private fun Cursor.toMemory() = MemoryRecord(
        id = getString(getColumnIndexOrThrow("id")),
        kind = runCatching { MemoryKind.valueOf(getString(getColumnIndexOrThrow("kind"))) }.getOrDefault(MemoryKind.EPISODIC),
        content = getString(getColumnIndexOrThrow("content")),
        trust = getDouble(getColumnIndexOrThrow("trust")),
        importance = getDouble(getColumnIndexOrThrow("importance")),
        createdAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("created_at"))),
        expiresAt = getColumnIndex("expires_at").takeIf { it >= 0 && !isNull(it) }?.let { Instant.ofEpochMilli(getLong(it)) },
    )

    private fun Cursor.toEvidence() = Evidence(
        id = getString(getColumnIndexOrThrow("id")),
        label = getString(getColumnIndexOrThrow("label")),
        content = getString(getColumnIndexOrThrow("content")),
        sourceUri = getColumnIndex("source_uri").takeIf { it >= 0 && !isNull(it) }?.let { getString(it) },
        independentKey = getString(getColumnIndexOrThrow("independent_key")),
        trust = getDouble(getColumnIndexOrThrow("trust")),
        expiresAt = getColumnIndex("expires_at").takeIf { it >= 0 && !isNull(it) }?.let { Instant.ofEpochMilli(getLong(it)) },
    )

    companion object {
        private const val DB_NAME = "truthcore.db"
        private const val DB_VERSION = 2
    }
}
