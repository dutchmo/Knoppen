package org.austindroids.knoppen

import org.austindroids.knoppen.datafile.DataFileLoader
import org.austindroids.knoppen.datafile.DataFileValidator
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.schema.DatabaseSchema
import java.io.InputStream

/**
 * Wraps the result of data file validation.
 *
 * [errors] contains both ERROR- and WARNING-severity items.
 * [hasErrors] is true only when at least one ERROR is present.
 */
data class DataValidationResult(
    val errors: List<DataValidationError>
) {
    val hasErrors: Boolean
        get() = errors.any { it.severity == DataValidationError.Severity.ERROR }

    fun prettyPrint(): String = buildString {
        if (errors.isEmpty()) {
            appendLine("✅ Data validation passed with no issues.")
            return@buildString
        }
        val (errs, warns) = errors.partition { it.severity == DataValidationError.Severity.ERROR }
        if (errs.isNotEmpty()) {
            appendLine("❌ ${errs.size} error(s):")
            errs.forEach { appendLine("   $it") }
        }
        if (warns.isNotEmpty()) {
            appendLine("⚠️  ${warns.size} warning(s):")
            warns.forEach { appendLine("   $it") }
        }
    }
}

/**
 * Stream-oriented facade over [DataFileValidator].
 *
 * Accepts a parsed [DatabaseSchema] and a raw data YAML stream, and returns
 * a [DataValidationResult] for use in tests and the CLI pipeline.
 */
object DataValidator {
    fun validate(schema: DatabaseSchema, dataStream: InputStream): DataValidationResult {
        val yamlContent = dataStream.bufferedReader().readText()
        val loadResult  = DataFileLoader().load(yamlContent)
        val errors      = DataFileValidator(schema, loadResult).validate()
        return DataValidationResult(errors)
    }
}
