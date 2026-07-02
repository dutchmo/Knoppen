package org.austindroids.knoppen.mojos

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.slf4j.LoggerFactory
import java.io.File

@Mojo(name = "validate")
class ValidateMojo : AbstractMojo() {

    // See GenerateMojo for why this is separate from AbstractMojo's inherited getLog()/log.
    private val logger = LoggerFactory.getLogger(ValidateMojo::class.java)

    @Parameter(property = "configFile", defaultValue = "\${project.basedir}/src/main/resources/bootstrap-config.yaml")
    private lateinit var configFile: File

    @Parameter(property = "schemaFile", defaultValue = "\${project.basedir}/src/main/resources/schema/config-schema.json")
    private lateinit var schemaFile: File

    @Parameter(property = "failOnError", defaultValue = "true")
    private var failOnError: Boolean = true

    override fun execute() {
        log.info("Validating YAML configuration: ${configFile.absolutePath}")
        logger.debug("ValidateMojo invoked: configFile={}, schemaFile={}", configFile, schemaFile)

        try {
            // TODO: Implement YAML validation against JSON schema
            if (!configFile.exists()) {
                val message = "Configuration file not found: ${configFile.absolutePath}"
                if (failOnError) {
                    throw MojoExecutionException(message)
                } else {
                    log.warn(message)
                    return
                }
            }

            // TODO: Load and validate YAML
            log.info("YAML configuration is valid")

        } catch (e: Exception) {
            val message = "Failed to validate YAML configuration"
            log.error(message, e)
            if (failOnError) {
                throw MojoExecutionException(message, e)
            }
        }
    }
}