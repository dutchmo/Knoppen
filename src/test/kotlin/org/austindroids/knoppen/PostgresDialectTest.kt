package org.austindroids.knoppen

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.OnConflictAction
import org.austindroids.knoppen.schema.OnConflictConfig
import org.austindroids.knoppen.schema.SqlType
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.sqlgen.DataRow
import org.austindroids.knoppen.sqlgen.SqlDialect
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import org.austindroids.knoppen.sqlgen.format.FormatConfig

// ============================================================
// PostgresDialectTest.kt
//
// Integration tests: generateUpsert() end-to-end for each named
// FormatConfig preset, exercised against a small in-memory schema
// (no file I/O — this is not the full pipeline test, see
// UpsertHappyPathTest for that).
// ============================================================

class PostgresDialectTest : FunSpec({

    val usersSchema = TableSchema(
        schemaName = "app",
        tableName  = "users",
        primaryKey = listOf("id"),
        onConflict = OnConflictConfig(
            target = listOf("id"),
            action = OnConflictAction.UPDATE
        ),
        columns = listOf(
            ColumnSchema(name = "id",    datatype = "INTEGER"),
            ColumnSchema(name = "name",  datatype = "VARCHAR(30)"),
            ColumnSchema(name = "email", datatype = "VARCHAR(60)")
        )
    )

    fun usersRow(schema: TableSchema = usersSchema) = DataRow(
        tableName = "users",
        fields = linkedMapOf("id" to 42, "name" to "Alice", "email" to "a@b.com"),
        schema = schema
    )

    test("upsert with SINGLE_LINE produces compact output") {
        val sql = PostgresDialect(FormatConfig.SINGLE_LINE).generateUpsert(usersRow())
        sql shouldBe "INSERT INTO app.users (\"id\", \"name\", \"email\") " +
            "VALUES (42, 'Alice', 'a@b.com') " +
            "ON CONFLICT (id) " +
            "DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"email\" = EXCLUDED.\"email\";"
    }

    test("upsert with CASCADE2 uses leading commas and 2-space indent") {
        val sql = PostgresDialect(FormatConfig.CASCADE2).generateUpsert(usersRow())
        sql shouldContain "INSERT INTO app.users (\n  \"id\"\n  , \"name\"\n  , \"email\"\n)"
        sql shouldContain "DO UPDATE SET\n  \"name\" = EXCLUDED.\"name\"\n  , \"email\" = EXCLUDED.\"email\""
    }

    test("upsert with RIVER uses right-aligned keywords") {
        val sql = PostgresDialect(FormatConfig.RIVER).generateUpsert(usersRow())
        val widest = "INSERT INTO app.users".length
        val conflictLine = sql.lines().first { it.contains("ON CONFLICT") }
        conflictLine shouldBe " ".repeat(widest - "ON CONFLICT (id)".length) + "ON CONFLICT (id)"
    }

    test("upsert with no onConflict defaults to DO NOTHING on PK") {
        val schema = usersSchema.copy(onConflict = null)
        val sql = PostgresDialect(FormatConfig.TRADITIONAL).generateUpsert(usersRow(schema))
        sql shouldContain "ON CONFLICT (\"id\")"
        sql shouldContain "DO NOTHING"
    }

    test("upsert with empty update columns degrades to DO NOTHING") {
        val schema = usersSchema.copy(
            onConflict = OnConflictConfig(
                target = listOf("id"),
                action = OnConflictAction.UPDATE,
                excludeFromUpdate = listOf("name", "email")
            )
        )
        val sql = PostgresDialect(FormatConfig.TRADITIONAL).generateUpsert(usersRow(schema))
        sql shouldContain "DO NOTHING"
        sql shouldNotContain "DO UPDATE SET"
    }

    test("default constructor (LEGACY preset) matches pre-formatter behaviour") {
        val sql = PostgresDialect().generateUpsert(usersRow())
        sql shouldBe """
            INSERT INTO app.users (
                "id",
                "name",
                "email"
            )
            VALUES (
                42,
                'Alice',
                'a@b.com'
            )
            ON CONFLICT (id)
            DO UPDATE SET
                "name" = EXCLUDED."name",
                "email" = EXCLUDED."email"
        """.trimIndent() + "\n;"
    }

    // ── Multi-row upserts ───────────────────────────────────────────────

    fun bobRow(schema: TableSchema = usersSchema) = DataRow(
        tableName = "users",
        fields = linkedMapOf("id" to 43, "name" to "Bob", "email" to "b@b.com"),
        schema = schema
    )

    test("multi-row upsert with SINGLE_LINE produces one compact statement") {
        val sql = PostgresDialect(FormatConfig.SINGLE_LINE).generateMultiRowUpsert(listOf(usersRow(), bobRow()))
        sql shouldBe "INSERT INTO app.users (\"id\", \"name\", \"email\") " +
            "VALUES (42, 'Alice', 'a@b.com'), (43, 'Bob', 'b@b.com') " +
            "ON CONFLICT (id) " +
            "DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"email\" = EXCLUDED.\"email\";"
    }

    test("multi-row upsert with TRADITIONAL renders one row-tuple per line with trailing commas") {
        val sql = PostgresDialect(FormatConfig.TRADITIONAL).generateMultiRowUpsert(listOf(usersRow(), bobRow()))
        sql shouldContain "VALUES\n    (42, 'Alice', 'a@b.com'),\n    (43, 'Bob', 'b@b.com')\nON CONFLICT"
    }

    test("multi-row upsert with RIVER uses leading commas between row-tuples") {
        val sql = PostgresDialect(FormatConfig.RIVER).generateMultiRowUpsert(listOf(usersRow(), bobRow()))
        sql shouldContain "\n    (42, 'Alice', 'a@b.com')\n    , (43, 'Bob', 'b@b.com')\n"
    }

    test("multi-row upsert emits ON CONFLICT and DO UPDATE SET once, not once per row") {
        val rows = (1..5).map { i ->
            DataRow(
                tableName = "users",
                fields = linkedMapOf("id" to i, "name" to "User$i", "email" to "u$i@b.com"),
                schema = usersSchema
            )
        }
        val sql = PostgresDialect(FormatConfig.TRADITIONAL).generateMultiRowUpsert(rows)
        Regex("ON CONFLICT").findAll(sql).count() shouldBe 1
        Regex("DO UPDATE SET").findAll(sql).count() shouldBe 1
        Regex("""\(\d, 'User\d', 'u\d@b\.com'\)""").findAll(sql).count() shouldBe 5
    }

    test("generateMultiRowUpsert rejects rows from different tables") {
        val otherSchema = usersSchema.copy(tableName = "other_users")
        shouldThrow<IllegalArgumentException> {
            PostgresDialect().generateMultiRowUpsert(listOf(usersRow(), usersRow(otherSchema)))
        }
    }

    test("generateMultiRowUpsert rejects rows with differing insertable columns") {
        val schemaWithOptionalColumn = usersSchema.copy(
            columns = usersSchema.columns + ColumnSchema(name = "notes", datatype = "TEXT")
        )
        val rowWithNotes = DataRow(
            tableName = "users",
            fields = linkedMapOf("id" to 44, "name" to "Cara", "email" to "c@b.com", "notes" to "vip"),
            schema = schemaWithOptionalColumn
        )
        val rowWithoutNotes = usersRow(schemaWithOptionalColumn)

        shouldThrow<IllegalArgumentException> {
            PostgresDialect().generateMultiRowUpsert(listOf(rowWithoutNotes, rowWithNotes))
        }
    }

    test("SqlDialect default generateMultiRowUpsert falls back to one statement per row") {
        val dialect = object : SqlDialect {
            override fun generateUpsert(row: DataRow): String = "INSERT ROW ${row.fields["id"]};"
            override fun formatValue(value: Any?, sqlType: SqlType): String = value.toString()
        }
        dialect.generateMultiRowUpsert(listOf(usersRow(), bobRow())) shouldBe
            "INSERT ROW 42;\nINSERT ROW 43;"
    }
})
