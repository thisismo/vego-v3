# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build system

This is an **Amper** project (JetBrains Kotlin CLI, pinned to 0.11.0) — there is no Gradle. Use the `./kotlin` wrapper at the repo root for everything:

```sh
./kotlin build                      # compile and link the whole project
./kotlin build -m analyst           # build one module (names from `./kotlin show modules`)
./kotlin test                       # run all tests
./kotlin test -m core               # tests for one module
./kotlin test --include-classes='io.thisismo.vego.client.CommonTest'   # one test class
./kotlin test --include-test='<fully.qualified.Class.method>'          # one test method
./kotlin run -m server              # run the Ktor backend (port 8081)
./kotlin run -m analyst             # run the ACP agent (talks JSON-RPC on stdin/stdout)
./kotlin package -m indexer         # build the executable jar used by the post-commit hook
```

Modules are declared in `project.yaml`; each has a `module.yaml` (not build.gradle). Shared dependency/settings blocks live in `apps/modules/build-utils/*.module-template.yaml` and are pulled in via `apply:`. Third-party versions are pinned in the root `libs.versions.toml` and referenced as `$libs.*`. Source roots are `src/` and `test/`, with platform-specific variants like `src@android/`, `src@ios/`, `test@android/`.

## Two independent halves

### 1. `apps/` — the Vego product (Kotlin Multiplatform)

- `apps/server` — Ktor (CIO) backend on port 8081 using **kotlinx-rpc (Krpc)** over Ktor, Koin DI, and JWT auth verified against a **local Keycloak** at `localhost:8080` (realm `vegoapp`; issuers/JWKS hardcoded in `apps/modules/common/server-auth/.../installAuth.kt`). The server is a thin shell — feature logic lives in modules like `apps/modules/identity/identity-server`.
- `apps/client/core` — shared Compose Multiplatform UI + logic (KMP: android, iosArm64, iosSimulatorArm64), consumed by the thin `android-app` and `ios-app` entrypoints.
- Feature modules follow a three-way split, e.g. identity: `identity-common` (shared API types, the `IdentityApi` RPC interface), `identity-common-client` (client repository/store + SQLDelight persistence), `identity-server` (Ktor implementation). Client auth is OIDC (`kalinjul` multiplatform oidc library) against the same Keycloak.
- SQLDelight codegen runs through a custom Amper plugin: `apps/modules/sqldelight-plugin` (wired via `plugin.yaml`; `.sq` files live under `src/sqldelight/` in client modules).

### 2. `agent/` — Business-Analysis & Architecture agent (Koog + ACP)

A local pair-programmer agent built on Koog that speaks the **Agent Client Protocol over stdio** — the IDE spawns the process and exchanges newline-delimited JSON-RPC on stdin/stdout. **Logs must go to stderr** (`resources/logback.xml`); anything printed to stdout corrupts the protocol stream. Detailed docs: `agent/analyst/README.md`.

Three modules:
- `agent/analyst` — the ACP agent app. `AnalystSession.kt` is the heart: a **phase machine across ACP turns** (`INTAKE` → `AWAITING_MODERATION` → `AWAITING_FINALIZE` → `DONE`) where each `prompt` runs one phase via its own Koog state graph and the turn boundary *is* the human-in-the-loop suspension. The pipeline: hydration (long-term memory + RAG index) → domain modeling → a data-driven **persona pool** (`resources/personas.conf`, HOCON) → a deterministic **consensus engine** (`Consensus.kt`, weighted-matrix synthesis of persona verdicts, no LLM call) → spec writing (ADRs to `docs/adr/`, UX specs to `docs/ux-specs/`) → a bounded self-healing markdown-lint loop. Chat slash-commands (`/help`, `/reset`, `/memory`, `/facts`, `/forget`) are handled in `AnalystSession.kt`.
- `agent/indexing` — shared library: the single source of truth for the on-disk RAG index format (`docs/.index/rag-index.json`), with `DocIndexer` (writer) and `RagIndexReader` (reader).
- `agent/indexer` — tiny CLI run by the git **post-commit hook** (`scripts/git-hooks/post-commit` → `run-indexer.sh`) to incrementally re-index committed specs. No server involved.

Agent invariants to preserve:
- **No Git write access** — the agent never stages/commits/pushes; it writes files into the working tree for manual review.
- Per-stage LLM models are centralized in `AnalystConfig.kt` (`AnalystModelConfig`), overridable via `ANALYST_MODEL_*` env vars; retry tuning via `ANALYST_RETRY_*` (`AnalystExecutor.kt`).
- Requires `OPENAI_API_KEY` in the environment. Embeddings for `/facts` semantic search are computed agent-side and cached so the indexer stays embedding-free.
- Long-term memory persists under `.vego/memory/`.
