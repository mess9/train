package train.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.core.BODYWEIGHT
import train.core.BodyGroup
import train.core.BodyRegion
import train.core.Bucket
import train.core.Measurement
import train.core.Stats
import train.core.StatsPeriod
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Графики раздела «Замеры». Схема тела нарисована здесь же простыми фигурами — своя, без чужих
// лицензий (docs/DESIGN.md, «Схема тела»)

fun BodyRegion.label() = when (this) {
    BodyRegion.CHEST -> "Грудь"
    BodyRegion.SHOULDERS -> "Плечи"
    BodyRegion.TRAPS -> "Трапеции"
    BodyRegion.UPPER_BACK -> "Широчайшие"
    BodyRegion.LOWER_BACK -> "Поясница"
    BodyRegion.BICEPS -> "Бицепс"
    BodyRegion.TRICEPS -> "Трицепс"
    BodyRegion.FOREARMS -> "Предплечья"
    BodyRegion.ABS -> "Пресс"
    BodyRegion.OBLIQUES -> "Косые"
    BodyRegion.GLUTES -> "Ягодицы"
    BodyRegion.QUADS -> "Квадрицепс"
    BodyRegion.HAMSTRINGS -> "Бицепс бедра"
    BodyRegion.ADDUCTORS -> "Приводящие"
    BodyRegion.CALVES -> "Икры"
}

fun BodyGroup.label() = when (this) {
    BodyGroup.CHEST -> "Грудь"
    BodyGroup.SHOULDERS -> "Плечи"
    BodyGroup.ARMS -> "Руки"
    BodyGroup.CORE -> "Кор"
    BodyGroup.LEGS -> "Ноги"
    BodyGroup.GLUTES -> "Ягодицы"
    BodyGroup.BACK -> "Спина"
}

/** Подходы с дробью: вспомогательная мышца — полподхода. */
fun sets(x: Double): String = if (x == x.roundToInt().toDouble()) x.roundToInt().toString() else num(x)

// Схема тела: фигура 100 × 200, описана левая половина; `pair` — ещё и зеркально справа. Это
// схема, а не анатомический рисунок: контур и мышцы — многоугольники с острыми углами, круглые
// только голова, кисти и стопы. Сначала силуэт одним цветом без обводки — руки, ноги, торс
// сливаются в одну фигуру, — поверх мышцы с тёмной обводкой: между соседями тонкий шов, а не дыра

private sealed interface Part {
    val region: BodyRegion?
    val pair: Boolean
    fun path(mirror: Boolean): Path
    fun contains(x: Float, y: Float, mirror: Boolean): Boolean
}

private class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, override val region: BodyRegion? = null, override val pair: Boolean = true) : Part {
    private fun x(mirror: Boolean) = if (mirror) 100 - cx else cx
    override fun path(mirror: Boolean) = Path().apply { addOval(Rect(x(mirror) - rx, cy - ry, x(mirror) + rx, cy + ry)) }
    override fun contains(x: Float, y: Float, mirror: Boolean): Boolean {
        val dx = (x - x(mirror)) / rx
        val dy = (y - cy) / ry
        return dx * dx + dy * dy <= 1
    }
}

private class Bar(val l: Float, val t: Float, val r: Float, val b: Float, override val region: BodyRegion? = null, override val pair: Boolean = false) : Part {
    private fun rect(mirror: Boolean) = if (mirror) Rect(100 - r, t, 100 - l, b) else Rect(l, t, r, b)
    override fun path(mirror: Boolean) = Path().apply { addRect(rect(mirror)) }
    override fun contains(x: Float, y: Float, mirror: Boolean) = rect(mirror).contains(Offset(x, y))
}

private class Poly(vararg val xy: Float, override val region: BodyRegion? = null, override val pair: Boolean = true) : Part {
    private fun points(mirror: Boolean) = (xy.indices step 2).map { Offset(if (mirror) 100 - xy[it] else xy[it], xy[it + 1]) }

    override fun path(mirror: Boolean) = Path().apply {
        points(mirror).forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        close()
    }

    override fun contains(x: Float, y: Float, mirror: Boolean): Boolean {
        val p = points(mirror)
        var inside = false
        var j = p.lastIndex
        for (i in p.indices) {
            if ((p[i].y > y) != (p[j].y > y) && x < (p[j].x - p[i].x) * (y - p[i].y) / (p[j].y - p[i].y) + p[i].x) inside = !inside
            j = i
        }
        return inside
    }
}

private val silhouette = listOf(
    Oval(50f, 13.5f, 8.5f, 10f, pair = false),
    Bar(45f, 20f, 55f, 32f),
    // Торс: от шеи по плечу, подмышка, талия, бедро, промежность
    Poly(45.5f, 28f, 36f, 31f, 28f, 34f, 31f, 46f, 34f, 52f, 36.5f, 66f, 37f, 80f, 35f, 90f, 33f, 96f, 34f, 100f, 40f, 104f, 50f, 108f, 60f, 104f, 66f, 100f, 67f, 96f, 65f, 90f, 63f, 80f, 63.5f, 66f, 66f, 52f, 69f, 46f, 72f, 34f, 64f, 31f, 54.5f, 28f, pair = false),
    Poly(28f, 34f, 23f, 39f, 22f, 48f, 20.5f, 60f, 19.5f, 72f, 17.5f, 84f, 16.5f, 96f, 22.5f, 96f, 25f, 84f, 27.5f, 72f, 30.5f, 60f, 32f, 48f, 31f, 40f),
    Oval(19.5f, 101f, 3.5f, 5f),
    Poly(33f, 94f, 31.5f, 112f, 33f, 135f, 35f, 149f, 34f, 163f, 36.5f, 183f, 38f, 188f, 43.5f, 188f, 44f, 178f, 45.5f, 160f, 46.5f, 148f, 48.5f, 128f, 50f, 107f, 42f, 100f),
    Oval(40.5f, 191f, 5f, 3f),
)

private val delts = Poly(34.5f, 33.5f, 29f, 34f, 24f, 39f, 23f, 47f, 26f, 50.5f, 30f, 46f, 33.5f, 39f, region = BodyRegion.SHOULDERS)
private val forearms = Poly(20f, 73f, 27f, 73f, 25f, 81f, 22f, 95f, 18f, 95f, 18.3f, 84f, region = BodyRegion.FOREARMS)

private val front = silhouette + listOf(
    delts,
    forearms,
    Poly(49f, 36f, 49f, 52f, 42f, 55f, 35f, 52f, 33f, 45f, 36f, 37f, region = BodyRegion.CHEST),
    Poly(24f, 53f, 29f, 52.5f, 30f, 58f, 28.5f, 68f, 25f, 70.5f, 22f, 64f, region = BodyRegion.BICEPS),
    Poly(36.5f, 57f, 42f, 59f, 42.5f, 86f, 39.5f, 92f, 37f, 84f, 37f, 68f, region = BodyRegion.OBLIQUES),
    // Пресс кубиками: три ряда и низ
    Bar(43.5f, 56f, 49f, 64.5f, BodyRegion.ABS, pair = true),
    Bar(43.5f, 66f, 49f, 74f, BodyRegion.ABS, pair = true),
    Bar(43.5f, 75.5f, 49f, 83.5f, BodyRegion.ABS, pair = true),
    Poly(43.5f, 85f, 49f, 85f, 49f, 97f, 45f, 93f, region = BodyRegion.ABS),
    Poly(34f, 98f, 41f, 101f, 45f, 112f, 46f, 140f, 42f, 147f, 37f, 146f, 33.5f, 128f, 32.5f, 110f, region = BodyRegion.QUADS),
    Poly(45.8f, 104f, 49f, 108.5f, 48.6f, 118f, 46.6f, 128f, 46.2f, 113f, region = BodyRegion.ADDUCTORS),
    Poly(35.5f, 153f, 40f, 152f, 43.5f, 158f, 43f, 175f, 40.5f, 184f, 37.5f, 183f, 35.5f, 168f, region = BodyRegion.CALVES),
)

private val back = silhouette + listOf(
    delts,
    forearms,
    Poly(50f, 21f, 55f, 27f, 64f, 32.5f, 58f, 38f, 53f, 52f, 50f, 62f, 47f, 52f, 42f, 38f, 36f, 32.5f, 45f, 27f, region = BodyRegion.TRAPS, pair = false),
    Poly(33f, 46f, 38f, 41.5f, 44f, 46.5f, 47.2f, 57f, 46f, 68f, 42f, 80f, 38.5f, 86f, 37.6f, 74f, 35.8f, 58f, region = BodyRegion.UPPER_BACK),
    Poly(48.8f, 64f, 48.8f, 95f, 44f, 95f, 42.5f, 86f, 45.5f, 72f, region = BodyRegion.LOWER_BACK),
    Poly(23f, 52f, 27f, 51.5f, 30f, 53f, 30f, 62f, 28f, 70f, 23.5f, 70f, 21.5f, 60f, region = BodyRegion.TRICEPS),
    Poly(34f, 97f, 42f, 95.5f, 48.8f, 99f, 48.8f, 111f, 44f, 115f, 36f, 113f, 33f, 105f, region = BodyRegion.GLUTES),
    Poly(33.5f, 117f, 40f, 115.5f, 48f, 117f, 47f, 137f, 44f, 146f, 38f, 146f, 34f, 132f, region = BodyRegion.HAMSTRINGS),
    Poly(35.5f, 152f, 41f, 150.5f, 44.6f, 156f, 44f, 168f, 41.5f, 180f, 38f, 180f, 35f, 166f, region = BodyRegion.CALVES),
)

private val Idle = Color(0xFF363A41)

/** Шкала нагрузки — как оценки подхода: мало — синий, средне — зелёный, дальше жёлтый, больше всего — красный. */
private val scale = listOf(
    0f to Color(0xFF4A7BE8),
    0.4f to Color(0xFF5ACF7E),
    0.72f to Color(0xFFF2C94C),
    1f to Color(0xFFFF6B6B),
)

/** Цвет участка: без нагрузки — серый, дальше по [scale] от доли самого нагруженного. */
fun loadColor(t: Double): Color {
    if (t <= 0) return Idle
    val x = t.toFloat().coerceIn(0f, 1f)
    val i = scale.indexOfLast { it.first <= x }.coerceAtMost(scale.size - 2)
    val (a, from) = scale[i]
    val (b, to) = scale[i + 1]
    return lerp(from, to, (x - a) / (b - a))
}

/** Тело спереди и сзади, участки подсвечены по подходам за период; тап по мышце — её подходы. */
@Composable
fun BodyMap(stats: Stats, modifier: Modifier = Modifier) {
    val max = stats.regions.maxOfOrNull { it.sets }?.takeIf { it > 0 } ?: 1.0
    var selected by remember { mutableStateOf<BodyRegion?>(null) }
    val pick = { r: BodyRegion? -> selected = if (r == selected) null else r }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Спереди" to front, "Сзади" to back).forEach { (title, parts) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Figure(parts, { r -> stats.load(r) / max }, selected, pick, Modifier.fillMaxWidth(0.82f).aspectRatio(0.5f))
                    Txt(title, T.small, Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.padding(top = 10.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val r = selected
            if (r == null) {
                Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Idle))
                Txt("нет", T.small, Modifier.padding(end = 6.dp))
                Txt("мало", T.small)
                listOf(0.05, 0.3, 0.55, 0.8, 1.0).forEach { Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(loadColor(it))) }
                Txt("много", T.small)
            } else {
                val n = stats.load(r)
                Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(loadColor(n / max)))
                Txt(r.label(), ts(13.sp, C.Soft, FontWeight.Medium))
                Txt(
                    when {
                        n <= 0 -> "не было"
                        n != n.roundToInt().toDouble() -> "${sets(n)} подхода"
                        else -> "${sets(n)} ${plural(n.roundToInt(), "подход", "подхода", "подходов")}"
                    },
                    T.small,
                )
                // Часть тела из нескольких участков — её луч на розе: подход в ней один раз,
                // и спина с тягами и поясницей в разных упражнениях больше любой своей мышцы
                val g = r.group
                if (BodyRegion.entries.count { it.group == g } > 1) Txt("· ${g.whole()} ${sets(stats.load(g))}", T.small)
            }
        }
    }
}

/** «Вся спина», «все ноги» — итог части тела рядом с её мышцей. */
private fun BodyGroup.whole() = when (this) {
    BodyGroup.CORE -> "весь кор"
    BodyGroup.BACK -> "вся спина"
    BodyGroup.ARMS, BodyGroup.LEGS -> "все ${label().lowercase()}"
    else -> label().lowercase()
}

@Composable
private fun Figure(parts: List<Part>, load: (BodyRegion) -> Double, selected: BodyRegion?, onTap: (BodyRegion?) -> Unit, modifier: Modifier) {
    val shapes = remember(parts) { parts.flatMap { p -> listOfNotNull(Triple(p, false, p.path(false)), if (p.pair) Triple(p, true, p.path(true)) else null) } }
    val tap by rememberUpdatedState(onTap)
    Canvas(
        modifier.pointerInput(parts) {
            detectTapGestures { o ->
                val k = size.width / 100f
                val x = o.x / k
                val y = o.y / k
                val muscles = shapes.filter { it.first.region != null }
                // Мимо мышцы на пару единиц — всё равно она: на телефоне кубик пресса шириной с полпальца
                val hit = muscles.lastOrNull { (p, m) -> p.contains(x, y, m) }
                    ?: muscles.map { it to it.third.getBounds().inflate(2.5f) }.filter { it.second.contains(Offset(x, y)) }
                        .minByOrNull { (it.second.center - Offset(x, y)).getDistance() }?.first
                tap(hit?.first?.region)
            }
        },
    ) {
        val k = size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            shapes.forEach { (part, _, path) ->
                val region = part.region
                if (region == null) {
                    drawPath(path, C.Field)
                } else {
                    drawPath(path, loadColor(load(region)))
                    drawPath(path, C.Bg, style = Stroke(0.9f))
                }
            }
            if (selected != null) shapes.filter { it.first.region == selected }.forEach { drawPath(it.third, C.Text, style = Stroke(1.4f)) }
        }
    }
}

/** Порядок по кругу: соседи на розе — соседи на теле. */
private val roseOrder = listOf(BodyGroup.SHOULDERS, BodyGroup.CHEST, BodyGroup.ARMS, BodyGroup.CORE, BodyGroup.LEGS, BodyGroup.GLUTES, BodyGroup.BACK)

/** «Роза ветров»: подходы по частям тела, лучи нормированы по самой нагруженной. */
@Composable
fun Rose(stats: Stats, modifier: Modifier = Modifier) {
    val values = roseOrder.map { stats.load(it) }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val tm = rememberTextMeasurer()
    val label = ts(11.sp, C.Muted, FontWeight.Medium)
    val value = ts(10.sp, C.Faint)
    Canvas(modifier.fillMaxWidth().aspectRatio(1.15f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = minOf(size.width, size.height) / 2 - 34.dp.toPx()
        val n = roseOrder.size
        fun at(i: Int, k: Float): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(c.x + (r * k * cos(a)).toFloat(), c.y + (r * k * sin(a)).toFloat())
        }
        fun ring(k: (Int) -> Float) = Path().apply {
            (0 until n).forEach { i -> at(i, k(i)).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            close()
        }
        listOf(1f / 3, 2f / 3, 1f).forEach { k -> drawPath(ring { k }, C.Border, style = Stroke(1.dp.toPx())) }
        (0 until n).forEach { i -> drawLine(C.Border, c, at(i, 1f), 1.dp.toPx()) }
        val shape = ring { i -> (values[i] / max).toFloat().coerceAtLeast(0.02f) }
        drawPath(shape, C.Lime.copy(alpha = 0.22f))
        drawPath(shape, C.Lime, style = Stroke(2.dp.toPx()))
        (0 until n).forEach { i ->
            val p = at(i, (values[i] / max).toFloat())
            if (values[i] > 0) drawCircle(C.Lime, 3.dp.toPx(), p)
            val t1 = tm.measure(roseOrder[i].label(), label)
            val t2 = tm.measure(sets(values[i]), value)
            val o = at(i, 1f + 20.dp.toPx() / r)
            val h = t1.size.height + t2.size.height
            drawText(t1, topLeft = Offset(o.x - t1.size.width / 2, o.y - h / 2))
            drawText(t2, topLeft = Offset(o.x - t2.size.width / 2, o.y - h / 2 + t1.size.height))
        }
    }
}

/** «45м», «1ч20», «12ч» */
fun hm(minutes: Int): String = when {
    minutes < 60 -> "${minutes}м"
    minutes % 60 == 0 || minutes >= 600 -> "${minutes / 60}ч"
    else -> "${minutes / 60}ч${(minutes % 60).toString().padStart(2, '0')}"
}

/** Часы тренировок столбиками: по дням (неделя, месяц), неделям (3 месяца), месяцам (год). */
@Composable
fun HoursChart(period: StatsPeriod, buckets: List<Bucket>, minutes: List<Int>, modifier: Modifier = Modifier) {
    val max = minutes.maxOrNull()?.takeIf { it > 0 } ?: 1
    val n = buckets.size
    val tm = rememberTextMeasurer()
    val axis = ts(10.sp, C.Faint)
    val value = ts(10.sp, C.Muted)
    // Подписи рисуются поверх, а не в колонке столбика: в месяце столбик уже «30» и «1ч10»
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val labelH = 16.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - labelH
        val gap = (if (n > 20) 2.dp else 4.dp).toPx()
        val w = (size.width - gap * (n - 1)) / n
        drawLine(C.Border, Offset(0f, bottom + 0.5f), Offset(size.width, bottom + 0.5f), 1.dp.toPx())
        // Значения: у редких столбиков — все, у частых — только максимум, чтобы не слиплись
        val valued = minutes.indices.filter { minutes[it] > 0 && (n <= 13 || minutes[it] == max) }
            .let { if (n <= 13) it else it.take(1) }
        minutes.forEachIndexed { i, m ->
            if (m <= 0) return@forEachIndexed
            val x = i * (w + gap)
            val h = maxOf((bottom - top) * m / max, 2.dp.toPx())
            drawRoundRect(C.Lime, Offset(x, bottom - h), Size(w, h), CornerRadius(minOf(3.dp.toPx(), w / 2)))
            if (i in valued) {
                val t = tm.measure(hm(m), value)
                val cx = (x + w / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(cx, bottom - h - t.size.height - 1.dp.toPx()))
            }
        }
        buckets.forEachIndexed { i, b ->
            val labelled = when (period) {
                StatsPeriod.MONTH -> (n - 1 - i) % 5 == 0
                StatsPeriod.QUARTER -> (n - 1 - i) % 3 == 0
                else -> true
            }
            if (!labelled) return@forEachIndexed
            val t = tm.measure(
                when (period) {
                    StatsPeriod.WEEK -> b.from.weekday()
                    StatsPeriod.MONTH -> b.from.day.toString()
                    StatsPeriod.QUARTER -> b.from.dm()
                    StatsPeriod.YEAR -> b.from.monthAlone()
                },
                axis,
            )
            val cx = (i * (w + gap) + w / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(cx, size.height - t.size.height))
        }
    }
}

/**
 * Вес тела линией. Взвешивание на тренировке — залитая точка: по нему считан её объём.
 * [points] — уже отобранные и по времени.
 */
@Composable
fun WeightChart(points: List<Measurement>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val axis = ts(10.sp, C.Faint)
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        if (points.size < 2) return@Canvas
        val left = 34.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val lo = points.minOf { it.value } - 0.5
        val hi = points.maxOf { it.value } + 0.5
        val t0 = points.first().measuredAt.toEpochMilliseconds().toDouble()
        val span = (points.last().measuredAt.toEpochMilliseconds() - t0).coerceAtLeast(1.0)
        fun x(m: Measurement) = (left + 6.dp.toPx() + (size.width - left - 12.dp.toPx()) * ((m.measuredAt.toEpochMilliseconds() - t0) / span)).toFloat()
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        // Метки — максимум, середина, минимум, округлённые до 0,1; совпавшие (вес не менялся) — одной
        listOf(hi - 0.5, (hi + lo) / 2, lo + 0.5).map { (it * 10).roundToInt() / 10.0 }.distinct().forEach { v ->
            drawLine(C.Border, Offset(left, y(v)), Offset(size.width, y(v)), 1.dp.toPx())
            val t = tm.measure(num(v), axis)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2))
        }
        val line = Path().apply {
            points.forEachIndexed { i, m -> if (i == 0) moveTo(x(m), y(m.value)) else lineTo(x(m), y(m.value)) }
        }
        drawPath(line, C.Lime, style = Stroke(2.dp.toPx()))
        points.forEach { m ->
            val p = Offset(x(m), y(m.value))
            if (m.workoutId != null) drawCircle(C.Lime, 4.dp.toPx(), p)
            else {
                drawCircle(C.Bg, 4.dp.toPx(), p)
                drawCircle(C.Lime, 4.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            }
        }
        val first = tm.measure(points.first().measuredAt.date().dm(), axis)
        val last = tm.measure(points.last().measuredAt.date().dm(), axis)
        drawText(first, topLeft = Offset(left, size.height - first.size.height))
        if (points.last().measuredAt.date() != points.first().measuredAt.date())
            drawText(last, topLeft = Offset(size.width - last.size.width, size.height - last.size.height))
    }
}

/** Взвешивания для графика: за период, а если там меньше двух — последние двенадцать. */
fun weightPoints(all: List<Measurement>, from: kotlinx.datetime.LocalDate): Pair<List<Measurement>, Boolean> {
    val weights = all.filter { it.type == BODYWEIGHT && !it.deleted }.sortedBy { it.measuredAt }
    val inPeriod = weights.filter { it.measuredAt.date() >= from }
    return if (inPeriod.size >= 2) inPeriod to true else weights.takeLast(12) to false
}
