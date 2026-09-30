# Agents, teams and project context (schema 1)

BuildCLI reads its configuration from files you can commit with the project. Everything is validated strictly when
loaded: a typo in a key or a permission is an **error** (never silently ignored), and **all** problems are reported at
once.

## Where files live

| Path | What | Committed? |
|---|---|---|
| `AGENTS.md` (project root) | Project **context** for humans and agents: build commands, architecture, conventions. Read as information only; it can never change an agent's role or permissions. Truncated at 8,000 characters. | Yes |
| `.buildcli/agents/*.md`, `*.yaml` | Agent definitions of the project | Optional |
| `.buildcli/teams/*.yaml` | Team definitions of the project | Optional |
| `~/.buildcli/agents`, `~/.buildcli/teams` | Global (per-user) definitions. `BUILDCLI_HOME` overrides `~/.buildcli`. | n/a |
| `~/.buildcli/projects/<id>/state.db` | Operational state (runs, tasks, events), keyed by project. **Never inside the project tree.** | Never |

Project definitions override global ones with the same name. (`.agents/` compatibility is planned for 1.4.)

## Agent

Either a Markdown file with YAML front matter (the body is appended to `instructions`) or a plain YAML file.

```markdown
---
schema: 1
name: bruno
role: developer
instructions: |
  You implement tasks with your tools, then report what you did.
capabilities: [filesystem.read, filesystem.write, command.execute]
permissions:
  filesystem:
    read: ["**"]          # default: the whole workspace
    write: ["src/**"]     # default: nothing
  command:
    allow:                # argv arrays, never shell strings
      - ["mvn", "test"]
      - ["./mvnw", "-q", "verify"]
    timeout: 10m          # 500ms | 30s | 10m | 1h (default 30s)
---
Prefer small, focused changes.
```

| Key | Required | Notes |
|---|---|---|
| `schema` | yes | Must be `1`. |
| `name` | yes | `[a-z][a-z0-9_-]*` |
| `role` | yes | Free text (architect, developer, reviewer...). |
| `instructions` | no | Behaviour and constraints. |
| `capabilities` | no | See below. Unknown names are rejected. |
| `permissions` | no | `filesystem.read`, `filesystem.write` (glob lists), `command.allow`, `command.timeout`. |
| `description`, `personality`, `memory` | no | Accepted; `personality` is style only, `memory` arrives in 1.1. |

**Capabilities**: `filesystem.read`, `filesystem.write`, `search`, `git.read`, `command.execute`, `agent.handoff`.
An agent asks for capabilities, never for tool names; the runtime resolves the tool.

Notes on permissions:

- `command.allow` entries match the **start** of the argv. `["mvn", "test"]` allows `mvn test -q` but not `mvn deploy`.
  A command that is not on the list needs your approval each time.
- An allowed command still runs project code (plugins, test code). The allow list reduces accidents; it is **not a
  sandbox**.
- `permissions.network` is **rejected** in 1.0: it cannot be enforced without the command sandbox planned for 1.2, and
  an unenforced permission would be misleading.

## Team

```yaml
schema: 1
name: backend
lead: ana                  # receives your request and delegates
agents: [ana, bruno, carla]
runtime:                   # which model serves which agent (runtime configuration, not part of the agent)
  default: { provider: ollama, model: qwen3-coder }
  ana:     { provider: openai, model: <model-id> }
limits:                    # all optional; defaults shown
  max_retries: 3                 # 0..10, retries before escalating to you
  max_steps: 12                  # 1..100, model calls per attempt
  max_depth: 3                   # 1..10, handoff nesting
  max_tokens_per_task: 30000     # >= 1000, hard budget per task
  max_handoffs_per_attempt: 3    # 1..20
```

Providers: `ollama`, `openai` (any OpenAI-compatible endpoint).

## Versioning

Every file carries `schema`. A file with a newer schema than the running BuildCLI is refused with a clear message.
The state database is versioned separately (`PRAGMA user_version`); a database written by a newer BuildCLI is refused
and left untouched.
