package org.austindroids.knoppen.mojos

import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo

/**
 * Validates the schema and its declared data files without generating or writing any SQL.
 * Equivalent to the CLI's `validate` subcommand. A validation error fails the build.
 */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE, requiresProject = true)
class ValidateMojo : AbstractKnoppenMojo() {

    override val generateSql: Boolean = false
}
