package org.austindroids.knoppen.datafile

import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * Parses a YAML data file into a [DataFileLoadResult].
 *
 * The data file format is:
 * ```yaml
 * users:
 *   - id: 1
 *     username: "alice"
 * posts:
 *   - id: 1
 *     user_id: 1
 * ```
 *
 * Produces:
 *  - A map of tableName → list of row [JsonNode]s
 *  - A [LineIndex] mapping "/tableName/rowIndex/fieldName" → source line number
 */
class DataFileLoader {

    /**
     * Maps a slash-delimited path → 1-based YAML source line number.
     * e.g. "/users/0/username" → 14
     */
    class LineIndex(private val map: Map<String, Int>) {
        fun lineFor(table: String, rowIndex: Int, field: String? = null): Int? {
            val path = if (field != null) "/$table/$rowIndex/$field" else "/$table/$rowIndex"
            return map[path]
                ?: map["/$table/$rowIndex"]   // fall back to row-level
                ?: map["/$table"]             // fall back to table-level
        }
    }

    data class DataFileLoadResult(
        /** Map of table name → ordered list of row nodes */
        val tables: Map<String, List<JsonNode>>,
        val lineIndex: LineIndex
    )

    // Single shared mapper — reused for both passes
    private val mapper = ObjectMapper(YAMLFactory())

    fun load(yamlContent: String): DataFileLoadResult {
        val lineMap = mutableMapOf<String, Int>()

        // ── Pass 1: token walk to capture line numbers ────────────────────────
        // mapper.createParser() satisfies the Jackson 3.x ObjectReadContext requirement
        // and avoids the deprecated no-context createParser(String) overload.
        // No cast to YAMLParser needed — all required APIs are on JsonParser.
        mapper.createParser(yamlContent).use { parser ->
            val pathStack       = ArrayDeque<String>()
            val arrayIndexStack = ArrayDeque<Int>()
            val containerStack  = ArrayDeque<ContainerType>()
            var pendingField: String? = null

            fun currentPath() = "/" + pathStack.joinToString("/")

            while (true) {
                val token: JsonToken = parser.nextToken() ?: break
                val line = parser.currentLocation().lineNr

                when (token) {
                    JsonToken.START_OBJECT -> {
                        when {
                            pendingField != null -> {
                                pathStack.addLast(pendingField!!)
                                pendingField = null
                            }
                            containerStack.lastOrNull() == ContainerType.ARRAY -> {
                                pathStack.addLast(arrayIndexStack.last().toString())
                            }
                        }
                        lineMap[currentPath()] = line
                        containerStack.addLast(ContainerType.OBJECT)
                        arrayIndexStack.addLast(0)
                    }

                    JsonToken.START_ARRAY -> {
                        if (pendingField != null) {
                            pathStack.addLast(pendingField!!)
                            pendingField = null
                        }
                        lineMap[currentPath()] = line
                        containerStack.addLast(ContainerType.ARRAY)
                        arrayIndexStack.addLast(0)
                    }

                    JsonToken.END_OBJECT,
                    JsonToken.END_ARRAY -> {
                        containerStack.removeLastOrNull()
                        arrayIndexStack.removeLastOrNull()
                        if (pathStack.isNotEmpty()) pathStack.removeLastOrNull()
                        // Advance parent array index after closing a child element
                        if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            val i = arrayIndexStack.removeLastOrNull() ?: 0
                            arrayIndexStack.addLast(i + 1)
                        }
                    }

                    JsonToken.PROPERTY_NAME -> {
                        // getString() returns the field name when token is PROPERTY_NAME
                        val name = parser.getString()
                        pendingField = name
                        lineMap["/" + (pathStack + name).joinToString("/")] = line
                    }

                    JsonToken.VALUE_STRING,
                    JsonToken.VALUE_NUMBER_INT,
                    JsonToken.VALUE_NUMBER_FLOAT,
                    JsonToken.VALUE_TRUE,
                    JsonToken.VALUE_FALSE,
                    JsonToken.VALUE_NULL -> {
                        pendingField = null
                        if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            val i = arrayIndexStack.removeLastOrNull() ?: 0
                            arrayIndexStack.addLast(i + 1)
                        }
                    }

                    else -> {}
                }
            }
        }

        // ── Pass 2: full parse into JsonNode tree ─────────────────────────────
        val root = mapper.readTree(yamlContent)

        // Support both direct format (tableName: [...]) and wrapped format (tables: {tableName: [...]})
        val tablesWrapper = root.path("tables")
        val tableRoot     = if (tablesWrapper.isObject) tablesWrapper else root

        val tables = mutableMapOf<String, List<JsonNode>>()
        tableRoot.properties().forEach { entry ->
            if (entry.value.isArray) {
                tables[entry.key] = entry.value.toList()
            }
        }

        // Normalize line-map keys: strip the "/tables" prefix when present so that
        // LineIndex.lineFor("tag", 0, "id") finds "/tag/0/id" regardless of format.
        val normalizedLineMap = if (tablesWrapper.isObject) {
            val prefix = "/tables"
            lineMap.mapKeys { (k, _) ->
                when {
                    k.startsWith("$prefix/") -> k.removePrefix(prefix)
                    k == prefix              -> "/"
                    else                     -> k
                }
            }
        } else {
            lineMap
        }

        return DataFileLoadResult(tables, LineIndex(normalizedLineMap))
    }

    private enum class ContainerType { OBJECT, ARRAY }
}
