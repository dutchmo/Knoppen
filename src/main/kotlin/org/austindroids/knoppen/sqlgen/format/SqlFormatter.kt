package org.austindroids.knoppen.sqlgen.format

/**
 * Lays out a list of [Clause] objects according to a [FormatConfig].
 *
 * The formatter has zero knowledge of SQL semantics. It only knows about
 * separators, indentation, comma placement, and optional river alignment.
 * All quoting, type-casting, and dialect-specific syntax is the dialect's
 * responsibility and is already baked into the clause strings.
 */
class SqlFormatter(private val config: FormatConfig) {

    /**
     * Formats [clauses] into a single SQL statement string ending with [terminator].
     * @param clauses    ordered list of clauses to render
     * @param terminator statement terminator (typically ";")
     */
    fun format(clauses: List<Clause>, terminator: String = ";"): String {
        if (clauses.isEmpty()) return terminator

        val betweenClauses = when (config.newlineStyle) {
            NewlineStyle.SINGLE_LINE -> " "
            else                     -> "\n"
        }
        val expandAllItems = config.newlineStyle == NewlineStyle.LINE_PER_COLUMN

        val keywordWidth = if (config.riverAlignment)
            clauses.maxOf { it.keyword.length }
        else 0

        return buildString {
            clauses.forEachIndexed { i, clause ->
                if (i > 0) append(betweenClauses)
                renderClause(clause, keywordWidth, expandAllItems)
            }
            append(terminator)
        }
    }

    // ── Private ────────────────────────────────────────────────────────────

    private fun StringBuilder.renderClause(
        clause: Clause,
        keywordWidth: Int,
        expandAllItems: Boolean
    ) {
        val kw = if (keywordWidth > 0) clause.keyword.padStart(keywordWidth)
                 else clause.keyword

        when (clause) {
            is AtomicClause    -> append(kw)
            is ItemizedClause  -> renderItemized(kw, clause, expandAllItems)
            is RowValuesClause -> renderRowValues(kw, clause)
        }
    }

    private fun StringBuilder.renderItemized(
        kw: String,
        clause: ItemizedClause,
        expandAllItems: Boolean
    ) {
        if (clause.items.isEmpty()) {
            append(kw)
            return
        }

        val shouldExpand = expandAllItems
            || clause.items.size > config.columnThresholdForExpand
            || estimateInlineLength(kw, clause) > config.maxLineWidth

        if (!shouldExpand) {
            // Inline: KEYWORD (item1, item2)  or  KEYWORD item1, item2
            val joined = clause.items.joinToString(", ")
            append(if (clause.parens) "$kw ($joined)" else "$kw $joined")
        } else {
            // Expanded: one item per line
            val indentStr = " ".repeat(config.indent)
            append(if (clause.parens) "$kw (" else kw)
            append("\n")
            clause.items.forEachIndexed { i, item ->
                append(indentStr)
                when (config.commaPosition) {
                    CommaPosition.LEADING ->
                        if (i == 0) append(item) else append(", $item")
                    CommaPosition.TRAILING ->
                        if (i == clause.items.lastIndex) append(item)
                        else append("$item,")
                }
                append("\n")
            }
            if (clause.parens) append(")")
        }
    }

    private fun StringBuilder.renderRowValues(kw: String, clause: RowValuesClause) {
        if (clause.rows.isEmpty()) {
            append(kw)
            return
        }

        val tuples = clause.rows.map { row -> "(" + row.joinToString(", ") + ")" }

        if (config.newlineStyle == NewlineStyle.SINGLE_LINE) {
            // Inline: KEYWORD (v1, v2), (v3, v4)
            append("$kw ${tuples.joinToString(", ")}")
            return
        }

        // One row-tuple per line — the tuple itself is never split across lines.
        // Newlines are placed *between* tuples (not trailing after the last one)
        // so no blank line appears before the next clause.
        val indentStr = " ".repeat(config.indent)
        append(kw)
        append("\n")
        tuples.forEachIndexed { i, tuple ->
            if (i > 0) append("\n")
            append(indentStr)
            when (config.commaPosition) {
                CommaPosition.LEADING ->
                    if (i == 0) append(tuple) else append(", $tuple")
                CommaPosition.TRAILING ->
                    if (i == tuples.lastIndex) append(tuple) else append("$tuple,")
            }
        }
    }

    /** Approximate length if this clause were rendered inline. */
    private fun estimateInlineLength(kw: String, clause: ItemizedClause): Int =
        kw.length + 1 +
            clause.items.joinToString(", ").length +
            if (clause.parens) 3 else 0  // " (…)"
}
