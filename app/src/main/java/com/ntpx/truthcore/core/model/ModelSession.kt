package com.ntpx.truthcore.core.model

import com.ntpx.truthcore.core.semantic.DeepVerificationGate
import com.ntpx.truthcore.core.semantic.EmbeddingProvider

/**
 * Process-local provider session. Credentials remain volatile: nothing here is
 * serialized to disk, preferences, saved instance state, logs, or GitHub.
 */
object ModelSession {
    @Volatile
    var provider: ModelProvider? = null
}

/**
 * Process-local verification settings shared by the UI and hands-free service.
 *
 * - [semanticGate] adds an NLI check behind deterministic ClaimLock (additive only).
 * - [requireSemantic] withholds claims whenever that gate is unavailable.
 * - [embeddings] ranks retrieved evidence before it is sent to the model.
 */
object VerificationSession {
    @Volatile
    var semanticGate: DeepVerificationGate? = null

    @Volatile
    var requireSemantic: Boolean = false

    @Volatile
    var embeddings: EmbeddingProvider? = null

    fun clear() {
        semanticGate = null
        requireSemantic = false
        embeddings = null
    }
}
