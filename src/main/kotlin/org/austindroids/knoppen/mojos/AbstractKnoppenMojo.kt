package org.austindroids.knoppen.mojos

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Parameter
import org.austindroids.knoppen.SchemaParser
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.sqlgen.format.FormatConfig
import org.austindroids.knoppen.validation.SchemaValidator
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Shared base for the Knoppen mojos. Mirrors the CLI's `BaseKnoppenCommand`: it holds
 * the same parameters/settings and drives the identical pipeline
 * (meta-validate → parse → apply `strict` → [UpsertGenerator.generateAll]). Subclasses
 * differ only in whether SQL is generated and whether the resulting files are written.
 *
 * Where the CLI reports failures via `echo` + a non-zero exit code, the mojos surface
 * them as [MojoExecutionException] so they fail the Maven build.
 */
abstract class AbstractKnoppenMojo : AbstractMojo() {

    // Shared pipeline classes (UpsertGenerator, SchemaValidator, ...) log via slf4j so the same
    // log statements apply whether invoked from the CLI (logback) or this Maven plugin (Maven's
    // own slf4j binding). Named `logger`, not `log`, to avoid shadowing AbstractMojo's getLog().
    private val logger = LoggerFactory.getLogger(AbstractKnoppenMojo::class.java)

    /** Schema YAML file — the CLI's `SCHEMA` positional argument. */
    @Parameter(property = "knoppen.schema", defaultValue = "\${project.basedir}/src/main/resources/schema.yaml")
    internal lateinit var schemaFile: File

    /** Overrides `validation.strictFields` in the schema — the CLI's `--strict`/`--no-strict`. */
    @Parameter(property = "knoppen.strict", defaultValue = "true")
    internal var strict: Boolean = true

    /** Overrides `rootDataPath` declared in the schema YAML — the CLI's `--root-data-path`. */
    @Parameter(property = "knoppen.rootDataPath")
    internal var rootDataPath: File? = null

    /** Overrides `rootOutputPath` declared in the schema YAML — the CLI's `--root-output-path`. */
    @Parameter(property = "knoppen.rootOutputPath")
    internal var rootOutputPath: File? = null

    /** SQL layout preset name — the CLI's `--output-format` (LEGACY, RIVER, CASCADE4, ...). */
    @Parameter(property = "knoppen.outputFormat", defaultValue = "LEGACY")
    internal var outputFormat: String = "LEGACY"

    /** Raises console logging to DEBUG for parity with the CLI's `--debug`; Maven itself uses `-X`. */
    @Parameter(property = "knoppen.debug", defaultValue = "false")
    internal var debug: Boolean = false

    /** Standard Maven convenience: skip execution entirely. */
    @Parameter(property = "knoppen.skip", defaultValue = "false")
    internal var skip: Boolean = false

    /** True for the generate goal, false for validate-only. */
    protected abstract val generateSql: Boolean

    final override fun execute() {
        if (skip) {
            log.info("Knoppen skipped (skip=true)")
            return
        }
        if (debug) System.setProperty("KNOPPEN_CONSOLE_LEVEL", "DEBUG")

        val schemaPath = schemaFile.toPath()
        val schema     = loadSchema(schemaPath)
        val format     = resolveFormat()

        logger.debug(
            "Knoppen mojo: schema={}, strict={}, outputFormat={}, generateSql={}",
            schemaPath, strict, outputFormat, generateSql
        )

        val result = UpsertGenerator(schema, PostgresDialect(format)).generateAll(
            schemaPath             = schemaPath,
            rootDataPathOverride   = rootDataPath?.toPath(),
            rootOutputPathOverride = rootOutputPath?.toPath(),
            generateSql            = generateSql
        )

        reportResult(result)

        val errorCount = result.errors.count { it.severity == DataValidationError.Severity.ERROR }
        if (result.hasErrors) {
            throw MojoExecutionException("Knoppen validation failed with $errorCount error(s); see the log above.")
        }

        onSuccess(result)
    }

    /** Hook for subclasses to act on a successful (error-free) result, e.g. write SQL files. */
    protected open fun onSuccess(result: UpsertGenerator.GenerationResult) {}

    /**
     * Reads, meta-validates, parses the schema, and applies the [strict] override.
     * Any failure is raised as a [MojoExecutionException] (the CLI equivalent prints to
     * stderr and exits non-zero).
     */
    private fun loadSchema(schemaPath: Path): DatabaseSchema {
        val file = schemaPath.toFile()
        if (!file.exists()) {
            throw MojoExecutionException("Schema file not found: ${file.absolutePath}")
        }
        val yamlContent = file.readText()

        val metaResult = SchemaValidator.validate(yamlContent)
        if (metaResult.hasErrors) {
            metaResult.errors.forEach { log.error("  $it") }
            throw MojoExecutionException("Schema meta-validation failed for ${file.absolutePath}")
        }

        return try {
            val parsed = SchemaParser.parse(yamlContent)
            parsed.copy(validation = parsed.validation.copy(strictFields = strict))
        } catch (e: Exception) {
            throw MojoExecutionException("Error deserializing schema ${file.absolutePath}: ${e.message}", e)
        }
    }

    /** Resolves [outputFormat] to a preset, failing the build on an unknown name. */
    private fun resolveFormat(): FormatConfig =
        FormatConfig.byName(outputFormat)
            ?: throw MojoExecutionException(
                "Unknown outputFormat '$outputFormat'. Valid values: " +
                    FormatConfig.presetsByName.keys.joinToString(", ")
            )

    /** Logs a concise summary of per-file row counts and any errors/warnings via the Maven log. */
    private fun reportResult(result: UpsertGenerator.GenerationResult) {
        result.fileStats.forEach { stats ->
            log.info("  ${stats.tableName}: ${stats.rowCount} row(s) from ${stats.filePath}")
        }
        val (errors, warnings) = result.errors.partition {
            it.severity == DataValidationError.Severity.ERROR
        }
        warnings.forEach { log.warn("  $it") }
        errors.forEach { log.error("  $it") }
    }
}
