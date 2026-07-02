package org.austindroids.knoppen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.austindroids.knoppen.sqlgen.GeneratorContext

class GeneratorContextTest :
    FunSpec({

        test("recordGeneratedValue stores a single value") {
            val ctx = GeneratorContext()
            ctx.recordGeneratedValue("users", "id", 1)
            ctx.valuesFor("users", "id") shouldBe listOf(1)
        }

        test("valuesFor returns empty list when nothing recorded") {
            val ctx = GeneratorContext()
            ctx.valuesFor("users", "id") shouldBe emptyList()
        }

        test("multiple values for same column accumulate in order") {
            val ctx = GeneratorContext()
            ctx.recordGeneratedValue("tag", "id", 1)
            ctx.recordGeneratedValue("tag", "id", 2)
            ctx.recordGeneratedValue("tag", "id", 3)
            ctx.valuesFor("tag", "id") shouldBe listOf(1, 2, 3)
        }

        test("different columns are tracked independently") {
            val ctx = GeneratorContext()
            ctx.recordGeneratedValue("users", "id", 10)
            ctx.recordGeneratedValue("users", "name", "alice")
            ctx.valuesFor("users", "id")   shouldBe listOf(10)
            ctx.valuesFor("users", "name") shouldBe listOf("alice")
        }

        test("different tables are tracked independently") {
            val ctx = GeneratorContext()
            ctx.recordGeneratedValue("users", "id", 1)
            ctx.recordGeneratedValue("post",  "id", 100)
            ctx.valuesFor("users", "id") shouldBe listOf(1)
            ctx.valuesFor("post",  "id") shouldBe listOf(100)
        }

        test("null values can be recorded") {
            val ctx = GeneratorContext()
            ctx.recordGeneratedValue("users", "deletedAt", null)
            ctx.valuesFor("users", "deletedAt") shouldBe listOf(null)
        }
    })
