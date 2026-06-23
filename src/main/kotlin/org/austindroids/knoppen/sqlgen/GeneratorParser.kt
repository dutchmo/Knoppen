package org.austindroids.knoppen.sqlgen


/**
 * Parses a generator expression string into a [ColumnGenerator].
 *
 * Supported syntax (case-insensitive name, strict argument types):
 *
 *   SEQUENCE(start, step)
 *   SEQUENCE(start, step, suffix)
 *   COUNTER(start)
 *   TEMPLATE(pattern)
 *   TIMESTAMP_OFFSET(unit, step)
 *   UUID()
 *   CYCLE(v1, v2, ..., vN)
 *   DISTRIBUTE(w1, w2, ..., wN) — weights must sum to 100
 *   FOREIGN_CYCLE(tableName, columnName)
 */
object GeneratorParser {

    /**
     * Parses [expression] and returns a [ColumnGenerator].
     *
     * @param expression   The raw value string from DefaultValue e.g. "SEQUENCE(10,10)"
     * @param generatorContext  Shared context providing access to already-generated rows
     *                         for generators like FOREIGN_CYCLE. May be null for generators
     *                         that don't need cross-table data.
     */
    fun parse(
        expression: String,
        generatorContext: GeneratorContext? = null
    ): ColumnGenerator {
        val trimmed = expression.trim()
        val name    = trimmed.substringBefore("(").uppercase().trim()
        val argStr  = trimmed
            .substringAfter("(", "")
            .substringBeforeLast(")", "")
            .trim()

        // Split on commas, but only at top level (no nested parens in current generators)
        val args = if (argStr.isEmpty()) emptyList()
        else argStr.split(",").map { it.trim() }

        return when (name) {
            "SEQUENCE"         -> parseSequence(args, expression)
            "COUNTER"          -> parseCounter(args, expression)
            "TEMPLATE"         -> parseTemplate(argStr, expression)  // pass raw — may contain commas
            "TIMESTAMP_OFFSET" -> parseTimestampOffset(args, expression)
            "UUID"             -> UuidGenerator()
            "CYCLE"            -> parseCycle(args, expression)
            "DISTRIBUTE"       -> parseDistribute(args, expression)
            "FOREIGN_CYCLE"    -> parseForeignCycle(args, expression, generatorContext)
            else -> throw IllegalArgumentException(
                "Unknown generator '$name' in expression '$expression'. " +
                        "Available: SEQUENCE, COUNTER, TEMPLATE, TIMESTAMP_OFFSET, " +
                        "UUID, CYCLE, DISTRIBUTE, FOREIGN_CYCLE"
            )
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseSequence(args: List<String>, raw: String): ColumnGenerator {
        require(args.size in 2..3) {
            "SEQUENCE requires 2 or 3 arguments: SEQUENCE(start, step) or " +
                    "SEQUENCE(start, step, suffix). Got: '$raw'"
        }
        val start  = args[0].toLongOrNull()
            ?: throw IllegalArgumentException("SEQUENCE start must be an integer, got '${args[0]}'")
        val step   = args[1].toLongOrNull()
            ?: throw IllegalArgumentException("SEQUENCE step must be an integer, got '${args[1]}'")
        require(step != 0L) { "SEQUENCE step must not be zero" }
        val suffix = args.getOrNull(2) ?: ""
        return SequenceGenerator(start, step, suffix)
    }

    private fun parseCounter(args: List<String>, raw: String): ColumnGenerator {
        require(args.size == 1) {
            "COUNTER requires exactly 1 argument: COUNTER(start). Got: '$raw'"
        }
        val start = args[0].toLongOrNull()
            ?: throw IllegalArgumentException("COUNTER start must be an integer, got '${args[0]}'")
        return SequenceGenerator(start, step = 1L, suffix = "")
    }

    private fun parseTemplate(argStr: String, raw: String): ColumnGenerator {
        require(argStr.isNotBlank()) {
            "TEMPLATE requires a pattern argument: TEMPLATE(pattern). Got: '$raw'"
        }
        return TemplateGenerator(argStr)
    }

    private fun parseTimestampOffset(args: List<String>, raw: String): ColumnGenerator {
        require(args.size == 2) {
            "TIMESTAMP_OFFSET requires 2 arguments: TIMESTAMP_OFFSET(unit, step). Got: '$raw'"
        }
        val unit = args[0].uppercase()
        val step = args[1].toLongOrNull()
            ?: throw IllegalArgumentException(
                "TIMESTAMP_OFFSET step must be an integer, got '${args[1]}'"
            )
        return TimestampOffsetGenerator(unit, step)
    }

    private fun parseCycle(args: List<String>, raw: String): ColumnGenerator {
        require(args.size >= 2) {
            "CYCLE requires at least 2 values: CYCLE(v1, v2, ...). Got: '$raw'"
        }
        return CycleGenerator(args)
    }

    private fun parseDistribute(args: List<String>, raw: String): ColumnGenerator {
        require(args.size >= 2) {
            "DISTRIBUTE requires at least 2 weight:value pairs: " +
                    "DISTRIBUTE(weight1:value1, weight2:value2, ...). Got: '$raw'"
        }
        val pairs = args.map { arg ->
            val parts = arg.split(":")
            require(parts.size == 2) {
                "DISTRIBUTE argument must be 'weight:value', got '$arg' in '$raw'"
            }
            val weight = parts[0].trim().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "DISTRIBUTE weight must be an integer, got '${parts[0]}' in '$raw'"
                )
            Pair(weight, parts[1].trim())
        }
        val totalWeight = pairs.sumOf { it.first }
        require(totalWeight == 100) {
            "DISTRIBUTE weights must sum to 100, got $totalWeight in '$raw'"
        }
        return DistributeGenerator(pairs)
    }

    private fun parseForeignCycle(
        args: List<String>,
        raw: String,
        context: GeneratorContext?
    ): ColumnGenerator {
        require(args.size == 2) {
            "FOREIGN_CYCLE requires 2 arguments: FOREIGN_CYCLE(tableName, columnName). Got: '$raw'"
        }
        requireNotNull(context) {
            "FOREIGN_CYCLE generator requires a GeneratorContext (no context was provided)"
        }
        return ForeignCycleGenerator(
            targetTable  = args[0].trim(),
            targetColumn = args[1].trim(),
            context      = context
        )
    }
}
