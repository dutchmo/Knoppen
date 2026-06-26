package org.austindroids.knoppen.datafile

import tools.jackson.databind.JsonNode

/**
 * Parses a data file (in any supported format) into a [DataFileLoadResult].
 *
 * Implementations are format-specific: [YamlDataFileLoader], [JsonDataFileLoader],
 * [CsvDataFileLoader]. The correct implementation is selected by [DataFileLoaderFactory]
 * based on the source file's extension.
 *
 * Data files contain rows for a **single table**. The table name is supplied by
 * the caller (from the schema's `files:` declaration) — it is not embedded in
 * the file content.
 */
interface DataFileLoader {

    /**
     * Loads rows from [content] for [tableName].
     *
     * @param content   Raw file content as a string
     * @param tableName Table name (from the schema declaration) — used for error context only
     */
    fun load(content: String, tableName: String): DataFileLoadResult

    // -------------------------------------------------------------------------

    /**
     * The result of loading a single data file.
     *
     * @param rows      Parsed rows in document order
     * @param lineIndex Best-effort mapping of row/field positions to source line numbers
     */
    data class DataFileLoadResult(
        val rows: List<JsonNode>,
        val lineIndex: LineIndex
    )

    /**
     * Maps a slash-delimited path → 1-based source line number.
     *
     * Path format: `/$rowIndex` or `/$rowIndex/$fieldName`
     * e.g. `/0/username` → 14
     */
    class LineIndex(private val map: Map<String, Int>) {
        fun lineFor(rowIndex: Int, field: String? = null): Int? {
            val path = if (field != null) "/$rowIndex/$field" else "/$rowIndex"
            return map[path]
                ?: map["/$rowIndex"]  // fall back to row-level
        }

        companion object {
            /** Returns a LineIndex with no entries — used when source lines are unavailable. */
            val EMPTY = LineIndex(emptyMap())

            /**
             * Merges two indices, offsetting [other]'s row indices by [rowOffset].
             * Used when concatenating rows from multiple files.
             */
            fun merge(base: LineIndex, other: LineIndex, rowOffset: Int): LineIndex {
                val merged = base.map.toMutableMap()
                other.map.forEach { (k, v) ->
                    // k is like "/0/field" or "/0" — re-prefix with the offset
                    val parts = k.split("/").filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) {
                        val shiftedRow = (parts[0].toIntOrNull() ?: return@forEach) + rowOffset
                        val newKey = "/" + (listOf(shiftedRow.toString()) + parts.drop(1)).joinToString("/")
                        merged[newKey] = v
                    }
                }
                return LineIndex(merged)
            }
        }
    }
}
