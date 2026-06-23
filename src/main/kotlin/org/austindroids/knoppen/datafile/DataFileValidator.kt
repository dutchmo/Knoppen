package org.austindroids.knoppen.datafile


import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ── Schema model imports (from your existing data classes) ────────────────────
import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.schema.EnumConstraint
import org.austindroids.knoppen.schema.PatternConstraint
import org.austindroids.knoppen.schema.RequiredConstraint
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.schema.TemporalConstraint
import org.austindroids.knoppen.schema.UniqueConstraint
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.*

/**
 * Validates every row in a data file against its matching [TableSchema].
 *
 * Checks performed per row:
 *  1. Unknown fields (if strictFields = true)
 *  2. Required fields present and non-null
 *  3. Type compatibility
 *  4. Per-column constraints (enum, pattern, temporal, unique)
 *
 * Uniqueness is tracked in-memory across all rows in the same data file
 * for columns marked with [UniqueConstraint]. Database-level uniqueness
 * (i.e. existing rows) is not checked here.
 */
class DataFileValidator(
    private val dbSchema: DatabaseSchema,
    private val loadResult: DataFileLoader.DataFileLoadResult
) {
    private val errors   = mutableListOf<DataValidationError>()
    private val config   = dbSchema.validation

    // tableName → columnName → set of already-seen values (for in-file uniqueness)
    private val uniqueSets = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    fun validate(): List<DataValidationError> {
        errors.clear()
        uniqueSets.clear()

        loadResult.tables.forEach { (tableName, rows) ->
            val tableSchema = dbSchema.tables.find { it.tableName == tableName }
            if (tableSchema == null) {
                // Top-level unknown table block
                addError(
                    table    = tableName,
                    rowIndex = -1,
                    field    = null,
                    line     = loadResult.lineIndex.lineFor(tableName, -1),
                    message  = "Data file contains table '$tableName' which is not defined in the schema"
                )
                return@forEach
            }
            rows.forEachIndexed { rowIndex, row ->
                validateRow(tableSchema, tableName, rowIndex, row)
            }
        }

        return errors.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row-level
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateRow(
        schema: TableSchema,
        tableName: String,
        rowIndex: Int,
        row: JsonNode
    ) {
        val definedColumns = schema.columns.map { it.name }.toSet()
        val rowFields      = row.properties().map { it.key }.toSet()

        // ── 1. Unknown fields ─────────────────────────────────────────────────
        if (config.strictFields) {
            (rowFields - definedColumns).forEach { unknown ->
                addError(
                    table    = tableName,
                    rowIndex = rowIndex,
                    field    = unknown,
                    line     = loadResult.lineIndex.lineFor(tableName, rowIndex, unknown),
                    message  = "Unknown field '$unknown' — not defined in schema for table '$tableName'." +
                            " (strictFields is enabled)"
                )
            }
        }

        // ── 2–4. Per-column validation ────────────────────────────────────────
        schema.columns.forEach { colSchema ->
            val value = row.get(colSchema.name)    // null if field is absent
            validateColumn(schema, tableName, rowIndex, colSchema, value)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Column-level
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateColumn(
        tableSchema: TableSchema,
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode?
    ) {
        val line = loadResult.lineIndex.lineFor(tableName, rowIndex, col.name)

        // ── Required ──────────────────────────────────────────────────────────
        val isRequired = col.constraints.any { it is RequiredConstraint }
        val isNullable = !isRequired && config.defaultNullable

        if (value == null || value.isNull) {
            if (isRequired) {
                addError(tableName, rowIndex, col.name, line,
                    "Field '${col.name}' is required but is missing or null")
            }
            // Nullable columns with no value: nothing more to check
            return
        }

        // ── Type compatibility ────────────────────────────────────────────────
        validateType(tableName, rowIndex, col, value, line)

        // ── Constraints ───────────────────────────────────────────────────────
        col.constraints.forEach { constraint ->
            when (constraint) {
                is RequiredConstraint -> { /* handled above */ }
                is UniqueConstraint   -> validateUnique(tableName, rowIndex, col, value, constraint, line)
                is EnumConstraint     -> validateEnum(tableName, rowIndex, col, value, constraint, line)
                is PatternConstraint  -> validatePattern(tableName, rowIndex, col, value, constraint, line)
                is TemporalConstraint -> validateTemporal(tableName, rowIndex, col, value, constraint, line)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type compatibility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks whether the YAML node type is compatible with the declared SQL type.
     * We are deliberately lenient here — e.g. an integer in YAML is also a valid
     * NUMERIC. Stricter coercion happens in the SQL generator.
     */
    private fun validateType(
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        line: Int?
    ) {
        val rawType  = col.type.uppercase().substringBefore("(").trim()
        val typeMismatch: String? = when (rawType) {
            "INTEGER", "INT", "BIGINT" ->
                if (!value.isNumber || value.isFloatingPointNumber)
                    "Expected integer, got ${nodeTypeName(value)}: '${value.asText()}'"
                else null

            "NUMERIC", "DECIMAL" ->
                if (!value.isNumber)
                    "Expected numeric, got ${nodeTypeName(value)}: '${value.asText()}'"
                else null

            "BOOLEAN" ->
                if (!value.isBoolean)
                    "Expected boolean, got ${nodeTypeName(value)}: '${value.asText()}'"
                else null

            "TEXT", "VARCHAR" ->
                if (!value.isTextual)
                    "Expected text, got ${nodeTypeName(value)}: '${value.asText()}'"
                else null

            "JSONB", "JSON" ->
                // YAML objects and arrays map naturally to JSONB
                if (!value.isObject && !value.isArray && !value.isTextual)
                    "Expected JSON object, array, or text, got ${nodeTypeName(value)}"
                else null

            "TIMESTAMP", "DATE" ->
                // Must be a string that parses as a date/timestamp
                if (!value.isTextual) {
                    "Expected timestamp string, got ${nodeTypeName(value)}: '${value.asText()}'"
                } else {
                    parseTimestamp(value.asText())   // returns error string or null
                        ?.let { "Cannot parse '${value.asText()}' as $rawType: $it" }
                }

            else -> null  // Unknown/custom types pass through
        }

        if (typeMismatch != null) {
            addError(tableName, rowIndex, col.name, line,
                "Type mismatch for column '${col.name}' (declared ${col.type}): $typeMismatch")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constraint validators
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateUnique(
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: UniqueConstraint,
        line: Int?
    ) {
        val key = value.asText()
        val seen = uniqueSets
            .getOrPut(tableName) { mutableMapOf() }
            .getOrPut(col.name)  { mutableSetOf() }

        if (!seen.add(key)) {
            if (constraint.conflictTarget) return  // conflict rows legitimately repeat; not a violation
            addError(tableName, rowIndex, col.name, line,
                "Unique constraint violated for '${col.name}': value '$key'" +
                        " appears more than once in this data file")
        }
    }

    private fun validateEnum(
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: EnumConstraint,
        line: Int?
    ) {
        val text = value.asText()
        if (text !in constraint.values) {
            val allowed = constraint.values.joinToString(", ") { "'$it'" }
            val message = constraint.message
                ?: "Value '$text' is not a valid enum value for '${col.name}'. Allowed: [$allowed]"
            addError(tableName, rowIndex, col.name, line, message)
        }
    }

    private fun validatePattern(
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: PatternConstraint,
        line: Int?
    ) {
        if (!value.isTextual) return   // type validator will have already flagged this
        val text  = value.asText()
        val regex = try { Regex(constraint.regex) } catch (e: Exception) { return }

        if (!regex.containsMatchIn(text)) {
            val message = constraint.message
                ?: "Value '$text' for '${col.name}' does not match required pattern: ${constraint.regex}"
            addError(tableName, rowIndex, col.name, line, message)
        }
    }

    private fun validateTemporal(
        tableName: String,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: TemporalConstraint,
        line: Int?
    ) {
        if (!value.isTextual) return

        val instant: Instant = run {
            var result: Instant? = null
            for (fmt in TIMESTAMP_FORMATTERS) {
                try { result = OffsetDateTime.parse(value.asText(), fmt).toInstant(); break }
                catch (_: DateTimeParseException) {}
            }
            result ?: return  // Type validator will have caught the parse error
        }

        val now = Instant.now()

        if (constraint.notFuture && instant.isAfter(now)) {
            addError(tableName, rowIndex, col.name, line,
                "Value '${value.asText()}' for '${col.name}' is in the future," +
                        " but notFuture constraint is set")
        }

        constraint.notPast?.let { duration ->
            // duration is like "-P4Y" — strip the leading "-" and negate
            val positive  = duration.removePrefix("-")
            val boundary  = try {
                // Java Duration doesn't handle Y/M — use period for date parts
                val period   = java.time.Period.parse(positive)
                now.atOffset(java.time.ZoneOffset.UTC)
                    .minus(period)
                    .toInstant()
            } catch (e: Exception) {
                return  // Semantic validator already flagged bad format
            }

            if (instant.isBefore(boundary)) {
                // notPast violations are WARNING: historical data may be intentional
                addError(tableName, rowIndex, col.name, line,
                    "Value '${value.asText()}' for '${col.name}' is older than" +
                            " the allowed window ($duration from now)",
                    severity = DataValidationError.Severity.WARNING)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to parse [text] as either an [OffsetDateTime] (various formats)
     * or [LocalDate]. Returns a human-readable error string on failure, null on success.
     *
     * Accepts both strict ISO-8601 and the PostgreSQL space-separator format:
     *   "2023-06-01T09:00:00+00:00"  (ISO)
     *   "2023-06-01 09:00:00+00:00"  (Postgres full)
     *   "2023-06-01 09:00:00+00"     (Postgres short TZ)
     */
    private fun parseTimestamp(text: String): String? {
        for (fmt in TIMESTAMP_FORMATTERS) {
            try { OffsetDateTime.parse(text, fmt); return null }
            catch (_: DateTimeParseException) {}
        }
        try  { LocalDate.parse(text); return null }
        catch (_: DateTimeParseException) {}
        return "not a valid ISO-8601 date or timestamp"
    }

    companion object {
        private val TIMESTAMP_FORMATTERS = listOf(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX"),
        )
    }

    private fun nodeTypeName(node: JsonNode) = when (node) {
        is StringNode    -> "string"
        is IntNode,
        is LongNode    -> "integer"
        is DoubleNode,
        is FloatNode,
        is DecimalNode -> "decimal"
        is BooleanNode -> "boolean"
        is ObjectNode  -> "object"
        is ArrayNode -> "array"
        is NullNode    -> "null"
        else           -> "unknown"
    }

    private fun addError(
        table: String,
        rowIndex: Int,
        field: String?,
        line: Int?,
        message: String,
        severity: DataValidationError.Severity = DataValidationError.Severity.ERROR
    ) {
        errors.add(DataValidationError(table, rowIndex, field, line, message, severity))
    }
}
