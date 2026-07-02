package org.austindroids.knoppen.sqlgen


/**
 * Shared mutable context available to all generators during a generation run.
 *
 * Allows generators like [ForeignCycleGenerator] to read values that were
 * produced for other tables/columns earlier in the same run.
 *
 * The generator adds rows itself via [recordGeneratedValue] — the
 * [UpsertGenerator] calls this after each row is finalized.
 */
class GeneratorContext {

    // tableName → columnName → ordered list of generated values
    private val generated = mutableMapOf<String, MutableMap<String, MutableList<Any?>>>()

    // Fields (data-file values merged with already-computed generated values) of the
    // row currently being generated — set by UpsertGenerator before invoking generators
    // for that row. Used by generators like GroupedSequenceGenerator that need to read
    // another column's value from the same row.
    private var currentRow: Map<String, Any?> = emptyMap()

    fun recordGeneratedValue(table: String, column: String, value: Any?) {
        generated
            .getOrPut(table)  { mutableMapOf() }
            .getOrPut(column) { mutableListOf() }
            .add(value)
    }

    /**
     * Returns all generated values for [table].[column], in row order.
     * Returns an empty list if no values have been recorded yet.
     */
    fun valuesFor(table: String, column: String): List<Any?> =
        generated[table]?.get(column) ?: emptyList()

    /** Called once per row, before its generators run, to make the row's own fields visible. */
    fun setCurrentRow(fields: Map<String, Any?>) {
        currentRow = fields
    }

    /** The value of [column] in the row currently being generated, or null if absent. */
    fun currentRowValue(column: String): Any? = currentRow[column]
}
