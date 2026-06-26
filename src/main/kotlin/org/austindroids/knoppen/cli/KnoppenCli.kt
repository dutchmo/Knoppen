package org.austindroids.knoppen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import org.austindroids.knoppen.SchemaParser
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.validation.SchemaValidator
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KnoppenCli : NoOpCliktCommand(name = "knoppen") {
    init {
        subcommands(ValidateCommand(), GenerateCommand())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared base with common args/options
// ─────────────────────────────────────────────────────────────────────────────

abstract class BaseKnoppenCommand(name: String) : CliktCommand(name = name) {

    val schema by argument("SCHEMA")
        .help("Path to the schema YAML file (filename resolved from CWD if no directory given)")

    val output by option("--output", "-o")
        .help("Output file for generated SQL (default: <schema>.sql in same directory)")

    val strict by option("--strict").flag("--no-strict", default = true)

    val rootDataPath by option("--root-data-path")
        .help("Override rootDataPath declared in the schema YAML")

    fun resolveSchemaPath(): Path {
        val p = Path(schema)
        return if (p.isAbsolute || schema.contains('/') || schema.contains('\\')) p
        else Path(System.getProperty("user.dir")).resolve(schema)
    }

    fun resolveOutputPath(schemaPath: Path): Path {
        return when {
            output != null -> {
                val p = Path(output!!)
                if (p.isAbsolute) p else Path(System.getProperty("user.dir")).resolve(output!!)
            }
            else -> schemaPath.parent.resolve("${schemaPath.nameWithoutExtension}.sql")
        }
    }

    fun loadAndValidateSchema(schemaPath: Path): DatabaseSchema? {
        if (!schemaPath.exists()) {
            echo("Error: schema file not found: ${schemaPath.absolute()}", err = true)
            return null
        }
        val yamlContent = schemaPath.readText()

        val metaResult = SchemaValidator.validate(yamlContent)
        if (metaResult.hasErrors) {
            echo("Schema meta-validation failed:", err = true)
            metaResult.errors.forEach { echo("  $it", err = true) }
            return null
        }

        return try {
            val parsed = SchemaParser.parse(yamlContent)
            // Override strictFields from CLI --strict/--no-strict flag
            parsed.copy(validation = parsed.validation.copy(strictFields = strict))
        } catch (e: Exception) {
            echo("Error deserializing schema: ${e.message}", err = true)
            null
        }
    }

    fun resolveRootDataPathOverride(): Path? =
        rootDataPath?.let {
            val p = Path(it)
            if (p.isAbsolute) p else Path(System.getProperty("user.dir")).resolve(p)
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// validate
// ─────────────────────────────────────────────────────────────────────────────

class ValidateCommand : BaseKnoppenCommand("validate") {
    override fun help(context: Context) = "Validate schema and data files without generating SQL"

    override fun run() {
        val startMs          = System.currentTimeMillis()
        val schemaPath       = resolveSchemaPath()
        val dbSchema         = loadAndValidateSchema(schemaPath) ?: throw ProgramResult(1)
        val dataPathOverride = resolveRootDataPathOverride()

        val generator = UpsertGenerator(dbSchema, PostgresDialect())
        val result    = generator.generateAll(schemaPath, dataPathOverride, generateSql = false)
        val elapsedMs = System.currentTimeMillis() - startMs

        SummaryPrinter.print(schemaPath.fileName.toString(), result, elapsedMs, "validate")

        if (result.hasErrors) throw ProgramResult(1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// generate
// ─────────────────────────────────────────────────────────────────────────────

class GenerateCommand : BaseKnoppenCommand("generate") {
    override fun help(context: Context) = "Validate and generate SQL upsert statements"

    override fun run() {
        val startMs          = System.currentTimeMillis()
        val schemaPath       = resolveSchemaPath()
        val dbSchema         = loadAndValidateSchema(schemaPath) ?: throw ProgramResult(1)
        val dataPathOverride = resolveRootDataPathOverride()
        val outputPath       = resolveOutputPath(schemaPath)

        val generator = UpsertGenerator(dbSchema, PostgresDialect())
        val result    = generator.generateAll(schemaPath, dataPathOverride)
        val elapsedMs = System.currentTimeMillis() - startMs

        SummaryPrinter.print(schemaPath.fileName.toString(), result, elapsedMs, "generate")

        if (result.hasErrors) throw ProgramResult(1)

        outputPath.writeText(result.toSqlString())
        echo("SQL written to: $outputPath")
    }
}
