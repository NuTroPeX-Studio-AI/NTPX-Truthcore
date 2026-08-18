package com.ntpx.truthcore.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.memory.MemoryKind
import com.ntpx.truthcore.core.memory.MemoryRecord
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class TruthCoreStore(context: Context) : SQLiteOpenHelper(context, "truthcore.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun remember(record: MemoryRecord) {
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

    fun remember(content: String, kind: MemoryKind = MemoryKind.EPISODIC, trust: Double = 0.85, importance: Double = 0.5): MemoryRecord {
        val record = MemoryRecord(UUID.randomUUID().toString(), kind, content, trust, importance)
        remember(record)
        return record
    }

    fun searchMemory(query: String, limit: Int = 8): List<MemoryRecord> {
        val now = Instant.now()
        val terms = tokenize(query)
        readableDatabase.query("memory", null, null, null, null, null, "importance DESC, created_at DESC", "200").use { c ->
            val rows = mutableListOf<Pair<MemoryRecord, Int>>()
            while (c.moveToNext()) {
                val record = MemoryRecord(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    kind = runCatching { MemoryKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))) }.getOrDefault(MemoryKind.EPISODIC),
                    content = c.getString(c.getColumnIndexOrThrow("content")),
                    trust = c.getDouble(c.getColumnIndexOrThrow("trust")),
                    importance = c.getDouble(c.getColumnIndexOrThrow("importance")),
                    createdAt = Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("created_at"))),
                    expiresAt = c.getColumnIndex("expires_at").takeIf { it >= 0 && !c.isNull(it) }?.let { Instant.ofEpochMilli(c.getLong(it)) },
                )
                if (!record.isUsable(now)) continue
                val score = score(terms, record.content)
                if (score > 0 || terms.isEmpty()) rows += record to score
            }
            return rows.sortedWith(compareByDescending<Pair<MemoryRecord, Int>> { it.second }.thenByDescending { it.first.importance })
                .take(limit).map { it.first }
        }
    }

    fun upsertKnowledge(evidence: Evidence) {
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

    fun searchKnowledge(query: String, limit: Int = 12): List<Evidence> {
        val terms = tokenize(query)
        val now = Instant.now()
        readableDatabase.query("knowledge", null, null, null, null, null, null).use { c ->
            val rows = mutableListOf<Pair<Evidence, Int>>()
            while (c.moveToNext()) {
                val evidence = Evidence(
                    id = c.getString(c.getColumnIndexOrThrow("id")),
                    label = c.getString(c.getColumnIndexOrThrow("label")),
                    content = c.getString(c.getColumnIndexOrThrow("content")),
                    sourceUri = c.getColumnIndex("source_uri").takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) },
                    independentKey = c.getString(c.getColumnIndexOrThrow("independent_key")),
                    trust = c.getDouble(c.getColumnIndexOrThrow("trust")),
                    expiresAt = c.getColumnIndex("expires_at").takeIf { it >= 0 && !c.isNull(it) }?.let { Instant.ofEpochMilli(c.getLong(it)) },
                )
                if (evidence.effectiveStrength(now) <= 0.0) continue
                val score = score(terms, evidence.label + " " + evidence.content)
                if (score > 0 || terms.isEmpty()) rows += evidence to score
            }
            return rows.sortedWith(compareByDescending<Pair<Evidence, Int>> { it.second }.thenByDescending { it.first.trust })
                .take(limit).map { it.first }
        }
    }

    fun appendAudit(event: String, payload: String): String {
        val previous = readableDatabase.rawQuery("SELECT entry_hash FROM audit ORDER BY seq DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else "GENESIS"
        }
        val at = System.currentTimeMillis()
        val payloadHash = sha256(payload)
        val entryHash = sha256("$previous|$at|$event|$payloadHash")
        val values = ContentValues().apply {
            put("at", at)
            put("event", event)
            put("payload_hash", payloadHash)
            put("previous_hash", previous)
            put("entry_hash", entryHash)
        }
        writableDatabase.insertOrThrow("audit", null, values)
        return entryHash
    }

    fun verifyAuditChain(): Boolean {
        readableDatabase.rawQuery("SELECT at,event,payload_hash,previous_hash,entry_hash FROM audit ORDER BY seq ASC", null).use { c ->
            var previous = "GENESIS"
            while (c.moveToNext()) {
                val at = c.getLong(0)
                val event = c.getString(1)
                val payloadHash = c.getString(2)
                val storedPrevious = c.getString(3)
                val entryHash = c.getString(4)
                if (storedPrevious != previous) return false
                if (sha256("$previous|$at|$event|$payloadHash") != entryHash) return false
                previous = entryHash
            }
        }
        return true
    }

    fun issueApproval(action: String, ttlSeconds: Long = 300): String {
        val token = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("token", token)
            put("action", action)
            put("expires_at", System.currentTimeMillis() + ttlSeconds * 1000)
            put("used", 0)
        }
        writableDatabase.insertOrThrow("approvals", null, values)
        appendAudit("approval.issued", action)
        return token
    }

    fun consumeApproval(token: String, action: String): Boolean {
        writableDatabase.beginTransaction()
        return try {
            val valid = readableDatabase.query(
                "approvals", arrayOf("expires_at", "used", "action"), "token=?", arrayOf(token), null, null, null
            ).use { c ->
                c.moveToFirst() && c.getInt(1) == 0 && c.getString(2) == action && c.getLong(0) > System.currentTimeMillis()
            }
            if (!valid) return false
            val values = ContentValues().apply { put("used", 1) }
            writableDatabase.update("approvals", values, "token=?", arrayOf(token))
            appendAudit("approval.consumed", action)
            writableDatabase.setTransactionSuccessful()
            true
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun tokenize(value: String): Set<String> = value.lowercase().split(Regex("\\W+"))
        .filter { it.length > 2 }.toSet()

    private fun score(terms: Set<String>, text: String): Int {
        val lower = text.lowercase()
        return terms.count(lower::contains)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
