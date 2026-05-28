import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class InMemoryRepository<K, V>(
    private val keyOf: (V) -> K,
) {
    private val mutex = Mutex()
    private val items = mutableMapOf<K, V>()

    protected suspend fun queryItems(query: (V) -> Boolean): List<V> = mutex.withLock { items.values.filter(query) }

    protected suspend fun get(key: K): V? = mutex.withLock { items[key] }

    protected suspend fun put(value: V) = mutex.withLock {
        items[keyOf(value)] = value
    }

    protected suspend fun remove(value: V) = mutex.withLock {
        items.remove(keyOf(value))
    }

    protected suspend fun removeBy(key: K) = mutex.withLock {
        items.remove(key)
    }
}