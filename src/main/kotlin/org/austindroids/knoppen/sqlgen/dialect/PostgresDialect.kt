package org.austindroids.knoppen.sqlgen.dialect

import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.DefaultType
import org.austindroids.knoppen.schema.DefaultValue
import org.austindroids.knoppen.schema.OnConflictAction
import org.austindroids.knoppen.schema.SqlType
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.sqlgen.DataRow
import org.austindroids.knoppen.sqlgen.SqlDialect
import org.austindroids.knoppen.sqlgen.format.AtomicClause
import org.austindroids.knoppen.sqlgen.format.Clause
import org.austindroids.knoppen.sqlgen.format.FormatConfig
import org.austindroids.knoppen.sqlgen.format.ItemizedClause
import org.austindroids.knoppen.sqlgen.format.SqlFormatter
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
 *
 * Layout (line breaks, indentation, comma placement) is delegated to
 * [SqlFormatter] via [config]; this class only builds the semantic
 * [Clause] list and formats the individual SQL values/identifiers.
 * The ON CONFLICT target list is always rendered as a single [AtomicClause]
 * (never expanded to one-column-per-line) since it is typically short and
 * expanding it independently of the config's newline style would be
 * surprising.
 */
class PostgresDialect(
    private val config: FormatConfig = FormatConfig.LEGACY
) : SqlDialect {

    private val formatter = SqlFormatter(config)

    override fun generateUpsert(row: DataRow): String {
        val schema     = row.schema
        val excludeSet = schema.onConflict?.excludeFromUpdate?.toSet() ?: emptySet()

        // ── Determine which columns to INSERT ─────────────────────────────────
        // Include columns that have a value in the row OR have a default defined.
        // Columns with neither are omitted (they will get DB-level DEFAULT/NULL).
        // GENERATOR columns are always resolved before this point and will be
        // present in row.fields — they must never fall through to renderDefault().
        val insertColumns = schema.columns.filter { col ->
            row.fields.containsKey(col.name) || (col.default != null && col.default.kind != DefaultType.GENERATOR)
        }

        require(insertColumns.isNotEmpty()) {
            "Row for table '${schema.tableName}' has no insertable columns"
        }

        val columns = insertColumns.map { qq(it.name) }
        val values  = insertColumns.map { col ->
            val sqlType  = SqlType.parse(col.datatype)
            val rawValue = row.fields[col.name]
            if (rawValue == null && !row.fields.containsKey(col.name)) {
                // Column absent from row — use schema default
                renderDefault(col.default!!, sqlType)
            } else {
                formatValue(rawValue, sqlType)
            }
        }

        // ── Table reference (schema-qualified when schemaName is set) ────────
        val tableRef = if (schema.schemaName.isBlank()) qq(schema.tableName)
                       else "${schema.schemaName}.${schema.tableName}"

        val clauses = buildUpsertClauses(schema, tableRef, columns, values, insertColumns, excludeSet)
        return formatter.format(clauses)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clause builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUpsertClauses(
        schema: TableSchema,
        tableRef: String,
        columns: List<String>,
        values: List<String>,
        insertColumns: List<ColumnSchema>,
        excludeSet: Set<String>
    ): List<Clause> {
        val base = mutableListOf<Clause>(
            ItemizedClause("INSERT INTO $tableRef", columns, parens = true),
            ItemizedClause("VALUES", values, parens = true)
        )

        val onConflict = schema.onConflict

        // If no onConflict defined, fall back to DO NOTHING on PK conflict
        if (onConflict == null) {
            val pkList = schema.primaryKey.joinToString(", ") { qq(it) }
            base += AtomicClause("ON CONFLICT ($pkList)")
            base += AtomicClause("DO NOTHING")
            return base
        }

        val targetList = onConflict.target.joinToString(", ")
        base += AtomicClause("ON CONFLICT ($targetList)")

        when (onConflict.action) {
            OnConflictAction.DO_NOTHING ->
                base += AtomicClause("DO NOTHING")

            OnConflictAction.UPDATE -> {
                // Update all inserted columns except excluded ones and the PK
                val pkSet = schema.primaryKey.toSet()
                val updateCols = insertColumns
                    .map { it.name }
                    .filter { it !in excludeSet && it !in pkSet }

                base += if (updateCols.isEmpty()) {
                    // Nothing to update — degrade gracefully to DO NOTHING
                    AtomicClause("DO NOTHING")
                } else {
                    val setClauses = updateCols.map { col -> "${qq(col)} = EXCLUDED.${qq(col)}" }
                    ItemizedClause("DO UPDATE SET", setClauses)
                }
            }
        }

        return base
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value formatting
    // ─────────────────────────────────────────────────────────────────────────

    override fun formatValue(value: Any?, sqlType: SqlType): String {
        if (value == null) return "NULL"

        return when (sqlType) {
            is SqlType.Integral -> {
                val n = value.toString().trim()
                requireNotNull(n.toLongOrNull()) { "Cannot format '$n' as ${sqlType.toDdl()}" }
                n
            }

            is SqlType.Decimal, is SqlType.Numeric -> {
                val n = value.toString().trim()
                requireNotNull(n.toBigDecimalOrNull()) { "Cannot format '$n' as ${sqlType.toDdl()}" }
                n
            }

            is SqlType.Floating ->
                value.toString().trim()

            is SqlType.BooleanType ->
                when (value.toString().lowercase()) {
                    "true",  "yes", "1" -> "TRUE"
                    "false", "no",  "0" -> "FALSE"
                    else -> throw IllegalArgumentException("Cannot format '$value' as BOOLEAN")
                }

            is SqlType.StringType ->
                "'" + value.toString().replace("'", "''") + "'"

            is SqlType.JsonB ->
                "'" + toJsonString(value).replace("'", "''") + "'::jsonb"

            is SqlType.Json ->
                "'" + toJsonString(value).replace("'", "''") + "'::json"

            is SqlType.Temporal -> when (sqlType) {
                is SqlType.Timestamp, is SqlType.DateTime ->
                    "'" + normalizeTimestamp(value.toString()) + "'::timestamp"
                is SqlType.TimestampTz ->
                    "'" + normalizeTimestamp(value.toString()) + "'::timestamptz"
                is SqlType.Date ->
                    "'" + value.toString().trim() + "'::date"
                is SqlType.Time ->
                    "'" + value.toString().trim() + "'::time"
                is SqlType.Year ->
                    value.toString().trim()
            }

            is SqlType.ByteA ->
                "'" + value.toString().replace("'", "''") + "'"

            is SqlType.Blob ->
                "'" + value.toString().replace("'", "''") + "'"

            is SqlType.Uuid ->
                "'" + value.toString().replace("'", "''") + "'"

            is SqlType.PgSpecific -> when (sqlType) {
                is SqlType.Money    -> "'" + value.toString().replace("'", "''") + "'::money"
                is SqlType.Inet     -> "'" + value.toString().replace("'", "''") + "'::inet"
                is SqlType.Cidr     -> "'" + value.toString().replace("'", "''") + "'::cidr"
                is SqlType.Interval -> "'" + value.toString().replace("'", "''") + "'::interval"
                is SqlType.TimeTz   -> "'" + value.toString().trim() + "'::timetz"
            }

            is SqlType.Unknown ->
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
    private fun renderDefault(default: DefaultValue, sqlType: SqlType): String =
        when (default.kind) {
            DefaultType.FUNCTION -> {
                val args = default.args.joinToString(", ")
                if (args.isBlank()) default.value else "${default.value}($args)"
            }
            DefaultType.EXPRESSION -> default.value
            DefaultType.LITERAL    -> formatValue(default.value, sqlType)
            DefaultType.GENERATOR  -> throw IllegalStateException(
                "GENERATOR default for column '${sqlType.toDdl()}' reached renderDefault() " +
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
