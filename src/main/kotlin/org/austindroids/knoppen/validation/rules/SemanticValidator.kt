package org.austindroids.knoppen.validation.rules

import tools.jackson.databind.JsonNode

/**
 * Validates business rules that cannot be expressed in JSON Schema alone:
 *
 *  - primaryKey columns must exist in the columns list
 *  - onConflict.target columns must exist in the columns list
 *  - onConflict.constraint + action: update + a non-default batchSize has no batching
 *    benefit, since Knoppen can't introspect the constraint's columns (warning)
 *  - a column's onConflict: COMPUTED requires a non-GENERATOR default
 *  - a column's onConflict strategy on a primaryKey column is a no-op (warning)
 *  - a column's onConflict strategy is a no-op when the table's onConflict.action is doNothing (warning)
 *  - a column's onConflict strategy is a no-op on an AUTO-defaulted column (warning)
 *  - foreignKey.table must reference a table defined in this schema file
 *  - temporal notPast must be a valid ISO 8601 negative duration
 *  - enum constraint values list must not contain duplicates
 *  - pattern constraint regex must compile without errors
 *  - FUNCTION defaults must have a non-blank value
 *  - conflictTarget:true is only meaningful on a "unique" constraint
 *  - GROUPED_SEQUENCE's groupByColumn must exist in this table's columns
 *  - FOREIGN_CYCLE's table and column must both be declared in this schema file
 */
class SemanticValidator {

    fun validate(context: RuleContext) {
        val tables = context.root.path("tables")
        if (!tables.isArray) return  // Structural validator will catch this

        // Build a set of known table names for FK cross-reference checks
        val knownTables = tables.mapNotNull { it.path("tableName").asString(null) }.toSet()

        tables.forEachIndexed { tableIdx, table ->
            val tablePath = "/tables/$tableIdx"
            val tableName = table.path("tableName").asString("(unknown)")

            // Collect column names for reference checks
            val columnNames = table.path("columns")
                .mapNotNull { it.path("name").asString(null) }
                .toSet()
            val primaryKeyColumns = table.path("primaryKey")
                .mapNotNull { it.asString(null) }
                .toSet()
            val tableOnConflictAction = table.path("onConflict").path("action").asString(null)

            validatePrimaryKey(context, table, tablePath, tableName, columnNames)
            validateOnConflict(context, table, tablePath, tableName, columnNames)

            table.path("columns").forEachIndexed { colIdx, column ->
                val colPath = "$tablePath/columns/$colIdx"
                val colName = column.path("name").asString("(unknown)")

                validateForeignKey(context, column, colPath, colName, knownTables)
                validateConstraints(context, column, colPath, colName)
                validateDefault(context, column, colPath, colName, columnNames, tables)
                validateColumnOnConflict(context, column, colPath, colName, primaryKeyColumns, tableOnConflictAction)
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
            val colName = pkCol.asString()
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
            val colName = col.asString()
            if (colName !in columnNames) {
                context.error(
                    "$conflictPath/target/$idx",
                    "Table '$tableName': onConflict.target references column '$colName'" +
                            " which is not defined in columns"
                )
            }
        }

        // constraint-based targeting has no column list for UpsertGenerator to key on
        // when splitting multi-row batches on duplicate conflict values (see chunkRows()
        // in UpsertGenerator.kt) — batching silently degrades to one-statement-per-row
        val usesConstraint = !onConflict.path("constraint").isMissingNode
        val action = onConflict.path("action").asString(null)
        val batchSize = table.path("batchSize").asInt(1)
        if (usesConstraint && action == "update" && batchSize != 1) {
            context.warning(
                "$conflictPath/constraint",
                "Table '$tableName': onConflict.constraint with action 'update' and batchSize" +
                        " $batchSize has no batching benefit — Knoppen cannot determine which columns" +
                        " the named constraint covers, so every row is forced into its own statement" +
                        " regardless of batchSize. Use onConflict.target instead if you need a real" +
                        " conflict-key check for batching."
            )
        }
    }

    // ── Column-level onConflict merge strategy ──────────────────────────────────

    private fun validateColumnOnConflict(
        context: RuleContext,
        column: JsonNode,
        colPath: String,
        colName: String,
        primaryKeyColumns: Set<String>,
        tableOnConflictAction: String?
    ) {
        val strategy = column.path("onConflict").asString(null) ?: return  // default OVERWRITE — nothing to check
        val onConflictPath = "$colPath/onConflict"
        val defaultKind = column.path("default").path("kind").asString(null)

        if (strategy == "COMPUTED") {
            val default = column.path("default")
            if (default.isMissingNode) {
                context.error(
                    onConflictPath,
                    "Column '$colName': onConflict: COMPUTED requires a 'default' to render on conflict"
                )
            } else if (defaultKind == "GENERATOR") {
                context.error(
                    onConflictPath,
                    "Column '$colName': onConflict: COMPUTED cannot be combined with a GENERATOR default" +
                            " — GENERATOR values are resolved per-row before SQL generation, not re-rendered on conflict"
                )
            }
        }

        if (strategy != "OVERWRITE" && colName in primaryKeyColumns) {
            context.warning(
                onConflictPath,
                "Column '$colName': onConflict: $strategy has no effect — primary key columns are never" +
                        " included in DO UPDATE SET"
            )
        }

        if (strategy != "OVERWRITE" && tableOnConflictAction == "doNothing") {
            context.warning(
                onConflictPath,
                "Column '$colName': onConflict: $strategy has no effect — table's onConflict.action is" +
                        " 'doNothing', so no DO UPDATE SET clause is ever generated"
            )
        }

        if (strategy != "OVERWRITE" && defaultKind == "AUTO") {
            context.warning(
                onConflictPath,
                "Column '$colName': onConflict: $strategy has no effect — AUTO-defaulted columns are" +
                        " always omitted from both INSERT and DO UPDATE SET"
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

        val fkTable = fk.path("table").asString(null) ?: return  // structural validator catches missing

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
            when (val type = constraint.path("constraint").asString()) {

                "PATTERN"  -> validatePatternConstraint(context, constraint, constraintPath, colName)
                "ENUM"     -> validateEnumConstraint(context, constraint, constraintPath, colName)
                "TEMPORAL" -> validateTemporalConstraint(context, constraint, constraintPath, colName)

                "UNIQUE" -> {
                    val conflictTarget = constraint.path("conflictTarget").asBoolean(false)
                    if (conflictTarget) {
                        // Verify the column actually has a "UNIQUE" constraint — redundant
                        // here since we ARE on a unique constraint, but guards copy-paste errors
                    }
                }

                "REQUIRED" -> { /* no extra fields to validate */ }

                else -> context.error(
                    "$constraintPath/constraint",
                    "Column '$colName': unknown constraint '$type'"
                )
            }
        }

        // Cross-constraint: conflictTarget on non-unique constraint
        column.path("constraints").forEachIndexed { idx, constraint ->
            val isUnique = constraint.path("constraint").asString() == "UNIQUE"
            val hasConflictTarget = constraint.path("conflictTarget").asBoolean(false)
            if (hasConflictTarget && !isUnique) {
                context.error(
                    "$colPath/constraints/$idx/conflictTarget",
                    "Column '$colName': conflictTarget: true is only valid on a 'UNIQUE' constraint," +
                            " found on '${constraint.path("constraint").asString()}'"
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
        val regex = constraint.path("regex").asString(null) ?: return
        try {
            val _ = Regex(regex)
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
            val text = v.asString()
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
        val notPast = constraint.path("notPast").asString(null) ?: return

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
        colName: String,
        columnNames: Set<String>,
        tables: JsonNode
    ) {
        val default = column.path("default")
        if (default.isMissingNode) return

        val type = default.path("kind").asString(null) ?: return  // structural catches missing
        if (type == "AUTO") return  // AUTO carries no value/args to validate

        val value = default.path("value").asString(null)

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
            "GENERATOR" -> validateGenerator(context, value, colPath, colName, columnNames, tables)
        }
    }

    // ── Generator Expressions ────────────────────────────────────────────────

    /**
     * Cross-references the arguments of GENERATOR default expressions against
     * the schema — these can't be checked by JSON Schema since they're embedded
     * in a free-form string ([value]).
     */
    private fun validateGenerator(
        context: RuleContext,
        value: String,
        colPath: String,
        colName: String,
        columnNames: Set<String>,
        tables: JsonNode
    ) {
        val trimmed = value.trim()
        val name    = trimmed.substringBefore("(").uppercase().trim()
        val argStr  = trimmed.substringAfter("(", "").substringBeforeLast(")", "").trim()
        val args    = if (argStr.isEmpty()) emptyList() else argStr.split(",").map { it.trim() }

        when (name) {
            "GROUPED_SEQUENCE" -> validateGroupedSequence(context, args, colPath, colName, columnNames)
            "FOREIGN_CYCLE"    -> validateForeignCycle(context, args, colPath, colName, tables)
        }
    }

    private fun validateGroupedSequence(
        context: RuleContext,
        args: List<String>,
        colPath: String,
        colName: String,
        columnNames: Set<String>
    ) {
        val groupByColumn = args.getOrNull(0) ?: return  // arg-count errors surface at generation time
        if (groupByColumn !in columnNames) {
            context.error(
                "$colPath/default/value",
                "Column '$colName': GROUPED_SEQUENCE references column '$groupByColumn'" +
                        " which is not declared in this table's columns"
            )
        }
    }

    private fun validateForeignCycle(
        context: RuleContext,
        args: List<String>,
        colPath: String,
        colName: String,
        tables: JsonNode
    ) {
        val targetTable = args.getOrNull(0) ?: return  // arg-count errors surface at generation time
        val targetTableNode = tables.find { it.path("tableName").asString(null) == targetTable }
        if (targetTableNode == null) {
            context.error(
                "$colPath/default/value",
                "Column '$colName': FOREIGN_CYCLE references table '$targetTable'" +
                        " which is not declared in this schema file"
            )
            return
        }

        val targetColumn = args.getOrNull(1) ?: return
        val targetColumnNames = targetTableNode.path("columns")
            .mapNotNull { it.path("name").asString(null) }
            .toSet()
        if (targetColumn !in targetColumnNames) {
            context.error(
                "$colPath/default/value",
                "Column '$colName': FOREIGN_CYCLE references column '$targetColumn'" +
                        " which is not declared in table '$targetTable'"
            )
        }
    }
}
