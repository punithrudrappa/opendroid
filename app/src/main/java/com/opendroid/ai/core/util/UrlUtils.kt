package com.opendroid.ai.core.util

/**
 * Normalizes user-entered base URLs for self-hosted / custom LLM endpoints
 * (Ollama, Copilot proxy, custom OpenAI-compatible servers).
 *
 * User input is often messy: a missing scheme ("192.168.1.5:11434"), trailing
 * slashes ("https://myserver.com/"), or surrounding whitespace. [formatBaseUrl]
 * cleans that up so callers can safely append a path (e.g. "$baseUrl/api/tags")
 * without producing a double slash or a scheme-less URL that OkHttp would reject.
 */
object UrlUtils {

    /**
     * Returns a normalized base URL (no trailing slash, scheme guaranteed) built
     * from [rawUrl]. If [rawUrl] is blank, [fallback] is normalized and returned
     * instead. If both are blank, returns an empty string rather than throwing,
     * so callers can decide how to handle "not configured".
     */
    fun formatBaseUrl(rawUrl: String?, fallback: String = ""): String {
        val normalizedInput = normalize(rawUrl)
        if (normalizedInput.isNotEmpty()) return normalizedInput
        return normalize(fallback)
    }

    /**
     * True when [rawUrl] would be contacted over an unencrypted connection.
     *
     * Classification runs on the *normalized* URL, not the raw text, because
     * [formatBaseUrl] is what actually builds the request URL and it both strips
     * internal whitespace and defaults a missing scheme to `http://`. Checking the raw
     * text gets this wrong in both directions:
     *
     * - `"https: //gateway.example.com/v1"` is sent over https (the space is stripped),
     *   so warning about it would be a false alarm;
     * - `"192.168.1.5:8080/v1"` is sent over http (defaulted scheme), so it must warn.
     *
     * A blank endpoint returns false: there is nothing to warn about until the user has
     * configured one.
     */
    fun usesCleartextTransport(rawUrl: String?): Boolean {
        val normalized = formatBaseUrl(rawUrl)
        if (normalized.isEmpty()) return false
        return !normalized.startsWith("https://", ignoreCase = true)
    }

    /**
     * True when [rawUrl] would send credentials in cleartext to a host that is not this
     * device. This is the only cleartext case that [allowCleartextTransport] refuses.
     *
     * `localhost`, `127.0.0.1`, and `10.0.2.2` are treated as local: the traffic never
     * leaves the device (or the emulator's host loopback), and the app's
     * `network_security_config.xml` deliberately permits cleartext for exactly those
     * three — a self-hosted gateway on the same device is a supported setup.
     *
     * Everything else over http is refused rather than attempted, for two reasons: the
     * request would carry an API key and any custom-header value in the clear, and
     * OkHttp enforces the same policy anyway (`RealRoutePlanner` fails with
     * "CLEARTEXT communication not enabled for client"), so attempting it only produces
     * a confusing failure instead of an actionable one.
     */
    fun sendsCleartextOffDevice(rawUrl: String?): Boolean {
        val normalized = formatBaseUrl(rawUrl)
        if (normalized.isEmpty()) return false
        if (normalized.startsWith("https://", ignoreCase = true)) return false
        val host = normalized.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return host.lowercase() !in LOCAL_CLEARTEXT_HOSTS
    }

    /**
     * False when a request to [rawUrl] must not be sent, because it would carry
     * credentials in cleartext to another host. Callers refuse the request instead of
     * letting the transport fail with an opaque message.
     */
    fun allowCleartextTransport(rawUrl: String?): Boolean = !sendsCleartextOffDevice(rawUrl)

    /** Hosts whose traffic never reaches the network, so cleartext carries no exposure. */
    private val LOCAL_CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    private fun normalize(url: String?): String {
        var trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""

        // Strip whitespace that may appear mid-string from copy/paste mistakes.
        trimmed = trimmed.replace(" ", "")
        if (trimmed.isEmpty()) return ""

        // Default self-hosted endpoints to http:// when no scheme is present —
        // local Ollama/Copilot-proxy servers are typically plain HTTP on a LAN.
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

        return withScheme.trimEnd('/')
    }
}
