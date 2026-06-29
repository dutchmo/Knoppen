package org.austindroids.knoppen.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// ============================================================
// Root Schema
// ============================================================

@Serializable
data class DatabaseSchema(
    val dialect: String,                            // e.g. "postgresql"
    val schema: String,                             // e.g. "code_sample"
    val rootDataPath: String? = null,               // optional base dir for data file resolution
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
    val files: List<String> = emptyList(),          // data file paths relative to rootDataPath
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
    val datatype: String,                           // Raw type string e.g. "VARCHAR(30)", "NUMERIC(8,2)"
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
    val kind: DefaultType,
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

@JsonClassDiscriminator("constraint")
@Serializable
sealed class ColumnConstraint {
    abstract val message: String?                   // Optional human-readable validation failure message
}

@Serializable
@SerialName("REQUIRED")
data class RequiredConstraint(
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("UNIQUE")
data class UniqueConstraint(
    val conflictTarget: Boolean = false,            // If true, this unique constraint drives ON CONFLICT target
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("ENUM")
data class EnumConstraint(
    val values: List<String>,                       // Allowed values — validation fails if data value not in list
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("PATTERN")
data class PatternConstraint(
    val regex: String,                              // Regex applied to string column values
    override val message: String? = null
) : ColumnConstraint()

@Serializable
@SerialName("TEMPORAL")
data class TemporalConstraint(
    val notFuture: Boolean = false,                 // Reject timestamps after validation runtime
    val notPast: String? = null,                    // ISO 8601 duration e.g. "-P4Y" = no older than 4 years
    override val message: String? = null
) : ColumnConstraint()

// ============================================================
// SqlType sealed class hierarchy (runtime-only — not serialized)
// ============================================================

sealed class SqlType {
    abstract fun toDdl(): String

    // ── Integer family ────────────────────────────────────────────────
    sealed class Integral : SqlType()
    data object SmallInt  : Integral() { override fun toDdl() = "SMALLINT" }
    data object Integer   : Integral() { override fun toDdl() = "INTEGER" }
    data object BigInt    : Integral() { override fun toDdl() = "BIGINT" }
    data object TinyInt   : Integral() { override fun toDdl() = "TINYINT" }    // MySQL
    data object MediumInt : Integral() { override fun toDdl() = "MEDIUMINT" }  // MySQL

    // ── Exact numeric (carry parameters) ─────────────────────────────
    data class Decimal(val precision: Int, val scale: Int = 0) : SqlType() {
        override fun toDdl() = "DECIMAL($precision, $scale)"
    }
    data class Numeric(val precision: Int, val scale: Int = 0) : SqlType() {
        override fun toDdl() = "NUMERIC($precision, $scale)"
    }

    // ── Floating point ────────────────────────────────────────────────
    sealed class Floating : SqlType()
    data object Real            : Floating() { override fun toDdl() = "REAL" }
    data object DoublePrecision : Floating() { override fun toDdl() = "DOUBLE PRECISION" }

    // ── String ────────────────────────────────────────────────────────
    sealed class StringType : SqlType()
    data class Char(val length: Int)    : StringType() { override fun toDdl() = "CHAR($length)" }
    data class VarChar(val length: Int) : StringType() { override fun toDdl() = "VARCHAR($length)" }
    data object Text       : StringType() { override fun toDdl() = "TEXT" }
    data object TinyText   : StringType() { override fun toDdl() = "TINYTEXT" }   // MySQL
    data object MediumText : StringType() { override fun toDdl() = "MEDIUMTEXT" } // MySQL
    data object LongText   : StringType() { override fun toDdl() = "LONGTEXT" }   // MySQL

    // ── Boolean ───────────────────────────────────────────────────────
    data object BooleanType : SqlType() { override fun toDdl() = "BOOLEAN" }

    // ── Temporal ──────────────────────────────────────────────────────
    sealed class Temporal : SqlType()
    data object Date        : Temporal() { override fun toDdl() = "DATE" }
    data object Time        : Temporal() { override fun toDdl() = "TIME" }
    data object Timestamp   : Temporal() { override fun toDdl() = "TIMESTAMP" }
    data object TimestampTz : Temporal() { override fun toDdl() = "TIMESTAMPTZ" } // PG
    data object DateTime    : Temporal() { override fun toDdl() = "DATETIME" }    // MySQL
    data object Year        : Temporal() { override fun toDdl() = "YEAR" }        // MySQL

    // ── Binary ────────────────────────────────────────────────────────
    data object ByteA : SqlType() { override fun toDdl() = "BYTEA" }
    data object Blob  : SqlType() { override fun toDdl() = "BLOB" }

    // ── JSON ──────────────────────────────────────────────────────────
    data object Json  : SqlType() { override fun toDdl() = "JSON" }
    data object JsonB : SqlType() { override fun toDdl() = "JSONB" }

    // ── PostgreSQL-specific ───────────────────────────────────────────
    sealed class PgSpecific : SqlType()
    data object Money    : PgSpecific() { override fun toDdl() = "MONEY" }
    data object Inet     : PgSpecific() { override fun toDdl() = "INET" }
    data object Cidr     : PgSpecific() { override fun toDdl() = "CIDR" }
    data object Interval : PgSpecific() { override fun toDdl() = "INTERVAL" }
    data object TimeTz   : PgSpecific() { override fun toDdl() = "TIMETZ" }

    // ── Misc ──────────────────────────────────────────────────────────
    data object Uuid    : SqlType() { override fun toDdl() = "UUID" }
    data object Unknown : SqlType() { override fun toDdl() = "TEXT" }

    companion object {
        fun parse(raw: String): SqlType {
            val base = raw.substringBefore("(").trim().uppercase()
            val params = if (raw.contains("("))
                raw.substringAfter("(").substringBefore(")")
                    .split(",").mapNotNull { it.trim().toIntOrNull() }
            else emptyList()

            return when (base) {
                "INTEGER", "INT", "INT4", "SERIAL"          -> Integer
                "SMALLINT", "INT2"                          -> SmallInt
                "BIGINT", "INT8", "BIGSERIAL"               -> BigInt
                "TINYINT"                                   -> TinyInt
                "MEDIUMINT"                                 -> MediumInt
                "DECIMAL"                                   ->
                    Decimal(params.getOrElse(0) { 10 }, params.getOrElse(1) { 0 })
                "NUMERIC"                                   ->
                    Numeric(params.getOrElse(0) { 10 }, params.getOrElse(1) { 0 })
                "REAL", "FLOAT4"                            -> Real
                "DOUBLE PRECISION", "FLOAT8", "FLOAT"       -> DoublePrecision
                "CHAR", "CHARACTER"                         ->
                    Char(params.getOrElse(0) { 1 })
                "VARCHAR", "CHARACTER VARYING"              ->
                    VarChar(params.getOrElse(0) { 255 })
                "TEXT"                                      -> Text
                "TINYTEXT"                                  -> TinyText
                "MEDIUMTEXT"                                -> MediumText
                "LONGTEXT"                                  -> LongText
                "BOOLEAN", "BOOL"                           -> BooleanType
                "DATE"                                      -> Date
                "TIME", "TIME WITHOUT TIME ZONE"            -> Time
                "TIMETZ", "TIME WITH TIME ZONE"             -> TimeTz
                "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE"  -> Timestamp
                "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE"   -> TimestampTz
                "DATETIME"                                  -> DateTime
                "YEAR"                                      -> Year
                "BYTEA"                                     -> ByteA
                "BLOB", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB" -> Blob
                "JSON"                                      -> Json
                "JSONB"                                     -> JsonB
                "UUID"                                      -> Uuid
                "MONEY"                                     -> Money
                "INET"                                      -> Inet
                "CIDR"                                      -> Cidr
                "INTERVAL"                                  -> Interval
                else                                        -> Unknown
            }
        }
    }
}
