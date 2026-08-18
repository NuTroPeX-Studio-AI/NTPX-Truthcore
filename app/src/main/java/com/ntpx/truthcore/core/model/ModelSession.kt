package com.ntpx.truthcore.core.model

/**
 * Process-local provider session. Credentials remain volatile: nothing here is
 * serialized to disk, preferences, saved instance state, logs, or GitHub.
 */
object ModelSession {
    @Volatile
    var provider: ModelProvider? = null
}
