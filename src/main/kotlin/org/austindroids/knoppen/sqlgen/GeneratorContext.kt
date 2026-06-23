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
}
