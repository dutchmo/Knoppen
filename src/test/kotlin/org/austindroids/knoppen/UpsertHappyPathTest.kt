package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.austindroids.knoppen.datafile.DataValidationError
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.validation.SchemaValidator
import java.io.InputStream

// ============================================================
// UpsertHappyPathTest.kt
//
// Kotest FunSpec — happy path only.
// Tests are intentionally coarse at this stage; failure cases
// and per-row SQL assertions will be added in later passes.
//
// Resource layout assumed:
//   src/test/resources/schema/code_sample_schema.yaml
//   src/test/resources/schema/upsert-schema.json   ← JSON meta-schema
//   src/test/resources/data/data_tag.yaml
//   src/test/resources/data/data_relational.yaml
// ============================================================

class UpsertHappyPathTest : FunSpec({

    // ── Helpers ───────────────────────────────────────────────────────────
    fun resourceStream(path: String): InputStream =
        UpsertHappyPathTest::class.java.classLoader
            .getResourceAsStream(path)
            ?: error("Test resource not found: $path")

    fun resourceText(path: String): String =
        resourceStream(path).bufferedReader().readText()

    // ── Shared fixtures ───────────────────────────────────────────────────
    val schemaYaml     by lazy { resourceText("schema/code_sample_schema.yaml") }
    //val metaSchemaJson by lazy { resourceText("schema/upsert-schema.json")}
    val dataTagYaml    by lazy { resourceText("data/data_tag.yaml")             }
    val dataRelYaml    by lazy { resourceText("data/data_relational.yaml")      }

    // ── Step 1: Schema meta-validation ────────────────────────────────────
    test("schema yaml passes meta-schema validation") {
        val result = SchemaValidator.validate(schemaYaml)

        result.prettyPrint().let(::println)

        result.hasErrors shouldBe false
    }

    // ── Step 2: Schema deserialisation ────────────────────────────────────
    test("schema yaml deserialises into a DatabaseSchema") {
        val dbSchema = SchemaParser.parse(schemaYaml.byteInputStream())

        dbSchema.tables                      shouldHaveSize 6
        dbSchema.tables.map { it.tableName } shouldContainAll listOf(
            "tag", "users", "post", "post_tag", "audit_log", "post_approval"
        )
    }

    test("table order in DatabaseSchema matches yaml declaration order") {
        val dbSchema = SchemaParser.parse(schemaYaml.byteInputStream())

        dbSchema.tables.map { it.tableName } shouldContainInOrder listOf(
            "tag", "users", "post", "post_tag", "audit_log", "post_approval"
        )
    }

    // ── Step 3: Data file validation ──────────────────────────────────────
    context("data file validation") {
        val dbSchema by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }

        test("data_tag.yaml validates without errors") {
            val result = DataValidator.validate(
                schema     = dbSchema,
                dataStream = dataTagYaml.byteInputStream()
            )

            result.prettyPrint().let(::println)

            result.errors
                .filter { it.severity == DataValidationError.Severity.ERROR }
                .shouldBeEmpty()
        }

        test("data_relational.yaml validates without errors (frank row is WARN not ERROR)") {
            val result = DataValidator.validate(
                schema     = dbSchema,
                dataStream = dataRelYaml.byteInputStream()
            )

            result.prettyPrint().let(::println)

            val errors   = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }
            val warnings = result.errors.filter { it.severity == DataValidationError.Severity.WARNING }

            errors   .shouldBeEmpty()
            warnings .shouldHaveSize(1)
            warnings.first().column shouldBe "approvedTs"
        }
    }

    // ── Step 4: SQL generation ────────────────────────────────────────────
    context("sql generation - data_tag.yaml") {
        val dbSchema  by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }
        val dialect   by lazy { PostgresDialect() }
        val generator by lazy { UpsertGenerator(dbSchema, dialect) }

        lateinit var result: UpsertGenerator.GenerationResult

        beforeTest {
            result = generator.generateStatements(dataTagYaml.byteInputStream())
        }

        test("generation produces no errors") {
            result.hasErrors shouldBe false
        }

        test("correct number of statements produced for tag") {
            result.sql
                .filter { it.table == "tag" }
                .shouldHaveSize(6)
        }

        test("all tag statements are INSERT INTO code_sample.tag") {
            result.sql
                .filter { it.table == "tag" }
                .forEach { stmt ->
                    stmt.sql shouldContain "INSERT INTO code_sample.tag"
                }
        }

        test("tag statements include ON CONFLICT DO NOTHING") {
            result.sql
                .filter { it.table == "tag" }
                .forEach { stmt ->
                    stmt.sql shouldContain "ON CONFLICT"
                    stmt.sql shouldContain "DO NOTHING"
                }
        }

        test("SEQUENCE generator produces ascending column_order values") {
            val orders = result.sql
                .filter { it.table == "tag" }
                .mapNotNull { stmt ->
                    Regex("""column_order\s*=\s*(\d+)""")
                        .find(stmt.sql)?.groupValues?.get(1)?.toIntOrNull()
                }

            orders.zipWithNext { a, b -> b - a }.forEach { delta ->
                delta shouldBe 10
            }
        }
    }

    context("sql generation - data_relational.yaml") {
        val dbSchema  by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }
        val dialect   by lazy { PostgresDialect() }
        val generator by lazy { UpsertGenerator(dbSchema, dialect) }

        lateinit var result: UpsertGenerator.GenerationResult

        beforeTest {
            result = generator.generateStatements(dataRelYaml.byteInputStream())
        }

        test("generation produces no errors") {
            result.hasErrors shouldBe false
        }

        test("statements are produced for all relational tables") {
            val tables = result.sql.map { it.table }.toSet()
            tables shouldContainAll listOf(
                "users", "post", "post_tag", "audit_log", "post_approval"
            )
        }

        test("correct statement counts per table") {
            val byTable = result.sql.groupBy { it.table }

            byTable["users"]!!         shouldHaveSize 7
            byTable["post"]!!          shouldHaveSize 6
            byTable["post_tag"]!!      shouldHaveSize 8
            byTable["audit_log"]!!     shouldHaveSize 9
            byTable["post_approval"]!! shouldHaveSize 6
        }

        test("post statements reference correct FK column user_id") {
            result.sql
                .filter { it.table == "post" }
                .forEach { stmt ->
                    stmt.sql shouldContain "user_id"
                }
        }

        test("post_tag statements include compound conflict target (post_id, tag_id)") {
            result.sql
                .filter { it.table == "post_tag" }
                .forEach { stmt ->
                    stmt.sql shouldContain "ON CONFLICT"
                    stmt.sql shouldContain "post_id"
                    stmt.sql shouldContain "tag_id"
                    stmt.sql shouldContain "DO NOTHING"
                }
        }

        test("post_approval conflict uses compound unique target not primary key") {
            result.sql
                .filter { it.table == "post_approval" }
                .forEach { stmt ->
                    stmt.sql shouldContain "ON CONFLICT (post_id, approver_id)"
                }
        }

        test("users conflict row updates type but preserves createTs") {
            val conflictRow = result.sql
                .filter { it.table == "users" }
                .find { it.rowIndex == 5 }

            conflictRow shouldNotBe null
            conflictRow!!.sql shouldContain "ON CONFLICT"
            conflictRow.sql shouldContain "type"
            conflictRow.sql shouldNotContain Regex("""DO UPDATE SET[^;]*createTs""")
        }

        test("post conflict row excludes user_id from update") {
            val conflictRow = result.sql
                .filter { it.table == "post" }
                .find { it.rowIndex == 5 }

            conflictRow shouldNotBe null
            conflictRow!!.sql shouldContain "ON CONFLICT"
            conflictRow.sql shouldNotContain Regex("""DO UPDATE SET[^;]*user_id""")
        }

        test("audit_log statements include ON CONFLICT DO NOTHING") {
            result.sql
                .filter { it.table == "audit_log" }
                .forEach { stmt ->
                    stmt.sql shouldContain "ON CONFLICT"
                    stmt.sql shouldContain "DO NOTHING"
                }
        }
    }
})
