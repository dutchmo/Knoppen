package org.austindroids.knoppen.validation

/**
 * Represents a single validation failure.
 *
 * @param path      Dot-separated path to the failing field  e.g. "tables[0].columns[2].datatype"
 * @param message   Human-readable description of the failure
 * @param line      Best-effort line number from the YAML source (null if not traceable)
 * @param severity  ERROR will abort upsert generation; WARNING is advisory only
 */
data class ValidationError(
    val path: String,
    val message: String,
    val line: Int? = null,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity { ERROR, WARNING }

    override fun toString(): String {
        val location = if (line != null) "Line $line" else "Line unknown"
        return "[$severity] $location | $path — $message"
    }
}
