package io.thisismo.vego.client.io

import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.RpcClient
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns a single kRPC connection to one endpoint and recreates it on
 * offline -> online transitions. Any number of service types can be
 * resolved from the shared connection via [service] / [serviceFlow].
 *
 * Callers supply the resolver (e.g. `{ it.withService<IdentityApi>() }`) so the
 * concrete @Rpc-annotated type is known at the call site, while this manager
 * stays service-agnostic. KMP-safe (Android + iOS): uses coroutine [Mutex].
 */
class RpcConnectionManager(
    private val httpClient: HttpClient,
    private val endpoint: String,
    private val networkMonitor: NetworkMonitor,
    private val backendReachability: BackendReachability,
    private val scope: CoroutineScope,
) {
    private val connectionMutex = Mutex()

    /** One generation per successful connection. Bumped on every reconnect. */
    private val _connection = MutableStateFlow<Connection?>(null)

    val isConnected: StateFlow<Boolean> = _connection
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            networkMonitor.status
                .map { it == NetworkStatus.Online }
                .distinctUntilChanged()
                .collectLatest { online ->
                    // collectLatest cancels an in-flight backoff loop when we go offline.
                    if (online) reconnectWithBackoff() else teardown()
                }
        }
    }

    /**
     * Returns the current live instance of a service, resolving it lazily from
     * the active connection and caching it for the connection's lifetime.
     * Suspends until a connection is available.
     *
     * Usage: `manager.service(IdentityApi::class) { it.withService<IdentityApi>() }`
     */
    suspend fun <T : Any> service(key: KClass<T>, resolver: (RpcClient) -> T): T =
        currentConnection().resolve(key, resolver)

    /** Reactive variant: emits a fresh service on each reconnect, null while offline. */
    fun <T : Any> serviceFlow(key: KClass<T>, resolver: (RpcClient) -> T): StateFlow<T?> =
        _connection
            .map { conn -> conn?.resolve(key, resolver) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun currentConnection(): Connection =
        _connection.filterNotNull().first()

    private suspend fun reconnectWithBackoff() {
        var attempt = 0
        while (true) {
            try {
                reconnect()
                backendReachability.reportSuccess()
                return
            } catch (_: Exception) {
                backendReachability.reportFailure()
                val delayMs = minOf(30_000L, 500L * (1L shl attempt.coerceAtMost(6)))
                delay(delayMs.milliseconds)
                attempt++
            }
        }
    }

    private suspend fun reconnect() = connectionMutex.withLock {
        teardownLocked()
        val client = httpClient.rpc(endpoint) {
            rpcConfig { serialization { json() } }
        }
        _connection.value = Connection(client)
    }

    private suspend fun teardown() = connectionMutex.withLock { teardownLocked() }

    private fun teardownLocked() {
        _connection.value?.close()
        _connection.value = null
    }

    /** A single connection generation with a per-type service cache. */
    class Connection(private val client: RpcClient) {
        private val cacheMutex = Mutex()
        private val cache = HashMap<KClass<*>, Any>()

        @Suppress("UNCHECKED_CAST")
        suspend fun <T : Any> resolve(key: KClass<T>, resolver: (RpcClient) -> T): T =
            cacheMutex.withLock {
                cache.getOrPut(key) { resolver(client) } as T
            }

        fun close() {
            (client as? AutoCloseable)?.close()
        }
    }
}

suspend inline fun <@Rpc reified T : Any> RpcConnectionManager.service(): T =
    service(T::class) { it.withService<T>() }

inline fun <@Rpc reified T : Any> RpcConnectionManager.serviceFlow(): StateFlow<T?> =
    serviceFlow(T::class) { it.withService<T>() }