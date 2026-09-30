# Concepts

BuildCLI runs a **team of agents** on your machine. This page explains the pieces and how a run unfolds.

## The pieces

| Concept | What it is |
|---|---|
| **Agent** | An identity with a role, instructions, **capabilities** and **permissions**. Not tied to a model: "ana" is the agent, the model is configuration. Defined in a Markdown/YAML file ([reference](reference/agents-and-teams.md)). |
| **Team** | A named group of agents with a **lead**, the models they run on, and run **limits**. |
| **Task** | The unit of execution: an objective, an owner, a status (`PENDING`, `RUNNING`, `WAITING_APPROVAL`, `ESCALATED`, `FAILED`, `DONE`) and a result. Persisted and traceable. |
| **Handoff** | How agents collaborate: an agent creates a *child task* for a teammate with an objective and a short brief, and gets the result back. Agents do not chat freely. |
| **Tool** | What an agent can do (read, search, write, run a command, git, hand off). Agents ask for *capabilities*; the runtime resolves the tool ([tools](reference/tools.md)). |
| **Policy** | The permissions the runtime enforces: which paths may be read or written, which commands may run, what needs approval. |
| **Event** | One entry of the append-only log of a run. The UI, history, audit and usage are all derived from it. |

**Chat is a view, not the model.** What you see in the terminal UI is a rendering of tasks, handoffs, tool calls and
approvals, which are structured data.

## The principle

> The LLM decides *what to try*. The runtime decides *whether it may, who does it, and when it stops*.

Everything that must be reliable is deterministic code, not a prompt: permissions, approvals, limits, handoff validation,
retries, redaction. The model can be wrong or be tricked by the files it reads; it cannot be given more power
([security model](security-model.md)).

## Anatomy of a run

1. You give a request. A **root task** is created for the team's **lead**.
2. The lead works with its tools. To delegate it makes a **handoff**: the runtime checks the target exists, is a teammate,
   and that the depth and count limits allow it, then runs the teammate **to completion** and returns the result.
3. Each tool call goes through the same gate: the agent must hold the capability, the tool applies the agent's policy,
   anything needing approval is shown to you (writes as a diff), output is scrubbed of secrets and delimited as data.
4. When the lead replies with no further tool call, the root task is `DONE` and the run ends.

Execution is **sequential**: exactly one agent runs at a time, so runs are reproducible and there are no write conflicts.

## When something goes wrong

A failed task (a provider error, an invalid reply, too many steps) is **retried up to three times**, continuing the same
conversation so side effects already done are not repeated; a failure caused by the agent's own behaviour is explained to
the model. If it still fails, the task is **escalated to you**: retry, skip it, or abort the run. A token budget overrun
escalates immediately. A denied approval is not a failure and is never retried.

## Interaction modes

- **Direct** (`buildcli run --agent NAME`): you talk to one agent, which cannot hand off.
- **Team** (`buildcli run --team NAME`, the default): the lead delegates through handoffs.

## Where things live

| What | Where |
|---|---|
| Agents and teams of a project | `<project>/.buildcli/agents/`, `.buildcli/teams/` |
| Project context for agents | `<project>/AGENTS.md` (information only, never configuration) |
| Your own agents and teams | `~/.buildcli/agents/`, `~/.buildcli/teams/` |
| Runs, tasks, events, usage | `~/.buildcli/projects/<id>/state.db` (SQLite), never inside the project |
| Which project definitions you approved | `~/.buildcli/trust.json` |

## Choosing a model

The runtime bounds what a model can do but cannot make a weak one reliable. Agents that use tools need a capable model:
7B and larger for local use, or any hosted OpenAI-compatible model. A 3B model passed a simple scenario once and then
failed after small prompt changes ([findings](m2-real-model-findings.md)). Qualify a model before relying on it with
`buildcli bench`, which needs a temperature above 0 and at least 10 runs to mean anything.
