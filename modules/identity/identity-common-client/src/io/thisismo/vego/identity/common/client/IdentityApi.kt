package io.thisismo.vego.identity.common.client

import kotlinx.rpc.annotations.Rpc

@Rpc
interface IdentityApi {
    suspend fun getUserName(): String
}