package org.austindroids.knoppen.validation.rules

import tools.jackson.databind.JsonNode

/**
 * Validates business rules that cannot be expressed in JSON Schema alone:
 *
 *  - primaryKey columns must exist in the columns list
 *  - onConflict.target columns must exist in the columns list
 *  - onConflict.excludeFromUpdate columns must exist in the columns list
 *  - foreignKey.table must reference a table defined in this schema file
 *  - temporal notPast must be a valid ISO 8601 negative duration
 *  - enum constraint values list must not contain duplicates
 *  - pattern constraint regex must compile without errors
 *  - FUNCTION defaults must have a non-blank value
 *  - conflictTarget:true is only meaningful on a "unique" constraint
 */
class SemanticValidator {

    fun validate(context: RuleContext) {
        val tables = context.root.path("tables")
        if (!tables.isArray) return  // Structural validator will catch this

        // Build a set of known table names for FK cross-reference checks
        val knownTables = tables.mapNotNull { it.path("tableName").asText(null) }.toSet()

        tables.forEachIndexed { tableIdx, table ->
            val tablePath = "/tables/$tableIdx"
            val tableName = table.path("tableName").asText("(unknown)")

            // Collect column names for reference checks
            val columnNames = table.path("columns")
                .mapNotNull { it.path("name").asText(null) }
                .toSet()

            validatePrimaryKey(context, table, tablePath, tableName, columnNames)
            validateOnConflict(context, table, tablePath, tableName, columnNames)

            table.path("columns").forEachIndexed { colIdx, column ->
                val colPath = "$tablePath/columns/$colIdx"
                val colName = column.path("name").asText("(unknown)")

                validateForeignKey(context, column, colPath, colName, knownTables)
                validateConstraints(context, column, colPath, colName)
                validateDefault(context, column, colPath, colName)
            }
        }
    }

    // ── Primary Key ───────────────────────────────────────────────────────────

    private fun validatePrimaryKey(
        context: RuleContext,
        table: JsonNode,
        tablePath: String,
        tableName: String,
        columnNames: Set<String>
    ) {
        table.path("primaryKey").forEachIndexed { idx, pkCol ->
            val colName = pkCol.asText()
            if (colName !in columnNames) {
                context.error(
                    "$tablePath/primaryKey/$idx",
                    "Table '$tableName': primaryKey references column '$colName'" +
                            " which is not defined in columns"
                )
            }
        }
    }

    // ── On Conflict ───────────────────────────────────────────────────────────

    private fun validateOnConflict(
        context: RuleContext,
        table: JsonNode,
        tablePath: String,
        tableName: String,
        columnNames: Set<String>
    ) {
        val onConflict = table.path("onConflict")
        if (onConflict.isMissingNode) return

        val conflictPath = "$tablePath/onConflict"

        // target columns must exist
        onConflict.path("target").forEachIndexed { idx, col ->
            val colName = col.asText()
            if (colName !in columnNames) {
                context.error(
                    "$conflictPath/target/$idx",
                    "Table '$tableName': onConflict.target references column '$colName'" +
                            " which is not defined in columns"
                )
            }
        }

        // excludeFromUpdate columns must exist
        onConflict.path("excludeFromUpdate").forEachIndexed { idx, col ->
            val colName = col.asText()
            if (colName !in columnNames) {
                context.error(
                    "$conflictPath/excludeFromUpdate/$idx",
                    "Table '$tableName': onConflict.excludeFromUpdate references column '$colName'" +
                            " which is not defined in columns"
                )
            }
        }

        // Warn if action is "update" but excludeFromUpdate is empty —
        // almost certainly the PK and create timestamps should be excluded
        val action = onConflict.path("action").asText()
        val excludeCount = onConflict.path("excludeFromUpdate").size()
        if (action == "update" && excludeCount == 0) {
            context.warning(
                "$conflictPath/excludeFromUpdate",
                "Table '$tableName': onConflict.action is 'update' but excludeFromUpdate is empty." +
                        " Consider excluding PK and audit timestamp columns."
            )
        }
    }

    // ── Foreign Key ───────────────────────────────────────────────────────────

    private fun validateForeignKey(
        context: RuleContext,
        column: JsonNode,
        colPath: String,
        colName: String,
        knownTables: Set<String>
    ) {
        val fk = column.path("foreignKey")
        if (fk.isMissingNode) return

        val fkTable = fk.path("table").asText(null) ?: return  // structural validator catches missing

        if (fkTable !in knownTables) {
            // This is a warning, not an error — the target table may live in
            // a different schema file or be a pre-existing table
            context.warning(
                "$colPath/foreignKey/table",
                "Column '$colName': foreignKey references table '$fkTable'" +
                        " which is not defined in this schema file." +
                        " If it is defined elsewhere this may be intentional."
            )
        }
    }

    // ── Constraints ───────────────────────────────────────────────────────────

    private fun validateConstraints(
        context: RuleContext,
        column: JsonNode,
        colPath: String,
        colName: String
    ) {
        column.path("constraints").forEachIndexed { idx, constraint ->
            val constraintPath = "$colPath/constraints/$idx"
            when (val type = constraint.path("type").asText()) {

                "pattern" -> validatePatternConstraint(context, constraint, constraintPath, colName)
                "enum"    -> validateEnumConstraint(context, constraint, constraintPath, colName)
                "temporal" -> validateTemporalConstraint(context, constraint, constraintPath, colName)

                "unique" -> {
                    val conflictTarget = constraint.path("conflictTarget").asBoolean(false)
                    if (conflictTarget) {
                        // Verify the column actually has a "unique" constraint type — redundant
                        // here since we ARE on a unique constraint, but guards copy-paste errors
                    }
                }

                "required" -> { /* no extra fields to validate */ }

                else -> context.error(
                    "$constraintPath/type",
                    "Column '$colName': unknown constraint type '$type'"
                )
            }
        }

        // Cross-constraint: conflictTarget on non-unique constraint
        column.path("constraints").forEachIndexed { idx, constraint ->
            val isUnique = constraint.path("type").asText() == "unique"
            val hasConflictTarget = constraint.path("conflictTarget").asBoolean(false)
            if (hasConflictTarget && !isUnique) {
                context.error(
                    "$colPath/constraints/$idx/conflictTarget",
                    "Column '$colName': conflictTarget: true is only valid on a 'unique' constraint," +
                            " found on '${constraint.path("type").asText()}'"
                )
            }
        }
    }

    private fun validatePatternConstraint(
        context: RuleContext,
        constraint: JsonNode,
        path: String,
        colName: String
    ) {
        val regex = constraint.path("regex").asText(null) ?: return
        try {
            Regex(regex)
        } catch (e: Exception) {
            context.error(
                "$path/regex",
                "Column '$colName': pattern constraint regex '$regex' does not compile: ${e.message}"
            )
        }
    }

    private fun validateEnumConstraint(
        context: RuleContext,
        constraint: JsonNode,
        path: String,
        colName: String
    ) {
        val values = constraint.path("values")
        if (!values.isArray || values.size() == 0) return  // structural validator catches this

        val seen = mutableSetOf<String>()
        values.forEachIndexed { idx, v ->
            val text = v.asText()
            if (!seen.add(text)) {
                context.error(
                    "$path/values/$idx",
                    "Column '$colName': enum constraint contains duplicate value '$text'"
                )
            }
        }
    }

    private fun validateTemporalConstraint(
        context: RuleContext,
        constraint: JsonNode,
        path: String,
        colName: String
    ) {
        val notPast = constraint.path("notPast").asText(null) ?: return

        // Must match ISO 8601 negative duration e.g. -P4Y, -P1Y6M, -P30D
        val iso8601NegativeDuration = Regex("^-P(?:\\d+Y)?(?:\\d+M)?(?:\\d+D)?(?:T(?:\\d+H)?(?:\\d+M)?(?:\\d+S)?)?$")
        if (!iso8601NegativeDuration.matches(notPast)) {
            context.error(
                "$path/notPast",
                "Column '$colName': temporal constraint notPast '$notPast' is not a valid" +
                        " ISO 8601 negative duration. Expected format: -PnYnMnD e.g. -P4Y, -P1Y6M"
            )
        }

        // Warn if the duration is zero-length (all components absent)
        if (notPast == "-P") {
            context.warning(
                "$path/notPast",
                "Column '$colName': temporal constraint notPast '-P' has no duration components — " +
                        "this effectively means no past values are allowed"
            )
        }
    }

    // ── Default Values ────────────────────────────────────────────────────────

    private fun validateDefault(
        context: RuleContext,
        column: JsonNode,
        colPath: String,
        colName: String
    ) {
        val default = column.path("default")
        if (default.isMissingNode) return

        val type = default.path("type").asText(null) ?: return  // structural catches missing
        val value = default.path("value").asText(null)

        if (value.isNullOrBlank()) {
            context.error(
                "$colPath/default/value",
                "Column '$colName': default value is blank. All default types require a non-empty value."
            )
            return
        }

        when (type) {
            "FUNCTION" -> {
                // Function names should be uppercase alphanumeric with optional underscores
                val validFunctionName = Regex("^[A-Z][A-Z0-9_]*$")
                if (!validFunctionName.matches(value)) {
                    context.warning(
                        "$colPath/default/value",
                        "Column '$colName': FUNCTION default '$value' does not look like a" +
                                " standard SQL function name. Verify this is intentional."
                    )
                }
            }
            "LITERAL" -> { /* any non-blank string is valid */ }
            "EXPRESSION" -> { /* any non-blank string is valid — rendered as-is */ }
        }
    }
}
