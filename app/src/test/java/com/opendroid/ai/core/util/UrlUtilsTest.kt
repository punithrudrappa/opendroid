package com.opendroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cleartext rule must match the URL the providers actually build.
 *
 * `formatBaseUrl` defaults a missing scheme to `http://` (a deliberate choice for LAN
 * Ollama/Copilot servers), so a scheme-less host is cleartext even though it does not
 * say so. A warning that only looked for an explicit `http://` would miss exactly the
 * case a self-hosted user is most likely to type.
 */
class UrlUtilsTest {

    @Test
    fun `a scheme-less host is cleartext because formatBaseUrl defaults it to http`() {
        assertTrue(UrlUtils.usesCleartextTransport("192.168.1.5:8080/v1"))
        assertEquals("http://192.168.1.5:8080/v1", UrlUtils.formatBaseUrl("192.168.1.5:8080/v1"))
    }

    @Test
    fun `an explicit http endpoint is cleartext`() {
        assertTrue(UrlUtils.usesCleartextTransport("http://gateway.example.com/v1"))
        assertTrue(UrlUtils.usesCleartextTransport("HTTP://gateway.example.com/v1"))
    }

    @Test
    fun `an https endpoint is not cleartext`() {
        assertFalse(UrlUtils.usesCleartextTransport("https://gateway.example.com/v1"))
        assertFalse(UrlUtils.usesCleartextTransport("HTTPS://gateway.example.com/v1"))
        assertFalse(UrlUtils.usesCleartextTransport("  https://gateway.example.com/v1  "))
    }

    @Test
    fun `an unconfigured endpoint is not reported as cleartext`() {
        assertFalse(UrlUtils.usesCleartextTransport(null))
        assertFalse(UrlUtils.usesCleartextTransport(""))
        assertFalse(UrlUtils.usesCleartextTransport("   "))
    }

    @Test
    fun `normalization still strips whitespace and trailing slashes`() {
        assertEquals(
            "https://gateway.example.com/v1",
            UrlUtils.formatBaseUrl("  https://gateway.example.com/v1/  ")
        )
        assertEquals("", UrlUtils.formatBaseUrl(null))
        assertEquals("https://fallback.example.com", UrlUtils.formatBaseUrl("", "https://fallback.example.com"))
    }
}
