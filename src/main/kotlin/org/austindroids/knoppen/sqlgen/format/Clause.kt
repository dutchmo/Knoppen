package org.austindroids.knoppen.sqlgen.format

/**
 * A single clause within a generated SQL DML statement.
 *
 * Two subtypes:
 *  - [AtomicClause]: rendered as-is, no expandable content.
 *  - [ItemizedClause]: has a list of items that can be inlined or expanded
 *    to one-per-line based on format settings.
 */
sealed class Clause {
    abstract val keyword: String
}

/** Clause that always renders as a single string (keyword only). */
data class AtomicClause(override val keyword: String) : Clause()

/**
 * Clause with a list of items that the formatter can inline or expand.
 * @param parens  If true, items are wrapped in parentheses: KEYWORD (item1, item2)
 *                If false:                        KEYWORD item1, item2
 */
data class ItemizedClause(
    override val keyword: String,
    val items: List<String>,
    val parens: Boolean = false
) : Clause()

/**
 * A multi-row VALUES clause: KEYWORD (v1, v2), (v3, v4), ...
 *
 * Each inner list of [rows] is one row's already-formatted values, in column
 * order. Unlike [ItemizedClause], a row-tuple is always rendered inline
 * (`(v1, v2)`) regardless of format style — only the tuple-to-tuple join
 * (single line vs. one tuple per line) varies, since splitting an individual
 * tuple's columns across lines would make a large batch unreadable.
 */
data class RowValuesClause(
    override val keyword: String,
    val rows: List<List<String>>
) : Clause()
