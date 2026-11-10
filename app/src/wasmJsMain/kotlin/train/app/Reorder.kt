package train.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

fun dbg(s: String): Unit = js("""{
  if (!window.__dbg) {
    const p = document.createElement('pre');
    Object.assign(p.style, { position: 'fixed', left: '0', right: '0', top: '0', margin: '0', padding: '4px', font: '11px monospace', color: '#0f0', background: 'rgba(0,0,0,.75)', pointerEvents: 'none', zIndex: 9, whiteSpace: 'pre-wrap' });
    document.body.appendChild(p);
    const lines = [];
    window.__dbg = (m) => { const t = performance.now().toFixed(0) + ' ' + m; console.log(t); lines.push(t); p.textContent = lines.slice(-16).join('\n'); };
    window.addEventListener('resize', () => window.__dbg('resize ' + innerWidth + 'x' + innerHeight));
    if (window.visualViewport) {
      visualViewport.addEventListener('resize', () => window.__dbg('vv resize h=' + visualViewport.height.toFixed(0) + ' top=' + visualViewport.offsetTop.toFixed(0)));
      visualViewport.addEventListener('scroll', () => window.__dbg('vv scroll top=' + visualViewport.offsetTop.toFixed(0) + ' pageTop=' + visualViewport.pageTop.toFixed(0)));
    }
    window.addEventListener('scroll', () => window.__dbg('win scroll ' + scrollY), true);
    window.addEventListener('pointercancel', () => window.__dbg('pointercancel'), true);
  }
  window.__dbg(s);
}""")

/**
 * Столбец, где элемент можно перетащить пальцем: подержать — карточка приподнимается, вести —
 * соседи уступают место, отпустить — [onMove] с прежним и новым местом. Пока не отпустил, порядок
 * только на экране. Короткое касание и прокрутка работают как обычно. [enabled] `false` — столбец
 * как столбец.
 */
@Composable
fun <T> ReorderColumn(
    items: List<T>,
    key: (T) -> Any,
    spacing: Dp,
    enabled: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    content: @Composable (item: T, lifted: Boolean) -> Unit,
) {
    val gap = with(LocalDensity.current) { spacing.toPx() }
    val scope = rememberCoroutineScope()
    var order by remember(items) { mutableStateOf(items) }
    var dragged by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    // Сдвиг карточки после обмена или броска: стартует с прежнего места и доезжает до нового
    val shifts = remember { HashMap<Any, Animatable<Float, AnimationVector1D>>() }
    fun shift(k: Any) = shifts.getOrPut(k) { Animatable(0f) }
    // Часть сдвига, которую анимация ещё не забрала. Место в раскладке меняется в том же кадре, что
    // и порядок, а корутина с анимацией — позже, и snapTo ещё ждёт отмены прежней анимации. Без этой
    // части сосед на кадр вставал на новое место без сдвига, и список дёргался при каждом обмене
    val pending = remember { mutableStateMapOf<Any, Float>() }
    fun shown(k: Any) = shift(k).value + (pending[k] ?: 0f)
    fun push(k: Any, by: Float) {
        pending[k] = (pending[k] ?: 0f) + by
        scope.launch {
            val p = pending[k] ?: 0f
            val a = shift(k)
            a.snapTo(a.value + p)
            pending[k] = (pending[k] ?: 0f) - p
            a.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    val move by rememberUpdatedState(onMove)

    /** Отпустили: карточка доезжает до своего места, порядок уходит наверх, если поменялся. */
    fun drop(k: Any) {
        if (dragged != k) return
        val from = items.indexOfFirst { key(it) == k }
        val to = order.indexOfFirst { key(it) == k }
        push(k, offset - shown(k))
        dragged = null
        offset = 0f
        if (from >= 0 && to >= 0 && from != to) move(from, to)
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        order.forEach { item ->
            val k = key(item)
            key(k) {
                val lifted = dragged == k
                val scale by animateFloatAsState(if (lifted) 1.03f else 1f, spring(stiffness = Spring.StiffnessMedium))
                Box(
                    Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .onSizeChanged { heights[k] = it.height }
                        .graphicsLayer {
                            translationY = if (lifted) offset else shown(k)
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(
                            if (!enabled) Modifier
                            else Modifier.pointerInput(k, items) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dbg("START")
                                        haptic(Haptic.Tick)
                                        scope.launch { shift(k).snapTo(0f) }
                                        offset = 0f
                                        dragged = k
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        offset += amount.y
                                        val i = order.indexOfFirst { key(it) == k }
                                        val down = order.getOrNull(i + 1)?.takeIf { offset > 0 }
                                        val up = order.getOrNull(i - 1)?.takeIf { offset < 0 }
                                        val other = down ?: up ?: return@detectDragGesturesAfterLongPress
                                        val ok = key(other)
                                        val step = (heights[ok] ?: 0) + gap
                                        val mine = (heights[k] ?: 0) + gap
                                        if (kotlin.math.abs(offset) < step / 2) return@detectDragGesturesAfterLongPress
                                        val j = if (down != null) i + 1 else i - 1
                                        order = order.toMutableList().also { it[i] = other; it[j] = item }
                                        // Карточка встала на новое место — держим её под пальцем
                                        offset += if (down != null) -step else step
                                        push(ok, if (down != null) mine else -mine)
                                        haptic(Haptic.Tick)
                                        dbg("SWAP")
                                    },
                                    onDragEnd = { dbg("END"); drop(k) },
                                    // Зовётся и при перезапуске жеста, когда список обновился: поднятой карточки тогда нет
                                    onDragCancel = { dbg("CANCEL"); drop(k) },
                                )
                            },
                        ),
                ) {
                    content(item, lifted)
                }
            }
        }
    }
}
