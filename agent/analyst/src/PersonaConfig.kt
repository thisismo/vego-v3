package io.thisismo.vego.agent

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * One member of the decision pool — a data-driven persona definition.
 *
 * Personas are not hardcoded: they are declared in `resources/personas.conf` (HOCON) and parsed into
 * these instances by [PersonaPoolConfig.load]. Adding, removing, or retuning a persona is a config
 * edit; the consensus graph fans out to exactly the personas present at runtime.
 *
 * @property id stable identifier used in logs and the confidence matrix.
 * @property role the human-facing name shown on the dashboard (e.g. "The Security Paranoid").
 * @property focus a one-line summary of what this persona optimizes for.
 * @property temperature sampling temperature for this persona's evaluation LLM call.
 * @property weight relative weight in the weighted-matrix consensus; must be >= 0.
 * @property boundedContexts the contexts this persona has authority over; empty means *all* contexts.
 * @property systemPrompt the persona's evaluation system prompt.
 */
data class PersonaDefinition(
    val id: String,
    val role: String,
    val focus: String,
    val temperature: Double,
    val weight: Double,
    val boundedContexts: List<String>,
    val systemPrompt: String,
) {
    /** Whether this persona is entitled to weigh in on [contextName] (a persona with no scope sees all). */
    fun coversContext(contextName: String): Boolean =
        boundedContexts.isEmpty() || boundedContexts.any { it.equals(contextName, ignoreCase = true) }
}

/**
 * The active persona pool, loaded once at startup from HOCON and shared (read-only) across sessions.
 *
 * Parsing is explicit and hand-rolled — mirroring [AnalystModelConfig.fromEnvironment] — rather than
 * reflection-based mapping, so the config schema stays visible in one place and a malformed file
 * fails loudly at boot.
 */
data class PersonaPoolConfig(val personas: List<PersonaDefinition>) {
    init {
        require(personas.isNotEmpty()) { "Persona pool is empty — at least one persona must be defined." }
        val duplicates = personas.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate persona ids in pool: $duplicates" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Classpath resource (under `resources/`) that declares the pool. */
        const val DEFAULT_RESOURCE: String = "personas.conf"

        /** Loads the pool from a classpath HOCON resource (default `personas.conf`). */
        fun load(resourceName: String = DEFAULT_RESOURCE): PersonaPoolConfig {
            val config = ConfigFactory.parseResources(resourceName).resolve()
            require(config.hasPath("personas")) {
                "Persona config resource '$resourceName' has no 'personas' list."
            }
            return fromConfig(config)
        }

        /** Parses a resolved [Config] holding a `personas` array into a [PersonaPoolConfig]. */
        fun fromConfig(config: Config): PersonaPoolConfig {
            val personas = config.getConfigList("personas").map { p ->
                PersonaDefinition(
                    id = p.getString("id"),
                    role = p.getString("role"),
                    focus = p.getString("focus"),
                    temperature = p.getDouble("temperature"),
                    weight = if (p.hasPath("weight")) p.getDouble("weight") else 1.0,
                    boundedContexts = if (p.hasPath("boundedContexts")) p.getStringList("boundedContexts") else emptyList(),
                    // System prompts are written as indented HOCON block strings for readability;
                    // collapse the indentation/newlines into a single clean paragraph.
                    systemPrompt = p.getString("systemPrompt").lines()
                        .joinToString(" ") { it.trim() }
                        .trim(),
                )
            }
            logger.info { "Loaded persona pool: ${personas.joinToString { it.id }}" }
            return PersonaPoolConfig(personas)
        }
    }
}
