package com.ntpx.truthcore.core.data

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.memory.MemoryKind
import com.ntpx.truthcore.core.memory.MemoryRecord
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract for TruthCore memory, knowledge, approvals, and the
 * hash-chained audit ledger. [TruthCoreStore] is the SQLite implementation;
 * [InMemoryTruthCoreRepository] backs unit tests and ephemeral sessions.
 */
interface TruthCoreRepository {
    fun remember(record: MemoryRecord)
    fun remember(content: String, kind: MemoryKind = MemoryKind.EPISODIC, trust: Double = 0.85, importance: Double = 0.5): MemoryRecord {
        val record = MemoryRecord(UUID.randomUUID().toString(), kind, content, trust, importance)
        remember(record)
        return record
    }
    fun searchMemory(query: String, limit: Int = 8): List<MemoryRecord>
    fun forgetMemory(query: String): Int
    fun memoryCount(): Int

    fun upsertKnowledge(evidence: Evidence)
    fun searchKnowledge(query: String, limit: Int = 12): List<Evidence>
    fun forgetKnowledge(query: String): Int

    fun appendAudit(event: String, payload: String): String
    fun verifyAuditChain(): Boolean

    /** Issues a short, speakable, single-use approval code bound to [action]. */
    fun issueApproval(action: String, ttlSeconds: Long = 300): String
    fun consumeApproval(token: String, action: String): Boolean

    fun close() = Unit
}

internal object RepositorySupport {
    private val random = SecureRandom()

    /** Six-digit code: speakable for hands-free use, single-use, short-lived, action-bound. */
    fun newApprovalCode(exists: (String) -> Boolean): String {
        repeat(32) {
            val code = (100_000 + random.nextInt(900_000)).toString()
            if (!exists(code)) return code
        }
        return UUID.randomUUID().toString()
    }

    fun tokenize(value: String): Set<String> = value.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()

    fun score(terms: Set<String>, text: String): Int {
        val lower = text.lowercase()
        return terms.count(lower::contains)
    }

    /** All query terms must appear for a destructive match, so short queries cannot wipe unrelated rows. */
    fun matchesAll(terms: Set<String>, text: String): Boolean = terms.isNotEmpty() && score(terms, text) == terms.size

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun entryHash(previous: String, at: Long, event: String, payloadHash: String) = sha256("$previous|$at|$event|$payloadHash")
}

/** Thread-safe in-memory repository with the same semantics as the SQLite store. */
class InMemoryTruthCoreRepository : TruthCoreRepository {
    private data class AuditRow(val at: Long, val event: String, val payloadHash: String, val previous: String, val entryHash: String)
    private data class Approval(val action: String, val expiresAt: Long, var used: Boolean = false)

    private val lock = Any()
    private val memory = linkedMapOf<String, MemoryRecord>()
    private val knowledge = linkedMapOf<String, Evidence>()
    private val audit = mutableListOf<AuditRow>()
    private val approvals = mutableMapOf<String, Approval>()

    override fun remember(record: MemoryRecord) {
        require(record.trust in 0.0..1.0)
        require(record.importance in 0.0..1.0)
        synchronized(lock) { memory[record.id] = record }
    }

    override fun searchMemory(query: String, limit: Int): List<MemoryRecord> = synchronized(lock) {
        val terms = RepositorySupport.tokenize(query)
        val now = Instant.now()
        memory.values.filter { it.isUsable(now) }
            .map { it to RepositorySupport.score(terms, it.content) }
            .filter { it.second > 0 || terms.isEmpty() }
            .sortedWith(compareByDescending<Pair<MemoryRecord, Int>> { it.second }.thenByDescending { it.first.importance }.thenByDescending { it.first.createdAt })
            .take(limit).map { it.first }
    }

    override fun forgetMemory(query: String): Int = synchronized(lock) {
        val terms = RepositorySupport.tokenize(query)
        val ids = memory.values.filter { it.id == query.trim() || RepositorySupport.matchesAll(terms, it.content) }.map { it.id }
        ids.forEach(memory::remove)
        ids.size
    }

    override fun memoryCount(): Int = synchronized(lock) { memory.size }

    override fun upsertKnowledge(evidence: Evidence) {
        synchronized(lock) { knowledge[evidence.id] = evidence }
    }

    override fun searchKnowledge(query: String, limit: Int): List<Evidence> = synchronized(lock) {
        val terms = RepositorySupport.tokenize(query)
        knowledge.values.filter { it.effectiveStrength() > 0.0 }
            .map { it to RepositorySupport.score(terms, it.label + " " + it.content) }
            .filter { it.second > 0 || terms.isEmpty() }
            .sortedWith(compareByDescending<Pair<Evidence, Int>> { it.second }.thenByDescending { it.first.trust })
            .take(limit).map { it.first }
    }

    override fun forgetKnowledge(query: String): Int = synchronized(lock) {
        val terms = RepositorySupport.tokenize(query)
        val ids = knowledge.values.filter { it.id == query.trim() || RepositorySupport.matchesAll(terms, it.label + " " + it.content) }.map { it.id }
        ids.forEach(knowledge::remove)
        ids.size
    }

    override fun appendAudit(event: String, payload: String): String = synchronized(lock) {
        val previous = audit.lastOrNull()?.entryHash ?: "GENESIS"
        val at = System.currentTimeMillis()
        val payloadHash = RepositorySupport.sha256(payload)
        val entry = RepositorySupport.entryHash(previous, at, event, payloadHash)
        audit += AuditRow(at, event, payloadHash, previous, entry)
        entry
    }

    override fun verifyAuditChain(): Boolean = synchronized(lock) {
        var previous = "GENESIS"
        for (row in audit) {
            if (row.previous != previous) return false
            if (RepositorySupport.entryHash(previous, row.at, row.event, row.payloadHash) != row.entryHash) return false
            previous = row.entryHash
        }
        true
    }

    /** Test hook: corrupt one audit row to prove tamper detection. */
    internal fun tamperAudit(index: Int) = synchronized(lock) {
        val row = audit[index]
        audit[index] = row.copy(event = row.event + ".tampered")
    }

    override fun issueApproval(action: String, ttlSeconds: Long): String {
        val code = synchronized(lock) {
            val now = System.currentTimeMillis()
            approvals.entries.removeIf { it.value.used || it.value.expiresAt <= now }
            val code = RepositorySupport.newApprovalCode(approvals::containsKey)
            approvals[code] = Approval(action, now + ttlSeconds * 1000)
            code
        }
        appendAudit("approval.issued", action)
        return code
    }

    override fun consumeApproval(token: String, action: String): Boolean {
        val ok = synchronized(lock) {
            val approval = approvals[token] ?: return@synchronized false
            if (approval.used || approval.action != action || approval.expiresAt <= System.currentTimeMillis()) return@synchronized false
            approval.used = true
            true
        }
        if (ok) appendAudit("approval.consumed", action)
        return ok
    }
}
