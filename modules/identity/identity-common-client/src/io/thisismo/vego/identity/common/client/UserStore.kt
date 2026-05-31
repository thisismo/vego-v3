package io.thisismo.vego.identity.common.client

import io.thisismo.vego.identity.common.User
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the local, offline-first persistence of the logged in [User].
 *
 * Lives in the core client module so that core services (e.g. [UserService]) and the UI can depend
 * on it without pulling in a concrete feature module. The actual Room backed implementation is
 * provided by the identity feature module (`UserRepository`) and bound to this interface via Koin,
 * which keeps the module dependency direction acyclic (feature -> core).
 */
interface UserStore {
    /** Emits the cached [User], or `null` when none has been stored yet. */
    val user: Flow<User?>

    /** Persists [user], overwriting any previously cached value. */
    suspend fun save(user: User)

    /** Removes the cached user (e.g. on logout). */
    suspend fun clear()
}
