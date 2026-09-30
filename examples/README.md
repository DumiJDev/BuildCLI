# Examples

Ready-to-copy teams. Copy the `.buildcli/` folder (and `AGENTS.md`) into your project, adjust the models and permissions,
then run `buildcli doctor`. The first run asks you to approve the project's agent definitions.

| Example | What it shows |
|---|---|
| [`java-maven-backend`](java-maven-backend) | What `buildcli init` generates: an architect (lead) who delegates, a developer who can write `src/**` and run `mvn`, and a reviewer |
| [`docs-team`](docs-team) | A writer limited to `docs/**` and a read-only editor; no agent can run commands |
| [`solo-reviewer`](solo-reviewer) | A single read-only agent run directly (`buildcli run --agent reviewer ...`) against a hosted model |

These files are checked by the test suite (`ExamplesTest`), so they always load with the current schema.
