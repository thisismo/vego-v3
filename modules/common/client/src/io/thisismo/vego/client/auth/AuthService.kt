package io.thisismo.vego.client.auth

class AuthService {
    suspend fun login() {

    }

    suspend fun refresh() {

    }

    suspend fun logout() {

    }
}

sealed class AuthException : Exception() {
    class RefreshFailed : AuthException()
    class NetworkError : AuthException()

}