package org.austindroids.knoppen.mojos

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.slf4j.LoggerFactory
import java.io.File

@Mojo(name = "generate")
class GenerateMojo : AbstractMojo() {

    // Shared pipeline classes (UpsertGenerator, SchemaValidator, ...) log via slf4j so the same
    // log statements apply whether invoked from the CLI (logback) or this Maven plugin (Maven's
    // own slf4j binding). Named `logger`, not `log`, to avoid shadowing AbstractMojo's getLog().
    private val logger = LoggerFactory.getLogger(GenerateMojo::class.java)

    @Parameter(property = "configFile", defaultValue = "\${project.basedir}/src/main/resources/bootstrap-config.yaml")
    private lateinit var configFile: File

    @Parameter(property = "outputDirectory", defaultValue = "\${project.build.directory}/generated-sql")
    private lateinit var outputDirectory: File

    @Parameter(property = "databaseType", defaultValue = "postgresql")
    private var databaseType: String = "postgresql"

    @Parameter(property = "generateInserts", defaultValue = "true")
    private var generateInserts: Boolean = true

    @Parameter(property = "generateUpdates", defaultValue = "true")
    private var generateUpdates: Boolean = true

    override fun execute() {
        log.info("Generating SQL from YAML configuration")
        logger.debug("GenerateMojo invoked: configFile={}, outputDirectory={}", configFile, outputDirectory)

        try {
            if (!configFile.exists()) {
                throw MojoExecutionException("Configuration file not found: ${configFile.absolutePath}")
            }

            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            // TODO: Load YAML, generate SQL based on databaseType
            // TODO: Write SQL files to outputDirectory

            log.info("SQL generated successfully to: ${outputDirectory.absolutePath}")

        } catch (e: Exception) {
            throw MojoExecutionException("Failed to generate SQL", e)
        }
    }
}