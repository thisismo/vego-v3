package io.thisismo.vego.identity.common.client

import co.touchlab.kermit.Logger
import io.thisismo.vego.common.client.network.RpcConnectionManager
import io.thisismo.vego.common.client.network.service
import io.thisismo.vego.identity.common.IdentityApi
import io.thisismo.vego.identity.common.User
import kotlinx.coroutines.flow.Flow

/**
 * Offline-first access to the logged in [User].
 *
 * Fetches the user info from the backend (via [IdentityApi] over the identity RPC connection) and
 * persists it locally through [UserStore], so the UI can keep reading the cached value even while
 * offline. Consumers should observe [user] (backed by the local database) and call [refreshUser]
 * after login (or whenever a fresh copy is desirable).
 */
class UserService(
    private val connectionManager: RpcConnectionManager,
    private val userStore: UserStore,
) {
    private val log = Logger.withTag("UserService")

    /** The locally cached user, read straight from the database. `null` until one is stored. */
    val user: Flow<User?> = userStore.user

    /**
     * Fetches the latest user info from the backend and persists it locally.
     *
     * Suspends until an identity connection is available; safe to call right after login.
     */
    suspend fun refreshUser() {
        log.i("Refreshing user info...")
        val api = connectionManager.service<IdentityApi>()
        val fetched = api.getUserInfo()
        if (fetched != null) {
            userStore.save(fetched)
            log.i("User info persisted locally.")
        } else {
            log.w("Backend returned no user info; keeping cached value.")
        }
    }

    /** Clears the cached user (e.g. on logout). */
    suspend fun clear() {
        userStore.clear()
    }
}
