# TestPlan.md — Knoppen Unit Testing

## 1. Overview

This plan covers the full implementation of unit testing for Knoppen, starting from the skeleton
`UpsertHappyPathTest.kt` and expanding to isolated per-component specs. The project pipeline has five
independently testable layers:

```
Schema YAML  →  SchemaParser  →  DatabaseSchema
                                       ↓
Data YAML   →  DataFileLoader  →  DataFileLoadResult
                                       ↓
                              DataFileValidator  →  List<DataValidationError>
                                       ↓
                              UpsertGenerator  →  GenerationResult
                                       ↓
                              SqlDialect (PostgresDialect)  →  SQL string
```

Each layer must be testable in complete isolation before integration tests
are added. The happy-path integration test in `UpsertHappyPathTest.kt` is
the final gate, not the starting point.

---

## 2. Gap Analysis — What Must Be Built First

The `UpsertHappyPathTest.kt` file as written will not compile until the
following items exist. These are prerequisites for every test.

### 2.1 Missing Source Classes

| Symbol used in test | Status | Work required |
|---|---|---|
| `SchemaParser.parse(InputStream)` | Missing | Create new `SchemaParser` object |
| `DataValidator.validate(schema, InputStream)` | Missing | Create new `DataValidator` facade |
| `UpsertGenerator.generateStatements(InputStream)` | Private / wrong sig | Expose as public with InputStream overload |
| Schema-qualified SQL `INSERT INTO code_sample.tag` | Wrong output | Add `schemaName` to `TableSchema`; update `PostgresDialect` |

### 2.2 Missing / Misplaced Test Resources

| Test expects | Current location | Action |
|---|---|---|
| `schema/code_sample_schema.yaml` | ✅ already there | Update test references to this name |
| `schema/upsert-schema.json` | `src/main/resources/json-schema.json` | Copy to `src/test/resources/schema/upsert-schema.json` |
| `data/data_tag.yaml` | ✅ already there | None |
| `data/data_relational.yaml` | `src/test/resources/schema/data_relational.yaml` | Move to `src/test/resources/data/` |

### 2.3 Missing Imports in UpsertHappyPathTest.kt

The following imports must be added alongside the existing ones:

```kotlin
import org.austindroids.knoppen.SchemaParser
import org.austindroids.knoppen.DataValidator
import org.austindroids.knoppen.sqlgen.UpsertGenerator
import org.austindroids.knoppen.sqlgen.dialect.PostgresDialect
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
```

---

## 3. Refactoring Required for Testability

### 3.1 `SchemaParser` (new file)

**Location:** `src/main/kotlin/org/austindroids/bootstrapsqlgen/SchemaParser.kt`

**Approach:** YAML → JSON string (via Jackson) → kotlinx-serialization `Json.decodeFromString`.
This reuses the existing `@Serializable` annotations on `Schema.kt` and the
existing `tools.jackson.dataformat:jackson-dataformat-yaml` dependency with no
new library additions.

```kotlin
object SchemaParser {
    fun parse(input: InputStream): DatabaseSchema
    fun parse(yamlContent: String): DatabaseSchema  // overload for convenience
}
```

**Key details:**
- Jackson reads YAML and writes canonical JSON (`ObjectMapper(YAMLFactory()).writeValueAsString(…)`)
- `Json { ignoreUnknownKeys = true }` decodes into `DatabaseSchema`
- Sealed class `ColumnConstraint` subclasses use `@SerialName` as the `"type"` discriminator —
  this aligns exactly with the YAML `type:` field in each constraint entry
- After parsing, `schemaName` is propagated to each `TableSchema` (see §3.4 below)

### 3.2 `DataValidator` facade (new file)

**Location:** `src/main/kotlin/org/austindroids/bootstrapsqlgen/DataValidator.kt`

The existing `DataFileValidator` is already well-structured but its constructor is awkward
for tests (requires a pre-loaded `DataFileLoadResult`). `DataValidator` wraps it behind
a stream-oriented API and provides a result wrapper.

```kotlin
data class DataValidationResult(
    val errors: List<DataValidationError>
) {
    val hasErrors: Boolean
        get() = errors.any { it.severity == DataValidationError.Severity.ERROR }

    fun prettyPrint(): String
}

object DataValidator {
    fun validate(schema: DatabaseSchema, dataStream: InputStream): DataValidationResult
}
```

The `column` property accessed in the test (`warnings.first().column`) maps to
`DataValidationError.field`.  The test assertion `warnings.first().column shouldBe "approvedTs"`
means `DataValidationError` needs a `column` property alias (or the test uses `.field`).

> **Decision needed during implementation:** either rename `DataValidationError.field` to
> `column`, add a `val column get() = field` alias, or adjust the test assertion to `.field`.

### 3.3 `UpsertGenerator` — expose `generateStatements(InputStream)`

**File:** `src/main/kotlin/org/austindroids/bootstrapsqlgen/sqlgen/UpsertGenerator.kt`

Add a public overload that accepts an `InputStream` and delegates to the existing
`generate(String)` logic:

```kotlin
fun generateStatements(dataStream: InputStream): GenerationResult =
    generate(dataStream.bufferedReader().readText())
```

The existing private `generateStatements(DataFileLoadResult)` is renamed to avoid
ambiguity (e.g. `buildStatements`).

### 3.4 Schema-qualified table names

**Approach:** Add `schemaName: String` to `TableSchema`. `SchemaParser` populates it
from the top-level `DatabaseSchema.schema` after deserialization. `PostgresDialect`
uses it when building the table reference:

```kotlin
// TableSchema addition
data class TableSchema(
    val schemaName: String = "",   // populated post-parse from DatabaseSchema.schema
    val tableName: String,
    ...
)

// PostgresDialect
val tableRef = if (schema.schemaName.isBlank()) qq(schema.tableName)
               else "${qq(schema.schemaName)}.${qq(schema.tableName)}"
```

`DataRow.tableName` is left unchanged; the schema qualifier is read from
`DataRow.schema.schemaName`.

---

## 4. Test File Inventory

### 4.1 Existing (to be completed)

| File | Status | Action |
|---|---|---|
| `UpsertHappyPathTest.kt` | Skeleton — compiles after §3 work | Fix resource paths, add imports |
| `YamlParserTest.kt` | Stub — tests nothing | Replace with content from §4.2.1 |

### 4.2 New Test Files

All new tests live under `src/test/kotlin/org/austindroids/bootstrapsqlgen/`.

---

#### 4.2.1 `SchemaParserTest.kt`

**Subject:** `SchemaParser.parse(InputStream)`

Tests the YAML → `DatabaseSchema` deserialization in complete isolation.
The JSON meta-schema is NOT involved here — that is `SchemaValidatorTest`.

| Test | Assertion |
|---|---|
| Six tables loaded | `dbSchema.tables.size == 6` |
| Table order preserved | `tables.map { it.name }` in YAML declaration order |
| Table name mapping | `tag`, `users`, `post`, `post_tag`, `audit_log`, `post_approval` |
| Schema qualifier propagated | Every `TableSchema.schemaName == "code_sample"` |
| `onConflict.action` mapping | `tag.onConflict.action == DO_NOTHING` |
| `onConflict.action` mapping | `users.onConflict.action == UPDATE` |
| `excludeFromUpdate` populated | `users.onConflict.excludeFromUpdate` contains `createTs` |
| GENERATOR default parsed | `tag.columns["column_order"].default.type == GENERATOR` |
| FUNCTION default parsed | `users.columns["createTs"].default.type == FUNCTION` |
| Constraint deserialization | `tag.columns["name"]` has `RequiredConstraint`, `UniqueConstraint`, `PatternConstraint` |
| TemporalConstraint fields | `users.columns["approvedTs"]` temporal notFuture=true, notPast="-P4Y" |
| ForeignKey config | `post.columns["user_id"].foreignKey.table == "users"` |
| EnumConstraint values | `users.columns["type"]` enum has `["USER","SUPERVISOR","ADMIN"]` |
| Compound PK | `post_tag.primaryKey == ["post_id", "tag_id"]` |
| ValidationConfig defaults | `defaultNullable == true`, `strictFields == true` |

---

#### 4.2.2 `SchemaValidatorTest.kt`

**Subject:** `SchemaValidator.validate(yamlContent, jsonSchemaContent)`

Validates structural (JSON Schema) and semantic (business rules) validation in
isolation. Uses inline YAML strings so the test is self-contained.

| Test | Assertion |
|---|---|
| Valid schema passes | `result.hasErrors == false` |
| Missing `dialect` field | `result.hasErrors == true` |
| Missing `tableName` in a table | ERROR on path `/tables/0/tableName` |
| PK column not in columns list | ERROR referencing the missing column name |
| `onConflict.target` column not in columns | ERROR |
| `excludeFromUpdate` column not in columns | ERROR |
| FK to unknown table (same file) | WARNING (not ERROR) |
| Duplicate enum values | ERROR |
| Invalid regex in pattern constraint | ERROR |
| Valid notPast duration `-P4Y` | no error |
| Invalid notPast `foo` | ERROR |
| `action: update` with empty `excludeFromUpdate` | WARNING |
| `conflictTarget: true` on non-unique constraint | ERROR |
| FUNCTION default with blank value | ERROR |

---

#### 4.2.3 `DataFileLoaderTest.kt`

**Subject:** `DataFileLoader.load(String)`

Tests YAML data file parsing and line-number tracking in isolation.

| Test | Assertion |
|---|---|
| Single-table file loaded | `result.tables.keys == setOf("tag")` |
| Row count correct | `result.tables["tag"]!!.size == 6` |
| Field values parsed — integer | `row.get("id").intValue() == 1` |
| Field values parsed — string | `row.get("name").textValue() == "technology"` |
| Null field | `row.get("approvedTs").isNull == true` |
| Absent field | `row.get("column_order") == null` |
| Line index populated | `lineIndex.lineFor("tag", 0, "id") != null` |
| Multi-table file loaded | both `users` and `post` keys present |
| Non-table key ignored | top-level scalar key not treated as table |

---

#### 4.2.4 `DataFileValidatorTest.kt`

**Subject:** `DataFileValidator(dbSchema, loadResult).validate()`

Tests each validation rule in isolation using minimal inline schemas and data.
Uses MockK or hand-built `DatabaseSchema` / `DataFileLoadResult` fixtures.

| Test | Assertion |
|---|---|
| Required field missing | ERROR, field name correct |
| Required field null | ERROR |
| Nullable field absent | no error |
| Nullable field null | no error |
| INTEGER type: string value | ERROR type mismatch |
| NUMERIC type: non-number | ERROR |
| BOOLEAN type: integer | ERROR |
| VARCHAR type: integer | ERROR |
| TIMESTAMP type: invalid string | ERROR |
| TIMESTAMP type: valid ISO-8601 | no error |
| DATE type: valid local date | no error |
| Enum constraint: valid value | no error |
| Enum constraint: invalid value | ERROR, lists allowed values |
| Pattern constraint: matching value | no error |
| Pattern constraint: non-matching | ERROR |
| Temporal: `notFuture` with future date | ERROR |
| Temporal: `notFuture` with past date | no error |
| Temporal: `notPast` within window | no error |
| Temporal: `notPast` outside window | WARNING (per frank row behavior) |
| Unique constraint: first occurrence | no error |
| Unique constraint: second occurrence | ERROR |
| Unique constraint: reset between tables | different tables track independently |
| Unknown table block | ERROR |
| Unknown field with `strictFields: true` | ERROR |
| Unknown field with `strictFields: false` | no error |

---

#### 4.2.5 `GeneratorParserTest.kt`

**Subject:** `GeneratorParser.parse(expression, context?)`

Tests expression parsing only — does NOT invoke `next()`. Uses inline expressions.

| Test | Assertion |
|---|---|
| `SEQUENCE(10, 10)` | returns `SequenceGenerator` |
| `SEQUENCE(100, 100, _id)` | suffix stored |
| `SEQUENCE` missing args | `IllegalArgumentException` |
| `SEQUENCE` non-integer start | `IllegalArgumentException` |
| `SEQUENCE` step = 0 | `IllegalArgumentException` |
| `COUNTER(1)` | returns `SequenceGenerator` with step=1 |
| `COUNTER` wrong arg count | `IllegalArgumentException` |
| `TEMPLATE(ROW_{index})` | returns `TemplateGenerator` |
| `TEMPLATE()` blank pattern | `IllegalArgumentException` |
| `TIMESTAMP_OFFSET(DAYS, -1)` | returns `TimestampOffsetGenerator` |
| `TIMESTAMP_OFFSET` bad unit | caught at construction |
| `TIMESTAMP_OFFSET` non-integer step | `IllegalArgumentException` |
| `UUID()` | returns `UuidGenerator` |
| `CYCLE(A, B, C)` | returns `CycleGenerator` |
| `CYCLE` single arg | `IllegalArgumentException` |
| `DISTRIBUTE(70:X, 30:Y)` | returns `DistributeGenerator` |
| `DISTRIBUTE` weights sum ≠ 100 | `IllegalArgumentException` |
| `DISTRIBUTE` bad weight:value format | `IllegalArgumentException` |
| `FOREIGN_CYCLE(users, id)` with context | returns `ForeignCycleGenerator` |
| `FOREIGN_CYCLE` without context | `IllegalArgumentException` |
| Unknown generator name | `IllegalArgumentException` with name in message |
| Case-insensitive name | `sequence(10,10)` parses as `SEQUENCE` |

---

#### 4.2.6 `GeneratorsTest.kt`

**Subject:** All `ColumnGenerator` implementations

Tests stateful generator behavior: `next()` sequence, `reset()`, and edge cases.

| Test | Subject | Assertion |
|---|---|---|
| Produces 10, 20, 30 | `SequenceGenerator(10, 10)` | first three calls |
| Suffix applied | `SequenceGenerator(100, 100, "_id")` | `"100_id"`, `"200_id"` |
| Reset restarts | `SequenceGenerator` | after reset, returns start again |
| `{index}` placeholder | `TemplateGenerator` | 0, 1, 2 |
| `{rownum}` placeholder | `TemplateGenerator` | 1, 2, 3 |
| `{index:03d}` format | `TemplateGenerator` | `"000"`, `"001"` |
| `{uuid}` unique per call | `TemplateGenerator` | two UUIDs differ |
| `{date}` is today | `TemplateGenerator` | matches ISO local date |
| Unknown placeholder preserved | `TemplateGenerator` | `{foo}` unchanged |
| Offset applied | `TimestampOffsetGenerator(HOURS, -6)` | row1 = now-6h |
| Cycles through values | `CycleGenerator(["A","B"])` | A, B, A, B |
| Reset is no-op | `CycleGenerator` | same cycle on reset |
| 70/30 distribution | `DistributeGenerator` | 70 of 100 are value X |
| Reads context values | `ForeignCycleGenerator` | cycles through recorded values |
| No context values yet | `ForeignCycleGenerator` | `IllegalStateException` |
| Context values grow | `GeneratorContext` | `recordGeneratedValue` → `valuesFor` |

---

#### 4.2.7 `PostgresDialectTest.kt`

**Subject:** `PostgresDialect.generateUpsert(DataRow)` and `formatValue(value, type)`

Tests SQL rendering in isolation using hand-built `DataRow` / `TableSchema` instances.
No YAML parsing, no validation.

**`formatValue` tests:**

| Test | Input | Expected |
|---|---|---|
| NULL | `null`, any type | `"NULL"` |
| INTEGER | `42` | `"42"` |
| BIGINT | `Long.MAX_VALUE` | quoted string |
| NUMERIC | `"3.14"` | `"3.14"` |
| BOOLEAN true variants | `"true"`, `"yes"`, `"1"` | `"TRUE"` |
| BOOLEAN false variants | `"false"`, `"no"`, `"0"` | `"FALSE"` |
| BOOLEAN invalid | `"maybe"` | `IllegalArgumentException` |
| VARCHAR simple | `"hello"` | `"'hello'"` |
| VARCHAR single quotes escaped | `"it's"` | `"'it''s'"` |
| JSONB object | Jackson `ObjectNode` | ends with `::jsonb` |
| JSONB string escaped | `"[1,2]"` | `"'[1,2]'::jsonb"` |
| TIMESTAMP ISO-8601 | `"2024-01-15T10:00:00+00:00"` | normalized + `::timestamp` |
| TIMESTAMP pass-through on bad input | `"not-a-date"` | contains the raw string |
| DATE | `"2024-01-15"` | `"'2024-01-15'::date"` |
| Unknown type | `"foo"`, `"CUSTOM"` | falls back to quoted string |

**`generateUpsert` tests:**

| Test | Assertion |
|---|---|
| Basic INSERT columns and values | column list matches `DataRow.fields` keys |
| Schema-qualified table name | `INSERT INTO "code_sample"."tag"` |
| Unqualified name (schemaName blank) | `INSERT INTO "tag"` |
| `DO NOTHING` on PK conflict | `ON CONFLICT ("id") DO NOTHING` |
| `DO NOTHING` with explicit target | `ON CONFLICT ("name") DO NOTHING` |
| `DO UPDATE SET` with excludeFromUpdate | `createTs` absent from SET clause |
| `DO UPDATE SET` PK excluded from SET | PK column not in SET clause |
| Compound conflict target | `ON CONFLICT ("post_id", "tag_id")` |
| Column with FUNCTION default absent from row | rendered as unquoted function name |
| Column with EXPRESSION default absent | rendered as-is |
| Column with LITERAL default absent | rendered quoted |
| GENERATOR default reaching renderDefault | `IllegalStateException` |
| No insertable columns | `IllegalArgumentException` |
| DO UPDATE with all updatable cols excluded | degrades to DO NOTHING |

---

#### 4.2.8 `UpsertGeneratorTest.kt`

**Subject:** `UpsertGenerator` end-to-end for the tag table only (no external DB)

Tests that the pipeline wires correctly: load → validate → generate.
Uses inline YAML strings so fixtures are self-contained.

| Test | Assertion |
|---|---|
| Single-table result: no errors | `result.hasErrors == false` |
| Statement count matches rows | 6 statements for 6 rows |
| Table name on each statement | `result.sql.all { it.table == "tag" }` |
| Row index assigned | indices 0–5 present |
| SEQUENCE generator values ascending | step-10 increments in SQL |
| Data-file value overrides generator | explicit `column_order` in data beats SEQUENCE |
| Validation error blocks output | inject required-field violation → `sql.isEmpty()` |
| Warning does not block output | temporal warning row still produces a statement |

---

## 5. Test Resource File Layout (target state)

```
src/test/resources/
├── schema/
│   ├── code_sample_schema.yaml   (existing — renamed from upsert_schema in test)
│   └── upsert-schema.json        (copied from src/main/resources/json-schema.json)
└── data/
    ├── data_tag.yaml             (existing)
    └── data_relational.yaml      (moved from schema/ subdirectory)
```

---

## 6. Implementation Sequence

Phases must be executed in order — later phases compile against earlier ones.

| Phase | Deliverable | Blocks |
|---|---|---|
| 0 | Move/copy test resource files | All tests |
| 1a | `SchemaParser` object | SchemaParserTest, all higher phases |
| 1b | `schemaName` on `TableSchema` + PostgresDialect fix | PostgresDialectTest, UpsertHappyPathTest Step 4 |
| 1c | `DataValidationResult` + `DataValidator` facade | DataFileValidatorTest, UpsertHappyPathTest Step 3 |
| 1d | `UpsertGenerator.generateStatements(InputStream)` | UpsertHappyPathTest Step 4 |
| 2 | Fix imports + resource paths in `UpsertHappyPathTest` | UpsertHappyPathTest compile |
| 3 | `SchemaParserTest`, `SchemaValidatorTest` | — |
| 4 | `DataFileLoaderTest`, `DataFileValidatorTest` | — |
| 5 | `GeneratorParserTest`, `GeneratorsTest` | — |
| 6 | `PostgresDialectTest`, `UpsertGeneratorTest` | — |
| 7 | Replace stub `YamlParserTest` with meaningful content | — |

---

## 7. Build / Dependency Notes

No new dependencies are required. The following are already present and sufficient:

| Dependency | Used by |
|---|---|
| `io.kotest:kotest-runner-junit5` | All test specs |
| `io.kotest:kotest-assertions-core` | All assertions |
| `io.kotest:kotest-property` | Property-based tests (optional, later) |
| `io.mockk:mockk` | Mocking in DataFileValidatorTest, UpsertGeneratorTest |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | SchemaParser YAML read pass |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | SchemaParser deserialization |

---

## 8. Coverage Targets

| Package | Target line coverage |
|---|---|
| `schema` | 90% (data classes — mostly covered by serialization) |
| `validation` | 85% |
| `datafile` | 85% |
| `sqlgen` | 90% |
| `sqlgen.dialect` | 90% |
| `mojos` | 60% (Maven lifecycle coupling limits unit testing) |

JaCoCo is already configured in `build.gradle.kts`. Run `./gradlew test jacocoTestReport`
to generate HTML coverage at `build/reports/jacoco/test/html/`.

---

## 9. Open Items

- `DataValidationError.field` vs `.column` — test assertion uses `.column`; resolve with alias or test edit.
- `YamlParserTest.kt` — currently a no-op stub; will be replaced in Phase 7 with the `SchemaValidatorTest` or `SchemaParserTest` content (to be determined based on what the old `YamlParser` class was).
- Maven plugin tests (`GenerateMojoTest`, `ValidateMojoTest`) — the mojos currently contain only TODO stubs. Unit tests can verify the error-path (file not found) but full integration requires a Maven test harness; deferred until mojos have real implementations.
