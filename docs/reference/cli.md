# CLI reference

`buildcli` with no arguments opens the terminal UI on a terminal, and prints the help otherwise.
Exit codes: **0** success, **1** the work failed or was aborted, **2** usage or configuration error.

| Command | What it does |
|---|---|
| `buildcli init [--model provider:model]` | Creates `.buildcli/` with a sample team (architect/lead, developer, reviewer) and an `AGENTS.md`. Never overwrites existing files. The files it writes are pre-approved; anything you add later asks again. |
| `buildcli agent list` / `show <name>` / `create <name> [--role R] [--capabilities a,b] [--global]` | Inspect and scaffold agents. |
| `buildcli team list` / `show <name>` / `create <name> --agents a,b [--lead x] [--model p:m] [--global]` | Inspect and scaffold teams. |
| `buildcli run [REQUEST...]` | Runs a request. Options below. |
| `buildcli runs [--limit N]` | The recent runs of this project. |
| `buildcli task list [--run ID]` / `task show <n> [--run ID]` | The tasks and handoffs of a run (default: the latest), and one task with its events. |
| `buildcli usage [--run ID] [--json]` | Tokens per agent for a run, derived from the event log. |
| `buildcli provider list` / `login <provider> [--stdin]` / `logout <provider>` / `add <name> --url <url>` / `test <provider:model>` | The places models come from. `login` saves the provider's API key on this computer (asked hidden, or the first line of standard input with `--stdin`) so no variable has to be set before starting; `logout` forgets it. A set environment variable wins over a saved key. Inside the app, `/connect` does the same step by step. |
| `buildcli doctor` | Checks Java, git, the state directory, the configuration and the model providers. Exits 1 if something is broken; an unreachable Ollama is only a warning. |
| `buildcli config` | Where configuration and state live, and the provider endpoints. For each key it says only whether it is set, and whether from the environment or saved by you (never the key). |

## `run`

```bash
buildcli run --team backend "add a health endpoint"        # team: the lead delegates via handoffs
buildcli run --agent carla "review the last commit"        # direct: one agent, no handoffs
buildcli run --headless --approve writes "fix the typo"    # plain text, no TUI
```

| Option | Meaning |
|---|---|
| `--team NAME` / `--agent NAME` | The team (default: the only team) or a single agent. A direct run uses the model of the first team that includes the agent. |
| `--model provider:model` | Model for agents the team did not configure, e.g. `ollama:qwen2.5:7b` or `openai:gpt-x`. |
| `--headless` | Plain-text output. Used automatically when there is no terminal. |
| `--approve ask\|none\|writes\|all` | How approvals are decided **without the TUI**. `ask` prompts on the console (default on a terminal); `none` denies everything that needs approval (default without a terminal); `writes` approves file writes only; `all` approves everything, only for throwaway workspaces. |
| `--no-stream`, `--threads N`, `--temperature T` | Model settings. `--threads` is Ollama's `num_thread` (default 4; Ollama's own default of 16 was ~50x slower on a WSL2 machine). |

Before anything runs, agents defined by the project are checked against the trust file. The first time, or after any change
to `.buildcli/`, you are shown what they may do and asked to approve. Without the TUI this is decided like any other
approval: `ask` prompts you, `all` approves it, and `none` and `writes` **deny** it, so an unattended script can never be
talked into trusting changed definitions.

### The terminal UI

Team panel (who is working or waiting for you), the task/handoff tree, the event log, the live output of the agent that is
generating, and a status bar with token usage. Approvals show the diff; a task that failed three retries asks you to
**retry**, **skip** or **abort**.

| Key | Action |
|---|---|
| `y` / `n` | Approve / deny the request on screen |
| `r` / `s` / `a` | Retry / skip / abort an escalated task |
| `q` (after the run) or `Ctrl+C` | Quit |

### Environment

`OLLAMA_HOST` (default `http://localhost:11434`), `OPENAI_BASE_URL`, the API-key variables of the providers
(`OPENROUTER_API_KEY`, `OPENAI_API_KEY`, ...; never printed, and a set one wins over a key saved with `provider login` or in the app),
`BUILDCLI_HOME` (default `~/.buildcli`), `BUILDCLI_MOUSE=0` (turn the mouse off).
