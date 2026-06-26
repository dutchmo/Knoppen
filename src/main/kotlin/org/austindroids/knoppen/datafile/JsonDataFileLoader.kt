package org.austindroids.knoppen.datafile

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * [DataFileLoader] implementation for JSON data files.
 *
 * Expected format — a JSON array at the document root:
 * ```json
 * [
 *   {"id": 1, "name": "technology"},
 *   {"id": 2, "name": "science"}
 * ]
 * ```
 *
 * Source line numbers are not tracked for JSON (line index is empty).
 */
class JsonDataFileLoader : DataFileLoader {

    private val mapper = ObjectMapper()

    override fun load(content: String, tableName: String): DataFileLoader.DataFileLoadResult {
        val root: JsonNode = mapper.readTree(content)
        val rows: List<JsonNode> = when {
            root.isArray  -> root.toList()
            root.isObject -> listOf(root)
            else          -> emptyList()
        }
        return DataFileLoader.DataFileLoadResult(rows, DataFileLoader.LineIndex.EMPTY)
    }
}
