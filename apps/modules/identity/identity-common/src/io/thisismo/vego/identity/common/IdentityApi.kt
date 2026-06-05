package io.thisismo.vego.identity.common

import kotlinx.rpc.annotations.Rpc

@Rpc
interface IdentityApi {
    suspend fun getUserInfo(): User?
}