package org.austindroids.knoppen.datafile

import org.austindroids.knoppen.schema.DatabaseSchema
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * Cross-table foreign key integrity validator.
 *
 * Runs **after** all tables have been loaded and their rows merged.
 * For every non-null FK column value it checks that a matching parent row exists
 * in [allData]. A missing parent is reported as an ERROR.
 *
 * Only single-column FK references are checked (the first entry in
 * [ForeignKeyConfig.columns] is the referenced parent column).
 * Null/missing FK values are silently skipped — nullable FK columns are allowed.
 */
class ForeignKeyValidator(
    private val dbSchema: DatabaseSchema,
    private val allData: Map<String, List<JsonNode>>
) {
    companion object {
        private val log = LoggerFactory.getLogger(ForeignKeyValidator::class.java)
    }

    fun validate(): List<DataValidationError> {
        val errors = mutableListOf<DataValidationError>()

        for (tableSchema in dbSchema.tables) {
            val rows = allData[tableSchema.tableName] ?: continue

            for ((rowIndex, row) in rows.withIndex()) {
                for (col in tableSchema.columns) {
                    val fk = col.foreignKey ?: continue

                    val value = row.get(col.name)
                    if (value == null || value.isNull) continue  // nullable FK — skip

                    val parentTableName = fk.table
                    val parentRows = allData[parentTableName]
                    if (parentRows == null) {
                        // Parent table not present in the loaded data set (may be external).
                        // Emit a warning rather than blocking generation.
                        errors.add(DataValidationError(
                            table    = tableSchema.tableName,
                            rowIndex = rowIndex,
                            field    = col.name,
                            line     = null,
                            message  = "Cannot validate FK '${col.name}': parent table '$parentTableName'" +
                                    " has no data files declared in the schema",
                            severity = DataValidationError.Severity.WARNING
                        ))
                        continue
                    }

                    val parentColName = fk.columns.firstOrNull() ?: continue
                    val valueStr = value.asString()

                    val parentExists = parentRows.any { parentRow ->
                        parentRow.get(parentColName)?.asString() == valueStr
                    }

                    if (!parentExists) {
                        errors.add(DataValidationError(
                            table    = tableSchema.tableName,
                            rowIndex = rowIndex,
                            field    = col.name,
                            line     = null,
                            message  = "FK violation: '${col.name}' = '$valueStr' does not match any" +
                                    " '$parentColName' in table '$parentTableName'"
                        ))
                    }
                }
            }
        }

        log.debug("Checked FK integrity across {} table(s): {} violation(s)", dbSchema.tables.size, errors.size)
        return errors
    }
}
