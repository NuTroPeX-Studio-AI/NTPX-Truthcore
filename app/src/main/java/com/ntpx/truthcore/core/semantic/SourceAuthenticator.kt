package com.ntpx.truthcore.core.semantic

import com.ntpx.truthcore.core.evidence.Evidence
import java.net.URI

data class SourceAuthResult(
    val authenticated: Boolean,
    val reason: String,
)

object SourceAuthenticator {
    fun verify(evidence: Evidence, expectedHash: String? = null): SourceAuthResult {
        if (expectedHash != null && expectedHash != evidence.contentHash) {
            return SourceAuthResult(false, "Evidence content hash does not match the authenticated digest")
        }
        val uri = evidence.sourceUri ?: return SourceAuthResult(false, "Evidence has no source URI")
        val parsed = runCatching { URI(uri) }.getOrNull()
            ?: return SourceAuthResult(false, "Source URI is invalid")
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return SourceAuthResult(false, "Remote source is not HTTPS-authenticated")
        }
        if (parsed.host.isNullOrBlank()) {
            return SourceAuthResult(false, "Source URI has no host")
        }
        return SourceAuthResult(true, "HTTPS source and content digest passed structural authentication")
    }
}
