
ON conflict on constraint
nextval('generic_code_id_seq')


- **One table per file.** Each data file maps to exactly one table. A file cannot contain rows for multiple tables.
- **Plain list format required.** YAML files must use a bare list at the top level. The legacy `tableName: [...]` wrapper is tolerated for compatibility but will not be supported indefinitely.

- **Single-column FK validation only.** When a `foreignKey` block lists multiple columns (e.g. a composite FK), only the **first** column in the `columns` list is checked against parent data rows at runtime. The remaining columns are declared but not validated.

- **No `RETURNING` clause.** Generated IDs or timestamps are not captured.
- **No sequences or identity columns.** Knoppen does not issue `NEXTVAL(...)` or use `DEFAULT` for auto-increment columns. Primary key values must be explicit in the data file or produced by a `GENERATOR`.

Maven Integ
kotlin Context Parameters
If a class needs a dependency for its entire lifetime, give it a constructor parameter. Context parameters are for things that vary per call chain, not per instan
Failure tests
License
Tutorial stylesheet

Java Include/rust
ADK


isSorted, isSortedBy
collection literals
explicit backing fields


1 - Write ADRs in a folder to document architectural choices. Reference them in the AGENTS.md. These will and should evolve over time.
2 - Write a decent AGENTS.md file. Explain the commands, make sure the agent runs the tests/lints/whatever like a good CI would do after every change.
3 - Make the AGENT write unit tests, functional tests, E2E tests and snapshot tests. Everything should be tested.
4 - Use some kind of grill-me skill when planning. Make the agent create a plan. Then call grill-me on it. Answer all the questions with intention. If you don't understand the questions then ask the agent to explain it better, until you understand the design choices
5 - Make small changes, small commits, commit often. If a task seems large, make the agent split it into small tasks. If you realize that the task was too big when it's too late (or there's some scope creep), then make the agent rewrite the commits so that they are readable in a chronological order, in a new branch
6 - Review the code ! Use an agent for the first pass of review, but also review it yourself.
7 - If you work in a team, use the agent to create good PRs, with a description, a walkthrough

8 - From time to time, ask the agent to evaluate the architecture choices, and spend some time refactoring if you feel it will solve an issue.

Then 9 - connect your agent to your tools (github, sentry, Linear whatever) to facilitate the tracking of tasks, issues, bugs.

The main challenge is to keep the architecture clean and documented, this helps A LOT.