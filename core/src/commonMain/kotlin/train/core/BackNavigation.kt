package train.core

/** Видимые временные слои. При равном приоритете первым закрывается последний открытый. */
class BackLayers(private val changed: () -> Unit = {}) {
    private data class Layer(val key: Any, val priority: Int, val canClose: () -> Boolean, val close: () -> Unit)
    private val layers = mutableListOf<Layer>()
    val size: Int get() = layers.size

    fun add(key: Any, priority: Int = 0, canClose: () -> Boolean = { true }, close: () -> Unit) {
        check(layers.none { it.key === key })
        layers += Layer(key, priority, canClose, close)
        changed()
    }

    fun remove(key: Any) {
        if (layers.removeAll { it.key === key }) changed()
    }

    /** true — событие поглощено, в том числе занятым слоем, который пока нельзя закрыть. */
    fun back(): Boolean {
        val top = layers.withIndex().maxWithOrNull(compareBy<IndexedValue<Layer>> { it.value.priority }.thenBy { it.index })?.value ?: return false
        if (top.canClose()) {
            layers.removeAll { it.key === top.key }
            changed()
            top.close()
        }
        return true
    }
}

/** Согласование асинхронного history.go с логической глубиной приложения. */
class BackHistorySync(
    private val read: () -> Int,
    private val push: (Int) -> Unit,
    private val move: (Int) -> Unit,
    private val userPop: () -> Unit,
) {
    private var desired = 0
    private var moving = false

    fun sync(depth: Int) {
        desired = depth.coerceAtLeast(0)
        align()
    }

    fun popped() {
        if (moving) moving = false else userPop()
        align()
    }

    private fun align() {
        if (moving) return
        val current = read()
        if (current > desired) {
            moving = true
            move(desired - current)
        } else for (next in current + 1..desired) push(next)
    }
}
