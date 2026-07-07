package org.austindroids.knoppen

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.apache.maven.plugin.MojoExecutionException
import org.austindroids.knoppen.mojos.AbstractKnoppenMojo
import org.austindroids.knoppen.mojos.GenerateMojo
import org.austindroids.knoppen.mojos.ValidateMojo
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ============================================================
// MojoPipelineTest.kt
//
// Layer-1 (Kotest) tests for the Maven mojos. The mojos are instantiated
// directly and their parameters set as internal fields — no Maven runtime.
// Each scenario builds a self-contained temp workspace on disk (schema +
// data + output dirs) because the pipeline resolves everything against real
// filesystem paths. Mirrors IOConventionsTest's workspace approach.
// ============================================================

class MojoPipelineTest : FunSpec({

    val workspaces = mutableListOf<Path>()

    afterSpec { workspaces.forEach { it.toFile().deleteRecursively() } }

    /**
     * Creates schema/data/out dirs under a fresh temp workspace and writes a schema.yaml
     * plus one data file. Returns (schemaFile, dataDir, outDir).
     */
    fun workspace(
        prefix: String,
        schemaYaml: String,
        dataFileName: String,
        dataContent: String
    ): Triple<Path, Path, Path> {
        val workDir = createTempDirectory(prefix).also { workspaces.add(it) }
        val dataDir = workDir.resolve("data").also { it.createDirectories() }
        val outDir  = workDir.resolve("out").also { it.createDirectories() }
        val schema  = workDir.resolve("schema.yaml")
        schema.writeText(schemaYaml)
        dataDir.resolve(dataFileName).writeText(dataContent)
        return Triple(schema, dataDir, outDir)
    }

    fun <T : AbstractKnoppenMojo> T.configure(
        schema: Path,
        dataDir: Path? = null,
        outDir: Path? = null,
        format: String = "LEGACY",
        strictFlag: Boolean = true
    ): T = apply {
        schemaFile = schema.toFile()
        rootDataPath = dataDir?.toFile()
        rootOutputPath = outDir?.toFile()
        outputFormat = format
        strict = strictFlag
    }

    // A clean, single-table schema whose data validates successfully.
    val widgetSchema = """
        dialect: postgresql
        schema: mojo_test
        validation:
          defaultNullable: true
          strictFields: false
        tables:
          - tableName: widget
            dataFiles: [widget.csv]
            outputFile: widget.sql
            primaryKey: [id]
            onConflict: { target: [id], action: doNothing }
            columns:
              - name: id
                datatype: INTEGER
                constraints: [{ constraint: REQUIRED }]
              - name: name
                datatype: VARCHAR(50)
    """.trimIndent()

    val widgetData = "id,name\n1,Widget\n2,Gadget\n"

    // ── generate goal ───────────────────────────────────────────────────────

    test("GenerateMojo writes the SQL output file with INSERT/ON CONFLICT") {
        val (schema, dataDir, outDir) =
            workspace("mojo-gen-", widgetSchema, "widget.csv", widgetData)

        GenerateMojo().configure(schema, dataDir, outDir).execute()

        val sqlFile = outDir.resolve("widget.sql")
        sqlFile.exists() shouldBe true
        val sql = sqlFile.readText()
        sql shouldContain "INSERT INTO mojo_test.widget"
        sql shouldContain "ON CONFLICT"
    }

    // ── validate goal ───────────────────────────────────────────────────────

    test("ValidateMojo succeeds on valid data and writes no files") {
        val (schema, dataDir, outDir) =
            workspace("mojo-val-", widgetSchema, "widget.csv", widgetData)

        ValidateMojo().configure(schema, dataDir, outDir).execute()

        outDir.listDirectoryEntries().isEmpty() shouldBe true
    }

    // ── failure: missing schema file ────────────────────────────────────────

    test("a missing schema file fails the build with MojoExecutionException") {
        val workDir = createTempDirectory("mojo-missing-schema-").also { workspaces.add(it) }
        val ex = shouldThrow<MojoExecutionException> {
            GenerateMojo().configure(workDir.resolve("nope.yaml")).execute()
        }
        ex.message shouldContain "Schema file not found"
    }

    // ── failure: unknown output format ──────────────────────────────────────

    test("an unknown outputFormat fails the build with MojoExecutionException") {
        val (schema, dataDir, outDir) =
            workspace("mojo-badfmt-", widgetSchema, "widget.csv", widgetData)

        val ex = shouldThrow<MojoExecutionException> {
            GenerateMojo().configure(schema, dataDir, outDir, format = "NONSENSE").execute()
        }
        ex.message shouldContain "Unknown outputFormat"
    }

    // ── failure: data validation error ──────────────────────────────────────

    test("a data validation error fails the build and writes no SQL") {
        // 'age' is REQUIRED but the second row omits it.
        val strictSchema = """
            dialect: postgresql
            schema: mojo_test
            validation:
              defaultNullable: false
              strictFields: true
            tables:
              - tableName: person
                dataFiles: [person.yaml]
                outputFile: person.sql
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
                  - name: age
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
        """.trimIndent()
        val data = "- id: 1\n  age: 30\n- id: 2\n"
        val (schema, dataDir, outDir) =
            workspace("mojo-invalid-", strictSchema, "person.yaml", data)

        shouldThrow<MojoExecutionException> {
            GenerateMojo().configure(schema, dataDir, outDir).execute()
        }
        outDir.resolve("person.sql").exists() shouldBe false
    }

    // ── format parity: a different preset changes the layout ────────────────

    test("RIVER preset produces different layout than LEGACY") {
        val (schemaA, dataA, outA) =
            workspace("mojo-legacy-", widgetSchema, "widget.csv", widgetData)
        val (schemaB, dataB, outB) =
            workspace("mojo-river-", widgetSchema, "widget.csv", widgetData)

        GenerateMojo().configure(schemaA, dataA, outA, format = "LEGACY").execute()
        GenerateMojo().configure(schemaB, dataB, outB, format = "RIVER").execute()

        val legacy = outA.resolve("widget.sql").readText()
        val river  = outB.resolve("widget.sql").readText()
        legacy shouldNotBe river
    }

    // ── skip flag short-circuits execution ──────────────────────────────────

    test("skip=true is a no-op that writes nothing") {
        val (schema, dataDir, outDir) =
            workspace("mojo-skip-", widgetSchema, "widget.csv", widgetData)

        GenerateMojo().configure(schema, dataDir, outDir).also { it.skip = true }.execute()

        outDir.listDirectoryEntries().isEmpty() shouldBe true
    }
})
