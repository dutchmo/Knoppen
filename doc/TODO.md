

maven skip variable

checksum if need regeneration


ON conflict on constraint
nextval('generic_code_id_seq')


- **One table per file.** Each data file maps to exactly one table. A file cannot contain rows for multiple tables.
- **Plain list format required.** YAML files must use a bare list at the top level. The legacy `tableName: [...]` wrapper is tolerated for compatibility but will not be supported indefinitely.

- **Single-column FK validation only.** When a `foreignKey` block lists multiple columns (e.g. a composite FK), only the **first** column in the `columns` list is checked against parent data rows at runtime. The remaining columns are declared but not validated.

- **No `RETURNING` clause.** Generated IDs or timestamps are not captured.
- **No sequences or identity columns.** Knoppen does not issue `NEXTVAL(...)` or use `DEFAULT` for auto-increment columns. Primary key values must be explicit in the data file or produced by a `GENERATOR`.

kotlin Context Parameters
If a class needs a dependency for its entire lifetime, give it a constructor parameter. Context parameters are for things that vary per call chain, not per instan

License
Tutorial stylesheet

Java Include/rust
ADK


isSorted, isSortedBy
collection literals
explicit backing fields


