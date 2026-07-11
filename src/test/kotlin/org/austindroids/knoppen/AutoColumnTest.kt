package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.sqlgen.format.FormatConfig
import org.austindroids.knoppen.validation.SchemaValidator
import java.nio.file.Path

// ============================================================
// AutoColumnTest.kt
//
// End-to-end coverage for DefaultType.AUTO via generateAll(), using a small
// dedicated fixture:
//   src/test/resources/schema/auto_column_schema.yaml
//   src/test/resources/data/gadget.yaml       (happy path)
//   src/test/resources/data/gadget_bad.yaml   (error path — id supplied)
//
// "id" and "created_at" are AUTO (database-assigned); "name" carries a real
// UNIQUE constraint and is the ON CONFLICT target instead of the (omitted) PK.
// ============================================================

class AutoColumnTest : FunSpec({

    fun resourceText(path: String): String =
        AutoColumnTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Test resource not found: $path")

    val schemaPath = Path.of("src/test/resources/schema/auto_column_schema.yaml")
    val schemaYaml by lazy { resourceText("schema/auto_column_schema.yaml") }

    test("schema yaml passes meta-schema validation") {
        val result = SchemaValidator.validate(schemaYaml)
        result.prettyPrint().let(::println)
        result.hasErrors shouldBe false
    }

    context("happy path") {
        val dbSchema  by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }
        val generator by lazy { UpsertGenerator(dbSchema, PostgresDialect(FormatConfig.SINGLE_LINE)) }

        test("REQUIRED id is never flagged missing even though every row omits it") {
            val result = generator.generateAll(schemaPath)
            result.errors.filter { it.severity == DataValidationError.Severity.ERROR }.shouldBeEmpty()
        }

        test("generated SQL omits id and created_at from every statement") {
            val result = generator.generateAll(schemaPath)
            val statements = result.sql.filter { it.table == "gadget" }
            statements shouldHaveSize 3
            statements.forEach { stmt ->
                stmt.sql shouldNotContain "\"id\""
                stmt.sql shouldNotContain "\"created_at\""
            }
        }

        test("insert column list is exactly name and status") {
            val result = generator.generateAll(schemaPath)
            val first = result.sql.first { it.table == "gadget" && it.rowRange == 0..0 }
            first.sql shouldContain "INSERT INTO auto_test.gadget (\"name\", \"status\")"
            first.sql shouldContain "VALUES ('widget-a', 'ACTIVE')"
        }

        test("ON CONFLICT targets name and DO UPDATE SET only touches status") {
            val result = generator.generateAll(schemaPath)
            val conflictRow = result.sql.first { it.table == "gadget" && it.rowRange == 2..2 }
            conflictRow.sql shouldContain "ON CONFLICT (name)"
            conflictRow.sql shouldContain "DO UPDATE SET \"status\" = EXCLUDED.\"status\";"
        }
    }

    context("error path") {
        test("a row supplying a value for an AUTO column is a hard error") {
            val dbSchema = SchemaParser.parse(schemaYaml.byteInputStream())
            val badSchema = dbSchema.copy(
                tables = dbSchema.tables.map { it.copy(dataFiles = listOf("gadget_bad.yaml")) }
            )
            val result = UpsertGenerator(badSchema, PostgresDialect(FormatConfig.SINGLE_LINE)).generateAll(schemaPath)

            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }
            errors shouldHaveSize 1
            errors.first().column shouldBe "id"
            errors.first().message shouldContain "AUTO"

            result.sql.shouldBeEmpty()
        }
    }
})
