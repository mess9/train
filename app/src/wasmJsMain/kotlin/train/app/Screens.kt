package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.client.Link
import train.core.BODYWEIGHT
import train.core.ItemComparison
import train.core.Measurement
import train.core.RecordType
import train.core.SetValues
import train.core.close
import train.core.comment
import train.core.compare
import train.core.measure
import train.core.newId
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Composable
fun Technique(app: AppState, exerciseId: Uuid) {
    val ex = app.exercise(exerciseId)
    Page {
        BackLink(if (app.session?.active != null) "К тренировке" else "Назад", app::back)
        Txt(ex?.nameRu ?: "Упражнение не в справочнике", T.h1, Modifier.padding(bottom = 14.dp))
        val key = ex?.mediaKey
        if (key != null) {
            Gif(app, key, Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            Column(
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(C.Card),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ico(Icon.Dumbbell, 36.dp, C.Muted, 1.6f)
                Txt("Медиа для этого упражнения нет", T.meta)
            }
        }
        if (ex == null) return@Page
        Section("Мышцы")
        val muscles = ex.musclesPrimary.joinToString(", ", transform = ::muscle).ifEmpty { "не указаны" } +
            (ex.musclesSecondary.takeIf { it.isNotEmpty() }?.let { " · вспомогательные: " + it.joinToString(", ", transform = ::muscle) } ?: "")
        Txt(muscles, ts(15.sp, C.Soft))
        Section("Как делать")
        if (ex.instructions.isEmpty()) Txt("Описания нет", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ex.instructions.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("${i + 1}.", ts(15.sp, C.Muted, FontWeight.SemiBold), Modifier.width(22.dp))
                    Txt(step, ts(15.sp, C.Soft), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Surface(modifier, radius = 14.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(label, ts(12.sp, C.Muted))
            Txt(value, ts(20.sp, weight = FontWeight.Bold))
        }
    }
}

@Composable
fun Finish(app: AppState) {
    val s = app.session
    val w = s?.workout
    if (w == null || w.active) {
        LaunchedEffect(Unit) { app.root(if (w != null) Screen.Workout else Screen.Home) }
        return
    }
    var text by remember(w.id) { mutableStateOf(w.comment.orEmpty()) }
    val bodyweight = app.cache.measurements.filter { it.type == BODYWEIGHT && !it.deleted && it.measuredAt <= w.startedAt }
        .maxByOrNull { it.measuredAt }?.value
    val c = remember(w, s.sets) {
        // Как в /compare на сервере: подходы до замены ссылаются на прежнее упражнение — его в раскладке уже нет
        val ids = w.layout.blocks.flatMap { b -> b.items.flatMap { listOfNotNull(it.exerciseId, it.replacedFrom) } }.toSet() +
            s.sets.map { it.exerciseId }
        compare(w, s.sets, ids.mapNotNull { app.exercise(it) }.associateBy { it.id }, s.previous, bodyweight)
    }
    val t = c.totals

    fun leave(to: Screen) {
        val done = app.session?.workout ?: return
        app.act { close() }
        app.rememberWorkout(done)
        wake(false)
        app.root(to)
    }

    Page {
        Header("Тренировка завершена", app.workoutTitle(w))
        Gap(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Время", t.durationSec?.let { hms(it.seconds) } ?: "—", Modifier.weight(1f))
            Stat("Подходы из плана", "${t.setsPlanned - t.setsSkipped} из ${t.setsPlanned}", Modifier.weight(1f))
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Объём", thousands(t.volumeKg.roundToLong()) + " кг" + (if (t.volumeUnknownSets > 0) "*" else ""), Modifier.weight(1f))
            Stat("Темп", t.paceSecPerKg?.takeIf { it > 0 }?.let { "${(60 / it).roundToLong()} кг/мин" } ?: "—", Modifier.weight(1f))
        }
        if (t.volumeUnknownSets > 0) {
            Gap(6.dp)
            Txt("* без ${t.volumeUnknownSets} ${plural(t.volumeUnknownSets, "подхода", "подходов", "подходов")}: нет замера веса тела", T.small)
        }
        Gap(14.dp)
        Txt(
            "Выполнено ${t.setsDone} ${plural(t.setsDone, "подход", "подхода", "подходов")} · добавлено ${t.setsAdded} · пропущено ${t.setsSkipped}. " +
                "Разбор «план против факта» сделает агент.",
            ts(15.sp, C.Soft),
        )
        Gap(18.dp)
        Txt("Комментарий для агента", ts(13.sp, C.Muted, FontWeight.Medium), Modifier.padding(bottom = 6.dp))
        TextArea(text, {
            text = it
            app.act { comment(it) }
        }, "Как прошло, что болело, что поменять")
        Gap(16.dp)
        Surface(Modifier.fillMaxWidth(), Color(0xFF1E2414), Color(0xFF3B4A16), 16.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Записать замеры и фото?", T.strong)
                val last = app.cache.measurements.filterNot { it.deleted }.maxByOrNull { it.measuredAt }
                Txt(last?.let { "Последние замеры — ${it.measuredAt.date().dayMonth()}" } ?: "Замеров ещё не было", T.kicker)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Замеры", { leave(Screen.Measurements) }, Modifier.weight(1f))
                    Btn("Не сейчас", { leave(Screen.Home) }, Modifier.weight(1f), Kind.Ghost)
                }
            }
        }
    }
}

@Composable
fun History(app: AppState) {
    val list = app.cache.workouts.filter { !it.deleted && it.finishedAt != null }.sortedByDescending { it.startedAt }
    Page {
        Header("Что было", "История")
        StatusTags(app)
        Gap(8.dp)
        if (list.isEmpty()) Txt(if (app.cache.savedAt == null) "История ещё не загружена" else "Пока пусто", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { w ->
                val minutes = (w.finishedAt!! - w.startedAt).inWholeMinutes
                PlanRow(w.startedAt.date().short(), app.workoutTitle(w), { Txt("$minutes мин", T.meta) }) { app.go(Screen.HistoryDetail(w.id)) }
            }
        }
    }
}

@Composable
fun HistoryDetail(app: AppState, id: Uuid) {
    val w = app.cache.workouts.firstOrNull { it.id == id }
    LaunchedEffect(id) { app.loadComparison(id) }
    val c = app.comparisons[id]
    Page {
        BackLink("История", app::back)
        if (w == null) {
            Txt("Тренировка не найдена", T.h1)
            return@Page
        }
        val minutes = w.finishedAt?.let { (it - w.startedAt).inWholeMinutes }
        Txt(w.startedAt.date().short() + (minutes?.let { " · $it мин" } ?: ""), T.kicker)
        Txt(app.workoutTitle(w), T.h1, Modifier.padding(top = 2.dp))
        w.comment?.let {
            Gap(12.dp)
            Surface(Modifier.fillMaxWidth(), C.Field, radius = 12.dp) { Txt(it, ts(14.sp, C.Soft), Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) }
        }
        Section("Факт против плана")
        if (c == null) {
            Txt(if (app.link == Link.ONLINE) "Загружаю разбор…" else "Разбор придёт с сервера, когда будет связь", T.meta)
            return@Page
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            c.blocks.flatMap { it.items }.forEach { item -> HistoryLine(app, item) }
        }
        if (c.unplaced.isNotEmpty()) {
            Gap(8.dp)
            Txt("Ещё ${c.unplaced.size} ${plural(c.unplaced.size, "подход", "подхода", "подходов")} вне раскладки", T.meta)
        }
    }
}

@Composable
private fun HistoryLine(app: AppState, item: ItemComparison) {
    val ex = app.exercise(item.exerciseId)
    val type = item.sets.firstNotNullOfOrNull { it.fact?.recordType } ?: ex?.recordType ?: RecordType.WEIGHT_REPS
    val facts = item.sets.mapNotNull { it.fact }
    val fact = facts.joinToString(" · ") { f ->
        short(f.recordType, SetValues(f.weight, f.reps, f.extraWeight, f.seconds)) + (f.rating?.let { " " + it.letter() } ?: "")
    }.ifEmpty { "не сделано" }
    val targets = item.sets.mapNotNull { it.target }
    val plan = when {
        item.added -> "Не было в плане"
        item.replacedFrom != null -> "Замена «${app.exercise(item.replacedFrom!!)?.nameRu ?: "?"}»"
        targets.isEmpty() -> "Без цели"
        targets.map { target(type, it) }.distinct().size > 1 -> "План: ${targets.size} × пирамида"
        else -> "План: ${targets.size} × ${target(type, targets.first())}"
    } + (if (item.removed) " · убрано" else "")
    Surface(Modifier.fillMaxWidth(), radius = 12.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(item.name ?: ex?.nameRu ?: "Упражнение", T.strong)
            Txt(fact, ts(14.sp, C.Soft))
            Txt(plan, T.meta)
        }
    }
}

private data class MField(val type: String, val label: String, val unit: String, val short: String)

private val mfields = listOf(
    MField(BODYWEIGHT, "Вес тела, кг", "kg", "Вес"),
    MField("waist", "Талия, см", "cm", "Талия"),
    MField("chest", "Грудь, см", "cm", "Грудь"),
    MField("biceps", "Бицепс, см", "cm", "Бицепс"),
)

@Composable
fun Measurements(app: AppState) {
    var values by remember { mutableStateOf(mfields.associate { it.type to "" }) }
    var saved by remember { mutableStateOf(false) }
    val all = app.cache.measurements.filterNot { it.deleted }
    Page {
        Header("Тело", "Замеры")
        StatusTags(app)
        Gap(8.dp)
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
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(14.dp)) {
            Txt("Фото: спереди / сбоку / сзади — следующая итерация", T.meta)
        }
        Gap(14.dp)
        val ready = mfields.mapNotNull { f -> parseNum(values.getValue(f.type))?.takeIf { it > 0 }?.let { f to it } }
        Btn(if (saved) "Сохранено" else "Сохранить замеры", {
            val now = Clock.System.now()
            val ms = ready.map { (f, v) -> Measurement(newId(), f.type, v, f.unit, now) }
            app.act { ms.fold(this) { s, m -> s.measure(m) } }
            app.rememberMeasurements(ms)
            values = mfields.associate { it.type to "" }
            saved = true
        }, Modifier.fillMaxWidth(), big = true, enabled = ready.isNotEmpty())

        Section("Раньше")
        val days = all.groupBy { it.measuredAt.date() }.entries.sortedByDescending { it.key }.take(12)
        if (days.isEmpty()) Txt("Замеров ещё нет", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (day, ms) ->
                val text = mfields.mapNotNull { f -> ms.filter { it.type == f.type }.maxByOrNull { it.measuredAt }?.let { "${f.short} ${num(it.value)} ${if (f.unit == "kg") "кг" else "см"}" } }
                    .joinToString(" · ")
                PlanRow(day.short(), text.ifEmpty { "${ms.size} ${plural(ms.size, "замер", "замера", "замеров")}" }, {})
            }
        }
    }
}
