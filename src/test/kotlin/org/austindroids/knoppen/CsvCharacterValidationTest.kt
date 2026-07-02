package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.austindroids.knoppen.datafile.DataValidationError

class CsvCharacterValidationTest :
    FunSpec({

        fun resourceText(path: String): String =
            CsvCharacterValidationTest::class.java.classLoader
                .getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
                ?: error("Test resource not found: $path")

        val schemaYaml by lazy { resourceText("schema/products_schema.yaml") }
        val dbSchema   by lazy { SchemaParser.parse(schemaYaml.byteInputStream()) }

        // ── Clean CSV passes ──────────────────────────────────────────────────────

        test("clean CSV with plain ASCII passes character validation") {
            val csv = resourceText("data/products_clean.csv")
            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            result.errors
                .filter { it.severity == DataValidationError.Severity.ERROR }
                .shouldBeEmpty()
        }

        // ── Smart quotes ─────────────────────────────────────────────────────────

        test("CSV with smart double quotes is rejected with error per violation") {
            // “ = " (left double), ” = " (right double) — typical Windows copy-paste
            val csv =
                "id,name,description\n" +
                    "1,Widget,“A useful gadget”\n"

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 2
            errors[0].message shouldContain "U+201C"
            errors[0].message shouldContain "LEFT DOUBLE QUOTATION MARK"
            errors[1].message shouldContain "U+201D"
            errors[1].message shouldContain "RIGHT DOUBLE QUOTATION MARK"
        }

        test("CSV with smart single quote / apostrophe is rejected") {
            // ’ = ' (right single / curly apostrophe)
            val csv =
                "id,name,description\n" +
                    "1,Alice’s Widget,Great\n"

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 1
            errors[0].message shouldContain "U+2019"
            errors[0].message shouldContain "RIGHT SINGLE QUOTATION MARK"
        }

        // ── Other common offenders ────────────────────────────────────────────────

        test("CSV with em dash is rejected") {
            // — = — (em dash — often pasted from Word or Google Docs)
            val csv =
                "id,name,description\n" +
                    "1,Widget,Best—in—class\n"

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 2
            errors.forEach { err -> err.message shouldContain "EM DASH" }
        }

        test("CSV with non-breaking space is rejected") {
            //   = non-breaking space — invisible but breaks SQL string comparisons
            val csv =
                "id,name,description\n" +
                    "1,Widget Pro,Fine product\n"

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 1
            errors[0].message shouldContain "NON-BREAKING SPACE"
        }

        // ── Line and column numbers ───────────────────────────────────────────────

        test("violation reports correct line and column") {
            // Line 1 = header, line 2 = first data row; char is at col 4 (0-indexed from 1)
            // "id,name,description\n" = 20 chars
            // "1,W“idget,desc\n"
            //       ^-- col 4
            val csv =
                "id,name,description\n" +
                    "1,W“idget,desc\n"

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 1
            errors[0].line shouldBe 2
            errors[0].message shouldContain "col 4"
        }

        // ── Multiple violations across multiple lines ─────────────────────────────

        test("all violations across multiple rows are reported") {
            val csv =
                "id,name,description\n" +
                    "1,“Widget”,good\n" +   // line 2: 2 violations
                    "2,Doohickey,nice…item\n"    // line 3: 1 violation (ellipsis)

            val result = DataValidator.validate(dbSchema, "product", csv, "csv")
            val errors = result.errors.filter { it.severity == DataValidationError.Severity.ERROR }

            errors shouldHaveSize 3
            errors[0].line shouldBe 2
            errors[1].line shouldBe 2
            errors[2].line shouldBe 3
            errors[2].message shouldContain "HORIZONTAL ELLIPSIS"
        }
    })
