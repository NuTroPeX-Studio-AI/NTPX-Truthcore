package com.ntpx.truthcore.core.evidence

import java.security.MessageDigest
import java.time.Instant

data class Evidence(
    val id: String,
    val label: String,
    val content: String,
    val trust: Double = 1.0,
    val sourceUri: String? = null,
    val independentKey: String = id,
    val expiresAt: Instant? = null,
    val quarantined: Boolean = false,
) {
    val contentHash: String = sha256(content)
    fun freshness(now: Instant = Instant.now()): Double =
        if (expiresAt != null && !expiresAt.isAfter(now)) 0.0 else 1.0

    fun effectiveStrength(now: Instant = Instant.now()): Double =
        if (quarantined) 0.0 else trust.coerceIn(0.0, 1.0) * freshness(now)

    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class SanitizedEvidence(
    val text: String,
    val flags: Set<String>,
    val quarantined: Boolean,
)
