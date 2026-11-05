package train.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
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

// Схема тела: фигура 100 × 200, `pair` — ещё и зеркально справа

private sealed interface Part {
    val region: BodyRegion?
    val pair: Boolean
    fun path(mirror: Boolean): Path
}

private class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, override val region: BodyRegion? = null, override val pair: Boolean = true) : Part {
    override fun path(mirror: Boolean) = Path().apply {
        val x = if (mirror) 100 - cx else cx
        addOval(Rect(x - rx, cy - ry, x + rx, cy + ry))
    }
}

private class Bar(val l: Float, val t: Float, val r: Float, val b: Float, val rad: Float, override val region: BodyRegion? = null) : Part {
    override val pair = false
    override fun path(mirror: Boolean) = Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(rad))) }
}

private class Poly(vararg val xy: Float, override val region: BodyRegion? = null, override val pair: Boolean = true) : Part {
    override fun path(mirror: Boolean) = Path().apply {
        for (i in xy.indices step 2) {
            val x = if (mirror) 100 - xy[i] else xy[i]
            if (i == 0) moveTo(x, xy[1]) else lineTo(x, xy[i + 1])
        }
        close()
    }
}

private val common = listOf(
    Bar(31f, 33f, 69f, 95f, 10f), // туловище — фон, чтобы между мышцами не было дыр
    Oval(50f, 15f, 9f, 10.5f, pair = false),
    Bar(46f, 24f, 54f, 32f, 2f),
    Oval(18.5f, 100.5f, 3.8f, 4.5f),
    Poly(35f, 91f, 65f, 91f, 62f, 104f, 38f, 104f, pair = false),
    Oval(40f, 148.5f, 5.5f, 5f),
    Oval(38.5f, 189f, 6.5f, 3.2f),
    Oval(28.5f, 42f, 7.5f, 8.5f, BodyRegion.SHOULDERS),
    Oval(20.5f, 84f, 4.5f, 12f, BodyRegion.FOREARMS),
)

private val front = common + listOf(
    Poly(45f, 26.5f, 48f, 30f, 36f, 36.5f, 31.5f, 35f, region = BodyRegion.TRAPS),
    Oval(41.5f, 47f, 9.5f, 8f, BodyRegion.CHEST),
    Oval(24f, 61f, 5f, 10.5f, BodyRegion.BICEPS),
    Oval(36.5f, 74f, 5f, 14f, BodyRegion.OBLIQUES),
    Bar(43.5f, 56f, 56.5f, 91f, 4f, BodyRegion.ABS),
    Oval(40.5f, 124f, 9f, 22f, BodyRegion.QUADS),
    Oval(47.3f, 111f, 3f, 10f, BodyRegion.ADDUCTORS),
    Oval(39.5f, 168f, 6f, 16f, BodyRegion.CALVES),
)

private val back = common + listOf(
    Poly(50f, 25f, 65f, 37f, 50f, 60f, 35f, 37f, region = BodyRegion.TRAPS, pair = false),
    Poly(36f, 46f, 46f, 53f, 47f, 82f, 40f, 84f, 33f, 62f, region = BodyRegion.UPPER_BACK),
    Bar(44.5f, 64f, 55.5f, 91f, 4f, BodyRegion.LOWER_BACK),
    Oval(24f, 61f, 5f, 10.5f, BodyRegion.TRICEPS),
    Oval(42f, 103f, 8.5f, 9f, BodyRegion.GLUTES),
    Oval(40.5f, 131f, 8.5f, 18f, BodyRegion.HAMSTRINGS),
    Oval(39.5f, 166f, 6.5f, 15f, BodyRegion.CALVES),
)

private val Idle = Color(0xFF363A41)
private val Low = Color(0xFF45561B)

/** Цвет участка: без нагрузки — серый, дальше от тёмно-зелёного к лаймовому. */
fun loadColor(t: Double): Color = if (t <= 0) Idle else lerp(Low, C.Lime, (0.15 + 0.85 * t).toFloat().coerceIn(0f, 1f))

/** Тело спереди и сзади, участки подсвечены по подходам за период. */
@Composable
fun BodyMap(stats: Stats, modifier: Modifier = Modifier) {
    val max = stats.regions.maxOfOrNull { it.sets }?.takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Спереди" to front, "Сзади" to back).forEach { (title, parts) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Figure(parts, { r -> stats.load(r) / max }, Modifier.fillMaxWidth(0.82f).aspectRatio(0.5f))
                    Txt(title, T.small, Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("нет", T.small)
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(loadColor(it))) }
            Txt("больше всего подходов", T.small)
        }
    }
}

@Composable
private fun Figure(parts: List<Part>, load: (BodyRegion) -> Double, modifier: Modifier) {
    val paths = remember(parts) { parts.flatMap { p -> listOfNotNull(p to p.path(false), if (p.pair) p to p.path(true) else null) } }
    Canvas(modifier) {
        val k = size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            paths.forEach { (part, path) ->
                drawPath(path, part.region?.let { loadColor(load(it)) } ?: C.Field)
                drawPath(path, C.Bg, style = Stroke(0.9f))
            }
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
