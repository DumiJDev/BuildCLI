# M0 spike findings

Measured on: WSL2, 16 logical CPUs, 7.4 GB RAM, no GPU, Java 25 (compiled with `--release 21`),
Ollama + `qwen2.5:3b` (the only model installed). TamboUI 0.5.0, LangChain4j 1.20.2.

## M0 acceptance criteria (from DRAFT.md)

| Criterion | Status |
|---|---|
| Handoff scenario completes with a local model in ≥ 8 of 10 runs | **Partly.** 5/5 runs OK, but see caveat 1: not a meaningful statistic yet |
| Approval view works in TamboUI on Linux, macOS, Windows | **Linux only** (tmux, shaded JAR). macOS/Windows not tested |
| TamboUI and DB decisions documented | Done below |
| Builds in CI on Linux/macOS/Windows | **Done for the spike.** `Spike CI` passes on ubuntu, macos and windows (17 tests, SQLite native loads on each). See caveat 7 |

## What the spike proves (all working)

- Two agents, handoff as a child task, tools under policy, human approval, events in an embedded DB, real model.
- 17 deterministic tests (scripted LLM, no terminal) cover: handoff, allow-list, shell strings rejected,
  write globs, workspace escape, capability enforcement, invalid handoff, depth limit, handoff cap,
  retry x3 then escalate, user retry/skip, token budget, denied approval not retried, max steps, empty-reply nudge.
- TamboUI: approval dialog (y/n), escalation dialog (r/s/a), task panel, event log, token status bar; the
  orchestrator runs on a worker thread and blocks on a future completed by the key handler. Works in the shaded JAR.

## Decisions to take

### SQLite vs H2: **decided: SQLite (WAL)** (measured with the same JDBC store, 2000 appends; H2 was removed from the spike afterwards)

| | open | per-event append | native lib in JAR |
|---|---|---|---|
| H2 (`WRITE_DELAY=0`) | 13 ms | 0.39 ms | none (2.6 MB, pure Java) |
| SQLite (WAL + `synchronous=NORMAL`) | 39 ms | 0.44 ms | ~11.4 MB, per-OS natives |
| H2 default / SQLite default | | 0.11 ms / **17.9 ms** | |

Performance is a tie once both are durable. **Never use SQLite's default sync mode for the event log** (fsync per
insert, ~40x slower). The real choice is trade-offs, not speed: H2 = smaller, pure Java, no native loading, but its
file format has changed across major versions (export/import to upgrade); SQLite = stable file format, inspectable
with the `sqlite3` CLI (good for an audit log), but ~9 MB larger and needs per-OS natives.
**Decision: SQLite with WAL** because the event log is meant to outlive versions and be inspected. Switching
is cheap (`JdbcEventStore` takes a URL), so this is reversible.

### TamboUI as the 1.0 interface

Works and is usable: layout, dialog overlay, key handling, tick-driven redraw from a worker thread. Points to know:
- It is only on Maven Central since 0.x; 0.5.0 is current. APIs change between minors: pin the version.
- JLine's native loader prints a `--enable-native-access` warning on Java 24+; the launcher script should pass the flag.
- Not yet verified: macOS and Windows terminals, resize, streaming text, long/complex diffs, mouse.
- Recommendation: proceed, but make macOS + Windows terminal checks the first M3 task, not the last.

## Findings that change the RFC

1. **Ollama default threads (16) was ~50x slower than 4** on this WSL2 box (0.2 vs ~10 tokens/s). Expose `num_thread`
   and document it; consider auto-detecting physical cores.
2. **Small models need runtime guardrails the RFC did not list.** Each was found by a real failing run:
   - Empty reply right after a tool result: added *one nudge per attempt* before failing (`AgentNudged` event).
   - Lead agent delegated in a loop and never answered: added `maxHandoffsPerAttempt` (default 3) and a completion
     hint appended to every handoff result.
   - Retrying a whole task **repeats its side effects** (and re-asks for approval). Retry granularity matters; M1
     should decide between restart-from-scratch and resume-from-last-tool-result.
3. **Policy enforcement worked against a real model:** it tried to write `backend/README.md` (outside `out/**`) and
   was denied by the runtime every time, without asking the user.
4. **Cost of a "team" is visible:** one trivial scenario = 5.6k tokens over 3 model calls per agent (~2.5 min at
   10 tok/s on CPU). Scoped context and handoff briefs matter.

## Caveats (read before trusting the numbers)

1. **5/5 is effectively n=1.** Temperature is 0, so every run produced identical token counts (5,618). The "≥ 8 of
   10" criterion needs a varied scenario set or non-zero temperature to mean anything.
2. **The 5/5 came after three runtime fixes** that earlier failing runs exposed, plus a tightened lead prompt.
   The 3B model is borderline as a lead; the RFC's "minimum supported models" question is still open. A 7B+ model
   (not installed; this machine has ~1 GB free RAM) should be benchmarked before choosing.
3. The TUI was exercised with the scripted LLM only, not with the real model.
4. The diff shown for approval is naive (`-` old lines, `+` new lines), not a real diff.
5. Known security gaps left for M2: no symlink check on workspace paths (use `toRealPath`); allow-list matches an
   argv *prefix*, so an entry like `["cat"]` would allow any file; no secret redaction; commands still run project
   code. The command policy is not a sandbox, as the RFC already says.
6. Windows/macOS untested for everything (process spawning, paths, glob matching with `\`).
7. CI on Windows passes, but the tests run real `cat`/`ls` processes and GitHub's Windows runners ship Git for
   Windows (which provides them). A plain Windows machine will not have them: M2 needs a cross-platform command
   fixture. CI only proves build + tests; the TamboUI TUI itself still has not been driven on macOS/Windows.
8. The repo's inherited workflows were built for the legacy CLI and were failing on this PR (legacy `core` test did not
   compile, Checkstyle `sun_checks` reported ~4,750 findings, `labeler.yml` was invalid for labeler v5). Since the
   project starts from scratch they were reworked: one `ci.yaml` (build + test + Checkstyle + smoke test on 3 OSes),
   a lean `spike/checkstyle.xml` enforced by `mvn verify`, CodeQL without the legacy build, `workflow-lint.yaml`
   (actionlint + zizmor), a valid labeler config, and a fix for the undefined `$PR_NUMBER` in
   `close-issue-on-pr-merge.yaml`. `release.yaml` still packages the legacy CLI and is left for M3 (packaging).
9. The OpenAI-compatible gateway (`--provider openai`) compiles and is wired into `bench`/`demo`, but it is **not yet
   validated against a real endpoint**. The only attempt, through Ollama's own `/v1` on the spike box, timed out on
   every request: `/v1` cannot set `num_thread`, so Ollama fell back to 16 threads (the ~50x slowdown from finding 1).
   That is a property of this machine, not of the gateway. Validate it against a 7B+ model on another host, with
   `--temperature` above 0 (the README has the commands).
