package org.austindroids.knoppen.validation.rules


import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/**
 * Validates the YAML-derived content against the JSON Schema.
 *
 * Uses the networknt json-schema-validator 3.x library (Jackson 3 / Java 17+)
 * which uses the new SchemaRegistry / Schema / Error API.
 *
 * The schema is parsed once and cached on construction for performance.
 */
class StructuralValidator(private val jsonSchemaContent: String) {

    // SchemaRegistry is the new entry point in 3.x (replaces JsonSchemaFactory)
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12
    )

    // Cache the compiled Schema for reuse — parsing is expensive
    private val schema: Schema = schemaRegistry.getSchema(jsonSchemaContent)

    fun validate(context: RuleContext) {
        // validate(JsonNode) → List<com.networknt.schema.Error>
        // This is the simplest overload that accepts an already-parsed JsonNode
        val errors: List<com.networknt.schema.Error> = schema.validate(context.root)

        errors.forEach { error ->
            // instanceLocation is the JSON Pointer to the failing node
            // e.g. "/tables/0/columns/2/type"
            val path = error.instanceLocation?.toString() ?: "/"

            val detail = buildString {
                append(error.message)
                // Append the schema rule location for diagnosability
                append(" [schema: ${error.schemaLocation}]")
            }

            context.error(path, detail)
        }
    }
}
