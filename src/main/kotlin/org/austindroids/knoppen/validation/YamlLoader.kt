package org.austindroids.knoppen.validation

import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * Loads a YAML file into two parallel structures:
 *
 *  1. A [JsonNode] tree for JSON Schema validation and semantic rule checking.
 *  2. A [LineIndex] map that records the best-effort start line for every
 *     JSON Pointer path (e.g. "/tables/0/columns/1/name") so that error
 *     messages can reference source line numbers.
 *
 * Line tracking works by walking the YAML token stream produced by
 * [YAMLFactory] before the tree is fully constructed, capturing the line
 * number at the moment each field name token is seen.
 */
class YamlLoader {

    /**
     * Maps a JSON-Pointer-style path  →  1-based line number in the YAML source.
     * e.g. "/tables/0/columns/2/name" → 47
     */
    class LineIndex(private val map: Map<String, Int>) {
        /**
         * Returns the line number for [path], or null if not found.
         * Falls back progressively to parent paths so callers always get
         * the closest available location.
         */
        fun lineFor(path: String): Int? {
            if (map.containsKey(path)) return map[path]
            var parent = path
            while (parent.contains("/")) {
                parent = parent.substringBeforeLast("/")
                if (map.containsKey(parent)) return map[parent]
            }
            return null
        }
    }

    data class LoadResult(
        val root: JsonNode,
        val lineIndex: LineIndex
    )

    // Single shared mapper — reused for both passes
    private val mapper = ObjectMapper(YAMLFactory())

    fun load(yamlContent: String): LoadResult {
        val lineMap = mutableMapOf<String, Int>()

        // ── Pass 1: walk the token stream to record line numbers ──────────────
        // createParser(ObjectReadContext, String) is the non-deprecated 3.x API.
        // mapper.readContext() provides the ObjectReadContext that Jackson 3 requires.
        mapper.createParser(yamlContent).use { parser ->
            val pathStack      = ArrayDeque<String>()    // current path segments
            val indexStack     = ArrayDeque<Int>()       // current array index per depth
            val containerStack = ArrayDeque<ContainerType>()

            var pendingFieldName: String? = null

            fun currentPath() = "/" + pathStack.joinToString("/")

            while (true) {
                val token: JsonToken = parser.nextToken() ?: break
                val line = parser.currentLocation().lineNr

                when (token) {
                    JsonToken.START_OBJECT -> {
                        if (pendingFieldName != null) {
                            pathStack.addLast(pendingFieldName!!)
                            pendingFieldName = null
                        } else if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            pathStack.addLast(indexStack.last().toString())
                        }
                        lineMap[currentPath()] = line
                        containerStack.addLast(ContainerType.OBJECT)
                        indexStack.addLast(0)
                    }

                    JsonToken.START_ARRAY -> {
                        if (pendingFieldName != null) {
                            pathStack.addLast(pendingFieldName!!)
                            pendingFieldName = null
                        }
                        lineMap[currentPath()] = line
                        containerStack.addLast(ContainerType.ARRAY)
                        indexStack.addLast(0)
                    }

                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                        containerStack.removeLastOrNull()
                        indexStack.removeLastOrNull()
                        if (pathStack.isNotEmpty()) pathStack.removeLastOrNull()
                        // Advance parent array index after closing a child object/array
                        if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            val last = indexStack.removeLastOrNull() ?: 0
                            indexStack.addLast(last + 1)
                        }
                    }

                    JsonToken.PROPERTY_NAME -> {
                        // In Jackson 3.x: getString() gives the current token's string value,
                        // which for PROPERTY_NAME is the field name.
                        // currentName() is the method form — either works here.
                        val fieldName = parser.getString()
                        pendingFieldName = fieldName
                        // Record field-level line for precise error targeting
                        val fieldPath = "/" + (pathStack + fieldName).joinToString("/")
                        lineMap[fieldPath] = line
                    }

                    JsonToken.VALUE_STRING,
                    JsonToken.VALUE_NUMBER_INT,
                    JsonToken.VALUE_NUMBER_FLOAT,
                    JsonToken.VALUE_TRUE,
                    JsonToken.VALUE_FALSE,
                    JsonToken.VALUE_NULL -> {
                        if (pendingFieldName != null) {
                            // Scalar value for an object field — path already recorded above
                            pendingFieldName = null
                        } else if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            // Scalar item inside an array
                            val idx = indexStack.removeLastOrNull() ?: 0
                            val itemPath = "/" + (pathStack + idx.toString()).joinToString("/")
                            lineMap[itemPath] = line
                            indexStack.addLast(idx + 1)
                        }
                    }

                    else -> { /* ignore */ }
                }
            }
        }

        // ── Pass 2: parse into a JsonNode tree for validation ─────────────────
        val root = mapper.readTree(yamlContent)

        return LoadResult(root, LineIndex(lineMap))
    }

    private enum class ContainerType { OBJECT, ARRAY }
}
