# Business-Analysis & Architecture Agent (ACP / Koog)

A coding agent that turns a raw product idea into formalized requirements and then into committed
architecture documents. It is built on [Koog](https://github.com/JetBrains/koog) and speaks the
[Agent Client Protocol](https://agentclientprotocol.com/) (ACP), modeled on the upstream
[`examples/acp-agent`](https://github.com/JetBrains/koog/tree/develop/examples/acp-agent) but kept on
**Amper** with this repo's pinned Koog dependencies.

## How it maps to the four workflows

The agent talks ACP over **stdio** (newline-delimited JSON-RPC on stdin/stdout). The IDE spawns this
process and opens a session; each session gets its own isolated Koog state graph
(`KoogAnalystSession`). The "Ktor server" in the design narrative is this local `Protocol` host, and
the **HitL suspensions are ACP turn boundaries**: a `prompt` runs one phase to completion, ends the
turn, and the process is idle while the IDE shows the result and waits for the user. No compute runs
between turns; the session just holds its phase and drafted artifacts until the next `prompt`.

| Workflow | Phase | What happens |
|---|---|---|
| 1 — Ingestion & Business Analysis | `INTAKE` | `hydration` node pulls contextual markdown from the workspace (a file-based stand-in for a vector store), then `business-analysis` produces a structured `RequirementsDraft` (epics + acceptance criteria + clarifying questions). |
| 2 — Native HitL Suspension | end of `INTAKE` | The clarifying questions are streamed to the IDE as a structured form and the turn ends. Session is dormant. |
| 3 — Compression & Technical Execution | `AWAITING_REQUIREMENTS` | The answered form is injected, then `nodeLLMCompressHistory` with a `FactRetrievalHistoryCompressionStrategy` distills the chat into dense facts and prunes the raw back-and-forth. `technical-design` (a tool-using subgraph) writes ADRs, an OpenAPI schema, and a C4 model to disk. |
| 4 — Validation & Teardown | `AWAITING_ARCH_REVIEW` → `DONE` | `validation` runs local shell tools to check the drafted docs; the result is streamed for a final Approve/Reject. On approval, `teardown-commit` stages and commits the files with git. |

## Configuration

Set an OpenRouter key in the environment (the agent reads `OPENROUTER_API_KEY`):

```sh
export OPENROUTER_API_KEY=sk-or-...
```

The model is `OpenRouterModels.Claude4_5Sonnet` (see `KoogAnalystSupport.kt`).

## Wiring into an ACP client

Configure your IDE's ACP integration to launch this module's `main` (`io.thisismo.vego.agent.MainKt`)
as the agent command, with `OPENROUTER_API_KEY` in its environment. Logs go to **stderr** so they
never corrupt the protocol stream on stdout.

## Source layout

- `Main.kt` — stdio `StdioTransport` → `Protocol` → `Agent` wiring.
- `KoogAnalystSupport.kt` — `AgentSupport` + `AgentSession`; the four-workflow phase machine and graphs.
- `AnalystTools.kt` — filesystem/shell tools + the hydration retriever.
- `AnalystModel.kt` — serializable structured types (`RequirementsDraft`, `ValidationReport`, …).
