package com.ntpx.truthcore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAICompatibleProviderTest {
    @Test
    fun validHttpsConfigurationPassesStructuralValidation() {
        val provider = OpenAICompatibleProvider(
            ModelConfig(baseUrl = "https://example.test/v1", model = "fixture-model")
        )
        assertNull(provider.validate())
    }

    @Test
    fun cleartextHttpEndpointIsRejected() {
        val provider = OpenAICompatibleProvider(
            ModelConfig(baseUrl = "http://192.168.1.10:11434/v1", model = "fixture-model")
        )
        assertEquals("Only HTTPS model endpoints are allowed", provider.validate())
    }

    @Test
    fun embeddedUrlCredentialsAreRejected() {
        val provider = OpenAICompatibleProvider(
            ModelConfig(baseUrl = "https://user:secret@example.test/v1", model = "fixture-model")
        )
        assertEquals("Credentials must not be embedded in the URL", provider.validate())
    }

    @Test
    fun missingModelIsRejected() {
        val provider = OpenAICompatibleProvider(
            ModelConfig(baseUrl = "https://example.test/v1", model = "")
        )
        assertEquals("Model name is required", provider.validate())
    }
}
