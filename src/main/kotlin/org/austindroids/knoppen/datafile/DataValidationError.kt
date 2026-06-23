package org.austindroids.knoppen.datafile

/**
 * A validation failure found in a data file row.
 *
 * @param table     Table name the row belongs to
 * @param rowIndex  0-based index of the row within the table block
 * @param field     Field name that failed (null = row-level error)
 * @param line      Best-effort source line number from the YAML data file
 * @param message   Human-readable description of the failure
 * @param severity  ERROR blocks upsert generation; WARNING is advisory
 */
data class DataValidationError(
    val table: String,
    val rowIndex: Int,
    val field: String?,
    val line: Int?,
    val message: String,
    val severity: Severity = Severity.ERROR
) {
    /** Alias for [field] — provided for readability in test assertions. */
    val column: String? get() = this.field

    enum class Severity { ERROR, WARNING }

    override fun toString(): String {
        val loc     = if (line != null) "Line $line" else "Line unknown"
        val rowDesc = "Table '$table' row[$rowIndex]"
        val colDesc = if (field != null) " field '$field'" else ""
        return "[$severity] $loc | $rowDesc$colDesc — $message"
    }
}
