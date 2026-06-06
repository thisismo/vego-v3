package io.thisismo.vego.agent

import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * One place to pick the model for every workflow stage.
 *
 * Each [Stage] of the analyst pipeline is matched to the model whose strengths fit the task. Change a
 * default below to re-tune a stage, or override any stage at runtime with an environment variable —
 * `ANALYST_MODEL_<STAGE>` set to an OpenAI model id (e.g. `ANALYST_MODEL_TECHNICAL_DESIGN=o3`). Ids
 * are resolved against [OpenAIModels]; an unknown id falls back to the default and is logged.
 *
 * The active model is applied per node inside the agent graph via `llm.writeSession { changeModel(..) }`,
 * which persists it on the shared LLM context for all subsequent nodes and subgraphs.
 */
data class AnalystModelConfig(
    /** Domain modeling (ubiquitous language + bounded contexts) — structured output, conversational. */
    val domainModeling: LLModel = OpenAIModels.Chat.GPT5_5,
    /** Persona-pool evaluation — must honour per-persona temperature, so a chat (non-reasoning) model. */
    val personaEvaluation: LLModel = OpenAIModels.Chat.GPT4_1,
    /** Heavy specification work (ADRs / C4 diagrams / UX specs) — a reasoning model. */
    val technicalDesign: LLModel = OpenAIModels.Chat.GPT5_5,
    /** Self-healing validation loop — fast again. */
    val validation: LLModel = OpenAIModels.Chat.GPT5_4,
    /** Distil the session into a durable long-term-memory memo — fast. */
    val finalize: LLModel = OpenAIModels.Chat.GPT5_4,
) {
    /** The pipeline stages, in execution order — used to drive environment-variable overrides. */
    enum class Stage(val envVar: String) {
        DOMAIN_MODELING("ANALYST_MODEL_DOMAIN_MODELING"),
        PERSONA_EVALUATION("ANALYST_MODEL_PERSONA_EVALUATION"),
        TECHNICAL_DESIGN("ANALYST_MODEL_TECHNICAL_DESIGN"),
        VALIDATION("ANALYST_MODEL_VALIDATION"),
        FINALIZE("ANALYST_MODEL_FINALIZE"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** All OpenAI models, keyed by their wire id (e.g. `gpt-4o`, `o3-mini`), for override resolution. */
        private val openAiModelsById: Map<String, LLModel> by lazy { OpenAIModels.modelsById() }

        /**
         * Builds a config from the per-stage defaults, letting any stage be overridden by its
         * `ANALYST_MODEL_<STAGE>` environment variable (value = an OpenAI model id). Unknown ids keep
         * the default. [getenv] is injectable for testing.
         */
        fun fromEnvironment(getenv: (String) -> String? = System::getenv): AnalystModelConfig {
            val defaults = AnalystModelConfig()
            fun resolve(stage: Stage, default: LLModel): LLModel {
                val id = getenv(stage.envVar)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
                val model = openAiModelsById[id]
                if (model == null) {
                    logger.warn { "Unknown model id '$id' for ${stage.envVar}; keeping default ${default.id}." }
                    return default
                }
                logger.info { "Stage ${stage.name} model overridden to '$id' via ${stage.envVar}." }
                return model
            }
            return AnalystModelConfig(
                domainModeling = resolve(Stage.DOMAIN_MODELING, defaults.domainModeling),
                personaEvaluation = resolve(Stage.PERSONA_EVALUATION, defaults.personaEvaluation),
                technicalDesign = resolve(Stage.TECHNICAL_DESIGN, defaults.technicalDesign),
                validation = resolve(Stage.VALIDATION, defaults.validation),
                finalize = resolve(Stage.FINALIZE, defaults.finalize),
            )
        }
    }
}
