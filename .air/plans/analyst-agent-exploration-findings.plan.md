# Analyst Agent (Koog/ACP) — Exploration Findings

## Project Overview
**vego-v3** is a Kotlin Amper project with two independent halves:
1. **apps/** — Vego product (Kotlin Multiplatform with Ktor server, Keycloak auth)
2. **agent/** — Business-Analysis & Architecture agent (Koog + Agent Client Protocol/ACP over stdio)

---

## 1. SPEC-WRITING STAGE: ADRs & UX Specs Generation

### Where Specs Are Written
- **ADRs**: `docs/adr/NNNN-title.md` (Architecture Decision Records)
- **C4 Diagrams**: `docs/c4/context.md` and `docs/c4/container.md` (Mermaid blocks)
- **UX Specs**: `docs/ux-specs/component-inventory.md` + `docs/ux-specs/<screen>.md`

### Kotlin Code That Writes Them
**Primary file**: `/Users/standarduser/air/vego-v3/agent/analyst/src/AnalystSession.kt`

- **Lines 108–110**: Directory constants defined
  ```kotlin
  private val adrDir: String = "$workspaceRoot/docs/adr"
  private val c4Dir: String = "$workspaceRoot/docs/c4"
  private val uxDir: String = "$workspaceRoot/docs/ux-specs"
  ```

- **Lines 525–542** (`technicalDesign` node): The autonomous agent subgraph that writes all three spec types
  - Uses `write-file` tool (Koog built-in) to write absolute paths directly to disk
  - Prompt instructions embed directory paths and constraints
  - **No templates or examples embedded in code** — the LLM prompt itself contains the spec requirements
  - Creates relative links between documents
  - Does NOT run git

### File Naming Conventions
- **ADRs**: `NNNN-title.md` where `NNNN` is a zero-padded sequence number
  - Continues numbering after highest existing ADR (per reconciliation guidance in lines 740–743)
  - Pattern: one per key architectural decision
  - **Sections required**: Context / Decision / Consequences

- **C4 Diagrams**: Fixed names (`context.md`, `container.md`)
  - Both are Mermaid fenced code blocks using `C4Context` or `C4Container` diagram types
  - Can fallback to `flowchart` if clearer

- **UX Specs**: 
  - `component-inventory.md` lists all UI components (name, purpose, key states, context)
  - `<screen>.md` per-screen UX specs with workspace-relative internal links

### Templates & Guidance
**No hardcoded templates exist in source code.** Instead:
- The prompt at lines 453–459 instructs the model on what to produce
- `reconciliationGuidance()` at line 445 appends **design/revise loop instructions**:
  - First round: preserve & extend committed specs, continue ADR numbering
  - Revise round: update drafts in place or delete (reconcile against new directives)

### Self-Healing Validation Loop
**File**: `/Users/standarduser/air/vego-v3/agent/analyst/src/AnalystTools.kt` (lines 162–255)

**Function**: `validateMarkdownDocs()` (lines 187–236)
- **Deterministic, no-LLM validation** of:
  - Empty files
  - Broken relative links (resolves file paths, ignores http/mailto/anchors)
  - Malformed Mermaid diagrams (empty fences or unrecognized diagram types)

**Mermaid Support** (lines 173–177):
- Recognized diagram types: `graph`, `flowchart`, `sequenceDiagram`, `classDiagram`, `stateDiagram`, **`C4Context`**, **`C4Container`**, `C4Component`, `C4Dynamic`, `C4Deployment`, etc.

**Self-Healing Node** (lines 544–556):
- Agent calls `lint_markdown_docs` on each directory (adr, c4, ux-specs)
- On findings, agent fixes files with edit/write tools
- Re-lints until clean (max 3 rounds, bounded by `MAX_SELF_HEAL_ROUNDS`)
- Before user sees validation report, a **deterministic in-process re-lint** (line 560) produces the authoritative `ValidationReport` — cannot be hallucinated

---

## 2. ARCHITECTURE-MODELING OUTPUT: Domain-Driven Design Artifacts

### Domain Model Structure
**File**: `/Users/standarduser/air/vego-v3/agent/analyst/src/DomainModel.kt`

**Output Type**: `DomainModel` (serializable, persists across ACP turns)
```kotlin
data class DomainModel(
    val summary: String,                           // restatement of idea + domain
    val ubiquitousLanguage: List<UbiquitousLanguageTerm>,  // shared vocabulary
    val boundedContexts: List<BoundedContext>,    // domain decomposition
    val outOfScope: List<String>,
)
```

**Core Domain Concepts**:

1. **UbiquitousLanguageTerm** — shared vocabulary for code/docs/conversation
   - `term`: exact usage (e.g., "Order", "Shipment")
   - `definition`: precise one-to-two sentence definition

2. **BoundedContext** — cohesive domain slice with its own model
   - `name`: context name
   - `purpose`: business capability in one-to-two sentences
   - `aggregateRoots: List<AggregateRoot>` — transactional consistency boundaries
   - `integrationPoints: List<String>` — how it integrates with others (events, APIs, shared data)

3. **AggregateRoot** — consistency boundary owning entities
   - `name`: drawn from ubiquitous language
   - `purpose`: what it's responsible for
   - `entities: List<String>` — owned entities and value objects
   - `invariants: List<String>` — must-be-true constraints

### Production Stage in Pipeline
**Node 2 — Domain Modeling** (referenced in README.md and AnalystSession.kt):
1. Node 1 (Hydration): retrieves prior context
2. **Node 2 (Domain Modeling)**: LLM extracts ubiquitous language + drafts bounded contexts/aggregates
3. Node 3 (Persona Pool): broadcasts model to consensus pool

### Storage Across Turns
- Serialized as `domainModel` in-memory state variable (line 121)
- Survives across ACP turn boundaries while session is suspended
- Dropped into prompt history on resume (lines 39–40 of AnalystModel.kt)

### Output Format
- **Serialized JSON** when passed to persona evaluators (PersonaEvaluation.kt, line 111)
- **Rendered as Markdown** by `ConsensusRenderers.kt` for dashboard display
- **Distilled into ArchitectureMemo** on finalize (lines 625–626)

---

## 3. C4 DIAGRAM OUTPUT: Mermaid-Based Architecture Models

### C4 Diagram Capability
**Supported Types**:
- `C4Context` — system context diagram
- `C4Container` — container diagram within a system
- Fallback to `flowchart` if clearer

**Location**: 
- `docs/c4/context.md` — C4 Context diagram
- `docs/c4/container.md` — C4 Container diagram

**Format**: Mermaid fenced code blocks
```mermaid
C4Context
...
```

**Validation**: Mermaid linter (AnalystTools.kt, lines 173–177) recognizes diagram types and ensures non-empty fence bodies.

### No PlantUML or Structurizr
- **Not generated**: PlantUML, Structurizr JSON, D2, or other architecture formats
- Mermaid is the **only** architecture diagram format currently supported
- All C4 models are Mermaid text blocks in Markdown

---

## 4. MARKDOWN-LINT SELF-HEALING LOOP

### Linting Mechanism
**Tool**: `lintMarkdownDocs()` (AnalystTools.kt, lines 238–254)
- Domain-specific, deterministic, no-LLM validation
- Exposes as custom Koog tool (registered in executionToolRegistry, line 717)

**Validation Checks**:
1. **Empty files** — file.isBlank()
2. **Broken relative links** — resolves `[text](path)` relative to file's directory
   - Ignores: http://, https://, mailto:, /, pure anchors (#)
3. **Malformed Mermaid** — empty diagram fence or unrecognized keyword

**Return Format**:
- `"OK — no issues found."` when clean
- Numbered list of issues: `"<file>: <message>"` otherwise

**Self-Healing Flow** (AnalystSession.kt, lines 544–556):
1. Agent calls `lint_markdown_docs` on each spec directory
2. If findings → agent edits/writes files to fix
3. Re-calls linter on that directory
4. Repeat up to `MAX_SELF_HEAL_ROUNDS = 3` rounds
5. Authoritatively re-lint in-process (line 560) for deterministic report

---

## 5. ACP PROTOCOL LAYER: JSON-RPC/Stdio Session Lifecycle

### Stdio Transport & Protocol Binding
**Entry Point**: `/Users/standarduser/air/vego-v3/agent/analyst/src/Main.kt`
```kotlin
val agentTransport = StdioTransport(
    parentScope = this,
    ioDispatcher = Dispatchers.IO,
    input = BufferedInputStream(System.`in`).asSource().buffered(),
    output = BufferedOutputStream(System.out).asSink().buffered(),
    name = "agent",
)
val agentProtocol = Protocol(this, agentTransport)
Agent(agentProtocol, KoogAnalystSupport(...))
agentProtocol.start()
```
- **Newline-delimited JSON-RPC** on stdin/stdout (Koog + ACP 0.13.1)
- **Logs to stderr** (`resources/logback.xml`) to never corrupt stdout
- IDE spawns process, opens session, exchanges messages

### Session Lifecycle
**Handler**: `KoogAnalystSupport` (AnalystSupport.kt)

1. **Initialize**
   - IDE calls `initialize()` with `ClientInfo`
   - Agent returns `AgentInfo` (protocol version, capabilities, auth methods)
   - Advertises `embeddedContext: true` (can use RAG + memory context)

2. **Create Session**
   - IDE calls `createSession(SessionCreationParameters)`
   - Agent mints a fresh `KoogAnalystSession` with isolated Koog state graph
   - Session ID is a UUID; workspace root comes from `sessionParameters.cwd`

3. **Phase Machine (the Turn Boundary)**
   - Session holds `phase: Phase` enum (INTAKE, AWAITING_MODERATION, AWAITING_FINALIZE, DONE)
   - User sends `prompt()` (ContentBlock list)
   - Agent runs one phase to completion **within that ACP turn** (no background compute between turns)
   - Turn ends; phase persists; next prompt resumes from same phase

### Phase Transitions
**Turn 1 (INTAKE)**:
- Input: raw product idea + user answers to clarifying questions
- Nodes: Hydration → Domain Modeling → Persona Pool → Consensus → emit Conflict Report
- Output: HitL Pause 1 (confidence matrix for human moderation)
- Exit: Phase → AWAITING_MODERATION

**Turn 1b (AWAITING_MODERATION)**:
- Input: human directive ("proceed", counter-proposals, constraints)
- Nodes: Re-route through consensus (debate rounds, bounded to MAX_DEBATE_ROUNDS=2)
- Output: Reconcile & write specs (ADRs + C4 + UX) to disk, self-heal, emit ValidationReport
- Exit: Phase → AWAITING_FINALIZE (HitL Pause 2)

**Turn 2 (AWAITING_FINALIZE)**:
- Input: "finalize" / "looks good" / feedback for revision
- If affirmative: distill memo, flush caches, print closure
- If feedback: back to AWAITING_MODERATION (revise loop)
- Exit: Phase → DONE

**Turn 3+ (DONE)**:
- Message: "This analysis session is complete. Start a new session…"
- Can `/reset` to go back to INTAKE within the same session, or close and mint a new session

### IDE Interaction Pattern
- No client permission-request RPC (Koog 0.13.1 has it marked `internal`)
- Finalize confirmation is via **text-reply heuristic** (isAffirmative() in AnalystRenderers.kt)
- File tools (read/write/edit/delete) run with Koog's `ReadWrite` access
- Shell tool runs in **brave mode** (no interactive confirmation) — agent forbids git in the prompt

### Data Persistence Across Sessions
- **In-Memory**: domainModel, conflictReport, validationReport — cleared on `/reset` or session close
- **Long-term Memory**: `.vego/memory/<namespace>/` (MEMORY_DIR_PATH = `.vego/memory`)
  - Distilled `ArchitectureMemo` files (Markdown) per finalized session
  - Switchable namespaces: `/memory <name>` for isolated contexts
  - Readable at hydration (Node 1) by next session
- **Committed-Design RAG Index**: `docs/.index/rag-index.json`
  - Written by post-commit hook (Workflow 4, indexer CLI)
  - Read by hydration via `RagIndexReader`

---

## 6. LONG-TERM MEMORY & FINALIZATION

### Memory Distillation
**Node 9 — Finalize** (AnalystSession.kt, lines 588–662):
- Input: validated specs, domain model, consensus report
- Process: Distil into **ArchitectureMemo** (AnalystModel.kt, lines 44–55)
- Storage: Write to `.vego/memory/<namespace>/` as Markdown

**ArchitectureMemo Structure**:
```kotlin
data class ArchitectureMemo(
    val title: String,                    // what was specified
    val decisions: List<String>,          // key architecture decisions (terse)
    val constraints: List<String>,        // hard constraints, non-goals, deprecations
    val components: List<String>,         // UI components / modules / containers
    val documents: List<String>,          // relative paths of spec documents
)
```

**Persistence Function**: `persistArchitectureMemory()` (AnalystTools.kt)
- Path: `.vego/memory/<namespace>/<sessionId>-<timestamp>.md`
- No git involved

**Retrieval**: Next session reads top 5 most-recent memos (AnalystTools.kt, line 112) at hydration

---

## 7. DOMAIN MODEL & PERSONA POOL ARCHITECTURE

### Persona Pool (Decision Pool)
**Configuration**: `/Users/standarduser/air/vego-v3/agent/analyst/resources/personas.conf` (HOCON)

**Six Built-in Personas**:
1. **The Security Paranoid** (id: `security-paranoid`, weight: 1.0)
   - Focus: threat vectors, blast radius, zero-trust
   - Temperature: 0.2 (low randomness, deterministic skepticism)

2. **The Cost-Conscious PM** (id: `cost-conscious-pm`, weight: 1.0)
   - Focus: operational overhead, scope creep
   - Temperature: 0.3

3. **The SRE Pragmatist** (id: `sre-pragmatist`, weight: 1.0)
   - Focus: latency budgets, resilience, observability
   - Temperature: 0.3

4. **The DevEx Advocate** (id: `devex-advocate`, weight: 1.0)
   - Focus: maintainability, API ergonomics, cognitive load
   - Temperature: 0.4

5. **The UX Visionary** (id: `ux-visionary`, weight: 1.0)
   - Focus: interface contracts, workflow friction, WCAG
   - Temperature: 0.5

6. **The Data Architect** (id: `data-architect`, weight: 1.2)
   - Focus: schema topology, aggregate-root design, data governance
   - Temperature: 0.3
   - **Highest weight** (1.2 vs others' 1.0) — weighted-matrix consensus gives extra vote

### Pool Evaluation Mechanics
**File**: `/Users/standarduser/air/vego-v3/agent/analyst/src/PersonaEvaluation.kt`

**Execution Model**:
- Each persona fans out as an **independent `PromptExecutor.executeStructured()` call**
- Own prompt, own temperature, own model
- Concurrent via `async`/`awaitAll` (true isolation, not serial)
- One persona's failure → neutral `APPROVE_WITH_CONCERNS` + error recorded

**Output per Persona**: `PersonaEvaluation`
```kotlin
data class PersonaEvaluation(
    val verdict: Verdict,              // APPROVE, APPROVE_WITH_CONCERNS, BLOCK
    val overallConfidence: Int,        // 0–100
    val assessments: List<ContextAssessment>,  // per-bounded-context scores
    val concerns: List<String>,        // concrete issues
    val counterProposals: List<String>,        // constructive alternatives
)
```

### Consensus Synthesis
**File**: `/Users/standarduser/air/vego-v3/agent/analyst/src/Consensus.kt`

**Algorithm**: `WeightedMatrixConsensus`
- Per-context weighted confidence = Σ(persona.weight × context.confidence) / Σ(persona.weight)
- Hard block if any persona blocks OR weighted confidence < threshold (default 60)
- No extra LLM call — purely deterministic synthesis

**Output**: `ConflictReport`
```kotlin
data class ConflictReport(
    val strategy: String,              // "weighted-matrix"
    val overallWeightedConfidence: Double,
    val deadlocked: Boolean,           // any blocker or sub-threshold context
    val contexts: List<ContextConsensus>,
    val blockers: List<String>,        // hard blockers from BLOCK verdicts
    val openConcerns: List<String>,    // non-blocking concerns
    val counterProposals: List<String>,
    val matrix: List<PersonaScoreRow>, // dashboard confidence matrix
)
```

### Design/Revise Reconciliation
**Guidance Function**: `buildReconciliationGuidance()` (AnalystSession.kt, lines 730–765)

**First Round**: Preserve committed specs, extend with new ADRs (continue numbering)
**Revise Round**: Reconcile rejected drafts against new directive
  - Update in place (edit-file)
  - Delete (delete_spec_doc) stale drafts
  - Never orphan

---

## 8. RAG INDEX & COMMITTED-DESIGN MEMORY

### Committed-Design Index Format
**File**: `docs/.index/rag-index.json` (maintained by post-commit hook)
**Shared Model**: `/Users/standarduser/air/vego-v3/agent/indexing/src/io/thisismo/vego/agent/indexing/RagIndex.kt`

```kotlin
data class RagIndex(
    val version: Int = 1,
    val documents: MutableMap<String, IndexedDoc> = mutableMapOf(),
)

data class IndexedDoc(
    val path: String,                  // workspace-relative path
    val title: String,                 // from first h1
    val summary: String,               // first non-heading, non-list line
    val headings: List<String>,        // all headings (up to 20)
    val commit: String,                // git commit hash
    val chars: Int,                    // file size
    val indexedAt: String,             // ISO timestamp
)
```

### Indexing Workflow (Workflow 4)
**CLI**: `/Users/standarduser/air/vego-v3/agent/indexer/src/io/thisismo/vego/agent/indexer/MainKt`
**Hook**: `scripts/git-hooks/post-commit` → `run-indexer.sh`

**Indexed Roots** (DocIndexer.kt, line 9):
- `docs/adr/**/*.md`
- `docs/ux-specs/**/*.md`
- **Not indexed**: `docs/c4/` (Mermaid diagrams are architecture docs, not specifications for the index)

**Incremental Update**:
- Runs after every commit in background
- Shells out to `git show --name-status` to detect changed files
- Upserts/deletes documents, rewrites JSON
- No server, no API key needed (embedding-free)

### Semantic Search (`/facts` Command)
**File**: `/Users/standarduser/air/vego-v3/agent/analyst/src/SemanticIndex.kt`

**Sidecar**: `docs/.index/embeddings.json`
- Vectors computed agent-side (where API key lives)
- Cached per document + commit hash
- Lazy embedding on first query, re-embed only if doc edited

**Query Flow**:
1. User sends `/facts <query>`
2. Agent searches RAG index + embeddings for top-K matches
3. Renders results with similarity scores

---

## 9. ROADMAP & FUTURE WORK (from README.md)

### Explicitly Noted Opportunities
From README.md lines 27–32 (re: native "Finalize" button):
- **ACP 0.13.1 limitation**: session/request_permission RPC is marked `internal` in Koog
- When Koog surfaces `session/request_permission`, `runFinalize` can swap text-reply heuristic for native option button
- No surrounding graph changes needed

### Architecture Notes for v2 Planning
1. **Phase Machine Extensibility**: New phases can be inserted between existing ones; agents already handle AWAITING_MODERATION → design/revise cycles
2. **Persona Pool Modularity**: Personas are data-driven (HOCON); adding roles is config-only
3. **Consensus Strategies**: Pluggable `ConsensusStrategy` interface (currently `WeightedMatrixConsensus`); Round-Robin and Unanimous Gate noted as future alternatives
4. **Embedding Model**: Can swap backend (e.g., vector DB) behind `RagIndexReader` localized change
5. **C4 Diagram Expansion**: Currently Mermaid-only; PlantUML or Structurizr would need new diagram renderers + validator rules
6. **Git Integration**: Explicitly kept out of agent (no write access); post-commit hook is the clean boundary

---

## 10. FILE SUMMARY FOR V2 ARCHITECTURE MODELING

### Core Agent Files
| File | Purpose |
|------|---------|
| `/Users/standarduser/air/vego-v3/agent/analyst/src/Main.kt` | Stdio transport + Protocol + ACP agent wiring |
| `AnalystSupport.kt` | ACP session lifecycle (initialize, createSession) |
| `AnalystSession.kt` | **Phase machine** (INTAKE → AWAITING_MODERATION → AWAITING_FINALIZE → DONE) + all node definitions |
| `AnalystModel.kt` | `ValidationReport`, `ArchitectureMemo` (serializable artifacts) |
| `DomainModel.kt` | `DomainModel`, `BoundedContext`, `AggregateRoot`, `UbiquitousLanguageTerm` |
| `PersonaEvaluation.kt` | `PersonaEvaluation`, `PersonaPool`, concurrent fan-out to personas |
| `Consensus.kt` | `ConflictReport`, `WeightedMatrixConsensus` synthesis (deterministic, no-LLM) |
| `AnalystTools.kt` | `validateMarkdownDocs()`, `lintMarkdownDocs()`, hydration, memory, reconciliation |
| `AnalystRenderers.kt` | Dashboard/markdown renderers for domain models, conflict reports, memos |
| `AnalystConfig.kt` | Per-stage model selection + environment overrides |
| `AnalystExecutor.kt` | Retry tuning for LLM calls |
| `SemanticIndex.kt` | `/facts` semantic search + embeddings sidecar |
| `resources/personas.conf` | **Data-driven persona definitions** (HOCON) |
| `resources/logback.xml` | Logging config (stderr only) |

### Indexing & Memory
| File | Purpose |
|------|---------|
| `agent/indexing/src/.../RagIndex.kt` | **Shared model**: `RagIndex`, `IndexedDoc` format |
| `agent/indexing/src/.../DocIndexer.kt` | Post-commit hook writer (incremental indexing) |
| `agent/indexing/src/.../RagIndexReader.kt` | Reader for hydration |
| `agent/indexer/src/.../MainKt` | CLI entry for `post-commit` hook |

### Generated Artifacts (Not in Repo)
- `docs/adr/NNNN-title.md` — ADRs written by agent
- `docs/c4/context.md`, `docs/c4/container.md` — Mermaid C4 diagrams
- `docs/ux-specs/component-inventory.md`, `docs/ux-specs/<screen>.md` — UX specs
- `docs/.index/rag-index.json` — Committed-design RAG index (post-commit hook)
- `docs/.index/embeddings.json` — Embeddings sidecar
- `.vego/memory/<namespace>/*.md` — Long-term memory memos

---

## 11. MERMAID C4 SUPPORT & EXPANSION OPPORTUNITIES

### Current Implementation
- **Diagram Types**: `C4Context`, `C4Container`, fallback to `flowchart`
- **Format**: Fenced Mermaid blocks in Markdown
- **Validation**: Keyword recognition + non-empty body check
- **Integration Points**: None — C4 diagrams are architecture output, not indexed in RAG

### Future Expansion (v2+ Candidates)
1. **Additional Mermaid C4 Types**: `C4Component`, `C4Dynamic`, `C4Deployment` (already in validator keyword list)
2. **PlantUML C4 Export**: Parallel to Mermaid, or as conversion layer
3. **Structurizr JSON Export**: Machine-readable architecture DSL (can be re-rendered as Structurizr diagrams, D2, SVG)
4. **Component-Level ADRs**: One ADR per component (currently one ADR per decision, which might span components)
5. **API/Integration Spec Diagrams**: Sequence diagrams of cross-context interactions (already Mermaid-capable)
6. **Deployment Topology**: Using `C4Deployment` for infrastructure-level decomposition

### Validator Enhancement Points
- Could add Mermaid syntax validation (beyond keyword check)
- Could validate C4 element naming conventions
- Could cross-check diagrams against domain model (components match aggregates, contexts match bounded contexts)
