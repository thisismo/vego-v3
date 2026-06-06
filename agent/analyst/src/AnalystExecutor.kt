package io.thisismo.vego.agent

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Builds the agent's shared [PromptExecutor], hardened against transient LLM-API failures per
 * Koog's "handling failures" guidance (https://docs.koog.ai/prompts/handling-failures/).
 *
 * The OpenAI client is wrapped in a [RetryingLLMClient] — a provider-agnostic decorator that
 * retries failed operations with exponential backoff + jitter. Because the wrap is at the *client*
 * layer (the same layer `simpleOpenAIExecutor` builds), the policy applies to every call the agent
 * makes through the single executor that is threaded into every session:
 *   - `executeStructured` — domain modeling, the finalize memo, and each independent persona-pool
 *     evaluation ([PersonaPool]);
 *   - `executeStreaming` — the streamed technical-design and self-healing-validation subgraphs.
 *     Per the docs, streaming retries only cover connection failures *before the first token*;
 *     once tokens flow a mid-stream break surfaces to the graph (and is contained by the session's
 *     own per-turn guard and the pool's neutral-verdict fallback).
 *
 * Retryable errors use Koog's default patterns (HTTP 429/5xx, and `rate limit` / `timeout` /
 * `overloaded` / `service unavailable` keywords); a provider `Retry-After` hint, when present,
 * overrides the computed backoff.
 *
 * The policy defaults to [RetryConfig.PRODUCTION] (3 attempts, 1s→20s backoff, 20% jitter) and is
 * tunable at runtime without a rebuild — mirroring the per-stage model overrides in
 * [AnalystModelConfig]:
 *   - `ANALYST_RETRY_PROFILE`      = production | conservative | aggressive | disabled
 *   - `ANALYST_RETRY_MAX_ATTEMPTS` = a positive integer that overrides the profile's attempt cap
 */
fun resilientOpenAIExecutor(
    apiKey: String,
    getenv: (String) -> String? = System::getenv,
): PromptExecutor {
    val config = retryConfigFromEnvironment(getenv)
    val resilientClient = RetryingLLMClient(OpenAILLMClient(apiKey), config)
    return MultiLLMPromptExecutor(LLMProvider.OpenAI to resilientClient)
}

private val logger = KotlinLogging.logger {}

/** Resolves the active [RetryConfig] from the `ANALYST_RETRY_*` environment variables. */
private fun retryConfigFromEnvironment(getenv: (String) -> String?): RetryConfig {
    val profile = getenv("ANALYST_RETRY_PROFILE")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val base = when (profile) {
        null, "production" -> RetryConfig.PRODUCTION
        "conservative" -> RetryConfig.CONSERVATIVE
        "aggressive" -> RetryConfig.AGGRESSIVE
        "disabled" -> RetryConfig.DISABLED
        else -> {
            logger.warn { "Unknown ANALYST_RETRY_PROFILE '$profile'; keeping PRODUCTION." }
            RetryConfig.PRODUCTION
        }
    }

    val maxAttempts = getenv("ANALYST_RETRY_MAX_ATTEMPTS")?.trim()?.takeIf { it.isNotEmpty() }
    val config = when (val n = maxAttempts?.toIntOrNull()) {
        null -> {
            if (maxAttempts != null) logger.warn { "Ignoring non-integer ANALYST_RETRY_MAX_ATTEMPTS '$maxAttempts'." }
            base
        }
        in 1..Int.MAX_VALUE -> base.copy(maxAttempts = n)
        else -> {
            logger.warn { "Ignoring ANALYST_RETRY_MAX_ATTEMPTS '$n' (must be >= 1)." }
            base
        }
    }

    logger.info {
        "LLM retry policy: maxAttempts=${config.maxAttempts}, initialDelay=${config.initialDelay}, " +
            "maxDelay=${config.maxDelay}, backoff=${config.backoffMultiplier}x, jitter=${config.jitterFactor}."
    }
    return config
}
