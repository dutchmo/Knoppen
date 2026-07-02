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
     * Formats a Kotlin/YAML value for embedding in a SQL literal.
     * Implementations must handle quoting, escaping, and type casting.
     */
    fun formatValue(value: Any?, sqlType: SqlType): String
}
