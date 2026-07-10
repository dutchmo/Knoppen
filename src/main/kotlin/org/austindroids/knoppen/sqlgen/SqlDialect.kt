package org.austindroids.knoppen.sqlgen

import org.austindroids.knoppen.schema.SqlType
import org.austindroids.knoppen.schema.TableSchema

/**
 * Represents a single data row ready for SQL generation.
 *
 * @param tableName  Target table
 * @param fields     Ordered map of columnName → raw YAML value (as string)
 * @param schema     The [TableSchema] this row belongs to
 */
data class DataRow(
    val tableName: String,
    val fields: Map<String, Any?>,     // preserves insertion order
    val schema: TableSchema
)

/**
 * All SQL dialects implement this interface.
 * Currently only Postgres is implemented; extend for MySQL, SQLite etc.
 */
interface SqlDialect {
    /**
     * Produces one complete upsert statement for [row].
     * The statement must be self-contained and executable standalone.
     */
    fun generateUpsert(row: DataRow): String

    /**
     * Produces one upsert statement covering all of [rows] (a single `INSERT`
     * with multiple `VALUES` tuples where the dialect supports it).
     *
     * [rows] must all belong to the same table. The default implementation
     * simply concatenates one statement per row, so a dialect that hasn't
     * implemented true multi-row support yet still behaves correctly.
     */
    fun generateMultiRowUpsert(rows: List<DataRow>): String =
        rows.joinToString("\n") { generateUpsert(it) }

    /**
     * Formats a Kotlin/YAML value for embedding in a SQL literal.
     * Implementations must handle quoting, escaping, and type casting.
     */
    fun formatValue(value: Any?, sqlType: SqlType): String
}
