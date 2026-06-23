package org.austindroids.knoppen

import kotlinx.serialization.json.Json
import org.austindroids.knoppen.schema.DatabaseSchema
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory
import java.io.InputStream

/**
 * Parses a schema YAML document into a [DatabaseSchema].
 *
 * Strategy: YAML → canonical JSON string (via Jackson) → kotlinx-serialization
 * decodeFromString. This leverages the @Serializable annotations already on
 * the schema model classes without adding a YAML-specific serialization library.
 *
 * After deserialization, [DatabaseSchema.schema] is copied into each
 * [TableSchema.schemaName] so that SQL generators can emit schema-qualified
 * table references without carrying the parent reference around.
 */
object SchemaParser {

    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val jsonMapper = ObjectMapper()
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(input: InputStream): DatabaseSchema = parse(input.bufferedReader().readText())

    fun parse(yamlContent: String): DatabaseSchema {
        // Read YAML, then write as JSON using a plain ObjectMapper (no YAMLFactory)
        val jsonString = jsonMapper.writeValueAsString(yamlMapper.readTree(yamlContent))
        val raw = json.decodeFromString<DatabaseSchema>(jsonString)
        return raw.copy(
            tables = raw.tables.map { it.copy(schemaName = raw.schema) }
        )
    }
}
