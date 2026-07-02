

Unsupported features
SQL format
Maven Integ
kotlin Context Parameters

Failure tests
License
Tutorial stylesheet

Java Include/rust


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