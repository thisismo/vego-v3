package io.thisismo.vego.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The Domain-Driven-Design artifacts produced by the modeling stage (graph Nodes 1–2) and carried,
 * as a serializable payload, through the consensus pool and across the suspended ACP turns.
 *
 * Node 1 (Context Hydration) grounds the analysis in retrieved domain context and extracts the
 * [UbiquitousLanguageTerm] dictionary; Node 2 (Domain Modeling) drafts the [BoundedContext]s and
 * their [AggregateRoot]s. The whole [DomainModel] is then broadcast to the persona pool, which
 * evaluates the aggregate roots and boundaries against each persona's domain constraints.
 */

/** A single shared term in the project's ubiquitous language — the vocabulary the model commits to. */
@Serializable
@LLMDescription("A term in the project's ubiquitous language: the shared, unambiguous vocabulary of the domain")
data class UbiquitousLanguageTerm(
    @property:LLMDescription("The term exactly as it should be used in code, docs, and conversation")
    val term: String,
    @property:LLMDescription("A precise, one or two sentence definition agreed for this term")
    val definition: String,
)

/**
 * An aggregate root: the consistency boundary the personas (especially the Data Architect) scrutinize.
 * It owns its entities and enforces the listed invariants as a single transactional unit.
 */
@Serializable
@LLMDescription("An aggregate root: a transactional consistency boundary that owns entities and enforces invariants")
data class AggregateRoot(
    @property:LLMDescription("The aggregate root's name, drawn from the ubiquitous language")
    val name: String,
    @property:LLMDescription("What this aggregate is responsible for, in one or two sentences")
    val purpose: String,
    @property:LLMDescription("The entities and value objects this aggregate owns")
    val entities: List<String>,
    @property:LLMDescription("The invariants this aggregate must keep true at all times")
    val invariants: List<String>,
)

/**
 * A bounded context: a cohesive slice of the domain with its own model and language. Its boundaries
 * and [integrationPoints] are exactly what the pool evaluates for blast radius, latency, and clarity.
 */
@Serializable
@LLMDescription("A bounded context: a cohesive area of the domain with its own model and clear boundaries")
data class BoundedContext(
    @property:LLMDescription("The bounded context's name")
    val name: String,
    @property:LLMDescription("The business capability this context owns, in one or two sentences")
    val purpose: String,
    @property:LLMDescription("The aggregate roots that live inside this context")
    val aggregateRoots: List<AggregateRoot>,
    @property:LLMDescription("How this context integrates with others (events, APIs, shared data) — the seams")
    val integrationPoints: List<String>,
)

/**
 * The drafted domain model: the output of Nodes 1–2 and the unit of evaluation for the persona pool.
 * Kept serializable so it can be dropped straight into prompt history when a suspended session
 * resumes, and so each persona can be handed the exact same model to evaluate independently.
 */
@Serializable
@LLMDescription("A drafted Domain-Driven-Design model: ubiquitous language plus the bounded contexts and their aggregates")
data class DomainModel(
    @property:LLMDescription("A concise restatement of the idea and the domain it lives in")
    val summary: String,
    @property:LLMDescription("The ubiquitous language dictionary extracted for this domain")
    val ubiquitousLanguage: List<UbiquitousLanguageTerm>,
    @property:LLMDescription("The bounded contexts the idea decomposes into, each with its aggregate roots")
    val boundedContexts: List<BoundedContext>,
    @property:LLMDescription("Things explicitly out of scope given the existing domain boundaries")
    val outOfScope: List<String>,
)
