package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.austindroids.knoppen.schema.ColumnSchema
import org.austindroids.knoppen.schema.OnConflictAction
import org.austindroids.knoppen.schema.OnConflictConfig
import org.austindroids.knoppen.schema.TableSchema
import org.austindroids.knoppen.sqlgen.DataRow
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
})
