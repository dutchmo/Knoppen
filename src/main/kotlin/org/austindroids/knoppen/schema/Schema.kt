package org.austindroids.knoppen.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================
// Root Schema
// ============================================================

@Serializable
data class DatabaseSchema(
    val dialect: String,                            // e.g. "postgresql"
    val schema: String,                             // e.g. "code_sample"
    val validation: ValidationConfig,
    val tables: List<TableSchema>
)

@Serializable
data class ValidationConfig(
    val defaultNullable: Boolean = true,            // If true, columns are nullable unless "required" constraint present
    val strictFields: Boolean = false               // If true, unknown fields in data files cause validation failure
)

// ============================================================
// Table
// ============================================================

@Serializable
data class TableSchema(
    val schemaName: String = "",                    // populated by SchemaParser from DatabaseSchema.schema
    val tableName: String,                          // Matches YAML key "tableName"
    val description: String? = null,
    val primaryKey: List<String>,                   // List of column names forming the PK
    val onConflict: OnConflictConfig? = null,
    val columns: List<ColumnSchema>
)

@Serializable
data class OnConflictConfig(
    val target: List<String>,                       // Columns used in ON CONFLICT (...) clause
    val action: OnConflictAction,                   // DO UPDATE or DO NOTHING
    val excludeFromUpdate: List<String> = emptyList() // Columns never overwritten on conflict
)

@Serializable
enum class OnConflictAction {
    @SerialName("update")    UPDATE,               // ON CONFLICT DO UPDATE SET ...
    @SerialName("doNothing") DO_NOTHING            // ON CONFLICT DO NOTHING
}

// ============================================================
// Column
// ============================================================

@Serializable
data class ColumnSchema(
    val name: String,
    val type: String,                               // Raw type string e.g. "VARCHAR(30)", "NUMERIC(8,2)"
    val default: DefaultValue? = null,              // Matches YAML key "default" (not "defaultValue")
    val foreignKey: ForeignKeyConfig? = null,
    val constraints: List<ColumnConstraint> = emptyList()
    // Note: nullable is NOT stored per-column — it is derived at runtime from
    // ValidationConfig.defaultNullable + presence of a "required" constraint
)

// ============================================================
// Default Values
// ============================================================

@Serializable
data class DefaultValue(
    val type: DefaultType,
    val value: String,                              // Always required — the literal, function name, or expression
    val args: List<String> = emptyList()            // Optional function arguments e.g. for NOW(precision)
)

@Serializable
enum class DefaultType {
    @SerialName("LITERAL")    LITERAL,             // Plain string/number literal — rendered quoted in SQL
    @SerialName("FUNCTION")   FUNCTION,            // SQL function call — rendered unquoted e.g. CURRENT_TIMESTAMP
    @SerialName("EXPRESSION") EXPRESSION,           // Arbitrary SQL expression — rendered as-is e.g. '[]'::jsonb
    @SerialName("GENERATOR")  GENERATOR     // Resolved in Kotlin per-row at generation time
}

// ============================================================
// Foreign Key
// ============================================================

@Serializable
data class ForeignKeyConfig(
    val schema: String? = null,                     // Optional — inherits top-level schema if omitted
    val table: String,
    val columns: List<String>,
    val onUpdate: ForeignKeyAction = ForeignKeyAction.NO_ACTION,
    val onDelete: ForeignKeyAction = ForeignKeyAction.NO_ACTION
)

@Serializable
enum class ForeignKeyAction {
    @SerialName("cascade")     CASCADE,
    @SerialName("setNull")     SET_NULL,
    @SerialName("setDefault")  SET_DEFAULT,
    @SerialName("restrict")    RESTRICT,
    @SerialName("noAction")    NO_ACTION           // PostgreSQL default — error on orphan delete/update
}

// ============================================================
// Column Constraints
// ============================================================
// Constraints use a sealed class hierarchy so each type carries
// only the fields relevant to it, enforced at deserialization.
// The "type" discriminator field drives which subclass is used.

@Serializable
sealed class ColumnConstraint {
    abstract val message: String?                   // Optional human-readable validation failure message
}

@Serializable
@SerialName("required")
data class RequiredConstraint(
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("unique")
data class UniqueConstraint(
    val conflictTarget: Boolean = false,            // If true, this unique constraint drives ON CONFLICT target
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("enum")
data class EnumConstraint(
    val values: List<String>,                       // Allowed values — validation fails if data value not in list
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("pattern")
data class PatternConstraint(
    val regex: String,                              // Regex applied to string column values
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("temporal")
data class TemporalConstraint(
    val notFuture: Boolean = false,                 // Reject timestamps after validation runtime
    val notPast: String? = null,                    // ISO 8601 duration e.g. "-P4Y" = no older than 4 years
    override val message: String? = null
) : ColumnConstraint()

// ============================================================
// Parsed Type Info (derived — not stored in YAML)
// ============================================================
// This is a runtime-only utility class produced by parsing
// the raw "type" string from ColumnSchema. Not serialized.

data class ColumnTypeInfo(
    val baseType: SqlType,
    val size: Int? = null,                          // e.g. 255 from VARCHAR(255)
    val precision: Int? = null,                     // e.g. 8 from NUMERIC(8,2)
    val scale: Int? = null                          // e.g. 2 from NUMERIC(8,2)
)

enum class SqlType {
    VARCHAR, INTEGER, BIGINT, NUMERIC, DECIMAL,
    DATE, TIMESTAMP, BOOLEAN, JSON, JSONB, TEXT
}
