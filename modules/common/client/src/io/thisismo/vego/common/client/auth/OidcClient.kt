package io.thisismo.vego.common.client.auth

import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

const val OIDC_DISCOVERY_URI = "http://Moritzs-MacBook-Pro.local:8080/realms/vegoapp/.well-known/openid-configuration"

val oidcClient = OpenIdConnectClient(discoveryUri = OIDC_DISCOVERY_URI) {
    clientId = "vego-kmp-client"
    scope = "openid profile offline_access"
    codeChallengeMethod = CodeChallengeMethod.S256
    redirectUri = "vegoapp://identity"
}