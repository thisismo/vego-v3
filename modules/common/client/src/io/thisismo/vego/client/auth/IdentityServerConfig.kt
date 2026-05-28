package io.thisismo.vego.client.auth

/**
 * Connection details for the Vego identity server.
 *
 * Centralized so the rest of the client never hard-codes a host/port. Tests can
 * substitute an instance pointing at a stub server or a deterministic URL.
 *
 * @param baseUrl Scheme + host + port of the identity server (no trailing slash),
 *                e.g. `http://localhost:8080`. The class normalises trailing
 *                slashes so callers can pass either form.
 */
data class IdentityServerConfig(
    val baseUrl: String,
) {
    private val normalisedBase: String = baseUrl.trimEnd('/')

    /**
     * The URL the client should open in an external browser to start the OAuth
     * login flow. The identity server responds with a redirect to Keycloak and
     * finally bounces back into the app via the `vego-v3://identity/callback`
     * deeplink (see `IdentityCallbackDeeplinkHandler`).
     */
    val loginUrl: String get() = "$normalisedBase/login"

    companion object {
        /** Default config matching the identity server's local docker-compose setup. */
        val Default: IdentityServerConfig = IdentityServerConfig(baseUrl = "http://localhost:8080")
    }
}
