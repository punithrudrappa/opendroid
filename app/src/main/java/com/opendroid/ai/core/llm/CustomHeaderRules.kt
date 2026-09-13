package com.opendroid.ai.core.llm

import okhttp3.Request

/**
 * Extra HTTP headers supplied by the user for a self-hosted or gateway
 * OpenAI-compatible endpoint (Portkey, Cloudflare AI Gateway, LiteLLM, vLLM,
 * a corporate proxy, ...). Gateways routinely require routing or attribution
 * headers that the app cannot know about, and some deployments expect a
 * vendor-specific auth header instead of `Authorization: Bearer`.
 *
 * The user edits one header per line in Settings:
 *
 * ```
 * X-Portkey-Config: pc-abc123
 * CF-Access-Client-Id: 0123.access
 * ```
 *
 * Parsing is all-or-nothing per line: a line is either applied or reported back
 * through [Parsed.reasons] so the UI can explain what was skipped. Nothing is
 * silently dropped, and nothing the transport itself owns (`Authorization`,
 * `Content-Type`, `Host`, ...) can be overridden — see [RESERVED_NAMES].
 */
object CustomHeaderRules {

    /** Header names a custom-header line may match but never replace. */
    val RESERVED_NAMES: Set<String> = setOf(
        "authorization",
        "content-type",
        "content-length",
        "host",
        "connection",
        "transfer-encoding",
        "cookie",
        "accept-encoding"
    )

    /** Fine for a bearer token, far below anything that would bloat a request. */
    private const val MAX_VALUE_LENGTH = 8_192

    /** Keeps one pasted blob from turning into an unbounded number of headers. */
    private const val MAX_HEADERS = 32

    /**
     * RFC 7230 `token`: header names are matched by servers, so a name carrying
     * whitespace or punctuation is a typo, not an exotic-but-valid header.
     */
    private val NAME_PATTERN = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")

    /** A value that carries its own line break could inject a second header. */
    private val FORBIDDEN_VALUE_CHARS = charArrayOf('\r', '\n', '\u0000')

    /**
     * Characters a header value can never legitimately contain here: the masking
     * bullet plus the invisible characters a copy/paste from a document brings
     * along. OkHttp rejects them, so a line carrying one would fail the whole
     * request with an opaque "unexpected char" error instead of a usable message.
     */
    private val MASKING_CHARACTERS = setOf(
        '\u2022', // • U+2022 BULLET, the mask
        '\u00a0', // non-breaking space
        '\u200b', // zero-width space
        '\u200e', // left-to-right mark
        '\u200f', // right-to-left mark
        '\ufeff'  // byte-order mark
    )

    private const val MASKED_VALUE_REASON =
        "looks pasted from the masked display; tap \"Show values\", clear the line, and retype it"

    private const val UNSENDABLE_VALUE_REASON =
        "contains a character OkHttp cannot send in a header value"

    /**
     * The only characters OkHttp accepts in a header value: horizontal tab and
     * printable ASCII. Everything else — control characters, DEL, and non-ASCII such
     * as `é`, `—`, or a smart quote — makes `Request.Builder.header` throw
     * `IllegalArgumentException` *while the request is being built*, which reaches the
     * user as an opaque failure instead of a fixable line.
     *
     * `addUnsafeNonAscii` exists but is deliberately not used: RFC 7230 says newly
     * defined fields SHOULD limit values to US-ASCII, and shipping raw bytes only
     * works when the server happens to decode them the same way. Refusing the value
     * with a reason keeps the failure visible and correctable.
     */
    private fun Char.isSendableInHeaderValue(): Boolean =
        this == '\t' || (this in '\u0020'..'\u007e')

    private const val MASK_CHAR = '•'

    /**
     * Resolution of one editor snapshot. [reasons] is empty when every line was
     * applied, so callers can show a warning only when there is something real
     * to say.
     */
    data class Parsed(
        val safe: List<Transition>,
        val ignored: List<Transition>,
        val reasons: List<String>
    )

    /**
     * One editor line and how far it got: [reason] is null for a line that is
     * applied, and the user-facing explanation for one that is not.
     */
    data class Transition(
        val line: String,
        val name: String,
        val value: String,
        val reason: String? = null
    ) {
        val isSafe: Boolean get() = reason == null
        override fun toString(): String = "<redacted custom header>"
    }

    /** True when [name] is a header the transport or the API key already owns. */
    fun isReservedName(name: String): Boolean =
        name.trim().lowercase() in RESERVED_NAMES

    /** True for characters that are only ever produced by masking a value. */
    fun isMaskingCharacter(character: Char): Boolean = character in MASKING_CHARACTERS

    /**
     * Parses the editor snapshot. `#` and `;` start a comment line, a blank line
     * is ignored, and the value keeps everything after the first `:` (a URL value
     * is common, e.g. `X-Target-Url: https://host/path`).
     *
     * Lines are split on `\n` only, and trimmed with [trimNameAndValue] rather
     * than [String.trim]: a pasted `\r\n` therefore splits into two lines
     * instead of leaving a carriage return inside a value, and a `\r` that does
     * end up inside a value is refused by [isTransportable] rather than trimmed
     * away into something that looks clean.
     */
    fun parse(raw: String): Parsed {
        val safe = mutableListOf<Transition>()
        val ignored = mutableListOf<Transition>()
        val reasons = mutableListOf<String>()
        val effectiveNames = mutableSetOf<String>()

        raw.split('\n').forEach { rawLine ->
            val line = rawLine.trimNameAndValue()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach

            val separator = line.indexOf(':')
            if (separator < 0) {
                reject(ignored, reasons, Transition(line, name = line, value = ""),
                    "line \"$line\" is missing \":\"")
                return@forEach
            }

            val name = line.substring(0, separator).trimNameAndValue()
            val value = line.substring(separator + 1).trimNameAndValue()
            val transition = Transition(line, name = name, value = value)

            when {
                !NAME_PATTERN.matches(name) ->
                    reject(ignored, reasons, transition, "\"$name\" is not a valid header name")

                value.isEmpty() ->
                    reject(ignored, reasons, transition, "\"$name\" has no value")

                value.length > MAX_VALUE_LENGTH ->
                    reject(ignored, reasons, transition, "\"$name\" value is too long")

                FORBIDDEN_VALUE_CHARS.any(value::contains) ->
                    reject(ignored, reasons, transition, "\"$name\" value contains a line break")

                value.any(::isMaskingCharacter) ->
                    reject(ignored, reasons, transition, "\"$name\" $MASKED_VALUE_REASON")

                !value.all { it.isSendableInHeaderValue() } ->
                    reject(ignored, reasons, transition, "\"$name\" $UNSENDABLE_VALUE_REASON")

                isReservedName(name) ->
                    reject(ignored, reasons, transition, "\"$name\" is set by the app and cannot be replaced")

                !effectiveNames.add(name.lowercase()) ->
                    reject(ignored, reasons, transition, "\"$name\" is repeated; the first value is used")

                safe.size >= MAX_HEADERS ->
                    reject(ignored, reasons, transition, "only the first $MAX_HEADERS headers are sent")

                else -> safe += transition
            }
        }

        return Parsed(safe = safe, ignored = ignored, reasons = reasons)
    }

    /** The transitions to send, in the order the user wrote them. */
    fun safeTransitions(raw: String): List<Transition> = parse(raw).safe

    /** Names of every header the request will carry, for diagnostics. */
    fun appliedNames(raw: String): List<String> = safeTransitions(raw).map { it.name }

    /**
     * Adds [transitions] to an in-flight request. The name checks are repeated
     * here because a caller may hold transitions parsed from an older snapshot;
     * `apply` is the last point before data reaches the network.
     */
    fun apply(builder: Request.Builder, transitions: List<Transition>): Request.Builder {
        transitions.forEach { transition ->
            if (transition.isSafe && isTransportable(transition)) {
                builder.header(transition.name, transition.value)
            }
        }
        return builder
    }

    /** Map form for callers that build headers before a request exists. */
    fun toHeaderMap(transitions: List<Transition>): Map<String, String> =
        transitions
            .filter(::isTransportable)
            .associate { it.name to it.value }

    /**
     * Values worth registering with [com.opendroid.ai.core.llm.error.SecretRegistry]:
     * a gateway token in a custom header must be redacted out of error text the
     * same way an API key is.
     */
    fun secretValues(transitions: List<Transition>): List<String> =
        transitions
            .filter { isTransportable(it) && it.value.length >= MIN_SECRET_LENGTH }
            .map { it.value }

    /**
     * Masked text for a display-only preview: names stay readable, values become
     * bullets, so a pasted gateway token is not shouldersurfable.
     *
     * This is text for *showing*, never text for editing. An earlier version fed
     * this output back into the header editor as the text field's own value, which
     * turned the bullets into the stored header value the moment the user typed;
     * [parse] now also refuses such a value outright, and the editor masks through
     * `HeaderValuesVisualTransformation` so its value stays the real block.
     */
    fun mask(raw: String): String = raw.split('\n').joinToString("\n") { rawLine ->
        val line = rawLine.trimNameAndValue()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
            rawLine
        } else {
            val separator = line.indexOf(':')
            if (separator < 0) {
                rawLine
            } else {
                val name = line.substring(0, separator)
                val value = line.substring(separator + 1).trimNameAndValue()
                if (value.isEmpty()) {
                    rawLine
                } else {
                    "$name: " + MASK_CHAR.toString().repeat(value.length.coerceAtMost(MAX_MASKED_CHARS))
                }
            }
        }
    }

    private const val MIN_SECRET_LENGTH = 8
    private const val MAX_MASKED_CHARS = 16

    /**
     * Trims the whitespace a paste adds around a name or value, but never a
     * carriage return: `\r` is a line break, and hiding one here would make the
     * value look clean while [isTransportable] happily sent it.
     */
    private fun String.trimNameAndValue(): String = trim(' ', '\t')

    private fun isTransportable(transition: Transition): Boolean =
        NAME_PATTERN.matches(transition.name) &&
            transition.value.isNotEmpty() &&
            transition.value.length <= MAX_VALUE_LENGTH &&
            FORBIDDEN_VALUE_CHARS.none(transition.value::contains) &&
            transition.value.none(::isMaskingCharacter) &&
            transition.value.all { it.isSendableInHeaderValue() } &&
            !isReservedName(transition.name)

    private fun reject(
        ignored: MutableList<Transition>,
        reasons: MutableList<String>,
        transition: Transition,
        reason: String
    ) {
        ignored += transition.copy(reason = reason)
        reasons += reason
    }
}
