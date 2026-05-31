package io.thisismo.vego.common.client.auth

interface AuthService {
    suspend fun hasStoredSession(): Boolean
    suspend fun login()
    suspend fun logout()
}