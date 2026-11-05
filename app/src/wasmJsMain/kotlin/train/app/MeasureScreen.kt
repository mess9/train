package train.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.client.Link
import train.core.BODYWEIGHT
import train.core.Measurement
import train.core.Stats
import train.core.StatsPeriod
import train.core.buckets
import train.core.minutesBy
import train.core.newId
import train.core.workoutForMeasurement
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Clock

// «Замеры» как профиль: период, карта мышц и роза, часы, вес и рост, фото, ручные замеры

private data class MField(val type: String, val label: String, val unit: String, val short: String)

private val mfields = listOf(
    MField(BODYWEIGHT, "Вес тела, кг", "kg", "Вес"),
    MField("waist", "Талия, см", "cm", "Талия"),
    MField("chest", "Грудь, см", "cm", "Грудь"),
    MField("biceps", "Бицепс, см", "cm", "Бицепс"),
)

private fun StatsPeriod.label() = when (this) {
    StatsPeriod.WEEK -> "Неделя"
    StatsPeriod.MONTH -> "Месяц"
    StatsPeriod.QUARTER -> "3 мес"
    StatsPeriod.YEAR -> "Год"
}

@Composable
fun Measurements(app: AppState) {
    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    val today = app.today()
    val buckets = remember(period, today) { period.buckets(today) }
    val from = buckets.first().from
    LaunchedEffect(from, today) { app.progress.loadStats(from, today) }
    LaunchedEffect(Unit) { app.progress.loadPhotos() }
    val st = app.progress.stats[from to today]
    val all = app.cache.measurements.filterNot { it.deleted }

    Page {
        Header("Тело и прогресс", "Замеры")
        StatusTags(app)
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatsPeriod.entries.forEach { p ->
                Btn(p.label(), { period = p }, Modifier.weight(1f), if (p == period) Kind.ChipOn else Kind.Chip)
            }
        }
        Gap(12.dp)
        if (st == null) {
            Txt(if (app.link == Link.ONLINE) "Считаю сводку…" else "Сводка за период считается на сервере — придёт, когда будет связь", T.meta)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Тренировок", "${st.workouts}", Modifier.weight(1f), Icon.Dumbbell)
                StatTile("Время", hm(st.minutes), Modifier.weight(1f), Icon.Clock)
                StatTile("Подходов", "${st.sets}", Modifier.weight(1f), Icon.Layers)
            }
            if (st.volumeKg > 0) Txt("Объём за период — ${thousands(st.volumeKg.roundToLong())} кг", T.small, Modifier.padding(top = 6.dp))
            Loads(st)
            Section("Часы тренировок")
            HoursChart(period, buckets, minutesBy(buckets, st.days))
            Txt(
                when (period) {
                    StatsPeriod.WEEK, StatsPeriod.MONTH -> "По дням"
                    StatsPeriod.QUARTER -> "По неделям, с понедельника"
                    StatsPeriod.YEAR -> "По месяцам"
                } + " · в среднем ${hm((st.minutes.toDouble() / buckets.size).roundToInt())} за " +
                    if (period == StatsPeriod.YEAR) "месяц" else if (period == StatsPeriod.QUARTER) "неделю" else "день",
                T.small, Modifier.padding(top = 6.dp),
            )
        }

        Section("Вес")
        val (points, inPeriod) = weightPoints(all, from)
        if (points.size < 2) Txt(if (points.isEmpty()) "Взвешиваний ещё нет" else "Одно взвешивание — ${num(points[0].value)} кг. Линия появится со вторым", T.meta)
        else {
            WeightChart(points)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("● после тренировки", T.small)
                Txt("○ отдельно", T.small)
                if (!inPeriod) Txt("последние ${points.size}: за период меньше двух", T.small)
            }
        }
        Gap(12.dp)
        Height(app)

        Section("Фото прогресса")
        PhotoButtons(app, null)
        Gap(10.dp)
        val photos = app.progress.photos
        when {
            photos.isNotEmpty() -> PhotoGrid(app, photos)
            app.progress.photosLoaded -> Txt("Фото ещё нет. Снимок из галереи можно отнести к прошлому дню", T.meta)
            app.link != Link.ONLINE -> Txt("Фото хранятся на сервере — покажу, когда будет связь", T.meta)
        }

        Section("Записать замер")
        NewMeasurements(app, all)

        Section("Раньше")
        val days = all.groupBy { it.measuredAt.date() }.entries.sortedByDescending { it.key }.take(12)
        if (days.isEmpty()) Txt("Замеров ещё нет", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (day, ms) ->
                val text = mfields.mapNotNull { f -> ms.filter { it.type == f.type }.maxByOrNull { it.measuredAt }?.let { "${f.short} ${num(it.value)} ${if (f.unit == "kg") "кг" else "см"}" } }
                    .joinToString(" · ")
                val linked = ms.firstNotNullOfOrNull { it.workoutId }
                PlanRow(day.short(), text.ifEmpty { "${ms.size} ${plural(ms.size, "замер", "замера", "замеров")}" }, {
                    if (linked != null) Ico(Icon.Dumbbell, 16.dp, C.Lime)
                }, linked?.takeIf { id -> app.cache.workouts.any { it.id == id } }?.let { id -> { app.go(Screen.HistoryDetail(id)) } })
            }
        }
    }
}

/** Карта мышц и роза: по подходам — вспомогательная мышца получает полподхода. */
@Composable
private fun Loads(st: Stats) {
    Section("Нагрузка по мышцам")
    if (st.sets == 0) {
        Txt("За период подходов нет", T.meta)
        return
    }
    BodyMap(st)
    val top = st.regions.filter { it.sets > 0 }.sortedByDescending { it.sets }.take(6)
    Txt(top.joinToString(" · ") { "${it.region.label()} ${sets(it.sets)}" }, ts(13.sp, C.Soft), Modifier.padding(top = 8.dp))
    Section("По частям тела")
    Rose(st)
}

@Composable
private fun Height(app: AppState) {
    val cm = app.cache.profile?.heightCm
    var value by remember(cm) { mutableStateOf(cm?.let(::num).orEmpty()) }
    val parsed = parseNum(value)?.takeIf { it in 50.0..260.0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Txt("Рост", T.strong)
            Txt("В профиле: агент видит его рядом с весом", T.small)
        }
        NumField(value, { value = it }, Modifier.width(84.dp).height(46.dp), decimal = true, placeholder = "—", unit = "см")
        Btn(if (parsed != null && parsed == cm) "Есть" else "Сохранить", { app.setHeight(parsed) }, Modifier.width(122.dp), enabled = parsed != null && parsed != cm)
    }
    app.profileError?.let {
        Gap(6.dp)
        LateTag(it)
    }
}

@Composable
private fun NewMeasurements(app: AppState, all: List<Measurement>) {
    var values by remember { mutableStateOf(mfields.associate { it.type to "" }) }
    var saved by remember { mutableStateOf(false) }
    mfields.chunked(2).forEach { pair ->
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { f ->
                val last = all.filter { it.type == f.type }.maxByOrNull { it.measuredAt }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(f.label, ts(13.sp, C.Muted, FontWeight.Medium))
                    NumField(
                        values.getValue(f.type),
                        { values = values + (f.type to it); saved = false },
                        Modifier.fillMaxWidth().height(48.dp),
                        decimal = true,
                        placeholder = last?.let { num(it.value) } ?: "—",
                        style = ts(18.sp, weight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
    val ready = mfields.mapNotNull { f -> parseNum(values.getValue(f.type))?.takeIf { it > 0 }?.let { f to it } }
    val now = Clock.System.now()
    // Замер сразу после тренировки — её замер: вес тела в её объёме, агент видит их рядом
    val workout = workoutForMeasurement(app.cache.workouts + listOfNotNull(app.session?.workout), now)
    if (workout != null && ready.isNotEmpty()) Txt("Привяжется к тренировке, которая только что закончилась", T.small, Modifier.padding(bottom = 8.dp))
    Btn(if (saved) "Сохранено" else "Сохранить замеры", {
        val at = Clock.System.now()
        val w = workoutForMeasurement(app.cache.workouts + listOfNotNull(app.session?.workout), at)
        app.saveMeasurements(ready.map { (f, v) -> Measurement(newId(), f.type, v, f.unit, at, workoutId = w) })
        values = mfields.associate { it.type to "" }
        saved = true
    }, Modifier.fillMaxWidth(), big = true, enabled = ready.isNotEmpty())
}
