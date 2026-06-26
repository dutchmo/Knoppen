package org.austindroids.knoppen.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.sqlgen.UpsertGenerator

object SummaryPrinter {

    private val terminal = Terminal()

    fun print(
        schemaFileName: String,
        result: UpsertGenerator.GenerationResult,
        elapsedMs: Long,
        mode: String
    ) {
        val errCount  = result.errors.count { it.severity == DataValidationError.Severity.ERROR }
        val warnCount = result.errors.count { it.severity == DataValidationError.Severity.WARNING }

        terminal.println()
        terminal.println(table {
            captionTop("Knoppen $mode")

            header {
                style(bold = true)
                row("File", "Table", "Rows", "Status")
            }

            body {
                row(schemaFileName, "(schema)", "—", check())

                result.fileStats.forEach { fs ->
                    val hasError = result.errors.any {
                        it.table == fs.tableName && it.severity == DataValidationError.Severity.ERROR
                    }
                    row(fs.filePath, fs.tableName, fs.rowCount.toString(), if (hasError) cross() else check())
                }
            }

            footer {
                val errLabel  = if (errCount > 0)
                    TextColors.red("$errCount error(s)")
                else
                    "$errCount error(s)"
                val warnLabel = if (warnCount > 0)
                    TextColors.yellow("$warnCount warning(s)")
                else
                    "$warnCount warning(s)"
                row {
                    cell("$errLabel   $warnLabel   Time: ${elapsedMs}ms") { columnSpan = 4 }
                }
            }
        })

        if (result.errors.isNotEmpty()) {
            terminal.println()
            result.errors.forEach { err ->
                val prefix = if (err.severity == DataValidationError.Severity.ERROR)
                    TextColors.red("ERROR")
                else
                    TextColors.yellow("WARN ")
                val location = buildString {
                    append("table='${err.table}'")
                    if (err.rowIndex >= 0) append(", row=${err.rowIndex}")
                    if (err.field != null) append(", field='${err.field}'")
                    if (err.line != null) append(", line=${err.line}")
                }
                terminal.println("$prefix  [$location] ${err.message}")
            }
        }
    }

    private fun check() = TextColors.green("✓")
    private fun cross() = TextColors.red("✗")
}
