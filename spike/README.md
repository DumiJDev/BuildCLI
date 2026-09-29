# BuildCLI M0 spike

Throwaway-quality code that answers the M0 questions in `DRAFT.md`. It is a standalone Maven project
(`spike/`), independent from the legacy modules.

What it proves end to end: **two agents · a handoff · tools under a policy · human approval in a TamboUI view ·
events in an embedded DB · a real local model via LangChain4j.**

## Layout (ports and adapters, as in the RFC)

```
domain          Agent, Team, Task, Event, Permissions, Limits
ports           LlmGateway, EventStore, UserInterface (+ neutral message/tool types)
application     Orchestrator (sequential, limits, retry x3 -> escalate), ToolRuntime (policy), Events
infrastructure  OllamaGateway (only class using LangChain4j), JdbcEventStore, TamboUiApp,
                ScriptedGateway (fake LLM), HeadlessUi (scripted user)
Scenario / Bench / DbBench / Main   demo scenario, benchmarks, picocli entry point
```

## Run

```bash
mvn -f spike/pom.xml package                       # tests + single shaded jar (~18 MB)
J=spike/target/buildcli-spike-0.0.1-SPIKE.jar

java -jar $J demo --fake                   # TUI with a scripted LLM (no Ollama): approve with y / deny with n
java -jar $J demo --fake --escalate        # shows retry x3 then the escalation dialog (r / s / a)
java -jar $J demo --model qwen2.5:3b       # TUI against a real local model
java -jar $J bench --runs 10 -v            # headless reliability benchmark (real model)
```

`--threads` (default 4) sets Ollama's `num_thread`. On the WSL2 machine used for the spike the default
(16 threads) ran ~50x slower than 4 (0.2 vs ~10 tokens/s for a 3B model).

## Tests

`OrchestratorTest` runs the runtime rules deterministically with a scripted LLM (no model, no terminal):
handoff, policy denial, shell strings rejected, write globs, workspace escape, capability enforcement,
invalid handoff, depth limit, retry x3 then escalate, user retry/skip, token budget, denied approval, max steps.
