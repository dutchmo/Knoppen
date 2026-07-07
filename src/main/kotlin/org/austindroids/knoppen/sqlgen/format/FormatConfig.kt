package org.austindroids.knoppen.sqlgen.format

enum class NewlineStyle {
    /** All clauses joined by a single space. Items always inline. */
    SINGLE_LINE,

    /** Each clause on its own line; items stay inline unless threshold exceeded. */
    LINE_PER_CLAUSE,

    /** Each clause on its own line; every ItemizedClause expands items to one per line. */
    LINE_PER_COLUMN
}

enum class CommaPosition { LEADING, TRAILING }

/**
 * Immutable formatting configuration.
 *
 * Some fields are ignored in certain modes:
 *   - SINGLE_LINE ignores [indent], [commaPosition], [riverAlignment].
 *   - [commaPosition] only matters when items are expanded to multiple lines.
 *   - [maxLineWidth] and [columnThresholdForExpand] are evaluated per-clause
 *     (only on ItemizedClause) and can trigger expansion even in LINE_PER_CLAUSE mode.
 */
data class FormatConfig(
    val newlineStyle: NewlineStyle = NewlineStyle.LINE_PER_CLAUSE,
    val indent: Int = 4,
    val commaPosition: CommaPosition = CommaPosition.TRAILING,
    val maxLineWidth: Int = Int.MAX_VALUE,
    val columnThresholdForExpand: Int = Int.MAX_VALUE,
    val riverAlignment: Boolean = false
) {
    companion object {
        val SINGLE_LINE = FormatConfig(newlineStyle = NewlineStyle.SINGLE_LINE)

        val TRADITIONAL = FormatConfig(
            newlineStyle = NewlineStyle.LINE_PER_CLAUSE,
            indent = 4,
            commaPosition = CommaPosition.TRAILING
        )

        val CASCADE2 = FormatConfig(
            newlineStyle = NewlineStyle.LINE_PER_COLUMN,
            indent = 2,
            commaPosition = CommaPosition.LEADING
        )

        val CASCADE4 = FormatConfig(
            newlineStyle = NewlineStyle.LINE_PER_COLUMN,
            indent = 4,
            commaPosition = CommaPosition.LEADING
        )

        /** River-aligned keywords (right-padded to widest keyword width),
         *  leading commas, 4-space indent. Follows sqlstyle.guide conventions. */
        val RIVER = FormatConfig(
            newlineStyle = NewlineStyle.LINE_PER_COLUMN,
            indent = 4,
            commaPosition = CommaPosition.LEADING,
            riverAlignment = true
        )

        /** Alias for RIVER — the Simon Holywell style guide. */
        val SQL_STYLE_GUIDE = RIVER

        /**
         * Reproduces the pre-formatter PostgresDialect output byte-for-byte:
         * INSERT/VALUES/SET columns always expand one-per-line with trailing
         * commas and 4-space indent. This is the default so existing callers
         * and tests that assume the legacy layout are unaffected.
         */
        val LEGACY = FormatConfig(
            newlineStyle = NewlineStyle.LINE_PER_COLUMN,
            indent = 4,
            commaPosition = CommaPosition.TRAILING
        )

        /**
         * Named presets accepted by the CLI's `--output-format` option and the Maven
         * plugin's `outputFormat` parameter. Single source of truth so both front-ends
         * expose the same layout names. [SQL_STYLE_GUIDE] is intentionally omitted as a
         * name here since it aliases [RIVER].
         */
        val presetsByName: Map<String, FormatConfig> = mapOf(
            "LEGACY"      to LEGACY,
            "SINGLE_LINE" to SINGLE_LINE,
            "TRADITIONAL" to TRADITIONAL,
            "CASCADE2"    to CASCADE2,
            "CASCADE4"    to CASCADE4,
            "RIVER"       to RIVER
        )

        /** Case-insensitive preset lookup by name; null when the name is unknown. */
        fun byName(name: String): FormatConfig? =
            presetsByName[name.uppercase()]
    }
}
