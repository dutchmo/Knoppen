package org.austindroids.knoppen.sqlgen.dialect

import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.DefaultType
import org.austindroids.knoppen.schema.DefaultValue
import org.austindroids.knoppen.schema.OnConflictAction
import org.austindroids.knoppen.schema.OnConflictConfig
import org.austindroids.knoppen.schema.OnConflictMerge
import org.austindroids.knoppen.schema.SqlType
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.sqlgen.DataRow
import org.austindroids.knoppen.sqlgen.SqlDialect
import org.austindroids.knoppen.sqlgen.format.AtomicClause
import org.austindroids.knoppen.sqlgen.format.Clause
import org.austindroids.knoppen.sqlgen.format.FormatConfig
import org.austindroids.knoppen.sqlgen.format.ItemizedClause
import org.austindroids.knoppen.sqlgen.format.RowValuesClause
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
 *  - The conflict target is `ON CONFLICT (col, ...)` when [OnConflictConfig.target] is
 *    declared, or `ON CONFLICT ON CONSTRAINT "name"` when [OnConflictConfig.constraint]
 *    is declared instead — see [conflictTargetClause].
 *  - Each column's DO UPDATE SET fragment is driven by its [ColumnSchema.onConflict]
 *    strategy: OVERWRITE (`col = EXCLUDED.col`), PRESERVE (omitted from SET entirely),
 *    COALESCE (`col = COALESCE(EXCLUDED.col, table.col)`), or COMPUTED (re-renders the
 *    column's `default`, ignoring EXCLUDED). Primary key columns are always omitted
 *    from SET regardless of their strategy.
 *  - Column defaults are applied when the data row omits a column entirely.
 *  - Columns whose default is [DefaultType.AUTO] are omitted entirely — from the
 *    INSERT column list, the VALUES list, and (as a consequence, since both are
 *    driven from the same resolved column list) DO UPDATE SET. The database is
 *    solely responsible for populating them (identity, DEFAULT, or trigger).
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
        val schema        = row.schema
        val insertColumns = resolveInsertColumns(schema, row)

        require(insertColumns.isNotEmpty()) {
            "Row for table '${schema.tableName}' has no insertable columns"
        }

        val columns = insertColumns.map { qq(it.name) }
        val values  = formatRowValues(row, insertColumns)

        val clauses = mutableListOf<Clause>(
            ItemizedClause("INSERT INTO ${tableRef(schema)}", columns, parens = true),
            ItemizedClause("VALUES", values, parens = true)
        )
        clauses += buildConflictClauses(schema, insertColumns)
        return formatter.format(clauses)
    }

    override fun generateMultiRowUpsert(rows: List<DataRow>): String {
        require(rows.isNotEmpty()) { "generateMultiRowUpsert requires at least one row" }
        val schema = rows.first().schema
        require(rows.all { it.schema.tableName == schema.tableName }) {
            "generateMultiRowUpsert requires all rows to belong to the same table " +
                "(got: ${rows.map { it.schema.tableName }.distinct()})"
        }
        val insertColumns = resolveInsertColumns(schema, rows.first())

        require(insertColumns.isNotEmpty()) {
            "Rows for table '${schema.tableName}' have no insertable columns"
        }
        // Defensive: callers (UpsertGenerator) are expected to guarantee every row in a
        // batch shares the same insertable columns before calling this. A single INSERT
        // statement can only declare one column list, so a mismatch here is a caller bug.
        rows.forEach { row ->
            val rowColumns = resolveInsertColumns(schema, row)
            require(rowColumns == insertColumns) {
                "Rows in the same multi-row batch for table '${schema.tableName}' must share the same " +
                    "insertable columns; expected ${insertColumns.map { it.name }} but a row has " +
                    "${rowColumns.map { it.name }}"
            }
        }

        val columns   = insertColumns.map { qq(it.name) }
        val rowTuples = rows.map { row -> formatRowValues(row, insertColumns) }

        val clauses = mutableListOf<Clause>(
            ItemizedClause("INSERT INTO ${tableRef(schema)}", columns, parens = true),
            RowValuesClause("VALUES", rowTuples)
        )
        clauses += buildConflictClauses(schema, insertColumns)
        return formatter.format(clauses)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared row/clause helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Determines which columns to INSERT for [row]: columns that have a value in
     * the row OR have a default defined. Columns with neither are omitted (they
     * will get DB-level DEFAULT/NULL). GENERATOR columns are always resolved
     * before this point and will be present in [DataRow.fields] — they must
     * never fall through to [renderDefault]. AUTO columns are unconditionally
     * excluded — even if [row] happens to carry a value for one (data-file
     * validation is expected to have already rejected that as an error before
     * SQL generation runs; this filter is a defensive backstop, not the
     * primary enforcement point).
     */
    private fun resolveInsertColumns(schema: TableSchema, row: DataRow): List<ColumnSchema> =
        schema.columns.filter { col ->
            col.default?.kind != DefaultType.AUTO &&
                (row.fields.containsKey(col.name) || (col.default != null && col.default.kind != DefaultType.GENERATOR))
        }

    /** Formats [row]'s values for each of [insertColumns], in order. */
    private fun formatRowValues(row: DataRow, insertColumns: List<ColumnSchema>): List<String> =
        insertColumns.map { col ->
            val sqlType  = SqlType.parse(col.datatype)
            val rawValue = row.fields[col.name]
            if (rawValue == null && !row.fields.containsKey(col.name)) {
                // Column absent from row — use schema default
                renderDefault(col.default!!, sqlType)
            } else {
                formatValue(rawValue, sqlType)
            }
        }

    /** Schema-qualified table reference (schema-qualified when schemaName is set). */
    private fun tableRef(schema: TableSchema): String =
        if (schema.schemaName.isBlank()) qq(schema.tableName)
        else "${schema.schemaName}.${schema.tableName}"

    /**
     * Builds the `ON CONFLICT` / `DO NOTHING` / `DO UPDATE SET` clauses. These are
     * entirely schema-driven (never per-row), so the same clauses apply whether
     * generating one statement per row or one statement per batch of rows.
     */
    private fun buildConflictClauses(
        schema: TableSchema,
        insertColumns: List<ColumnSchema>
    ): List<Clause> {
        val clauses = mutableListOf<Clause>()
        val onConflict = schema.onConflict

        // If no onConflict defined, fall back to DO NOTHING on PK conflict
        if (onConflict == null) {
            val pkList = schema.primaryKey.joinToString(", ") { qq(it) }
            clauses += AtomicClause("ON CONFLICT ($pkList)")
            clauses += AtomicClause("DO NOTHING")
            return clauses
        }

        clauses += AtomicClause(conflictTargetClause(schema, onConflict))

        when (onConflict.action) {
            OnConflictAction.DO_NOTHING ->
                clauses += AtomicClause("DO NOTHING")

            OnConflictAction.UPDATE -> {
                // The PK is always the identity of the row and is never re-assigned on
                // conflict, regardless of a column's declared onConflict strategy.
                val pkSet = schema.primaryKey.toSet()
                val updateCols = insertColumns.filter { it.name !in pkSet && it.onConflict != OnConflictMerge.PRESERVE }

                clauses += if (updateCols.isEmpty()) {
                    // Nothing to update — degrade gracefully to DO NOTHING
                    AtomicClause("DO NOTHING")
                } else {
                    val setClauses = updateCols.map { col -> renderSetClause(schema, col) }
                    ItemizedClause("DO UPDATE SET", setClauses)
                }
            }
        }

        return clauses
    }

    /**
     * Renders the conflict target portion of the `ON CONFLICT` clause: either
     * `ON CONFLICT ON CONSTRAINT "name"` when [OnConflictConfig.constraint] is set,
     * or `ON CONFLICT (col, ...)` when [OnConflictConfig.target] is set. `constraint`
     * takes precedence if both are somehow set (schema meta-validation rejects that
     * combination via `oneOf` before this is ever reached in practice).
     */
    private fun conflictTargetClause(schema: TableSchema, onConflict: OnConflictConfig): String =
        when {
            onConflict.constraint != null -> "ON CONFLICT ON CONSTRAINT ${qq(onConflict.constraint)}"
            onConflict.target != null     -> "ON CONFLICT (${onConflict.target.joinToString(", ")})"
            else -> throw IllegalStateException(
                "Table '${schema.tableName}': onConflict must declare either 'target' or 'constraint'"
            )
        }

    /** Renders one column's `DO UPDATE SET` fragment per its [ColumnSchema.onConflict] strategy. */
    private fun renderSetClause(schema: TableSchema, col: ColumnSchema): String {
        val name = qq(col.name)
        return when (col.onConflict) {
            OnConflictMerge.OVERWRITE -> "$name = EXCLUDED.$name"
            OnConflictMerge.COALESCE  -> "$name = COALESCE(EXCLUDED.$name, ${conflictRowRef(schema)}.$name)"
            OnConflictMerge.COMPUTED  -> "$name = ${renderDefault(col.default!!, SqlType.parse(col.datatype))}"
            OnConflictMerge.PRESERVE  -> throw IllegalStateException(
                "Column '${col.name}' with onConflict: PRESERVE reached renderSetClause() — " +
                        "PRESERVE columns must be filtered out of updateCols before this point."
            )
        }
    }

    /**
     * References the pre-conflict row inside a `DO UPDATE SET` expression (e.g. for
     * [OnConflictMerge.COALESCE]). Postgres implicitly exposes the target table under
     * its own bare (unqualified) name unless an `INSERT INTO t AS alias` is used — the
     * schema-qualified form used by [tableRef] for the `INSERT INTO` clause is not
     * valid here.
     */
    private fun conflictRowRef(schema: TableSchema): String = qq(schema.tableName)

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
     *  - [DefaultType.AUTO] should NEVER reach here either — AUTO columns are
     *    excluded from [resolveInsertColumns] entirely, so [formatRowValues]
     *    never calls this for one. It is only reached via [renderSetClause]'s
     *    `COMPUTED` branch, and `COMPUTED` on an AUTO column is unreachable by
     *    construction (AUTO columns never appear in `updateCols`).
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
            DefaultType.AUTO -> throw IllegalStateException(
                "AUTO default for column '${sqlType.toDdl()}' reached renderDefault() — AUTO columns " +
                        "must be entirely excluded from the INSERT column/value lists and can never be " +
                        "the target of an onConflict: COMPUTED strategy."
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
