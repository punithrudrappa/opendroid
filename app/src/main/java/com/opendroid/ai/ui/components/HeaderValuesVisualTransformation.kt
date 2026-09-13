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
        // Index just past the first colon on the current line, or -1 while the
        // caret is still in the header name.
        var valueStart = -1

        source.forEachIndexed { index, character ->
            when {
                character == '\n' -> {
                    masked.append(character)
                    valueStart = -1
                }

                // A colon inside a value (a URL, for instance) leaves the span open.
                character == ':' && valueStart < 0 -> {
                    masked.append(character)
                    valueStart = index + 1
                }

                // Padding around the value stays visible so an accidental trailing
                // space is still diagnosable.
                valueStart >= 0 && character != ' ' && character != '\t' -> masked.append(MASK_CHARACTER)

                else -> masked.append(character)
            }
        }

        return TransformedText(AnnotatedString(masked.toString()), OffsetMapping.Identity)
    }

    private companion object {
        const val MASK_CHARACTER = '•'
    }
}
