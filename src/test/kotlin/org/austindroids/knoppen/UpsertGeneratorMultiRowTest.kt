package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import java.nio.file.Path

// ============================================================
// UpsertGeneratorMultiRowTest.kt
//
// Exercises UpsertGenerator's multi-row batching (multiRowBatchSize > 1)
// end-to-end via generateAll(), using small dedicated fixtures:
//   src/test/resources/schema/multirow_schema.yaml + data/widget.yaml
//   src/test/resources/schema/multirow_mismatch_schema.yaml + data/widget_mismatch.yaml
//
// Default behavior (multiRowBatchSize = 1) is covered by UpsertHappyPathTest
// and SystemCodeTest and is intentionally left untouched by this feature.
// ============================================================

class UpsertGeneratorMultiRowTest : FunSpec({

    fun resourceText(path: String): String =
        UpsertGeneratorMultiRowTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Test resource not found: $path")

    context("clean batching (no column mismatch)") {
        val schemaPath = Path.of("src/test/resources/schema/multirow_schema.yaml")
        val dbSchema   by lazy { SchemaParser.parse(resourceText("schema/multirow_schema.yaml").byteInputStream()) }
        val dialect    by lazy { PostgresDialect() }

        // widget.yaml: 5 rows, id sequence [1, 1, 2, 3, 4] — rows[0] and rows[1]
        // intentionally share id=1 (the ON CONFLICT target) to force an early split.
        test("batchSize=10 splits early on a duplicate conflict key, not just on size") {
            val generator = UpsertGenerator(dbSchema, dialect, multiRowBatchSize = 10)
            val result    = generator.generateAll(schemaPath)

            result.errors.filter { it.severity == DataValidationError.Severity.ERROR }.shouldHaveSize(0)

            val widgetStatements = result.sql.filter { it.table == "widget" }
            widgetStatements shouldHaveSize 2
            widgetStatements.map { it.rowRange } shouldBe listOf(0..0, 1..4)

            // First (single-row) statement is a one-tuple multi-row INSERT for id=1 — a
            // batch of one row still goes through generateMultiRowUpsert, not generateUpsert.
            widgetStatements[0].sql shouldContain "VALUES\n    (1, 'one')"
            // Second statement batches the remaining 4 rows (including the second id=1) together.
            widgetStatements[1].sql shouldContain "(1, 'one-updated')"
            widgetStatements[1].sql shouldContain "(4, 'four')"
        }

        test("batchSize=1 preserves one-statement-per-row behavior") {
            val generator = UpsertGenerator(dbSchema, dialect, multiRowBatchSize = 1)
            val result    = generator.generateAll(schemaPath)

            result.sql.filter { it.table == "widget" } shouldHaveSize 5
        }
    }

    context("column-presence mismatch within a batch") {
        val schemaPath = Path.of("src/test/resources/schema/multirow_mismatch_schema.yaml")
        val dbSchema   by lazy {
            SchemaParser.parse(resourceText("schema/multirow_mismatch_schema.yaml").byteInputStream())
        }
        val dialect = PostgresDialect()

        test("batching rows that disagree on a no-default column's presence is a hard error") {
            val generator = UpsertGenerator(dbSchema, dialect, multiRowBatchSize = 10)
            val result    = generator.generateAll(schemaPath)

            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }
            errors shouldHaveSize 1
            errors.first().table shouldBe "widget_mismatch"
            errors.first().column shouldBe "note"

            // Any hard error suppresses all SQL output, not just the offending table's.
            result.sql shouldHaveSize 0
        }
    }
})
