package io.thisismo.vego.client.auth

interface AuthService {
    suspend fun hasStoredSession(): Boolean
    suspend fun login()
    suspend fun logout()
}