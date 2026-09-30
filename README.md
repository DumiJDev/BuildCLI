# BuildCLI

> **Your local AI engineering team.**
> An open-source runtime that executes a configurable team of AI agents from your terminal. 100% local: no
> account, no server, no telemetry. With a local model (Ollama) everything runs offline.

**Status: pre-1.0.** BuildCLI is being rebuilt from scratch as a runtime for teams of agents (the previous CLI is on the
[`legacy`](../../tree/legacy) branch, tag `v0.14.0`). The design is in [`docs/rfc/0001-buildcli-1.0.md`](docs/rfc/0001-buildcli-1.0.md).
Read [Known limitations](#known-limitations) before relying on it.

## The idea

```text
Developer
    │
    ▼
BuildCLI Runtime
    │
    ├── Architect (lead)
    ├── Developer
    ├── Reviewer
    └── ...
```

You describe your team as agents. The lead breaks your request into **tasks** and **hands them off** to teammates. The
**runtime, not the LLM,** enforces what each agent may do: permissions, approvals, limits, and when a task has failed.

- **Handoffs instead of chatter:** an agent delegates a task with an objective and a short brief, not a transcript.
- **Policies, not prompts:** "the reviewer cannot modify code" is enforced by the runtime ([security model](docs/security-model.md)).
- **You stay in control:** file writes are shown as diffs, commands outside the allow list ask first, and a task that
  fails is retried three times and then handed to you.
- **Everything is an event:** one append-only log in a local SQLite database feeds the UI, history and usage.

## Install

Requires **JDK 21 or newer**.

```bash
# Linux and macOS
curl -fsSL https://github.com/BuildCLI/BuildCLI/releases/latest/download/install.sh | sh

# Windows (PowerShell)
irm https://github.com/BuildCLI/BuildCLI/releases/latest/download/install.ps1 | iex
```

The installer verifies the jar's SHA-256, installs it under `~/.buildcli/bin` and adds a `buildcli` launcher; it needs
no administrator rights. Or build it yourself: `mvn verify` produces `target/buildcli.jar` (`java -jar target/buildcli.jar`).

## Quick start

```bash
cd my-project
buildcli init          # a sample team: architect (lead), developer, reviewer, plus an AGENTS.md
buildcli doctor        # checks Java, git, your configuration and whether Ollama has the model
buildcli               # opens the terminal UI and asks what the team should do
```

or without the TUI:

```bash
buildcli run --team backend --headless --approve ask "add a health endpoint to the API"
buildcli runs          # the runs of this project
buildcli task list     # the tasks and handoffs of the latest run
buildcli usage         # tokens per agent
```

`init` uses `ollama / qwen2.5:7b` by default (`buildcli init --model provider:model` to change it). Pull the model first
(`ollama pull qwen2.5:7b`) or point the team at any OpenAI-compatible endpoint. Every command is documented in the
[CLI reference](docs/reference/cli.md); agent and team files in [agents and teams](docs/reference/agents-and-teams.md);
ready-to-copy teams in [`examples/`](examples). New to the ideas? Read [concepts](docs/concepts.md); stuck? [troubleshooting](docs/troubleshooting.md).

## Known limitations

Be aware of these, they are stated plainly on purpose:

- **Model quality decides everything.** Tool-using agents need a capable model. `qwen2.5:3b` was unreliable in our
  tests (see [`docs/m2-real-model-findings.md`](docs/m2-real-model-findings.md)); use 7B or larger, or a hosted model.
  The runtime bounds what a weak model can do, but it cannot make it reliable. Support for specific models is not yet
  claimed.
- **Not a sandbox.** The command allow list reduces accidents; a command you allow still runs project code, and there is
  no network permission until the sandbox planned for 1.2. See the [security model](docs/security-model.md).
- **Terminals:** the TUI has been exercised on Linux only. CI builds and tests on Linux, macOS and Windows, including the
  installers, but nobody has driven the TUI on macOS or Windows terminals yet.
- **No cost figures:** usage is reported in tokens; no prices are assumed.
- Sequential execution: one agent runs at a time (parallel agents, sessions and memory are planned, see the RFC).

## Architecture

Ports and adapters, enforced by tests: `domain` ← `ports` ← `application` ← `infrastructure`, with LangChain4j, TamboUI,
JDBC and Jackson confined to `infrastructure`. See [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md).

## Contributing

Contributions are welcome. Read [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) and the RFC first.

## License

[MIT](LICENSE)
