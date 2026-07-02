package org.austindroids.knoppen

import org.austindroids.knoppen.datafile.DataFileLoaderFactory
import org.austindroids.knoppen.datafile.DataFileValidator
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.datafile.TextValidator
import org.austindroids.knoppen.schema.DatabaseSchema
import org.slf4j.LoggerFactory

/**
 * Wraps the result of data file validation.
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
 * Utility facade for validating a single data file against a schema table.
 *
 * @param schema    The parsed [DatabaseSchema]
 * @param tableName The table whose schema the data rows are validated against
 * @param content   Raw data file content
 * @param extension File extension used to select the correct loader (default: "yaml")
 */
object DataValidator {
    private val log = LoggerFactory.getLogger(DataValidator::class.java)

    fun validate(
        schema:    DatabaseSchema,
        tableName: String,
        content:   String,
        extension: String = "yaml"
    ): DataValidationResult {
        log.debug("Validating data file for table '{}' (format={})", tableName, extension)
        val charViolations = TextValidator().validate(content)
        if (charViolations.isNotEmpty()) {
            return DataValidationResult(charViolations.map { v ->
                DataValidationError(
                    table    = tableName,
                    rowIndex = -1,
                    field    = null,
                    line     = v.line,
                    message  = "Illegal character at line ${v.line}, col ${v.col}: ${v.codePoint} — ${v.name}"
                )
            })
        }
        val loader     = DataFileLoaderFactory.forExtension(extension)
        val loadResult = loader.load(content, tableName)
        val errors     = DataFileValidator(schema, tableName, loadResult.rows, loadResult.lineIndex).validate()
        return DataValidationResult(errors)
    }
}
