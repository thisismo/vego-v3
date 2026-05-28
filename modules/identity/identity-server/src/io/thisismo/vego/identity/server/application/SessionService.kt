package io.thisismo.vego.identity.server.application

import com.auth0.jwt.JWT
import io.ktor.server.plugins.NotFoundException
import io.thisismo.vego.common.server.auth.SessionToken
import io.thisismo.vego.common.server.auth.UserSession
import io.thisismo.vego.identity.common.UserId
import io.thisismo.vego.identity.server.domain.OAuthRefreshService
import io.thisismo.vego.identity.server.domain.OAuthTokens
import io.thisismo.vego.identity.server.domain.SessionRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

internal class SessionService(
    private val authService: AuthService,
    private val sessionRepository: SessionRepository,
    private val oauthRefreshService: OAuthRefreshService,
) {
    suspend fun initiateSession(tokens: OAuthTokens): SessionToken {
        val identityTokenJwt = JWT().decodeJwt(tokens.identityToken)
        val sub = identityTokenJwt.subject
            ?: throw IllegalArgumentException("Access token does not contain subject (sub) claim")

        val user = try {
            authService.getUser(sub)
        } catch (_: NotFoundException) {
            val name = identityTokenJwt.claims["name"]?.asString()
                ?: throw IllegalArgumentException("Access token does not contain name claim")
            val email = identityTokenJwt.claims["email"]?.asString()
                ?: throw IllegalArgumentException("Access token does not contain email claim")
            authService.signUp(sub, name, email)
        } catch (e: Exception) {
            println("Failed to retrieve user for sub: $sub. Error: ${e.message}")
            throw e
        }

        return newSessionFor(user.userId, tokens)
    }

    suspend fun getActiveSession(token: SessionToken): UserSession {
        var session = sessionRepository.findByToken(token)
            ?: throw IllegalArgumentException("Session not found for token: $token")

        if (session.accessExpiresAt < Clock.System.now()) {
            try {
                session = refreshSession(session)
            } catch (e: Exception) {
                println("Failed to refresh session for token: $token. Error: ${e.message}")
                throw e
            }
            sessionRepository.save(session)
        }

        return session
    }

    private suspend fun refreshSession(session: UserSession): UserSession {
        val tokens = oauthRefreshService.refreshTokens(session.refreshToken)
        val newSession = session.copy(
            accessToken = tokens.accessToken,
            accessExpiresAt = parseJwtAndGetExpiry(tokens.accessToken),
            refreshToken = tokens.refreshToken
        )
        sessionRepository.save(newSession)
        return newSession
    }

    private fun parseJwtAndGetExpiry(token: String): Instant {
        val jwt = JWT().decodeJwt(token)
        return jwt.expiresAtAsInstant.toKotlinInstant()
    }

    suspend fun revokeSession(sessionToken: SessionToken) {
        val session = sessionRepository.findByToken(sessionToken)
            ?: throw IllegalArgumentException("Session not found for token: $sessionToken")
        sessionRepository.delete(session)
    }

    private suspend fun newSessionFor(userId: UserId, tokens: OAuthTokens): SessionToken {
        //TODO: Maybe check for existing session?
        val session = UserSession(
            token = SessionToken.generate(),
            userId = userId,
            accessToken = tokens.accessToken,
            accessExpiresAt = parseJwtAndGetExpiry(tokens.accessToken),
            refreshToken = tokens.refreshToken
        )
        sessionRepository.save(session)
        return session.token
    }
}