package org.austindroids.knoppen.sqlgen


import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.datafile.DataFileLoader
import java.io.InputStream
import org.austindroids.knoppen.datafile.DataFileValidator
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.schema.DefaultType
import tools.jackson.databind.JsonNode

/**
 * A [ColumnGenerator] produces one value per call to [next].
 *
 * Generators are stateful — each instance is tied to one column
 * in one generation run. The [rowIndex] passed to [next] is the
 * 0-based position of the current row in the data block.
 *
 * [next] returns Any? so the value flows into [SqlDialect.formatValue]
 * exactly as if the user had supplied it in the data file.
 */
interface ColumnGenerator {
    fun next(rowIndex: Int): Any?
    fun reset()
}


/**
 * Orchestrates the full pipeline:
 *
 *   Load data file → Validate against schema → Generate SQL
 *
 * Returns a [GenerationResult] containing any validation errors and,
 * if validation passed, the generated SQL statements.
 */
class UpsertGenerator(
    private val dbSchema: DatabaseSchema,
    private val dialect: SqlDialect
) {
    data class GenerationResult(
        val errors: List<DataValidationError>,
        val sql: List<NamedStatement>
    ) {
        data class NamedStatement(
            val table: String,
            val rowIndex: Int,
            val sql: String
        )

        val hasErrors: Boolean get() = errors.any {
            it.severity == DataValidationError.Severity.ERROR
        }

        fun prettyPrint(): String = buildString {
            if (errors.isNotEmpty()) {
                val (errs, warns) = errors.partition {
                    it.severity == DataValidationError.Severity.ERROR
                }
                if (errs.isNotEmpty()) {
                    appendLine("❌ ${errs.size} validation error(s):")
                    errs.forEach { appendLine("   $it") }
                }
                if (warns.isNotEmpty()) {
                    appendLine("⚠\uFE0F  ${warns.size} warning(s):")
                    warns.forEach { appendLine("   $it") }
                }
            }
            if (sql.isNotEmpty()) {
                appendLine()
                appendLine("-- ============================")
                appendLine("-- Generated Upsert Statements")
                appendLine("-- ============================")
                sql.forEach { stmt ->
                    appendLine()
                    appendLine("-- Table: ${stmt.table}, row[${stmt.rowIndex}]")
                    appendLine(stmt.sql)
                }
            }
        }
    }

    fun generate(dataYaml: String): GenerationResult {
        // ── Step 1: Load data file ─────────────────────────────────────────────
        val loader     = DataFileLoader()
        val loadResult = loader.load(dataYaml)

        // ── Step 2: Validate ───────────────────────────────────────────────────
        val validationErrors = DataFileValidator(dbSchema, loadResult).validate()

        // ── Step 3: Generate SQL only if no hard errors ────────────────────────
        val statements = if (validationErrors.any {
                it.severity == DataValidationError.Severity.ERROR }) {
            emptyList()
        } else {
            buildStatements(loadResult)
        }

        return GenerationResult(validationErrors, statements)
    }

    fun generateStatements(dataStream: InputStream): GenerationResult =
        generate(dataStream.bufferedReader().readText())

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildStatements(
        loadResult: DataFileLoader.DataFileLoadResult
    ): List<GenerationResult.NamedStatement> {
        val statements       = mutableListOf<GenerationResult.NamedStatement>()
        val generatorContext = GeneratorContext()

        for ((tableName, rows) in loadResult.tables) {
            val tableSchema = dbSchema.tables.find { it.tableName == tableName }
                ?: continue

            // Build one generator instance per GENERATOR-type column.
            // Generators are reset between tables so state doesn't bleed across blocks.
            val generators: Map<String, ColumnGenerator> = tableSchema.columns
                .filter { col -> col.default?.type == DefaultType.GENERATOR }
                .associate { col ->
                    col.name to GeneratorParser.parse(
                        expression       = col.default!!.value,
                        generatorContext = generatorContext
                    )
                }

            // Reset all generators at the start of each table block
            generators.values.forEach { it.reset() }

            for ((rowIndex, rowNode) in rows.withIndex()) {
                // Merge generator-produced values into the field map.
                // Data file values always take precedence over generator defaults —
                // this lets individual rows override a generated default when needed.
                val baseFields     = flattenNode(rowNode)
                val generatedFields: Map<String, Any?> = generators
                    .filter { (colName, _) -> colName !in baseFields }
                    .mapValues { (_, gen) -> gen.next(rowIndex) }

                val mergedFields = generatedFields + baseFields  // base wins on conflict

                val dataRow = DataRow(
                    tableName = tableName,
                    fields    = mergedFields,
                    schema    = tableSchema
                )

                val sql = dialect.generateUpsert(dataRow)
                statements.add(GenerationResult.NamedStatement(tableName, rowIndex, sql))

                // Record generated values so FOREIGN_CYCLE can read them
                for ((colName, value) in mergedFields) {
                    generatorContext.recordGeneratedValue(tableName, colName, value)
                }
            }
        }

        return statements
    }


    /**
     * Flattens a [JsonNode] object into a Kotlin Map<String, Any?>.
     * Nested objects and arrays are kept as-is (as [JsonNode]) so the
     * dialect can serialize them appropriately (e.g. to JSONB).
     */
    private fun flattenNode(node: JsonNode): Map<String, Any?> =
        buildMap {
            node.properties().forEach { entry ->
                put(entry.key, nodeToValue(entry.value))
            }
        }


    private fun nodeToValue(node: JsonNode): Any? = when {
        node.isNull                           -> null
        node.isBoolean                        -> node.booleanValue()
        node.isInt                            -> node.intValue()
        node.isLong                           -> node.longValue()
        node.isDouble || node.isFloat         -> node.doubleValue()
        node.isBigDecimal                     -> node.decimalValue()
        node.isString                        -> node.stringValue()
        // Objects and arrays: keep as JsonNode for the dialect to serialize
        node.isObject || node.isArray         -> node
        else                                  -> node.asString()
    }
}
