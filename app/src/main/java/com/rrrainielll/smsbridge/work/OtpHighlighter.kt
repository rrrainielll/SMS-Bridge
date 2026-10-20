package com.rrrainielll.smsbridge.work

/**
 * Detects OTP / verification codes in SMS text and wraps them in Telegram HTML &lt;code&gt; tags so
 * users can tap-to-copy them in the Telegram app.
 *
 * Detection strategy (conservative – prefer false negatives over false positives):
 *
 * 1. Keyword-anchored codes – a code that immediately follows (or is preceded by) a contextual
 *    keyword such as "code", "OTP", "pin", "token", "password", "verification", etc.
 * 2. Standalone digit runs – 4–8 digit sequences that are NOT part of a longer number string,
 *    NOT a year (1900-2099), NOT a typical phone number, NOT a common date/time pattern, and
 *    preceded by "is", ":", "–", or similar delimiters.
 *
 * Only one highlighting pass is made per message; overlapping matches are skipped.
 *
 * HTML escaping: the entire message (including non-code portions) is escaped for Telegram's HTML
 * parse mode so that special characters in the original SMS do not break formatting.
 */
object OtpHighlighter {

    // Contextual keywords that strongly suggest an adjacent token is a code
    private val KEYWORD_REGEX = Regex(
        """(?ix)
        \b(?:
            one[\s\-]time[\s\-](?:pass(?:word|code|phrase)?|pin|code)|
            otp|
            verification[\s\-]code|
            verif(?:y|ication)|
            auth(?:entication|orization)?[\s\-]code|
            security[\s\-]code|
            access[\s\-]code|
            confirm(?:ation)?[\s\-]code|
            activation[\s\-]code|
            login[\s\-]code|
            sign[\s\-]in[\s\-]code|
            passcode|
            pass[\s\-]?code|
            pin[\s\-]code|
            \bpin\b|
            \bcode\b|
            \btoken\b|
            secret[\s\-]code|
            temporary[\s\-](?:password|code)|
            2fa[\s\-]code|
            two[\s\-]factor
        )\b"""
    )

    // A code that directly follows a keyword + optional separator
    private val KEYWORD_THEN_CODE = Regex(
        """(?ix)
        \b(?:
            one[\s\-]time[\s\-](?:pass(?:word|code|phrase)?|pin|code)|
            otp|
            verification[\s\-]code|
            verif(?:y|ication)|
            auth(?:entication|orization)?[\s\-]code|
            security[\s\-]code|
            access[\s\-]code|
            confirm(?:ation)?[\s\-]code|
            activation[\s\-]code|
            login[\s\-]code|
            sign[\s\-]in[\s\-]code|
            passcode|
            pass[\s\-]?code|
            pin[\s\-]code|
            \bpin\b|
            \bcode\b|
            \btoken\b|
            secret[\s\-]code|
            temporary[\s\-](?:password|code)|
            2fa[\s\-]code|
            two[\s\-]factor
        )\b
        [\s:=\-]*
        (?<code>[A-Za-z0-9]{4,12})
        \b"""
    )

    // Standalone digit codes preceded by "is", "are", ":", "–", or "-"
    private val STANDALONE_CODE = Regex(
        """(?:is|are|[:=\-])\s+(?<code>\d{4,8})\b"""
    )

    // Patterns to exclude from standalone detection (phones, years, dates, times)
    private val EXCLUSION = Regex(
        """(?x)
        (?:
            (?:\+|00)\d{6,15}                       | # international phone
            \(?\d{3}\)?[\s.\-]\d{3}[\s.\-]\d{4}    | # US phone
            \b(?:19|20)\d{2}\b                      | # year 1900-2099
            \b\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{2,4}\b | # date
            \b\d{1,2}:\d{2}(?::\d{2})?\b              # time
        )"""
    )

    /**
     * Highlights OTP/verification codes in [text] by wrapping them in `<code>…</code>` HTML tags
     * and escapes the rest of the text for Telegram HTML parse mode.
     */
    fun highlight(text: String): String {
        val ranges = mutableListOf<IntRange>()

        // Pass 1: keyword-anchored (highest confidence)
        for (match in KEYWORD_THEN_CODE.findAll(text)) {
            val group = match.groups["code"] ?: continue
            addNonOverlapping(ranges, group.range)
        }

        // Pass 2: standalone digit codes after "is / are / : / ="
        for (match in STANDALONE_CODE.findAll(text)) {
            val group = match.groups["code"] ?: continue
            val snippet = text.substring(group.range)
            if (!EXCLUSION.containsMatchIn(snippet)) {
                addNonOverlapping(ranges, group.range)
            }
        }

        if (ranges.isEmpty()) return escapeHtml(text)

        ranges.sortBy { it.first }

        val sb = StringBuilder()
        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) sb.append(escapeHtml(text.substring(cursor, range.first)))
            sb.append("<tg-spoiler><code>")
            sb.append(escapeHtml(text.substring(range.first, range.last + 1)))
            sb.append("</code></tg-spoiler>")
            cursor = range.last + 1
        }
        if (cursor < text.length) sb.append(escapeHtml(text.substring(cursor)))
        return sb.toString()
    }

    /** Escapes characters that are special in Telegram HTML parse mode. */
    fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&'  -> append("&amp;")
                '<'  -> append("&lt;")
                '>'  -> append("&gt;")
                '"'  -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    private fun addNonOverlapping(ranges: MutableList<IntRange>, candidate: IntRange) {
        if (ranges.none { it.first <= candidate.last && it.last >= candidate.first }) {
            ranges.add(candidate)
        }
    }
}
