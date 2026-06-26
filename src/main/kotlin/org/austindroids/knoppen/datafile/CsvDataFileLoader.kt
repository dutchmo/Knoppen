package org.austindroids.knoppen.datafile

import com.jsoizo.kotlincsv.csvReader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.StringNode

/**
 * [DataFileLoader] implementation for CSV data files.
 *
 * Expected format — standard CSV with a header row:
 * ```csv
 * id,name
 * 1,technology
 * 2,science
 * ```
 *
 * All CSV values arrive as strings. Best-effort type coercion is applied:
 * blanks → null, "true"/"false" → boolean, integers, longs, doubles fall through;
 * anything else stays as a string.
 *
 * Source line numbers are tracked at row level (1-based, skipping the header row).
 */
class CsvDataFileLoader : DataFileLoader {

    private val objectMapper = ObjectMapper()

    override fun load(content: String, tableName: String): DataFileLoader.DataFileLoadResult {
        // v2.0.0 API: readAll returns List<List<String>> (all rows including header)
        val allRows: List<List<String>> = csvReader()
            .readAll(content)

        if (allRows.isEmpty()) {
            return DataFileLoader.DataFileLoadResult(emptyList(), DataFileLoader.LineIndex.EMPTY)
        }

        val headers  = allRows.first()
        val dataRows = allRows.drop(1)

        val lineMap = mutableMapOf<String, Int>()
        val nodes   = dataRows.mapIndexed { index, row ->
            val node = objectMapper.createObjectNode()
            headers.forEachIndexed { colIdx, header ->
                node.set(header, coerce(row.getOrElse(colIdx) { "" }))
            }
            // CSV rows start at line 2 (line 1 is the header)
            lineMap["/$index"] = index + 2
            node as JsonNode
        }

        return DataFileLoader.DataFileLoadResult(nodes, DataFileLoader.LineIndex(lineMap))
    }

    private fun coerce(raw: String): JsonNode {
        if (raw.isBlank()) return NullNode.instance
        if (raw.equals("true",  ignoreCase = true)) return BooleanNode.TRUE
        if (raw.equals("false", ignoreCase = true)) return BooleanNode.FALSE
        raw.toIntOrNull()?.let  { return IntNode.valueOf(it) }
        raw.toLongOrNull()?.let { return LongNode.valueOf(it) }
        raw.toDoubleOrNull()?.let {
            return objectMapper.nodeFactory.numberNode(it.toBigDecimal())
        }
        if (raw.startsWith("{") || raw.startsWith("[")) {
            try { return objectMapper.readTree(raw) } catch (_: Exception) {}
        }
        return StringNode.valueOf(raw)
    }
}
