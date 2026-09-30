# BuildCLI

> **Your local AI engineering team.**
> An open-source runtime that executes a configurable team of AI agents from your terminal. 100% local: no
> account, no server, no telemetry. With a local model (Ollama) everything runs offline.

**Status: pre-1.0, under active construction.** BuildCLI is being rebuilt from scratch as a runtime for teams of
agents. The previous CLI is preserved on the [`legacy`](../../tree/legacy) branch (tag `v0.14.0`). The design is in
[`docs/rfc/0001-buildcli-1.0.md`](docs/rfc/0001-buildcli-1.0.md); what has been built so far is tracked by the
milestones there (M0-M4).

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
    └── QA
```

You describe your team as agents (architect, developer, reviewer...). The lead breaks your request into **tasks** and
**hands them off** to teammates. The **runtime, not the LLM,** enforces what each agent may do: permissions, approvals,
limits and when a task has failed.

- **Handoffs instead of chatter:** an agent delegates a task with an objective and a short brief, not a transcript.
- **Policies, not prompts:** "Ana cannot modify production code" is enforced by the runtime.
- **You stay in control:** file writes are shown as diffs, commands outside the allow list need approval, and a task
  that fails is retried three times and then escalated to you.
- **Everything is an event:** one append-only log in a local SQLite database feeds the UI, history and usage.

## Try it

Requires JDK 21.

```bash
mvn verify
java -jar target/buildcli.jar demo --fake            # TUI with a scripted model: approve with y / deny with n
java -jar target/buildcli.jar demo --fake --escalate # retry x3, then the escalation dialog (r / s / a)

# with a real local model (Ollama)
java -jar target/buildcli.jar demo --model qwen2.5:3b
```

Small models need guardrails and are slow on CPU; see [`docs/m0-spike-findings.md`](docs/m0-spike-findings.md) for
measurements and for how to run the benchmark against a larger or hosted OpenAI-compatible model.

## Architecture

Ports and adapters, enforced by tests: `domain` ← `ports` ← `application` ← `infrastructure`, with LangChain4j,
TamboUI and JDBC confined to `infrastructure`. See [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md).

## Contributing

Contributions are welcome. Read [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) and the RFC first.

## License

[MIT](LICENSE)
