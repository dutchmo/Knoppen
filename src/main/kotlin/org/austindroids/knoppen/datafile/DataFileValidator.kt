package org.austindroids.knoppen.datafile

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.schema.EnumConstraint
import org.austindroids.knoppen.schema.PatternConstraint
import org.austindroids.knoppen.schema.RequiredConstraint
import org.austindroids.knoppen.schema.SqlType
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.schema.TemporalConstraint
import org.austindroids.knoppen.schema.UniqueConstraint
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.DoubleNode
import tools.jackson.databind.node.FloatNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

/**
 * Validates every row in a single table's loaded data against its [TableSchema].
 *
 * Checks performed per row:
 *  1. Unknown fields — WARNING (escalated to ERROR when [org.austindroids.knoppen.schema.ValidationConfig.strictFields] is true)
 *  2. Required fields present and non-null — ERROR
 *  3. Type compatibility — ERROR
 *  4. Per-column constraints (enum, pattern, temporal, unique) — ERROR or WARNING
 *
 * In-memory uniqueness is tracked across all supplied rows for columns with
 * [UniqueConstraint]. Database-level uniqueness (existing rows) is not checked here.
 */
class DataFileValidator(
    private val dbSchema: DatabaseSchema,
    private val tableName: String,
    private val rows: List<JsonNode>,
    private val lineIndex: DataFileLoader.LineIndex
) {
    private val errors   = mutableListOf<DataValidationError>()
    private val config   = dbSchema.validation

    // columnName → set of already-seen values (for in-file uniqueness)
    private val uniqueSets = mutableMapOf<String, MutableSet<String>>()

    fun validate(): List<DataValidationError> {
        errors.clear()
        uniqueSets.clear()

        val tableSchema = dbSchema.tables.find { it.tableName == tableName }
        if (tableSchema == null) {
            addError(
                rowIndex = -1,
                field    = null,
                line     = null,
                message  = "Data file references table '$tableName' which is not defined in the schema"
            )
            return errors.toList()
        }

        rows.forEachIndexed { rowIndex, row ->
            validateRow(tableSchema, rowIndex, row)
        }

        return errors.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row-level
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateRow(schema: TableSchema, rowIndex: Int, row: JsonNode) {
        val definedColumns = schema.columns.map { it.name }.toSet()
        val rowFields      = row.properties().map { it.key }.toSet()

        // ── 1. Unknown fields ─────────────────────────────────────────────────
        // Always at least WARNING; escalated to ERROR when strictFields is true.
        (rowFields - definedColumns).forEach { unknown ->
            val severity = if (config.strictFields)
                DataValidationError.Severity.ERROR
            else
                DataValidationError.Severity.WARNING

            addError(
                rowIndex = rowIndex,
                field    = unknown,
                line     = lineIndex.lineFor(rowIndex, unknown),
                message  = "Unknown field '$unknown' — not declared in schema for table '$tableName'" +
                        if (config.strictFields) " (strictFields is enabled)" else "",
                severity = severity
            )
        }

        // ── 2–4. Per-column validation ────────────────────────────────────────
        schema.columns.forEach { colSchema ->
            val value = row.get(colSchema.name)
            validateColumn(schema, rowIndex, colSchema, value)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Column-level
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateColumn(
        tableSchema: TableSchema,
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode?
    ) {
        val line = lineIndex.lineFor(rowIndex, col.name)

        val isRequired = col.constraints.any { it is RequiredConstraint }

        if (value == null || value.isNull) {
            if (isRequired) {
                addError(rowIndex, col.name, line,
                    "Field '${col.name}' is required but is missing or null")
            }
            return
        }

        validateType(rowIndex, col, value, line)

        col.constraints.forEach { constraint ->
            when (constraint) {
                is RequiredConstraint -> { /* handled above */ }
                is UniqueConstraint   -> validateUnique(rowIndex, col, value, constraint, line)
                is EnumConstraint     -> validateEnum(rowIndex, col, value, constraint, line)
                is PatternConstraint  -> validatePattern(rowIndex, col, value, constraint, line)
                is TemporalConstraint -> validateTemporal(rowIndex, col, value, constraint, line)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type compatibility
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateType(rowIndex: Int, col: ColumnSchema, value: JsonNode, line: Int?) {

        val sqlType = SqlType.parse(col.datatype)
        val typeMismatch: String? = when (sqlType) {
            is SqlType.Integral ->
                if (!value.isNumber || value.isFloatingPointNumber)
                    "Expected integer, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.Decimal, is SqlType.Numeric ->
                if (!value.isNumber)
                    "Expected numeric, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.Floating ->
                if (!value.isNumber)
                    "Expected number, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.StringType ->
                if (!value.isString)
                    "Expected text, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.BooleanType ->
                if (!value.isBoolean)
                    "Expected boolean, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.Json, is SqlType.JsonB ->
                if (!value.isObject && !value.isArray && !value.isString)
                    "Expected JSON object, array, or text, got ${nodeTypeName(value)}"
                else null

            is SqlType.Temporal -> when (sqlType) {
                is SqlType.Timestamp, is SqlType.TimestampTz, is SqlType.DateTime ->
                    if (!value.isString)
                        "Expected timestamp string, got ${nodeTypeName(value)}: '${value.asString()}'"
                    else
                        parseTimestamp(value.asString())
                            ?.let { "Cannot parse '${value.asString()}' as ${sqlType.toDdl()}: $it" }

                is SqlType.Date, is SqlType.Time, is SqlType.Year ->
                    if (!value.isString)
                        "Expected string, got ${nodeTypeName(value)}: '${value.asString()}'"
                    else null
            }

            is SqlType.ByteA, is SqlType.Blob ->
                if (!value.isString)
                    "Expected binary string, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.Uuid ->
                if (!value.isString)
                    "Expected UUID string, got ${nodeTypeName(value)}: '${value.asString()}'"
                else null

            is SqlType.PgSpecific -> when (sqlType) {
                is SqlType.Money ->
                    if (!value.isString && !value.isNumber)
                        "Expected string or number, got ${nodeTypeName(value)}: '${value.asString()}'"
                    else null

                is SqlType.Inet, is SqlType.Cidr, is SqlType.Interval, is SqlType.TimeTz ->
                    if (!value.isString)
                        "Expected string, got ${nodeTypeName(value)}: '${value.asString()}'"
                    else null
            }

            is SqlType.Unknown -> null
        }

        if (typeMismatch != null) {
            addError(rowIndex, col.name, line,
                "Type mismatch for column '${col.name}' (declared ${col.datatype}): $typeMismatch")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constraint validators
    // ─────────────────────────────────────────────────────────────────────────

    private fun validateUnique(
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: UniqueConstraint,
        line: Int?
    ) {
        val key  = value.asString()
        val seen = uniqueSets.getOrPut(col.name) { mutableSetOf() }

        if (!seen.add(key)) {
            if (constraint.conflictTarget) return
            addError(rowIndex, col.name, line,
                "Unique constraint violated for '${col.name}': value '$key'" +
                        " appears more than once in this data file")
        }
    }

    private fun validateEnum(
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: EnumConstraint,
        line: Int?
    ) {
        val text = value.asString()
        if (text !in constraint.values) {
            val allowed  = constraint.values.joinToString(", ") { "'$it'" }
            val message  = constraint.message
                ?: "Value '$text' is not a valid enum value for '${col.name}'. Allowed: [$allowed]"
            addError(rowIndex, col.name, line, message)
        }
    }

    private fun validatePattern(
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: PatternConstraint,
        line: Int?
    ) {
        if (!value.isString) return
        val text  = value.asString()
        val regex = try { Regex(constraint.regex) } catch (_: Exception) { return }
        if (!regex.containsMatchIn(text)) {
            val message = constraint.message
                ?: "Value '$text' for '${col.name}' does not match required pattern: ${constraint.regex}"
            addError(rowIndex, col.name, line, message)
        }
    }

    private fun validateTemporal(
        rowIndex: Int,
        col: ColumnSchema,
        value: JsonNode,
        constraint: TemporalConstraint,
        line: Int?
    ) {
        if (!value.isString) return

        val instant: Instant = run {
            var result: Instant? = null
            for (fmt in TIMESTAMP_FORMATTERS) {
                try { result = OffsetDateTime.parse(value.asString(), fmt).toInstant(); break }
                catch (_: DateTimeParseException) {}
            }
            result ?: return
        }

        val now = Instant.now()

        if (constraint.notFuture && instant.isAfter(now)) {
            addError(rowIndex, col.name, line,
                "Value '${value.asString()}' for '${col.name}' is in the future," +
                        " but notFuture constraint is set")
        }

        constraint.notPast?.let { duration ->
            val positive = duration.removePrefix("-")
            val boundary = try {
                val period = java.time.Period.parse(positive)
                now.atOffset(java.time.ZoneOffset.UTC).minus(period).toInstant()
            } catch (_: Exception) { return }

            if (instant.isBefore(boundary)) {
                addError(rowIndex, col.name, line,
                    "Value '${value.asString()}' for '${col.name}' is older than" +
                            " the allowed window ($duration from now)",
                    severity = DataValidationError.Severity.WARNING)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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
        is StringNode  -> "string"
        is IntNode,
        is LongNode    -> "integer"
        is DoubleNode,
        is FloatNode,
        is DecimalNode -> "decimal"
        is BooleanNode -> "boolean"
        is ObjectNode  -> "object"
        is ArrayNode   -> "array"
        is NullNode    -> "null"
        else           -> "unknown"
    }

    private fun addError(
        rowIndex: Int,
        field: String?,
        line: Int?,
        message: String,
        severity: DataValidationError.Severity = DataValidationError.Severity.ERROR
    ) {
        errors.add(DataValidationError(tableName, rowIndex, field, line, message, severity))
    }
}
