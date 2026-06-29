package org.austindroids.knoppen.datafile

import tools.jackson.core.JsonToken
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * [DataFileLoader] implementation for YAML data files.
 *
 * Expected format — a plain YAML sequence at the document root:
 * ```yaml
 * - id: 1
 *   name: "technology"
 * - id: 2
 *   name: "science"
 * ```
 *
 * Two-pass strategy:
 *  - Pass 1: token walk to capture source line numbers into a [DataFileLoader.LineIndex]
 *  - Pass 2: full parse into a [JsonNode] tree
 */
class YamlDataFileLoader : DataFileLoader {

    private val mapper = ObjectMapper(YAMLFactory())

    override fun load(content: String, tableName: String): DataFileLoader.DataFileLoadResult {
        val lineMap = mutableMapOf<String, Int>()

        // ── Pass 1: token walk to capture line numbers ────────────────────────
        mapper.createParser(content).use { parser ->
            val arrayIndexStack = ArrayDeque<Int>()
            val pathStack       = ArrayDeque<String>()
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
                                pathStack.addLast(pendingField)
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
                            pathStack.addLast(pendingField)
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
                        if (containerStack.lastOrNull() == ContainerType.ARRAY) {
                            val i = arrayIndexStack.removeLastOrNull() ?: 0
                            arrayIndexStack.addLast(i + 1)
                        }
                    }

                    JsonToken.PROPERTY_NAME -> {
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

        // ── Pass 2: full parse into JsonNode ──────────────────────────────────
        val root = mapper.readTree(content)
        val rows: List<JsonNode> = when {
            root.isArray  -> root.toList()
            root.isObject -> {
                // Legacy single-table wrapper: tableName: [...] — still accepted
                val arrayNode = root.properties().asSequence()
                    .map { it.value }.firstOrNull { it.isArray }
                arrayNode?.toList() ?: emptyList()
            }
            else -> emptyList()
        }

        return DataFileLoader.DataFileLoadResult(rows, DataFileLoader.LineIndex(lineMap))
    }

    private enum class ContainerType { OBJECT, ARRAY }
}
