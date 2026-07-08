package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.validation.SchemaValidator
import java.nio.file.Path

// ============================================================
// SystemCodeTest.kt
//
// Kotest FunSpec exercising a schema modelled on the real
// `system_code` reference-data table (src/test/resources/system_code.sql)
// against a data fixture covering the "name_prefix" and "name_suffix"
// groups (name_prefix is equivalent to the boot data in
// src/test/resources/system_code_inserts.sql), verifying Knoppen
// regenerates equivalent INSERT statements.
//
// system_code_id (FUNCTION default) and code_order (GENERATOR default)
// are both REQUIRED *and* absent from the data file — the pipeline must
// fill them in via their declared defaults rather than flagging them as
// missing during structural validation.
//
// Resource layout (test classpath):
//   src/test/resources/schema/system_code_schema.yaml
//   src/test/resources/data/system_code.csv
// ============================================================

class SystemCodeTest : FunSpec({

    fun resourceText(path: String): String =
        SystemCodeTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Test resource not found: $path")

    // Looks up a single column's rendered value in a generated INSERT statement by
    // name, matching the declared "col_name" in the column list to the value on the
    // same line position inside VALUES (...).
    fun fieldValue(sql: String, field: String): String {
        val columns = Regex("""INSERT INTO public\.system_code \(([\s\S]*?)\)\s*VALUES""")
            .find(sql)!!.groupValues[1]
            .trim().lines().map { it.trim().trimEnd(',').trim('"') }
        val values = Regex("""VALUES\s*\(([\s\S]*?)\)\s*ON CONFLICT""")
            .find(sql)!!.groupValues[1]
            .trim().lines().map { it.trim().trimEnd(',') }
        val idx = columns.indexOf(field)
        require(idx >= 0) { "Column '$field' not found in INSERT column list: $columns" }
        return values[idx]
    }

    val schemaPath: Path = Path.of("src/test/resources/schema/system_code_schema.yaml")
    val schemaYaml by lazy { resourceText("schema/system_code_schema.yaml") }

    test("schema yaml passes meta-schema validation") {
        val result = SchemaValidator.validate(schemaYaml)
        result.prettyPrint().let(::println)
        result.hasErrors shouldBe false
    }

    test("schema yaml deserialises into a DatabaseSchema") {
        val dbSchema = SchemaParser.parse(schemaYaml.byteInputStream())

        dbSchema.tables shouldHaveSize 1
        dbSchema.tables.first().tableName shouldBe "system_code"
    }

    context("pipeline - system_code") {
        val dbSchema  by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }
        val dialect   by lazy { PostgresDialect() }
        val generator by lazy { UpsertGenerator(dbSchema, dialect) }

        lateinit var result: UpsertGenerator.GenerationResult

        beforeTest {
            result = generator.generateAll(schemaPath)
            result.prettyPrint().let(::println)
        }

        test("generation produces no hard errors") {
            result.errors
                .filter { it.severity == DataValidationError.Severity.ERROR }
                .shouldBeEmpty()
        }

        test("one statement per data row across both table_name groups") {
            result.sql shouldHaveSize 79
        }

        test("all statements are INSERT INTO public.system_code") {
            result.sql.forEach { stmt ->
                stmt.sql shouldContain "INSERT INTO public.system_code"
            }
        }

        test("statements include ON CONFLICT (table_name, code_value) DO NOTHING") {
            result.sql.forEach { stmt ->
                stmt.sql shouldContain "ON CONFLICT (table_name, code_value)"
                stmt.sql shouldContain "DO NOTHING"
            }
        }

        test("schema defaults are rendered — audit columns and row_source") {
            val stmt = result.sql.first().sql
            stmt shouldContain "CURRENT_TIMESTAMP"
            stmt shouldContain "'SYSTEM:9999999'"
            stmt shouldContain "'BOOT_90000'"
        }

        test("AGENT row matches the equivalent hand-written insert") {
            val agent = result.sql.first { it.sql.contains("'AGENT'") }
            agent.sql shouldContain "'name_prefix'"
            agent.sql shouldContain "10"
            agent.sql shouldContain "'Agent'"
        }

        test("row_version defaults to 1 for every row") {
            result.sql.forEach { stmt ->
                fieldValue(stmt.sql, "row_version") shouldBe "1"
            }
        }

        test("system_code_id is filled by its FUNCTION default, not left blank") {
            result.sql.forEach { stmt ->
                fieldValue(stmt.sql, "system_code_id") shouldBe "nextval('system_code_id_seq')"
            }
        }

        test("GROUPED_SEQUENCE resets code_order when table_name group changes") {
            val orders = result.sql
                .sortedBy { it.rowIndex }
                .map { fieldValue(it.sql, "code_order").toInt() }

            // rows 0-28:  name_prefix  → 10, 20, ..., 290 (29 values)
            // rows 29-78: name_suffix  → group change resets to 10, 20, ..., 500 (50 values)
            val expectedPrefix = (1..29).map { it * 10 }
            val expectedSuffix = (1..50).map { it * 10 }
            orders shouldBe expectedPrefix + expectedSuffix
        }
    }
})
