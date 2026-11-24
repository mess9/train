package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackLayersTest {
    @Test fun rootHasNothingToClose() { assertFalse(BackLayers().back()) }

    @Test fun closesLastOfEqualPriorityFirst() {
        val closed = mutableListOf<Int>()
        val layers = BackLayers()
        layers.add(Any()) { closed += 1 }
        layers.add(Any()) { closed += 2 }
        assertTrue(layers.back()); assertEquals(listOf(2), closed)
        assertTrue(layers.back()); assertEquals(listOf(2, 1), closed)
        assertFalse(layers.back())
    }

    @Test fun visualPriorityWinsOverMountOrder() {
        val closed = mutableListOf<String>()
        val layers = BackLayers()
        layers.add(Any(), 2000) { closed += "viewer" }
        layers.add(Any(), 100) { closed += "sheet" }
        layers.add(Any(), 10000) { closed += "keyboard" }
        repeat(3) { layers.back() }
        assertEquals(listOf("keyboard", "viewer", "sheet"), closed)
    }

    @Test fun busyLayerConsumesBackWithoutClosingAnything() {
        var allowed = false
        var closed = 0
        val layers = BackLayers()
        layers.add(Any()) { closed++ }
        layers.add(Any(), canClose = { allowed }) { closed++ }
        repeat(3) { assertTrue(layers.back()) }
        assertEquals(2, layers.size); assertEquals(0, closed)
        allowed = true
        layers.back()
        assertEquals(1, layers.size); assertEquals(1, closed)
    }

    @Test fun disposalAfterBackDoesNotRemoveNextLayer() {
        val layers = BackLayers()
        val first = Any(); val second = Any()
        layers.add(first) {}; layers.add(second) {}
        layers.back(); layers.remove(second); layers.remove(second)
        assertEquals(1, layers.size)
        layers.back(); assertFalse(layers.back())
    }

    @Test fun underlyingLayerMayDisappearWhileViewerRemains() {
        val closed = mutableListOf<Int>()
        val layers = BackLayers()
        val underlying = Any()
        layers.add(underlying) { closed += 1 }
        layers.add(Any(), 2000) { closed += 2 }
        layers.remove(underlying)
        layers.back()
        assertEquals(listOf(2), closed)
    }

    @Test fun removingAndClosingUseIdentityAndNotifyOnce() {
        data class Key(val value: Int)
        val sizes = mutableListOf<Int>()
        lateinit var layers: BackLayers
        layers = BackLayers { sizes += layers.size }
        val first = Key(1); val second = Key(1)
        val close = {}
        layers.add(first, close = close); layers.add(second, close = close)
        layers.back(); layers.remove(first); layers.remove(second)
        assertEquals(listOf(1, 2, 1, 0), sizes)
    }

    @Test fun duplicateRegistrationIsRejected() {
        val layers = BackLayers(); val key = Any()
        layers.add(key) {}
        assertFailsWith<IllegalStateException> { layers.add(key) {} }
    }
}

class BackHistorySyncTest {
    private var physical = 0
    private var users = 0
    private val pushes = mutableListOf<Int>()
    private val moves = mutableListOf<Int>()
    private var onUser: () -> Unit = {}
    private val history = BackHistorySync({ physical }, { physical = it; pushes += it }, { moves += it }, { users++; onUser() })

    @Test fun pushesEveryIntermediateLevel() {
        history.sync(3)
        assertEquals(listOf(1, 2, 3), pushes); assertEquals(0, users)
    }

    @Test fun ownAsynchronousPopIsNotAnotherUserBack() {
        history.sync(3); history.sync(1)
        assertEquals(listOf(-2), moves)
        physical = 1; history.popped()
        assertEquals(0, users); assertEquals(1, physical)
    }

    @Test fun latestTargetWinsWhileHistoryGoIsPending() {
        history.sync(3); history.sync(0); history.sync(2)
        assertEquals(listOf(-3), moves)
        physical = 0; history.popped()
        assertEquals(2, physical); assertEquals(listOf(1, 2), pushes.takeLast(2)); assertEquals(0, users)
    }

    @Test fun userBackUpdatesModelWithoutDoublePop() {
        history.sync(2)
        onUser = { history.sync(physical) }
        physical = 1; history.popped()
        assertEquals(1, users); assertTrue(moves.isEmpty()); assertEquals(1, physical)
    }

    @Test fun busyLayerRestoresConsumedBrowserEntry() {
        history.sync(2)
        onUser = { history.sync(2) }
        physical = 1; history.popped()
        assertEquals(2, physical); assertEquals(1, users); assertTrue(moves.isEmpty())
    }

    @Test fun forwardDoesNotResurrectClosedScreensOrLayers() {
        history.sync(1)
        onUser = { history.sync(1) }
        physical = 2; history.popped()
        assertEquals(listOf(-1), moves)
        physical = 1; history.popped()
        assertEquals(1, users); assertEquals(1, physical)
    }

    @Test fun negativeDepthIsClampedToRoot() {
        history.sync(-5)
        assertTrue(pushes.isEmpty()); assertTrue(moves.isEmpty()); assertEquals(0, physical)
    }
}
