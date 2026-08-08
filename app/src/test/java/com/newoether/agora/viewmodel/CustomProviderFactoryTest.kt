package com.newoether.agora.viewmodel

import com.newoether.agora.api.RustCustomAnthropicProvider
import com.newoether.agora.api.RustCustomOpenAiProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomEndpointResolution
import com.newoether.agora.data.CustomProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderFactoryTest {
    @Test
    fun factoryReusesExistingProtocolImplementations() {
        val url = "https://example.test/api"

        val openAi = createCustomProvider(
            CustomProviderConfig("OpenAI proxy", CustomEndpointProtocol.OPENAI),
            url,
        )
        val anthropic = createCustomProvider(
            CustomProviderConfig("Anthropic proxy", CustomEndpointProtocol.ANTHROPIC),
            url,
        )

        assertTrue(openAi is RustCustomOpenAiProvider)
        assertTrue(anthropic is RustCustomAnthropicProvider)
        assertEquals("Anthropic proxy", anthropic?.name)
        assertEquals(url, anthropic?.defaultBaseUrl)
    }

    @Test
    fun unknownProtocolIsNotRegistered() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig("Unknown", CustomEndpointProtocol.UNKNOWN),
                "https://example.test",
            ),
        )
    }

    @Test
    fun baseUrlCandidatesAreProtocolSpecific() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.OPENAI,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.ANTHROPIC,
                "https://example.test",
            ),
        )
        assertEquals(
            emptyList<String?>(),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.UNKNOWN,
                "https://example.test",
            ),
        )
    }

    @Test
    fun explicitVersionedBaseUrlIsTriedBeforeMigrationFallbacks() {
        val url = "https://example.test/v1beta"

        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.OPENAI, url),
        )
        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.ANTHROPIC, url),
        )
    }

    @Test
    fun resolvedEndpointIsScopedToProtocolAndConfiguredUrl() {
        val resolution = CustomEndpointResolution(
            protocol = CustomEndpointProtocol.OPENAI,
            configuredBaseUrl = "https://example.test/",
            effectiveBaseUrl = "https://example.test/v1",
        )

        assertTrue(resolution.matches(CustomEndpointProtocol.OPENAI, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.ANTHROPIC, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.OPENAI, "https://other.test"))
    }
}