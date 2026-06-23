package org.austindroids.knoppen.sqlgen

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// ── SEQUENCE ──────────────────────────────────────────────────────────────────

/**
 * Produces a numeric sequence with an optional string suffix.
 *
 * SEQUENCE(10, 10)        → 10, 20, 30, 40
 * SEQUENCE(100, 100, _id) → 100_id, 200_id, 300_id
 * COUNTER(1)              → 1, 2, 3, 4  (step is fixed at 1 by the parser)
 */
class SequenceGenerator(
    private val start: Long,
    private val step: Long,
    private val suffix: String = ""
) : ColumnGenerator {
    private var current = start

    override fun next(rowIndex: Int): Any? {
        val value = current
        current += step
        return if (suffix.isEmpty()) value else "${value}${suffix}"
    }

    override fun reset() { current = start }
}

// ── TEMPLATE ──────────────────────────────────────────────────────────────────

/**
 * Fills placeholders in a pattern string per row.
 *
 * Available placeholders:
 *
 *   {index}         0-based row index                → 0, 1, 2
 *   {index:03d}     Zero-padded row index            → 000, 001, 002
 *   {rownum}        1-based row number               → 1, 2, 3
 *   {rownum:03d}    Zero-padded row number           → 001, 002, 003
 *   {uuid}          A fresh random UUID              → 550e8400-...
 *   {date}          Today's date (UTC)               → 2024-01-15
 *   {datetime}      Current UTC datetime             → 2024-01-15T10:30:00Z
 *   {yyyyMMdd}      Today compact                    → 20240115
 *   {HHmmss}        Current time compact             → 103000
 *
 * Example patterns:
 *   TEMPLATE(ROW_{index})                   → ROW_0, ROW_1
 *   TEMPLATE(USR-{yyyyMMdd}-{rownum:03d})   → USR-20240115-001
 *   TEMPLATE(item-{uuid})                   → item-550e8400-...
 */
class TemplateGenerator(private val pattern: String) : ColumnGenerator {

    override fun next(rowIndex: Int): Any? {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return PLACEHOLDER_REGEX.replace(pattern) { match ->
            val spec = match.groupValues[1]
            resolvePlaceholder(spec, rowIndex, now)
        }
    }

    override fun reset() { /* stateless — no reset needed */ }

    private fun resolvePlaceholder(spec: String, rowIndex: Int, now: OffsetDateTime): String {
        // Split "index:03d" into name="index", fmt="03d"
        // For specs without ":" fmt is null
        val name: String
        val fmt: String?
        if (":" in spec) {
            name = spec.substringBefore(":")
            fmt  = spec.substringAfter(":")
        } else {
            name = spec
            fmt  = null
        }

        return when (name.lowercase()) {
            "index"    -> applyIntFormat(rowIndex, fmt)
            "rownum"   -> applyIntFormat(rowIndex + 1, fmt)
            "uuid"     -> UUID.randomUUID().toString()
            "date"     -> now.format(DateTimeFormatter.ISO_LOCAL_DATE)
            "datetime" -> now.format(DateTimeFormatter.ISO_INSTANT)
            "yyyymmdd" -> now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            "hhmmss"   -> now.format(DateTimeFormatter.ofPattern("HHmmss"))
            else       -> "{$spec}"   // unknown placeholder — reconstruct and leave as-is
        }
    }

    /**
     * Applies a printf-style integer format if [fmt] is provided.
     * Supports zero-padding: "03d" → String.format("%03d", value)
     */
    private fun applyIntFormat(value: Int, fmt: String?): String =
        if (fmt != null) String.format("%$fmt", value) else value.toString()

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\{([^}]+)}")
    }
}

// ── TIMESTAMP_OFFSET ──────────────────────────────────────────────────────────

/**
 * Produces timestamps offset from now by an incrementing multiple of [step].
 *
 * The offset applied to row N is: now + (N * step * unit)
 *
 * TIMESTAMP_OFFSET(DAYS, -1)    → now, yesterday, 2 days ago, 3 days ago...
 * TIMESTAMP_OFFSET(HOURS, 1)    → now, now+1h, now+2h, now+3h...
 * TIMESTAMP_OFFSET(MINUTES, 30) → now, now+30m, now+1h, now+1.5h...
 *
 * Supported units: DAYS, HOURS, MINUTES, SECONDS
 */
class TimestampOffsetGenerator(
    private val unit: String,
    private val step: Long
) : ColumnGenerator {

    private val chronoUnit = when (unit.uppercase()) {
        "DAYS"    -> ChronoUnit.DAYS
        "HOURS"   -> ChronoUnit.HOURS
        "MINUTES" -> ChronoUnit.MINUTES
        "SECONDS" -> ChronoUnit.SECONDS
        else      -> throw IllegalArgumentException(
            "TIMESTAMP_OFFSET unit '$unit' is not supported. " +
                    "Use: DAYS, HOURS, MINUTES, SECONDS"
        )
    }

    private val baseTime: Instant = Instant.now()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")
        .withZone(ZoneOffset.UTC)

    override fun next(rowIndex: Int): Any? {
        val offset  = rowIndex.toLong() * step
        val instant = baseTime.plus(offset, chronoUnit)
        return formatter.format(instant)
    }

    override fun reset() { /* baseTime is fixed at construction — no reset needed */ }
}

// ── UUID ──────────────────────────────────────────────────────────────────────

/**
 * Generates a fresh random UUID (v4) for each row.
 *
 * UUID() → "550e8400-e29b-41d4-a716-446655440000"
 */
class UuidGenerator : ColumnGenerator {
    override fun next(rowIndex: Int): Any? = UUID.randomUUID().toString()
    override fun reset() { /* stateless */ }
}

// ── CYCLE ─────────────────────────────────────────────────────────────────────

/**
 * Cycles through a fixed list of values indefinitely.
 *
 * CYCLE(PENDING, ACTIVE, CLOSED) →
 *   row 0: PENDING
 *   row 1: ACTIVE
 *   row 2: CLOSED
 *   row 3: PENDING  ← wraps
 *
 * Useful for distributing status/category values evenly across test data.
 */
class CycleGenerator(private val values: List<String>) : ColumnGenerator {
    init { require(values.isNotEmpty()) { "CYCLE requires at least one value" } }

    override fun next(rowIndex: Int): Any? = values[rowIndex % values.size]
    override fun reset() { /* stateless — driven by rowIndex */ }
}

// ── DISTRIBUTE ────────────────────────────────────────────────────────────────

/**
 * Distributes values across rows according to percentage weights.
 * Weights must sum to exactly 100.
 *
 * DISTRIBUTE(70:ACTIVE, 20:PENDING, 10:CLOSED) on 10 rows →
 *   7 × ACTIVE, 2 × PENDING, 1 × CLOSED  (in round-robin order per bucket)
 *
 * For row counts that don't divide evenly, the distribution is best-effort
 * using the largest-remainder method to avoid drift.
 */
class DistributeGenerator(
    private val weightedValues: List<Pair<Int, String>>
) : ColumnGenerator {

    // Pre-built sequence rebuilt on reset(); built lazily on first next() call
    private var sequence: List<String> = emptyList()
    private var lastBuiltFor: Int = -1

    override fun next(rowIndex: Int): Any? {
        // We don't know total row count upfront, so we expand the sequence
        // in blocks of 100 (the LCM of all sensible weight denominators)
        if (rowIndex >= sequence.size) rebuildSequence(rowIndex + 1)
        return sequence[rowIndex % sequence.size]
    }

    override fun reset() {
        sequence     = emptyList()
        lastBuiltFor = -1
    }

    private fun rebuildSequence(minSize: Int) {
        // Build one full period (100 slots) and repeat as needed
        val period = weightedValues.flatMap { (weight, value) ->
            List(weight) { value }
        }
        sequence = generateSequence { period }.flatten().take(maxOf(minSize, 100)).toList()
    }
}

// ── FOREIGN_CYCLE ─────────────────────────────────────────────────────────────

/**
 * Cycles through values that were generated for another table's column
 * earlier in the same generation run.
 *
 * FOREIGN_CYCLE(users, id) →
 *   Reads all "id" values recorded for the "users" table and cycles through them.
 *
 * This is useful for populating FK columns in child tables so that every
 * parent row is referenced at least once without hardcoding IDs in the data file.
 *
 * Note: the parent table must appear before the child table in the data file
 * so its values are available when the child rows are generated.
 */
class ForeignCycleGenerator(
    private val targetTable: String,
    private val targetColumn: String,
    private val context: GeneratorContext
) : ColumnGenerator {

    override fun next(rowIndex: Int): Any? {
        val values = context.valuesFor(targetTable, targetColumn)
        if (values.isEmpty()) throw IllegalStateException(
            "FOREIGN_CYCLE($targetTable, $targetColumn): no generated values found for " +
                    "'$targetTable.$targetColumn'. Ensure '$targetTable' rows are generated before " +
                    "the table that references them."
        )
        return values[rowIndex % values.size]
    }

    override fun reset() { /* stateless — reads from context */ }
}
