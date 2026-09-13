package com.opendroid.ai.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Masks the values of a `Name: Value` header block for display, leaving the field's
 * real text untouched.
 *
 * A text field's masking must never be part of its *value*. Rendering masked text
 * as the value makes the mask the data: one keystroke in masked mode replaces the
 * real block with bullets, which are then stored and sent as a header value, and
 * OkHttp rejects the request with an opaque "unexpected char 0x2022" error. That is
 * exactly what happened when this editor first shipped. A [VisualTransformation]
 * avoids the class of bug by construction — the field keeps the real characters and
 * only the rendered glyphs change, precisely how `PasswordVisualTransformation`
 * masks a single-line API key.
 *
 * The transformation is length-preserving: every original character maps to exactly
 * one rendered character, so [OffsetMapping.Identity] is exact and the caret lands
 * where the user expects on every line.
 */
class HeaderValuesVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val masked = StringBuilder(source.length)

        // Each line is classified before masking: a comment is shown verbatim, a
        // `Name: Value` line keeps its name and hides its value, and anything else
        // (a half-typed line, or one whose colon was deleted) is hidden entirely,
        // because its text may be a token the user is mid-way through pasting.
        var lineStart = 0
        while (lineStart <= source.length) {
            val newline = source.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) source.length else newline
            maskAppend(source, lineStart, lineEnd, masked)
            if (newline < 0) break
            masked.append('\n')
            lineStart = newline + 1
        }

        return TransformedText(AnnotatedString(masked.toString()), OffsetMapping.Identity)
    }

    /** Appends one line's characters, masked according to what the line looks like. */
    private fun maskAppend(source: String, start: Int, end: Int, masked: StringBuilder) {
        val line = source.substring(start, end)
        val colon = line.indexOf(':')

        // Comments, blank lines, and a value-less `Name:` keep their text: masking
        // them would imply a secret that is not there.
        if (line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith(";") ||
            colon == line.length - 1
        ) {
            masked.append(line)
            return
        }

        if (colon < 0) {
            // No colon at all: mask every non-whitespace character, keeping length
            // (and therefore the caret mapping) exact.
            line.forEach { character ->
                masked.append(if (character == ' ' || character == '\t') character else MASK_CHARACTER)
            }
            return
        }

        for (index in line.indices) {
            val character = line[index]
            when {
                // The header name stays readable so the user can see what is configured.
                index <= colon -> masked.append(character)

                // Whitespace around the value stays visible, so an accidental trailing
                // space is still diagnosable.
                character == ' ' || character == '\t' -> masked.append(character)

                // Everything after the colon is the value.
                else -> masked.append(MASK_CHARACTER)
            }
        }
    }

    private companion object {
        const val MASK_CHARACTER = '•'
    }
}
