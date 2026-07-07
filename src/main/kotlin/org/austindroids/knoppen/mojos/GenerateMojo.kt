package org.austindroids.knoppen.mojos

import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import kotlin.io.path.writeText

/**
 * Validates the schema and its data files, then generates SQL upsert statements and
 * writes them to the resolved output files. Equivalent to the CLI's `generate` subcommand.
 */
@Mojo(name = "generateSQL", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresProject = true)
class GenerateMojo : AbstractKnoppenMojo() {

    override val generateSql: Boolean = true

    override fun onSuccess(result: UpsertGenerator.GenerationResult) {
        if (result.outputFiles.isEmpty()) {
            log.info("No SQL statements generated — nothing to write.")
            return
        }
        result.outputFiles.forEach { file ->
            file.path.writeText(file.sql)
            log.info("SQL written to: ${file.path} (${file.tables.joinToString(", ")})")
        }
    }
}
