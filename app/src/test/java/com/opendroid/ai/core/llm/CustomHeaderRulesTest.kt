package com.opendroid.ai.core.llm

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the user-supplied header block for a custom OpenAI-compatible
 * endpoint.
 *
 * The interesting cases are the ones that decide whether a request is *shaped*
 * as the user asked: which lines are sent, which are refused with a reason the
 * Settings UI can show, and that no line can override a header the transport
 * itself depends on — especially `Authorization`, which carries the API key.
 */
class CustomHeaderRulesTest {

    @Test
    fun `blank lines and comments are not headers`() {
        val parsed = CustomHeaderRules.parse(
            """
            # gateway routing
            X-Portkey-Config: pc-abc123

            ; legacy comment style
            X-Tenant: acme
            """.trimIndent()
        )

        assertEquals(listOf("X-Portkey-Config", "X-Tenant"), parsed.safe.map { it.name })
        assertEquals(listOf("pc-abc123", "acme"), parsed.safe.map { it.value })
        assertTrue(parsed.reasons.isEmpty())
    }

    @Test
    fun `a value keeps everything after the first colon`() {
        val parsed = CustomHeaderRules.parse(
            """
            X-Target-Url: https://gateway.example.com/v1
            X-Trace: a:b:c
            """.trimIndent()
        )

        assertEquals(
            listOf("https://gateway.example.com/v1", "a:b:c"),
            parsed.safe.map { it.value }
        )
    }

    @Test
    fun `whitespace around the name and value is trimmed`() {
        val parsed = CustomHeaderRules.parse("   X-Tenant :   acme   ")

        assertEquals("X-Tenant", parsed.safe.single().name)
        assertEquals("acme", parsed.safe.single().value)
    }

    @Test
    fun `the first of two headers with the same name wins`() {
        val parsed = CustomHeaderRules.parse(
            """
            X-Tenant: first
            x-tenant: second
            """.trimIndent()
        )

        assertEquals(listOf("first"), parsed.safe.map { it.value })
        assertEquals(1, parsed.ignored.size)
        assertTrue(parsed.reasons.single().contains("repeated"))
    }

    @Test
    fun `a line without a colon is reported instead of sent`() {
        val parsed = CustomHeaderRules.parse("X-Tenant acme")

        assertTrue(parsed.safe.isEmpty())
        assertEquals(1, parsed.ignored.size)
        assertTrue(parsed.reasons.single().contains("missing"))
    }

    @Test
    fun `a header name with a space or trailing colon is refused`() {
        val parsed = CustomHeaderRules.parse(
            """
            X Tenant: acme
            X-Tenant:: acme
            """.trimIndent()
        )

        // "X Tenant" is not a token; "X-Tenant:" parses as name "X-Tenant" with
        // the value ": acme", which is a value the user can see and fix.
        assertEquals(listOf("X-Tenant"), parsed.safe.map { it.name })
        assertEquals(": acme", parsed.safe.single().value)
        assertEquals(1, parsed.ignored.size)
        assertTrue(parsed.reasons.single().contains("not a valid header name"))
    }

    @Test
    fun `a header with no value is refused`() {
        val parsed = CustomHeaderRules.parse("X-Tenant:")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.single().contains("no value"))
    }

    @Test
    fun `an over-long value is refused rather than sent`() {
        val parsed = CustomHeaderRules.parse("X-Padding: ${"y".repeat(9_000)}")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.single().contains("too long"))
    }

    @Test
    fun `a pasted line break cannot smuggle a header into another value`() {
        val parsed = CustomHeaderRules.parse("X-Tenant: acme\r\nX-Injected: yes")

        // The block splits into two editor lines, so no value ever carries a
        // newline; the smuggled header is visible to the user and deletable
        // instead of riding along inside the first value.
        assertEquals(listOf("X-Injected" to "yes"), parsed.safe.map { it.name to it.value })
        // The line that lost its value to the line break is refused, not guessed
        // at: "acme" was never the whole value the user wrote.
        assertEquals(listOf("X-Tenant"), parsed.ignored.map { it.name })
        assertTrue(parsed.reasons.single().contains("line break"))
    }

    @Test
    fun `a value carrying a control character is refused`() {
        val parsed = CustomHeaderRules.parse("X-Tenant: acme\u0000tail")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.single().contains("line break"))
    }

    @Test
    fun `a value with an embedded carriage return is refused`() {
        val parsed = CustomHeaderRules.parse("X-Tenant: acme\rtail")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.single().contains("line break"))
    }

    @Test
    fun `a trailing carriage return is refused rather than trimmed away`() {
        // A paste that leaves the line break at the end of the value must not be
        // laundered into a clean-looking header by the parser's own trimming.
        val parsed = CustomHeaderRules.parse("X-Tenant: acme\r")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.single().contains("line break"))
        assertTrue(CustomHeaderRules.toHeaderMap(parsed.ignored).isEmpty())
        assertTrue(CustomHeaderRules.secretValues(parsed.ignored).isEmpty())
    }

    @Test
    fun `the app-managed headers cannot be replaced`() {
        val parsed = CustomHeaderRules.parse(
            """
            Authorization: Bearer attacker
            content-type: text/plain
            Host: evil.example.com
            Content-Length: 999999
            X-Tenant: acme
            """.trimIndent()
        )

        assertEquals(listOf("X-Tenant"), parsed.safe.map { it.name })
        assertEquals(4, parsed.ignored.size)
        assertTrue(parsed.reasons.any { it.contains("Authorization") && it.contains("cannot be replaced") })
    }

    @Test
    fun `reserved names are matched case-insensitively`() {
        assertTrue(CustomHeaderRules.isReservedName("AUTHORIZATION"))
        assertTrue(CustomHeaderRules.isReservedName(" content-type "))
        assertFalse(CustomHeaderRules.isReservedName("X-Authorization"))
    }

    @Test
    fun `only the first thirty-two headers are sent`() {
        val lines = (1..40).joinToString("\n") { "X-Header-$it: value-$it" }
        val parsed = CustomHeaderRules.parse(lines)

        assertEquals(32, parsed.safe.size)
        assertEquals(8, parsed.ignored.size)
        assertTrue(parsed.reasons.any { it.contains("32") })
    }

    @Test
    fun `applying headers leaves the app's own headers in place`() {
        val transitions = CustomHeaderRules.safeTransitions(
            """
            Authorization: Bearer attacker
            X-Tenant: acme
            """.trimIndent()
        )
        val request = CustomHeaderRules.apply(
            Request.Builder()
                .url("https://gateway.example.com/v1/chat/completions")
                .header("Authorization", "Bearer sk-real-key")
                .header("Content-Type", "application/json"),
            transitions
        ).build()

        assertEquals("Bearer sk-real-key", request.header("Authorization"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("acme", request.header("X-Tenant"))
    }

    @Test
    fun `applying a stale transition list still refuses reserved names`() {
        // A list built by hand (or held across a settings edit) must not be able to
        // slip a reserved header past apply().
        val forged = listOf(
            CustomHeaderRules.Transition("Authorization: Bearer attacker", "Authorization", "Bearer attacker", reason = null),
            CustomHeaderRules.Transition("X-Tenant: acme", "X-Tenant", "acme", reason = null)
        )
        val request = CustomHeaderRules.apply(
            Request.Builder()
                .url("https://gateway.example.com/v1/chat/completions")
                .header("Authorization", "Bearer sk-real-key"),
            forged
        ).build()

        assertEquals("Bearer sk-real-key", request.header("Authorization"))
        assertEquals("acme", request.header("X-Tenant"))
    }

    @Test
    fun `header values reach the wire as a map`() {
        val map = CustomHeaderRules.toHeaderMap(
            CustomHeaderRules.safeTransitions(
                """
                X-Tenant: acme
                Authorization: Bearer attacker
                """.trimIndent()
            )
        )

        assertEquals(mapOf("X-Tenant" to "acme"), map)
    }

    @Test
    fun `only values long enough to be a secret are registered for redaction`() {
        val transitions = CustomHeaderRules.safeTransitions(
            """
            X-Flag: true
            X-Tenant: tenant
            X-Gateway-Token: gw-0123456789abcdef
            """.trimIndent()
        )

        assertEquals(listOf("gw-0123456789abcdef"), CustomHeaderRules.secretValues(transitions))
    }

    @Test
    fun `masking hides values but keeps names readable`() {
        val masked = CustomHeaderRules.mask(
            """
            # gateway routing
            X-Tenant: acme
            X-Empty:
            not-a-header
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "# gateway routing",
                "X-Tenant: ••••",
                "X-Empty:",
                "not-a-header"
            ),
            masked.lines()
        )
        assertFalse(masked.contains("acme"))
    }

    @Test
    fun `a masked block still parses back to the same names`() {
        val raw = "X-Tenant: acme\nX-Gateway-Token: gw-0123456789abcdef"
        val masked = CustomHeaderRules.mask(raw)

        assertEquals(
            CustomHeaderRules.appliedNames(raw),
            CustomHeaderRules.appliedNames(masked)
        )
        assertEquals(listOf("X-Tenant", "X-Gateway-Token"), CustomHeaderRules.appliedNames(raw))
    }

    @Test
    fun `transition rendering never leaks a value`() {
        val transition = CustomHeaderRules.parse("X-Gateway-Token: gw-0123456789abcdef").safe.single()

        assertEquals("<redacted custom header>", transition.toString())
        assertNull(CustomHeaderRules.parse("X-Gateway-Token: gw-secret").safe.single().reason)
    }

    @Test
    fun `an empty block produces no headers and no warnings`() {
        val parsed = CustomHeaderRules.parse("")

        assertTrue(parsed.safe.isEmpty())
        assertTrue(parsed.reasons.isEmpty())
    }
}
