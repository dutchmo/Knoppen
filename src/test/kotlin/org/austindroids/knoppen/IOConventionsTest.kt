package org.austindroids.knoppen

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

// ============================================================
// IOConventionsTest.kt
//
// Kotest FunSpec covering the filesystem-facing conventions introduced
// alongside dataFiles/outputFile/rootDataPath/rootOutputPath:
//
//   1. Tables that declare the same outputFile are merged into one file,
//      in dependency (topological) order rather than declaration order.
//   2. Path validation: a missing rootOutputPath directory, a dataFiles
//      entry that doesn't exist on disk, and an empty dataFiles list
//      (which silently skips the table instead of erroring).
//   3. A missing classpath resource (e.g. the packaged meta-schema)
//      fails loudly via ResourceNotFoundException.
//
// Every scenario builds its own temp workspace on disk since
// UpsertGenerator.generateAll() resolves dataFiles/outputFile against
// real filesystem paths, not classpath resources.
// ============================================================

class IOConventionsTest : FunSpec({

    val workspaces = mutableListOf<Path>()

    afterSpec {
        workspaces.forEach { it.toFile().deleteRecursively() }
    }

    fun workspace(prefix: String): Path {
        val dir = createTempDirectory(prefix)
        workspaces.add(dir)
        return dir
    }

    fun parse(yaml: String) = SchemaParser.parse(yaml.byteInputStream())

    // ── 1. Shared outputFile merges tables, in dependency order ────────────

    context("multiple tables sharing an outputFile") {
        val workDir = workspace("knoppen-io-shared-")
        val dataDir = workDir.resolve("data").also { it.createDirectories() }
        val outDir  = workDir.resolve("out").also { it.createDirectories() }

        dataDir.resolve("parent.csv").writeText("id,name\n1,Root\n")
        dataDir.resolve("child.csv").writeText("id,parent_id,name\n10,1,Leaf\n")

        // "child" is declared BEFORE "parent" in the YAML, but child has an FK
        // to parent, so topological sort must still place parent first.
        val dbSchema = parse(
            """
            dialect: postgresql
            schema: io_test
            validation:
              defaultNullable: true
              strictFields: false
            tables:
              - tableName: child
                dataFiles: [child.csv]
                outputFile: combined.sql
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
                  - name: parent_id
                    datatype: INTEGER
                    foreignKey: { table: parent, columns: [id] }
                    constraints: [{ constraint: REQUIRED }]
                  - name: name
                    datatype: VARCHAR(50)
              - tableName: parent
                dataFiles: [parent.csv]
                outputFile: combined.sql
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
                  - name: name
                    datatype: VARCHAR(50)
            """.trimIndent()
        )

        val result = UpsertGenerator(dbSchema, PostgresDialect())
            .generateAll(
                schemaPath = workDir.resolve("schema.yaml"),
                rootDataPathOverride = dataDir,
                rootOutputPathOverride = outDir
            )

        test("generation produces no hard errors") {
            result.hasErrors shouldBe false
        }

        test("both tables collapse into a single output file") {
            result.outputFiles shouldHaveSize 1
            result.outputFiles.first().path shouldBe outDir.resolve("combined.sql")
        }

        test("merged file's table list follows dependency order, not declaration order") {
            result.outputFiles.first().tables shouldContainExactly listOf("parent", "child")
        }

        test("merged SQL content places the parent statement before the child statement") {
            val sql        = result.outputFiles.first().sql
            val parentIdx  = sql.indexOf("INSERT INTO io_test.parent")
            val childIdx   = sql.indexOf("INSERT INTO io_test.child")

            parentIdx shouldNotBe -1
            childIdx  shouldNotBe -1
            (parentIdx < childIdx) shouldBe true
        }
    }

    // ── 2a. Missing rootOutputPath directory ────────────────────────────────

    context("rootOutputPath directory does not exist") {
        val workDir = workspace("knoppen-io-missing-output-root-")
        val dataDir = workDir.resolve("data").also { it.createDirectories() }
        val missingOutDir = workDir.resolve("does-not-exist")

        dataDir.resolve("widget.csv").writeText("id,name\n1,Widget\n")

        val dbSchema = parse(
            """
            dialect: postgresql
            schema: io_test
            validation:
              defaultNullable: true
              strictFields: false
            tables:
              - tableName: widget
                dataFiles: [widget.csv]
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
                  - name: name
                    datatype: VARCHAR(50)
            """.trimIndent()
        )

        val result = UpsertGenerator(dbSchema, PostgresDialect())
            .generateAll(
                schemaPath = workDir.resolve("schema.yaml"),
                rootDataPathOverride = dataDir,
                rootOutputPathOverride = missingOutDir
            )

        test("a missing rootOutputPath is reported as an error") {
            result.hasErrors shouldBe true
            val message = result.errors.joinToString { it.message }
            message shouldContain "rootOutputPath"
            message shouldContain "does not exist"
        }

        test("generation is blocked — no SQL and no output files") {
            result.sql.shouldBeEmpty()
            result.outputFiles.shouldBeEmpty()
        }
    }

    // ── 2b. A declared dataFiles entry that doesn't exist on disk ──────────

    context("a dataFiles entry does not exist on disk") {
        val workDir = workspace("knoppen-io-missing-datafile-")
        val dataDir = workDir.resolve("data").also { it.createDirectories() }
        val outDir  = workDir.resolve("out").also { it.createDirectories() }
        // Note: "ghost.csv" is declared but never written to dataDir.

        val dbSchema = parse(
            """
            dialect: postgresql
            schema: io_test
            validation:
              defaultNullable: true
              strictFields: false
            tables:
              - tableName: ghost
                dataFiles: [ghost.csv]
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
            """.trimIndent()
        )

        val result = UpsertGenerator(dbSchema, PostgresDialect())
            .generateAll(
                schemaPath = workDir.resolve("schema.yaml"),
                rootDataPathOverride = dataDir,
                rootOutputPathOverride = outDir
            )

        test("the missing file is reported as an error against its table") {
            result.hasErrors shouldBe true
            val fileError = result.errors.find { it.table == "ghost" }
            fileError shouldNotBe null
            fileError!!.message shouldContain "ghost.csv"
            fileError.message shouldContain "not found"
        }

        test("generation is blocked — no SQL and no output files") {
            result.sql.shouldBeEmpty()
            result.outputFiles.shouldBeEmpty()
        }
    }

    // ── 2c. An empty dataFiles list skips the table instead of erroring ────

    context("a table with an empty dataFiles list") {
        val workDir = workspace("knoppen-io-empty-datafiles-")
        val dataDir = workDir.resolve("data").also { it.createDirectories() }
        val outDir  = workDir.resolve("out").also { it.createDirectories() }

        dataDir.resolve("widget.csv").writeText("id,name\n1,Widget\n")

        val dbSchema = parse(
            """
            dialect: postgresql
            schema: io_test
            validation:
              defaultNullable: true
              strictFields: false
            tables:
              - tableName: widget
                dataFiles: [widget.csv]
                primaryKey: [id]
                onConflict: { target: [id], action: doNothing }
                columns:
                  - name: id
                    datatype: INTEGER
                    constraints: [{ constraint: REQUIRED }]
                  - name: name
                    datatype: VARCHAR(50)
              - tableName: empty_table
                dataFiles: []
                primaryKey: [id]
                columns:
                  - name: id
                    datatype: INTEGER
            """.trimIndent()
        )

        val result = UpsertGenerator(dbSchema, PostgresDialect())
            .generateAll(
                schemaPath = workDir.resolve("schema.yaml"),
                rootDataPathOverride = dataDir,
                rootOutputPathOverride = outDir
            )

        test("no errors are raised for the empty table") {
            result.hasErrors shouldBe false
            result.errors.none { it.table == "empty_table" } shouldBe true
        }

        test("the populated table still generates SQL") {
            result.sql.none { it.table == "widget" } shouldBe false
        }

        test("the empty table produces no rows and no output file") {
            result.sql.none { it.table == "empty_table" } shouldBe true
            result.outputFiles.none { "empty_table" in it.tables } shouldBe true
        }
    }

    // ── 3. Missing meta-schema (classpath resource) ─────────────────────────

    context("a required classpath resource is missing") {
        val loader = ClasspathResourceLoader(ResourceLoader::class.java)

        test("the packaged meta-schema resource loads fine today") {
            val json = loader.resourceText(ResourceConstants.META_JSON_SCHEMA)
            json shouldContain "DatabaseSchema"
        }

        test("a missing classpath resource throws ResourceNotFoundException") {
            val missingPath = "schema/json-schema-MISSING.json"
            val ex = shouldThrow<ResourceNotFoundException> {
                loader.resourceText(missingPath)
            }
            ex.message shouldContain missingPath
        }
    }
})
