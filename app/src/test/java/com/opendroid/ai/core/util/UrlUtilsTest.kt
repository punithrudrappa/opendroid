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
    fun `classification follows the normalized URL, not the raw text`() {
        // formatBaseUrl strips whitespace mid-string, so this is genuinely sent over
        // https and must not be flagged as cleartext.
        assertEquals(
            "https://gateway.example.com/v1",
            UrlUtils.formatBaseUrl("https: //gateway.example.com/v1")
        )
        assertFalse(UrlUtils.usesCleartextTransport("https: //gateway.example.com/v1"))

        // The inverse direction: no scheme means http, so it must be flagged even though
        // the raw text says nothing about a scheme.
        assertEquals("http://192.168.1.5:8080/v1", UrlUtils.formatBaseUrl("192.168.1.5:8080/v1"))
        assertTrue(UrlUtils.usesCleartextTransport("192.168.1.5:8080/v1"))
    }

    @Test
    fun `cleartext to this device is allowed`() {
        // The app's network security config permits exactly these, and their traffic never
        // reaches a network, so carrying a key over them exposes nothing to an on-path peer.
        listOf(
            "http://localhost:8080/v1",
            "http://127.0.0.1:11434",
            "http://10.0.2.2:4141/v1",
            "HTTP://LOCALHOST:8080/v1"
        ).forEach { endpoint ->
            assertTrue("$endpoint must be allowed", UrlUtils.allowCleartextTransport(endpoint))
            assertFalse("$endpoint must not be refused", UrlUtils.sendsCleartextOffDevice(endpoint))
        }
    }

    @Test
    fun `cleartext to another host is refused`() {
        // These would put the API key and every custom-header value on the wire in the
        // clear, and OkHttp refuses them anyway ("CLEARTEXT communication not enabled"),
        // so the app refuses first with a reason the user can act on.
        listOf(
            "http://192.168.1.50:8080/v1",
            "http://gateway.example.com/v1",
            "192.168.1.50:8080/v1" // scheme-less, so formatBaseUrl makes it http
        ).forEach { endpoint ->
            assertTrue("$endpoint must be refused", UrlUtils.sendsCleartextOffDevice(endpoint))
            assertFalse("$endpoint must not be allowed", UrlUtils.allowCleartextTransport(endpoint))
        }
    }

    @Test
    fun `https is never refused, whatever the host`() {
        listOf(
            "https://gateway.example.com/v1",
            "https://192.168.1.50:8443/v1",
            "https: //gateway.example.com/v1",
            "HTTPS://GATEWAY.EXAMPLE.COM/v1"
        ).forEach { endpoint ->
            assertTrue("$endpoint must be allowed", UrlUtils.allowCleartextTransport(endpoint))
        }
    }

    @Test
    fun `an unconfigured endpoint is not classified as cleartext`() {
        listOf(null, "", "   ").forEach { endpoint ->
            assertFalse(UrlUtils.usesCleartextTransport(endpoint))
            assertTrue("a blank endpoint must not be refused", UrlUtils.allowCleartextTransport(endpoint))
        }
    }

    @Test
    fun `a trailing slash or surrounding whitespace does not change the classification`() {
        assertFalse(UrlUtils.usesCleartextTransport("  https://gateway.example.com/v1/  "))
        assertTrue(UrlUtils.usesCleartextTransport("  http://gateway.example.com/v1/  "))
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
