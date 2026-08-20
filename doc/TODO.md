

- **One table per file.** Each data file maps to exactly one table. A file cannot contain rows for multiple tables.

- **Plain list format required.** YAML files must use a bare list at the top level. The legacy `tableName: [...]` wrapper is tolerated for compatibility but will not be supported indefinitely.

- **Single-column FK validation only.** When a `foreignKey` block lists multiple columns (e.g. a composite FK), only the **first** column in the `columns` list is checked against parent data rows at runtime. The remaining columns are declared but not validated.

- **No `RETURNING` clause.** Generated IDs or timestamps are not captured.

kotlin Context Parameters
If a class needs a dependency for its entire lifetime, give it a constructor parameter. Context parameters are for things that vary per call chain, not per instan

Noop sql  updates
License
Tutorial stylesheet

Name based restructuring 
https://blog.jetbrains.com/kotlin/2026/05/the-road-to-name-based-destructuring/

---

Java Include/rust
ADK


isSorted, isSortedBy
collection literals
explicit backing fields


