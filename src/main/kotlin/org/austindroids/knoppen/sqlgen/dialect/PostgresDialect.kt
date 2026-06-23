package org.austindroids.knoppen.sqlgen.dialect

import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.DefaultType
import org.austindroids.knoppen.schema.DefaultValue
import org.austindroids.knoppen.schema.OnConflictAction
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.sqlgen.DataRow
import org.austindroids.knoppen.sqlgen.SqlDialect
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates PostgreSQL-flavoured `INSERT ... ON CONFLICT DO UPDATE` statements.
 *
 * Design decisions:
 *  - All string values are single-quote escaped (doubled single quotes).
 *  - JSONB columns are cast with `::jsonb`.
 *  - TIMESTAMP columns are cast with `::timestamp`.
 *  - NULL is rendered as the SQL keyword NULL (no quotes).
 *  - Column and table names are always double-quoted to handle reserved words
 *    and mixed-case identifiers safely.
 *  - The schema qualifier is prepended when present on the TableSchema.
 *  - Columns in onConflict.excludeFromUpdate are omitted from the DO UPDATE SET clause.
 *  - Column defaults are applied when the data row omits a column entirely.
 */
class PostgresDialect : SqlDialect {

    override fun generateUpsert(row: DataRow): String {
        val schema      = row.schema
        val onConflict  = schema.onConflict
        val excludeSet  = onConflict?.excludeFromUpdate?.toSet() ?: emptySet()

        // ── Determine which columns to INSERT ─────────────────────────────────
        // Include columns that have a value in the row OR have a default defined.
        // Columns with neither are omitted (they will get DB-level DEFAULT/NULL).
        // GENERATOR columns are always resolved before this point and will be
        // present in row.fields — they must never fall through to renderDefault().
        val insertColumns = schema.columns.filter { col ->
            row.fields.containsKey(col.name) || (col.default != null && col.default.type != DefaultType.GENERATOR)
        }

        if (insertColumns.isEmpty()) {
            throw IllegalArgumentException(
                "Row for table '${schema.tableName}' has no insertable columns"
            )
        }

        // ── Build column list and value list ──────────────────────────────────
        val columnList = insertColumns.joinToString(",\n    ") { qq(it.name) }

        val valueList  = insertColumns.joinToString(",\n    ") { col ->
            val rawValue = row.fields[col.name]
            if (rawValue == null && !row.fields.containsKey(col.name)) {
                // Column absent from row — use schema default
                renderDefault(col.default!!, col.type)
            } else {
                formatValue(rawValue, col.type)
            }
        }

        // ── ON CONFLICT clause ────────────────────────────────────────────────
        val conflictClause = buildConflictClause(schema, insertColumns, excludeSet)

        // ── Table reference (schema-qualified when schemaName is set) ────────
        val tableRef = if (schema.schemaName.isBlank()) qq(schema.tableName)
                       else "${schema.schemaName}.${schema.tableName}"

        return buildString {
            appendLine("INSERT INTO $tableRef (")
            appendLine("    $columnList")
            appendLine(")")
            appendLine("VALUES (")
            appendLine("    $valueList")
            appendLine(")")
            append(conflictClause)
            append(";")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conflict clause builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildConflictClause(
        schema: TableSchema,
        insertColumns: List<ColumnSchema>,
        excludeSet: Set<String>
    ): String {
        val onConflict = schema.onConflict

        // If no onConflict defined, fall back to DO NOTHING on PK conflict
        if (onConflict == null) {
            val pkList = schema.primaryKey.joinToString(", ") { qq(it) }
            return "ON CONFLICT ($pkList) DO NOTHING\n"
        }

        val targetList = onConflict.target.joinToString(", ")

        return when (onConflict.action) {
            OnConflictAction.DO_NOTHING ->
                "ON CONFLICT ($targetList) DO NOTHING\n"

            OnConflictAction.UPDATE -> {
                // Update all inserted columns except excluded ones and the PK
                val pkSet = schema.primaryKey.toSet()
                val updateCols = insertColumns
                    .map { it.name }
                    .filter { it !in excludeSet && it !in pkSet }

                if (updateCols.isEmpty()) {
                    // Nothing to update — degrade gracefully to DO NOTHING
                    "ON CONFLICT ($targetList) DO NOTHING\n"
                } else {
                    val setClauses = updateCols.joinToString(",\n        ") { col ->
                        "${qq(col)} = EXCLUDED.${qq(col)}"
                    }
                    buildString {
                        appendLine("ON CONFLICT ($targetList)")
                        appendLine("DO UPDATE SET")
                        append("    $setClauses")
                        appendLine()
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value formatting
    // ─────────────────────────────────────────────────────────────────────────

    override fun formatValue(value: Any?, columnType: String): String {
        if (value == null) return "NULL"

        val baseType = columnType.uppercase().substringBefore("(").trim()

        return when (baseType) {
            "INTEGER", "INT", "BIGINT" -> {
                val n = value.toString().trim()
                requireNotNull(n.toLongOrNull()) {
                    "Cannot format '$n' as $baseType"
                }
                n
            }

            "NUMERIC", "DECIMAL" -> {
                val n = value.toString().trim()
                requireNotNull(n.toBigDecimalOrNull()) {
                    "Cannot format '$n' as $baseType"
                }
                n
            }

            "BOOLEAN" ->
                when (value.toString().lowercase()) {
                    "true",  "yes", "1" -> "TRUE"
                    "false", "no",  "0" -> "FALSE"
                    else -> throw IllegalArgumentException(
                        "Cannot format '${value}' as BOOLEAN"
                    )
                }

            "TEXT", "VARCHAR" ->
                // Escape single quotes by doubling them
                "'" + value.toString().replace("'", "''") + "'"

            "JSONB" ->
                // value may be a Map/List (from YAML) or already a JSON string
                "'" + toJsonString(value).replace("'", "''") + "'::jsonb"

            "JSON" ->
                "'" + toJsonString(value).replace("'", "''") + "'::json"

            "TIMESTAMP" ->
                // Normalize to ISO-8601 and cast
                "'" + normalizeTimestamp(value.toString()) + "'::timestamp"

            "DATE" ->
                "'" + value.toString().trim() + "'::date"

            else ->
                // Unknown type — render as a quoted string and let Postgres decide
                "'" + value.toString().replace("'", "''") + "'"
        }
    }

    /**
     * Renders a schema-level default as a SQL fragment for embedding directly
     * into an INSERT VALUES clause.
     *
     * Called only for columns that are absent from [DataRow.fields] at SQL
     * generation time. This means:
     *
     *  - [DefaultType.GENERATOR] should NEVER reach here — generator values
     *    are resolved in Kotlin by [UpsertGenerator] and injected into
     *    [DataRow.fields] before [generateUpsert] is called. If one does
     *    reach here it means the generator was not wired up correctly.
     *
     *  - [DefaultType.FUNCTION]   → rendered unquoted, e.g. CURRENT_TIMESTAMP
     *  - [DefaultType.EXPRESSION] → rendered as-is,    e.g. '[]'::jsonb
     *  - [DefaultType.LITERAL]    → rendered quoted,   e.g. 'active'
     */
    private fun renderDefault(default: DefaultValue, columnType: String): String =
        when (default.type) {
            DefaultType.FUNCTION -> {
                val args = default.args.joinToString(", ")
                if (args.isBlank()) default.value else "${default.value}($args)"
            }
            DefaultType.EXPRESSION -> default.value
            DefaultType.LITERAL    -> formatValue(default.value, columnType)
            DefaultType.GENERATOR  -> throw IllegalStateException(
                "GENERATOR default for column type '$columnType' reached renderDefault() " +
                        "— generator values must be resolved by UpsertGenerator before " +
                        "generateUpsert() is called. Check that the column is included in " +
                        "the generators map and that GeneratorParser.parse() was invoked for it."
            )
        }


    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Double-quotes a SQL identifier to handle reserved words and case. */
    private fun qq(identifier: String) = "\"$identifier\""

    /** Serializes a Kotlin Map/List/scalar to a JSON string. */
    private fun toJsonString(value: Any?): String {
        val mapper = ObjectMapper()
        return mapper.writeValueAsString(value)
    }

    /** Normalises a timestamp string to the format Postgres accepts. */
    private fun normalizeTimestamp(raw: String): String =
        try {
            OffsetDateTime.parse(raw)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"))
        } catch (e: Exception) {
            raw  // pass through and let Postgres validate
        }
}
