package org.austindroids.knoppen.sqlgen.format

/**
 * Known SQL keywords for metadata / documentation purposes.
 *
 * The [SqlFormatter] does **not** reference this enum. It is available for:
 *  - Dialect code to reference consistent keyword strings
 *  - Future linting (e.g., "is this a root clause keyword?")
 *  - Future auto-casing or keyword validation
 *
 * @property token   the SQL keyword text
 * @property isRoot  `true` if the keyword starts a top-level statement clause
 *                   (INSERT INTO, MERGE INTO, VALUES, ON CONFLICT, etc.)
 *                   `false` for sub-clause or continuation keywords (SET, AND, AS, etc.)
 */
enum class SqlKeyword(val token: String, val isRoot: Boolean) {
    // ── ANSI DML ────────────────────────────────────────────────────────────
    INSERT_INTO("INSERT INTO", true),
    VALUES("VALUES", true),
    UPDATE("UPDATE", true),
    DELETE_FROM("DELETE FROM", true),
    MERGE_INTO("MERGE INTO", true),
    USING("USING", true),
    ON("ON", true),
    WHEN_MATCHED("WHEN MATCHED", true),
    WHEN_NOT_MATCHED("WHEN NOT MATCHED", true),
    DO_NOTHING("DO NOTHING", true),
    RETURNING("RETURNING", false),

    // ── Sub-clause / continuation ───────────────────────────────────────────
    SET("SET", false),
    AND("AND", false),
    OR("OR", false),
    THEN("THEN", false),
    THEN_UPDATE("THEN UPDATE", false),
    THEN_INSERT("THEN INSERT", false),
    AS("AS", false),

    // ── PostgreSQL extensions ───────────────────────────────────────────────
    ON_CONFLICT("ON CONFLICT", true),
    DO_UPDATE_SET("DO UPDATE SET", true)
}
