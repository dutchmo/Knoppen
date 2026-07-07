package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.austindroids.knoppen.sqlgen.format.AtomicClause
import org.austindroids.knoppen.sqlgen.format.CommaPosition
import org.austindroids.knoppen.sqlgen.format.FormatConfig
import org.austindroids.knoppen.sqlgen.format.ItemizedClause
import org.austindroids.knoppen.sqlgen.format.NewlineStyle
import org.austindroids.knoppen.sqlgen.format.SqlFormatter

// ============================================================
// SqlFormatterTest.kt
//
// Pure layout tests: fixed List<Clause> in, exact String out.
// The formatter has no SQL semantics, so these clauses are
// deliberately generic (not tied to any real dialect).
// ============================================================

class SqlFormatterTest : FunSpec({

    test("SINGLE_LINE puts everything on one line") {
        val fmt = SqlFormatter(FormatConfig.SINGLE_LINE)
        val clauses = listOf(
            ItemizedClause("INSERT INTO t", listOf("a", "b"), parens = true),
            ItemizedClause("VALUES", listOf("1", "2"), parens = true),
            AtomicClause("DO NOTHING")
        )
        fmt.format(clauses) shouldBe "INSERT INTO t (a, b) VALUES (1, 2) DO NOTHING;"
    }

    test("TRADITIONAL puts one clause per line with items inline") {
        val fmt = SqlFormatter(FormatConfig.TRADITIONAL)
        val clauses = listOf(
            ItemizedClause("INSERT INTO t", listOf("a", "b"), parens = true),
            ItemizedClause("VALUES", listOf("1", "2"), parens = true),
            AtomicClause("DO NOTHING")
        )
        fmt.format(clauses) shouldBe """
            INSERT INTO t (a, b)
            VALUES (1, 2)
            DO NOTHING;
        """.trimIndent()
    }

    test("CASCADE2 expands items with leading commas and 2-space indent") {
        val fmt = SqlFormatter(FormatConfig.CASCADE2)
        val clauses = listOf(
            ItemizedClause("INSERT INTO t", listOf("a", "b"), parens = true)
        )
        fmt.format(clauses) shouldBe """
            INSERT INTO t (
              a
              , b
            );
        """.trimIndent()
    }

    test("CASCADE4 expands items with leading commas and 4-space indent") {
        val fmt = SqlFormatter(FormatConfig.CASCADE4)
        val clauses = listOf(
            ItemizedClause("INSERT INTO t", listOf("a", "b"), parens = true)
        )
        fmt.format(clauses) shouldBe """
            INSERT INTO t (
                a
                , b
            );
        """.trimIndent()
    }

    test("RIVER right-pads keywords to widest keyword width") {
        val fmt = SqlFormatter(FormatConfig.RIVER)
        val clauses = listOf(
            AtomicClause("A"),
            AtomicClause("MUCH LONGER")
        )
        val result = fmt.format(clauses)
        val widest = "MUCH LONGER".length
        result shouldBe "A".padStart(widest) + "\n" + "MUCH LONGER".padStart(widest) + ";"
    }

    test("columnThresholdForExpand triggers expansion even in LINE_PER_CLAUSE") {
        val fmt = SqlFormatter(
            FormatConfig(
                newlineStyle = NewlineStyle.LINE_PER_CLAUSE,
                columnThresholdForExpand = 1
            )
        )
        val clauses = listOf(ItemizedClause("VALUES", listOf("1", "2", "3"), parens = true))
        fmt.format(clauses) shouldBe """
            VALUES (
                1,
                2,
                3
            );
        """.trimIndent()
    }

    test("maxLineWidth triggers expansion when inline would overflow") {
        val fmt = SqlFormatter(
            FormatConfig(
                newlineStyle = NewlineStyle.LINE_PER_CLAUSE,
                maxLineWidth = 10
            )
        )
        val clauses = listOf(ItemizedClause("VALUES", listOf("1", "2", "3"), parens = true))
        fmt.format(clauses) shouldBe """
            VALUES (
                1,
                2,
                3
            );
        """.trimIndent()
    }

    test("trailing commas render correctly") {
        val fmt = SqlFormatter(
            FormatConfig(newlineStyle = NewlineStyle.LINE_PER_COLUMN, commaPosition = CommaPosition.TRAILING)
        )
        val clauses = listOf(ItemizedClause("VALUES", listOf("1", "2"), parens = true))
        fmt.format(clauses) shouldBe """
            VALUES (
                1,
                2
            );
        """.trimIndent()
    }

    test("empty items list renders keyword only") {
        val fmt = SqlFormatter(FormatConfig.TRADITIONAL)
        val clauses = listOf(ItemizedClause("DO UPDATE SET", emptyList()))
        fmt.format(clauses) shouldBe "DO UPDATE SET;"
    }

    test("parens=false renders without parentheses") {
        val fmt = SqlFormatter(FormatConfig.TRADITIONAL)
        val clauses = listOf(ItemizedClause("DO UPDATE SET", listOf("a = 1", "b = 2")))
        fmt.format(clauses) shouldBe "DO UPDATE SET a = 1, b = 2;"
    }

    test("zero clauses render just the terminator") {
        val fmt = SqlFormatter(FormatConfig.TRADITIONAL)
        fmt.format(emptyList()) shouldBe ";"
    }

    test("single item in ItemizedClause with LINE_PER_COLUMN still expands to one line") {
        val fmt = SqlFormatter(FormatConfig.CASCADE4)
        val clauses = listOf(ItemizedClause("ON CONFLICT", listOf("id"), parens = true))
        fmt.format(clauses) shouldBe """
            ON CONFLICT (
                id
            );
        """.trimIndent()
    }
})
