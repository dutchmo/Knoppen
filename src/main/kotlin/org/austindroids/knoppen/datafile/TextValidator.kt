package org.austindroids.knoppen.datafile

data class CharViolation(
    val index: Int,
    val line: Int,
    val col: Int,
    val char: Char,
    val codePoint: String,
    val name: String
)

class TextValidator(
    allowedChars: Set<Char> = DEFAULT_ALLOWED_CHARS
) {
    private val allowedSet: Set<Char> = allowedChars

    companion object {
        val DEFAULT_ALLOWED_CHARS: Set<Char> = buildSet {
            addAll((0x20..0x7E).map { it.toChar() })
            add('\t')
            add('\n')
            add('\r')
        }

        fun charName(c: Char): String = when (c) {
            '“' -> "LEFT DOUBLE QUOTATION MARK (smart quote “)"
            '”' -> "RIGHT DOUBLE QUOTATION MARK (smart quote ”)"
            '‘' -> "LEFT SINGLE QUOTATION MARK (smart quote ‘)"
            '’' -> "RIGHT SINGLE QUOTATION MARK (smart quote ’ / apostrophe)"
            '‚' -> "SINGLE LOW-9 QUOTATION MARK"
            '„' -> "DOUBLE LOW-9 QUOTATION MARK"
            '–' -> "EN DASH"
            '—' -> "EM DASH"
            '…' -> "HORIZONTAL ELLIPSIS"
            ' ' -> "NON-BREAKING SPACE"
            '€' -> "EURO SIGN"
            '™' -> "TRADEMARK SIGN"
            'ﬁ' -> "LATIN SMALL LIGATURE FI"
            'ﬂ' -> "LATIN SMALL LIGATURE FL"
            else     -> "U+${c.code.toString(16).uppercase().padStart(4, '0')}"
        }
    }

    fun validate(text: String): List<CharViolation> {
        val violations = mutableListOf<CharViolation>()
        var line = 1
        var col = 1

        text.forEachIndexed { index, char ->
            if (char !in allowedSet) {
                violations.add(
                    CharViolation(
                        index     = index,
                        line      = line,
                        col       = col,
                        char      = char,
                        codePoint = "U+${char.code.toString(16).uppercase().padStart(4, '0')}",
                        name      = charName(char)
                    )
                )
            }
            if (char == '\n') { line++; col = 1 } else { col++ }
        }

        return violations
    }

    fun isValid(text: String): Boolean = text.all { it in allowedSet }
}
