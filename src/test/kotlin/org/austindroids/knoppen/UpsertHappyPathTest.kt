package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
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
import java.nio.file.Path

// ============================================================
// UpsertHappyPathTest.kt
//
// Kotest FunSpec — happy path only.
//
// Resource layout (test classpath):
//   src/test/resources/schema/code_sample_schema.yaml
//   src/test/resources/data/tag.yaml
//   src/test/resources/data/users.yaml
//   src/test/resources/data/users2.yaml
//   src/test/resources/data/post.yaml
//   src/test/resources/data/post_tag.yaml
//   src/test/resources/data/audit_log.yaml
//   src/test/resources/data/post_approval.yaml
//
// The schema declares rootDataPath: ../data and files: [...] for each table.
// The pipeline resolves all paths relative to the schema file location.
// ============================================================

class UpsertHappyPathTest : FunSpec({

    // ── Helpers ───────────────────────────────────────────────────────────
    fun resourceText(path: String): String =
        UpsertHappyPathTest::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: error("Test resource not found: $path")

    // Filesystem path used for file resolution in generateAll().
    // Gradle sets the working directory to the project root during tests.
    val schemaPath: Path = Path.of("src/test/resources/schema/code_sample_schema.yaml")

    // Schema YAML loaded via classpath for meta-validation and deserialization.
    val schemaYaml by lazy { resourceText("schema/code_sample_schema.yaml") }

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

    // ── Steps 3 + 4: Pipeline (validation + SQL generation together) ──────
    context("pipeline - all tables") {
        val dbSchema  by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }
        val dialect   by lazy { PostgresDialect() }
        val generator by lazy { UpsertGenerator(dbSchema, dialect) }

        lateinit var result: UpsertGenerator.GenerationResult

        beforeTest {
            result = generator.generateAll(schemaPath)
            result.prettyPrint().let(::println)
        }

        // ── Validation ────────────────────────────────────────────────────

        test("generation produces no hard errors") {
            result.errors
                .filter { it.severity == DataValidationError.Severity.ERROR }
                .shouldBeEmpty()
        }

        test("frank row produces temporal warning not an error") {
            val warnings = result.errors.filter { it.severity == DataValidationError.Severity.WARNING }
            warnings shouldHaveSize 1
            warnings.first().column shouldBe "approvedTs"
        }

        // ── Statement counts ──────────────────────────────────────────────

        test("correct statement counts per table") {
            val byTable = result.sql.groupBy { it.table }

            byTable["tag"]!!           shouldHaveSize 8
            byTable["users"]!!         shouldHaveSize 7
            byTable["post"]!!          shouldHaveSize 6
            byTable["post_tag"]!!      shouldHaveSize 8
            byTable["audit_log"]!!     shouldHaveSize 9
            byTable["post_approval"]!! shouldHaveSize 6
        }

        // ── tag table ─────────────────────────────────────────────────────

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

        test("GROUPED_SEQUENCE resets column_order when category changes") {
            // column_order is the last declared column, so its value is the last
            // line inside the VALUES (...) tuple.
            fun columnOrderOf(sql: String): Int =
                Regex("""VALUES\s*\(([\s\S]*?)\)\s*ON CONFLICT""")
                    .find(sql)!!.groupValues[1]
                    .trim().lines().last().trim().trimEnd(',').toInt()

            val orders = result.sql
                .filter { it.table == "tag" }
                .sortedBy { it.rowIndex }
                .map { columnOrderOf(it.sql) }

            // rows 0-2: category IT      → 0, 10, 20
            // rows 3-6: category SPORTS  → group change resets to 0, then 10, 20, 30
            // row 7:    still SPORTS (duplicate PK / DO NOTHING row) → continues at 40
            orders shouldContainExactly listOf(0, 10, 20, 0, 10, 20, 30, 40)
        }

        // ── Relational tables ─────────────────────────────────────────────

        test("statements are produced for all relational tables") {
            val tables = result.sql.map { it.table }.toSet()
            tables shouldContainAll listOf(
                "users", "post", "post_tag", "audit_log", "post_approval"
            )
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
            // users.yaml rows: alice(0), bob-SUPERVISOR(1), bob-ADMIN-conflict(2), frank(3)
            // users2.yaml rows: carol(4), dave(5), eve(6)
            // The ON CONFLICT update row (bob ADMIN) is at merged rowIndex 2.
            val conflictRow = result.sql
                .filter { it.table == "users" }
                .find { it.rowIndex == 2 }

            conflictRow shouldNotBe null
            conflictRow!!.sql shouldContain "ON CONFLICT"
            conflictRow.sql shouldContain "type"
            conflictRow.sql shouldNotContain Regex("""DO UPDATE SET[^;]*createTs""")
        }

        test("post conflict row excludes user_id from update") {
            // post.yaml rows: id100(0), id101(1), id102(2), id103(3), id104(4), id101-conflict(5)
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
