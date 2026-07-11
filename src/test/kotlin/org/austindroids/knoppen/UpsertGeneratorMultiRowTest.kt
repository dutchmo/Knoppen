package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.schema.DatabaseSchema
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import java.nio.file.Path

// ============================================================
// UpsertGeneratorMultiRowTest.kt
//
// Exercises UpsertGenerator's multi-row batching (TableSchema.batchSize),
// including batchSize == 0 ("no limit"), end-to-end via generateAll(), using
// small dedicated fixtures:
//   src/test/resources/schema/multirow_schema.yaml + data/widget.yaml
//   src/test/resources/schema/multirow_mismatch_schema.yaml + data/widget_mismatch.yaml
//
// Default behavior (batchSize = 1, the schema default) is covered by
// UpsertHappyPathTest and SystemCodeTest and is intentionally left untouched
// by this feature.
// ============================================================

class UpsertGeneratorMultiRowTest : FunSpec({

    fun resourceText(path: String): String =
        UpsertGeneratorMultiRowTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Test resource not found: $path")

    /** Overrides every table's batchSize in this schema — these fixtures declare a single table. */
    fun DatabaseSchema.withBatchSize(batchSize: Int): DatabaseSchema =
        copy(tables = tables.map { it.copy(batchSize = batchSize) })

    context("clean batching (no column mismatch)") {
        val schemaPath = Path.of("src/test/resources/schema/multirow_schema.yaml")
        val dbSchema   by lazy { SchemaParser.parse(resourceText("schema/multirow_schema.yaml").byteInputStream()) }
        val dialect    by lazy { PostgresDialect() }

        // widget.yaml: 5 rows, id sequence [1, 1, 2, 3, 4] — rows[0] and rows[1]
        // intentionally share id=1 (the ON CONFLICT target) to force an early split.
        test("batchSize=10 splits early on a duplicate conflict key, not just on size") {
            val generator = UpsertGenerator(dbSchema.withBatchSize(10), dialect)
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

        test("batchSize=0 means no size limit — only the conflict key forces a split") {
            val generator = UpsertGenerator(dbSchema.withBatchSize(0), dialect)
            val result    = generator.generateAll(schemaPath)

            // Same chunk shape as batchSize=10: with only 5 rows, "no limit" and "cap of 10"
            // are indistinguishable by row count alone. This specifically guards the
            // `batchSize > 0` guard in chunkRows() — a naive `currentChunk.size >= batchSize`
            // with no such guard would split on *every* row when batchSize is 0 (0 >= 0 is
            // always true), silently collapsing "unlimited" into one-statement-per-row.
            val widgetStatements = result.sql.filter { it.table == "widget" }
            widgetStatements shouldHaveSize 2
            widgetStatements.map { it.rowRange } shouldBe listOf(0..0, 1..4)
        }

        test("batchSize=2 caps each batch at 2 rows in addition to the conflict-key split") {
            val generator = UpsertGenerator(dbSchema.withBatchSize(2), dialect)
            val result    = generator.generateAll(schemaPath)

            val widgetStatements = result.sql.filter { it.table == "widget" }
            // row[0] alone (conflict key forces a split before row[1]); row[1]+row[2] (the
            // size cap of 2 forces a split before row[3]); row[3]+row[4].
            widgetStatements.map { it.rowRange } shouldBe listOf(0..0, 1..2, 3..4)
        }

        test("batchSize=1 (the schema default) preserves one-statement-per-row behavior") {
            val result = UpsertGenerator(dbSchema, dialect).generateAll(schemaPath)

            result.sql.filter { it.table == "widget" } shouldHaveSize 5
        }

        test("constraint-based targeting has no column list to key on, so batchSize degrades to one row per statement") {
            val constraintSchema = dbSchema.withBatchSize(10).let { schema ->
                schema.copy(tables = schema.tables.map { it.copy(onConflict = it.onConflict?.copy(target = null, constraint = "widget_pkey")) })
            }
            val result = UpsertGenerator(constraintSchema, dialect).generateAll(schemaPath)

            result.errors.filter { it.severity == DataValidationError.Severity.ERROR }.shouldHaveSize(0)
            // Every row's "conflict key" collapses to the same empty string when there's no
            // target column list, forcing a split before every row — safe (never risks two
            // rows in one statement sharing an unknown constraint's key), but no batching
            // benefit despite batchSize=10. See chunkRows()'s KDoc in UpsertGenerator.kt.
            result.sql.filter { it.table == "widget" } shouldHaveSize 5
            result.sql.first { it.table == "widget" }.sql shouldContain "ON CONFLICT ON CONSTRAINT \"widget_pkey\""
        }
    }

    context("column-presence mismatch within a batch") {
        val schemaPath = Path.of("src/test/resources/schema/multirow_mismatch_schema.yaml")
        val dbSchema   by lazy {
            SchemaParser.parse(resourceText("schema/multirow_mismatch_schema.yaml").byteInputStream())
        }
        val dialect = PostgresDialect()

        test("batching rows that disagree on a no-default column's presence is a hard error") {
            val generator = UpsertGenerator(dbSchema.withBatchSize(10), dialect)
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
