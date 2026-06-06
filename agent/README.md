# Business-Analysis & Architecture Agent (ACP / Koog)

A local pair-programmer that turns a raw product idea into formalized requirements and then into
**validated specification documents written straight into your working directory** — for you to
review and commit manually. It is built on [Koog](https://github.com/JetBrains/koog) and speaks the
[Agent Client Protocol](https://agentclientprotocol.com/) (ACP), modeled on the upstream
[`examples/acp-agent`](https://github.com/JetBrains/koog/tree/develop/examples/acp-agent) but kept on
**Amper** with this repo's pinned Koog dependencies.

The agent has **no Git write access**. It never stages, commits, or pushes — it prepares workspace
changes and hands them back for your manual staging, keeping you fully in control of history.

## How it maps to the rethought workflows

The agent talks ACP over **stdio** (newline-delimited JSON-RPC on stdin/stdout). The IDE spawns this
process and opens a session; each session gets its own isolated Koog state graph
(`KoogAnalystSession`). The **HitL suspensions are ACP turn boundaries**: a `prompt` runs one phase
to completion, ends the turn, and the process is idle while the IDE shows the result and waits for
the user. No compute runs between turns; the session just holds its phase and drafted artifacts.

| Workflow | Phase | What happens |
|---|---|---|
| 1 — Local workspace writing | `INTAKE` → `AWAITING_REQUIREMENTS` → spec writing | `hydration` retrieves context (long-term memory + the committed-design index + workspace markdown); `business-analysis` produces a structured `RequirementsDraft`; after the answered form, `technical-design` writes **ADRs to `docs/adr/`** and a **UI component inventory / UX specs to `docs/ux-specs/`**, directly into the uncommitted working directory. |
| 2 — Pre-review self-healing validation | within `AWAITING_REQUIREMENTS` turn | A **deterministic** linter (`lint_markdown_docs`) checks the drafted docs for broken relative links / empty files. The agent repairs the files and re-lints, up to a bounded number of rounds, *before* alerting you. An authoritative in-process re-lint produces the `ValidationReport` the user finally sees, so it can't be hallucinated. |
| 3 — ACP finalize confirmation | `AWAITING_FINALIZE` → `DONE` | The validated specs are streamed to chat with the file paths to diff. Reply **finalize** / "looks good" (or send feedback to revise). On finalize the agent distils the choices into a durable `ArchitectureMemo` in long-term memory (`.vego/memory/`), flushes its in-memory caches, and prints *"Specification finalized. Session closed. Ready for your manual Git commit."* — **no git is run.** |
| 4 — Post-commit synchronization | out of process | When you `git commit`, `.git/hooks/post-commit` runs the local `indexer` CLI (no server), which incrementally re-indexes the committed specs into `docs/.index/rag-index.json`. Workflow 1's hydration reads that index next time. |

> On the native "Finalize" button: Koog's `agents-features-acp` (ACP 0.13.1) exposes event streaming
> but not the client permission-request RPC (it's `internal`), so the confirmation is the
> turn-boundary **text** reply the spec also allows ("…or confirm via text"). When Koog surfaces
> `session/request_permission`, `runFinalize` can swap the text check for a native option button with
> no change to the surrounding graph.

## The lifecycle as you experience it

1. Pitch an idea in the IntelliJ AI Chat.
2. Answer a few clarifying questions in the chat.
3. The agent drops ADRs + UX specs into your project tree and self-heals them.
4. Review the new files in the Git tool window, reply **finalize**, and the session tears down.
5. Run `git commit` whenever you're ready; the post-commit hook quietly re-indexes what you committed.

## Configuration

Set an OpenAI key in the environment (the agent reads `OPENAI_API_KEY`):

```sh
export OPENAI_API_KEY=sk-...
```

Per-stage models are pinned in `KoogAnalystSupport.kt` (`businessAnalysisModel` / `technicalDesignModel`
/ `validationModel` / `finalizeModel`) and switched per node via `changeModel`.

Build the indexer once (the post-commit hook launches its executable jar), then install the hook
(it lives outside version control under `.git/`):

```sh
./kotlin package -m indexer                                 # produces the indexer executable jar
cp scripts/git-hooks/post-commit .git/hooks/post-commit && chmod +x .git/hooks/post-commit
```

Indexing now runs entirely locally — there is **no server to keep running**. Each `git commit` fires
the hook, which runs `run-indexer.sh <repo> <commit>` in the background to update
`docs/.index/rag-index.json`.

## Wiring into an ACP client

Configure your IDE's ACP integration to launch this module's `main`
(`io.thisismo.vego.agent.MainKt`) as the agent command, with `OPENAI_API_KEY` in its environment.
Logs go to **stderr** (see `resources/logback.xml`) so they never corrupt the protocol stream on
stdout.

## Source layout

The agent is split across three modules:

**`agent/`** — the ACP agent app:
- `Main.kt` — stdio `StdioTransport` → `Protocol` → `Agent` wiring.
- `AnalystSupport.kt` — `AgentSupport`; advertises capabilities and mints a session per ACP session.
- `AnalystSession.kt` — `AgentSession`; the phase machine and the per-stage Koog graphs.
- `AnalystRenderers.kt` — pure presentation helpers for the artifacts streamed to chat.
- `AnalystTools.kt` — filesystem/lint/shell tools, the hydration retriever, and long-term memory.
- `AnalystModel.kt` — serializable structured types (`RequirementsDraft`, `ValidationReport`, `ArchitectureMemo`, …).

**`indexing/`** — the shared committed-design RAG index domain (the single source of truth for the
on-disk index format): the `RagIndex` model, the canonical path, `DocIndexer` (writer) and
`RagIndexReader` (reader). The agent depends on it for hydration; the indexer depends on it to write.

**`indexer/`** — the tiny Workflow-4 CLI (`io.thisismo.vego.agent.indexer.MainKt`) that the
post-commit hook runs to re-index committed specs. It replaces the former server HTTP endpoint, so
local indexing needs no running server.
