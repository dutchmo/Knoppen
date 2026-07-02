package org.austindroids.knoppen.sqlgen

import org.austindroids.knoppen.ResourceConstants
import org.austindroids.knoppen.datafile.DataFileLoader
import org.austindroids.knoppen.datafile.DataFileLoaderFactory
import org.austindroids.knoppen.datafile.DataFileValidator
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.datafile.ForeignKeyValidator
import org.austindroids.knoppen.datafile.TextValidator
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.schema.DefaultType
import org.austindroids.knoppen.schema.TableSchema
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * A [ColumnGenerator] produces one value per call to [next].
 * Generators are stateful and tied to one column in one generation run.
 */
interface ColumnGenerator {
    fun next(rowIndex: Int): Any?
    fun reset()
}

/**
 * Orchestrates the full schema-driven pipeline:
 *
 *   Topological sort → Load data files → Validate → Generate SQL
 *
 * Entry point: [generateAll] reads each table's declared data files from the
 * filesystem, merges multi-file tables, runs structural and FK validation, then
 * produces SQL statements in dependency order.
 *
 * Returns a [GenerationResult] containing any errors and, when there are no
 * hard errors, the generated SQL statements.
 */
class UpsertGenerator(
    private val dbSchema: DatabaseSchema,
    private val dialect: SqlDialect
) {
    companion object {
        private val log = LoggerFactory.getLogger(UpsertGenerator::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result types
    // ─────────────────────────────────────────────────────────────────────────

    data class FileStats(
        val filePath: String,
        val tableName: String,
        val rowCount: Int
    )

    data class GenerationResult(
        val errors: List<DataValidationError>,
        val sql: List<NamedStatement>,
        val fileStats: List<FileStats> = emptyList(),
        val outputFiles: List<OutputFileGroup> = emptyList()
    ) {
        data class NamedStatement(
            val table: String,
            val rowIndex: Int,
            val sql: String
        )

        /**
         * One resolved SQL output file and the statements it contains. Normally one
         * table per file, but tables that declare the same `outputFile` are merged
         * into a single [OutputFileGroup].
         */
        data class OutputFileGroup(
            val path: Path,
            val tables: List<String>,
            val sql: String
        )

        val hasErrors: Boolean get() = errors.any {
            it.severity == DataValidationError.Severity.ERROR
        }

        fun prettyPrint(): String = buildString {
            if (errors.isNotEmpty()) {
                val (errs, warns) = errors.partition {
                    it.severity == DataValidationError.Severity.ERROR
                }
                if (errs.isNotEmpty()) {
                    appendLine("❌ ${errs.size} validation error(s):")
                    errs.forEach { appendLine("   $it") }
                }
                if (warns.isNotEmpty()) {
                    appendLine("⚠️  ${warns.size} warning(s):")
                    warns.forEach { appendLine("   $it") }
                }
            }
            if (sql.isNotEmpty()) {
                appendLine()
                appendLine(render(sql))
            }
        }

        fun toSqlString(): String = render(sql)

        companion object {
            /** Renders a comment header plus every statement — shared by whole-result and per-output-file rendering. */
            fun render(statements: List<NamedStatement>): String = buildString {
                appendLine(header(statements))
                statements.forEach { stmt ->
                    appendLine()
                    appendLine("-- Table: ${stmt.table}, row[${stmt.rowIndex}]")
                    appendLine(stmt.sql)
                }
            }

            private fun header(statements: List<NamedStatement>): String {
                val now       = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val user      = System.getProperty("user.name", "unknown")
                val appName   = ResourceConstants.APP_NAME
                val version   = ResourceConstants.APP_VERSION
                val byCounts  = statements.groupBy { it.table }
                    .entries
                    .joinToString("\n") { (table, stmts) ->
                        "--   %-20s %d statement(s)".format("$table:", stmts.size)
                    }
                return """
                    |-- ============================================================
                    |-- Generated by $appName version $version
                    |-- User:      $user
                    |-- Generated: $now
                    |-- ------------------------------------------------------------
                    |-- Tables:
                    |$byCounts
                    |-- ============================================================
                """.trimMargin()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema-driven pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the full pipeline driven by the schema's `dataFiles:` declarations.
     *
     * @param schemaPath            Path to the schema file; its parent directory is the base for
     *                              resolving [DatabaseSchema.rootDataPath]/[DatabaseSchema.rootOutputPath]
     *                              and relative file entries.
     * @param rootDataPathOverride   When non-null, replaces the schema's `rootDataPath` entirely.
     * @param rootOutputPathOverride When non-null, replaces the schema's `rootOutputPath` entirely.
     * @param generateSql           When false, skips SQL generation (validate-only mode).
     */
    fun generateAll(
        schemaPath: Path,
        rootDataPathOverride: Path? = null,
        rootOutputPathOverride: Path? = null,
        generateSql: Boolean = true
    ): GenerationResult {
        log.debug("Starting generation pipeline for schema={} generateSql={}", schemaPath, generateSql)
        val schemaDir = schemaPath.parent
        val cwd       = Path.of(System.getProperty("user.dir"))

        // CLI override > schema-declared value (relative to the schema file) > current working directory
        val baseDataDir = when {
            rootDataPathOverride != null  -> rootDataPathOverride
            dbSchema.rootDataPath != null -> schemaDir.resolve(dbSchema.rootDataPath).normalize()
            else                          -> cwd
        }
        val baseOutputDir = when {
            rootOutputPathOverride != null  -> rootOutputPathOverride
            dbSchema.rootOutputPath != null -> schemaDir.resolve(dbSchema.rootOutputPath).normalize()
            else                             -> cwd
        }

        // ── 0. rootDataPath / rootOutputPath must exist and be writable ────────
        val allErrors = mutableListOf<DataValidationError>()
        allErrors.addAll(validateRootPath(baseDataDir, "rootDataPath"))
        allErrors.addAll(validateRootPath(baseOutputDir, "rootOutputPath"))

        // ── 1. Topological sort ────────────────────────────────────────────────
        val sortedTables: List<TableSchema> = try {
            TableDependencyResolver(dbSchema).sort()
        } catch (e: TableDependencyResolver.CyclicDependencyException) {
            allErrors.add(DataValidationError(
                table    = "",
                rowIndex = -1,
                field    = null,
                line     = null,
                message  = e.message ?: "Cycle detected in table dependencies"
            ))
            return GenerationResult(errors = allErrors, sql = emptyList())
        }
        log.debug("Resolved {} table(s) in dependency order: {}", sortedTables.size, sortedTables.map { it.tableName })

        val allRows      = mutableMapOf<String, List<JsonNode>>()
        val allFileStats = mutableListOf<FileStats>()

        // ── 2. Load, merge, and validate each table ────────────────────────────
        for (tableSchema in sortedTables) {
            val (tableRows, tableErrors, fileStats) = loadAndValidateTable(tableSchema, baseDataDir)
            log.debug(
                "Loaded table '{}': {} row(s), {} error(s)",
                tableSchema.tableName, tableRows.size, tableErrors.size
            )
            allErrors.addAll(tableErrors)
            allRows[tableSchema.tableName] = tableRows
            allFileStats.addAll(fileStats)
        }

        // ── 3. Cross-table FK integrity ────────────────────────────────────────
        val fkErrors = ForeignKeyValidator(dbSchema, allRows).validate()
        log.debug("Foreign key validation complete: {} issue(s)", fkErrors.size)
        allErrors.addAll(fkErrors)

        // ── 4. Generate SQL (only when no hard errors and generateSql=true) ────
        val statements = if (!generateSql || allErrors.any { it.severity == DataValidationError.Severity.ERROR }) {
            emptyList()
        } else {
            buildAllStatements(sortedTables, allRows)
        }

        // ── 5. Group statements into per-table output files ────────────────────
        val outputFiles = if (statements.isEmpty()) {
            emptyList()
        } else {
            buildOutputFileGroups(sortedTables, statements, baseOutputDir)
        }

        log.debug("Generation complete: {} statement(s), {} error(s)", statements.size, allErrors.size)
        return GenerationResult(allErrors, statements, allFileStats, outputFiles)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads all declared files for [tableSchema], merges their rows, detects
     * duplicate primary keys across files, and runs structural validation.
     *
     * Returns a triple of (merged rows, validation errors, per-file stats).
     */
    private fun loadAndValidateTable(
        tableSchema: TableSchema,
        baseDataDir: Path
    ): Triple<List<JsonNode>, List<DataValidationError>, List<FileStats>> {

        if (tableSchema.dataFiles.isEmpty()) {
            log.debug("Table '{}' has no dataFiles declared — skipping", tableSchema.tableName)
            return Triple(emptyList(), emptyList(), emptyList())
        }

        val errors    = mutableListOf<DataValidationError>()
        val tableRows = mutableListOf<JsonNode>()
        val fileStats = mutableListOf<FileStats>()
        // Tracks PKs seen in previously-processed files — intra-file duplicates are
        // intentional (ON CONFLICT test rows) and must not trigger this check.
        val pkValuesFromPreviousFiles = mutableSetOf<String>()
        var mergedLineIndex = DataFileLoader.LineIndex.EMPTY

        for (filePath in tableSchema.dataFiles) {
            val fullPath = baseDataDir.resolve(filePath).normalize()
            if (!Files.exists(fullPath)) {
                errors.add(DataValidationError(
                    table    = tableSchema.tableName,
                    rowIndex = -1,
                    field    = null,
                    line     = null,
                    message  = "Data file '$filePath' not found (resolved to $fullPath)"
                ))
                continue
            }
            val content  = fullPath.readText()
            val ext      = fullPath.extension

            // Character validation — catches smart quotes and other non-ASCII before parsing
            val charViolations = TextValidator().validate(content)
            if (charViolations.isNotEmpty()) {
                charViolations.forEach { v ->
                    errors.add(DataValidationError(
                        table    = tableSchema.tableName,
                        rowIndex = -1,
                        field    = null,
                        line     = v.line,
                        message  = "Illegal character in '$filePath' at line ${v.line}, col ${v.col}: ${v.codePoint} — ${v.name}"
                    ))
                }
                continue
            }

            val loader     = DataFileLoaderFactory.forExtension(ext)
            val loadResult = loader.load(content, tableSchema.tableName)
            val rowOffset  = tableRows.size

            // ── Structural validation per file ─────────────────────────────
            val fileErrors = DataFileValidator(
                dbSchema  = dbSchema,
                tableName = tableSchema.tableName,
                rows      = loadResult.rows,
                lineIndex = loadResult.lineIndex
            ).validate()

            // Re-offset row indices to merged position
            errors.addAll(fileErrors.map { err ->
                if (err.rowIndex >= 0) err.copy(rowIndex = err.rowIndex + rowOffset) else err
            })

            // ── Duplicate PK detection across files ────────────────────────
            val thisFilePkKeys = mutableSetOf<String>()
            for ((localIdx, row) in loadResult.rows.withIndex()) {
                val globalIdx = localIdx + rowOffset
                val pkKey = tableSchema.primaryKey.joinToString(",") { colName ->
                    row.get(colName)?.asString() ?: "null"
                }
                if (pkKey in pkValuesFromPreviousFiles) {
                    errors.add(DataValidationError(
                        table    = tableSchema.tableName,
                        rowIndex = globalIdx,
                        field    = null,
                        line     = null,
                        message  = "Duplicate primary key {$pkKey} found across data files" +
                                " for table '${tableSchema.tableName}'"
                    ))
                }
                thisFilePkKeys.add(pkKey)
                tableRows.add(row)
            }
            pkValuesFromPreviousFiles.addAll(thisFilePkKeys)

            fileStats.add(FileStats(filePath, tableSchema.tableName, loadResult.rows.size))
            mergedLineIndex = DataFileLoader.LineIndex.merge(mergedLineIndex, loadResult.lineIndex, rowOffset)
        }

        return Triple(tableRows, errors, fileStats)
    }

    /** Verifies a root path (data or output) exists, is a directory, and is writable. */
    private fun validateRootPath(path: Path, label: String): List<DataValidationError> {
        val problem = when {
            !Files.exists(path)      -> "does not exist"
            !Files.isDirectory(path) -> "is not a directory"
            !Files.isWritable(path)  -> "is not writable"
            else                      -> null
        } ?: return emptyList()

        return listOf(DataValidationError(
            table    = "",
            rowIndex = -1,
            field    = null,
            line     = null,
            message  = "$label '$path' $problem"
        ))
    }

    /** Resolves the output SQL file path declared for [tableSchema], defaulting to "<tableName>.sql". */
    private fun resolveOutputPath(tableSchema: TableSchema, baseOutputDir: Path): Path {
        val fileName = tableSchema.outputFile?.takeIf { it.isNotBlank() } ?: "${tableSchema.tableName}.sql"
        return baseOutputDir.resolve(fileName).normalize()
    }

    /**
     * Groups generated [statements] by resolved output file, merging tables that declare
     * the same `outputFile` into a single [GenerationResult.OutputFileGroup].
     */
    private fun buildOutputFileGroups(
        sortedTables: List<TableSchema>,
        statements: List<GenerationResult.NamedStatement>,
        baseOutputDir: Path
    ): List<GenerationResult.OutputFileGroup> {
        val tablesWithStatements = sortedTables.filter { table ->
            statements.any { it.table == table.tableName }
        }
        val tablesByPath = tablesWithStatements.groupBy { resolveOutputPath(it, baseOutputDir) }

        return tablesByPath.map { (path, tables) ->
            if (tables.size > 1) {
                log.debug("Tables {} share output file '{}'", tables.map { it.tableName }, path)
            }
            val tableNames       = tables.map { it.tableName }.toSet()
            val groupedStatements = statements.filter { it.table in tableNames }
            GenerationResult.OutputFileGroup(
                path   = path,
                tables = tables.map { it.tableName },
                sql    = GenerationResult.render(groupedStatements)
            )
        }
    }

    /**
     * Generates SQL statements for all tables in the given order using their
     * pre-loaded, pre-validated rows.
     */
    private fun buildAllStatements(
        sortedTables: List<TableSchema>,
        allRows: Map<String, List<JsonNode>>
    ): List<GenerationResult.NamedStatement> {

        val statements       = mutableListOf<GenerationResult.NamedStatement>()
        val generatorContext = GeneratorContext()

        for (tableSchema in sortedTables) {
            val rows = allRows[tableSchema.tableName] ?: continue

            val generators: Map<String, ColumnGenerator> = tableSchema.columns
                .filter { col -> col.default?.kind == DefaultType.GENERATOR }
                .associate { col ->
                    col.name to GeneratorParser.parse(
                        expression       = col.default!!.value,
                        generatorContext = generatorContext
                    )
                }
            generators.values.forEach { it.reset() }

            for ((rowIndex, rowNode) in rows.withIndex()) {
                val baseFields      = flattenNode(rowNode)
                val generatedFields = generators
                    .filter { (colName, _) -> colName !in baseFields }
                    .mapValues { (_, gen) -> gen.next(rowIndex) }

                val mergedFields = generatedFields + baseFields

                val dataRow = DataRow(
                    tableName = tableSchema.tableName,
                    fields    = mergedFields,
                    schema    = tableSchema
                )

                val sql = dialect.generateUpsert(dataRow)
                statements.add(GenerationResult.NamedStatement(tableSchema.tableName, rowIndex, sql))

                for ((colName, value) in mergedFields) {
                    generatorContext.recordGeneratedValue(tableSchema.tableName, colName, value)
                }
            }
        }

        return statements
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node flattening utilities (shared with dialect)
    // ─────────────────────────────────────────────────────────────────────────

    private fun flattenNode(node: JsonNode): Map<String, Any?> =
        buildMap {
            node.properties().forEach { entry ->
                put(entry.key, nodeToValue(entry.value))
            }
        }

    private fun nodeToValue(node: JsonNode): Any? = when {
        node.isNull                   -> null
        node.isBoolean                -> node.booleanValue()
        node.isInt                    -> node.intValue()
        node.isLong                   -> node.longValue()
        node.isDouble || node.isFloat -> node.doubleValue()
        node.isBigDecimal             -> node.decimalValue()
        node.isString                 -> node.stringValue()
        node.isObject || node.isArray -> node
        else                          -> node.asString()
    }
}
