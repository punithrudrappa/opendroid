package com.opendroid.ai.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import com.opendroid.ai.core.llm.CustomHeaderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The masking shown in the header editor must be a rendering concern only.
 *
 * These pin the property that regressed in the field: the editor's *value* must
 * stay the real text so a keystroke can never commit the mask. That bug shipped as
 * `value = if (revealValues) value else CustomHeaderRules.mask(value)`, and users
 * hit it as `unexpected char 0x2022` on the wire.
 */
class HeaderValuesVisualTransformationTest {

    private val transformation = HeaderValuesVisualTransformation()

    @Test
    fun `values are masked while names and punctuation stay readable`() {
        val rendered = transformation.filter(AnnotatedString("X-Tenant: acme"))

        assertEquals("X-Tenant: ••••", rendered.text.text)
    }

    @Test
    fun `every line of a block is masked independently`() {
        val rendered = transformation.filter(
            AnnotatedString("X-Portkey-Config: pc-abc123\nCF-Access-Client-Id: 0123.access")
        )

        // One bullet per real character: "pc-abc123" is 9, "0123.access" is 11.
        assertEquals(
            "X-Portkey-Config: •••••••••\nCF-Access-Client-Id: •••••••••••",
            rendered.text.text
        )
    }

    @Test
    fun `the transformation is length preserving so the caret maps exactly`() {
        val source = AnnotatedString("X-Tenant: acme\nX-Pad:   spaced value  ")

        val rendered = transformation.filter(source)

        assertEquals(source.text.length, rendered.text.text.length)
        assertEquals(OffsetMapping.Identity, rendered.offsetMapping)
    }

    @Test
    fun `masking leaves the header names exactly as the parse step sees them`() {
        val block = "X-Tenant: acme\nCF-Access-Client-Id: 0123.access"

        val rendered = transformation.filter(AnnotatedString(block)).text.text

        // Names stay readable, which is the whole point of masking per line: the user
        // can still see what is configured without seeing the values. (The rendered
        // text itself is deliberately *not* a sendable block — parse() refuses bullets.)
        assertEquals(
            listOf("X-Tenant", "CF-Access-Client-Id"),
            rendered.lines().map { it.substringBefore(':') }
        )
        assertEquals(
            CustomHeaderRules.appliedNames(block),
            rendered.lines().map { it.substringBefore(':') }
        )
    }

    @Test
    fun `a colon inside a value does not reopen the name span`() {
        val rendered = transformation.filter(
            AnnotatedString("X-Target-Url: https://gateway.example.com/v1")
        )

        // Everything after the first colon is the value (30 characters), including the
        // scheme's colons — a colon cannot end the value span early.
        assertEquals("X-Target-Url: " + "•".repeat(30), rendered.text.text)
    }

    @Test
    fun `comment lines and blank lines are rendered unchanged`() {
        val block = "# routing for the gateway\n\nX-Tenant: acme"

        val rendered = transformation.filter(AnnotatedString(block)).text.text

        // A comment has no value to hide; masking it would imply a secret that is not there.
        assertEquals("# routing for the gateway\n\nX-Tenant: ••••", rendered)
    }

    @Test
    fun `an empty block renders empty`() {
        assertEquals("", transformation.filter(AnnotatedString("")).text.text)
    }

    @Test
    fun `a value-less line is left alone`() {
        val rendered = transformation.filter(AnnotatedString("X-Tenant:"))

        assertEquals("X-Tenant:", rendered.text.text)
    }

    @Test
    fun `the rendered mask never contains the real value`() {
        val gatewayToken = "gw-token-0123456789abcdef"

        val rendered = transformation.filter(AnnotatedString("X-Gateway-Token: $gatewayToken")).text.text

        assertFalse(rendered.contains(gatewayToken))
        assertEquals("X-Gateway-Token: " + "\u2022".repeat(gatewayToken.length), rendered)
        assertTrue(CustomHeaderRules.isMaskingCharacter('\u2022'))
    }
}
