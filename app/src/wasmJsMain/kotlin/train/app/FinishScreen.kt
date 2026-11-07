package train.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import train.core.BODYWEIGHT
import train.core.Comparison
import train.core.ItemComparison
import train.core.ItemOutcome
import train.core.Measurement
import train.core.RecordType
import train.core.SetValues
import train.core.Totals
import train.core.Workout
import train.core.bodyweightFor
import train.core.close
import train.core.comment
import train.core.compare
import train.core.like
import train.core.newId
import train.core.outcome
import train.core.outcomes
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

// Итог тренировки: одна сводка, сердечки, комментарий, вес и фото — всё к этой тренировке

@Composable
fun StatTile(label: String, value: String, modifier: Modifier, icon: Icon? = null) {
    Surface(modifier, radius = 14.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (icon != null) Ico(icon, 14.dp, C.Muted)
                Txt(label, ts(12.sp, C.Muted), maxLines = 1)
            }
            Txt(value, ts(19.sp, weight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Время · объём · подходы из плана. `bodyweight` — с каким весом тела посчитан объём. */
@Composable
fun TotalsRow(t: Totals, bodyweight: Double?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Время", t.durationSec?.let { hms(it.seconds) } ?: "—", Modifier.weight(1f), Icon.Clock)
        StatTile("Объём", thousands(t.volumeKg.roundToLong()) + (if (t.volumeUnknownSets > 0) "*" else "") + " кг", Modifier.weight(1f), Icon.Dumbbell)
        StatTile("Подходы", if (t.setsPlanned > 0) "${t.setsPlanned - t.setsSkipped}/${t.setsPlanned}" else "${t.setsDone}", Modifier.weight(1f), Icon.Layers)
    }
    if (t.volumeUnknownSets > 0) {
        Gap(6.dp)
        val n = "${t.volumeUnknownSets} ${plural(t.volumeUnknownSets, "подхода", "подходов", "подходов")}"
        Txt(if (bodyweight == null) "* без $n с собственным весом: нет взвешивания" else "* без $n: не записан вес или повторы", T.small)
    }
}

fun ItemOutcome.icon() = when (this) {
    ItemOutcome.DONE -> Icon.Check
    ItemOutcome.PARTIAL -> Icon.Check
    ItemOutcome.SKIPPED -> Icon.Skip
    ItemOutcome.ADDED -> Icon.Plus
    ItemOutcome.REPLACED -> Icon.Swap
    ItemOutcome.REMOVED -> Icon.Close
}

fun ItemOutcome.color() = when (this) {
    ItemOutcome.DONE, ItemOutcome.ADDED -> C.Easy
    ItemOutcome.PARTIAL, ItemOutcome.REPLACED -> C.Normal
    ItemOutcome.SKIPPED, ItemOutcome.REMOVED -> C.Faint
}

fun ItemOutcome.label(n: Int) = when (this) {
    ItemOutcome.DONE -> plural(n, "сделано", "сделано", "сделано")
    ItemOutcome.PARTIAL -> "не до конца"
    ItemOutcome.SKIPPED -> plural(n, "пропущено", "пропущено", "пропущено")
    ItemOutcome.ADDED -> plural(n, "добавлено", "добавлено", "добавлено")
    ItemOutcome.REPLACED -> plural(n, "заменено", "заменено", "заменено")
    ItemOutcome.REMOVED -> plural(n, "убрано", "убрано", "убрано")
}

/** Сводка по упражнениям значками: «✓ 5 сделано · ⊘ 1 пропущено · ♥ 2». */
@Composable
fun OutcomeChips(c: Comparison) {
    val o = c.outcomes()
    val hearts = c.blocks.sumOf { b -> b.items.count { it.liked } }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ItemOutcome.entries.forEach { k ->
            val n = o[k] ?: 0
            if (n > 0) Chip(k.icon(), "$n ${k.label(n)}", k.color())
        }
        if (hearts > 0) Chip(Icon.Heart, "$hearts", C.Heart, fill = true)
    }
}

@Composable
private fun Chip(icon: Icon, text: String, color: Color, fill: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Ico(icon, 14.dp, color, fill = fill)
        Txt(text, ts(12.sp, C.Soft, FontWeight.Medium), maxLines = 1)
    }
}

/** Подходы упражнения одной строкой: «80×10 · 80×8 · 75×8». */
fun factLine(item: ItemComparison, fallback: RecordType): String =
    item.sets.mapNotNull { it.fact }.joinToString(" · ") { f -> short(f.recordType, SetValues(f.weight, f.reps, f.extraWeight, f.seconds)) }
        .ifEmpty { if (item.sets.isEmpty()) "без подходов" else "не сделано" }

/** Сравнение на телефоне — как `/compare` на сервере, пока тренировка не ушла. */
@Composable
fun rememberLocalComparison(app: AppState, w: Workout): Comparison {
    val s = app.session!!
    val bodyweight = bodyweightFor(w, app.cache.measurements)
    return remember(w, s.sets, bodyweight) {
        // Подходы до замены ссылаются на прежнее упражнение — его в раскладке уже нет
        val ids = w.layout.blocks.flatMap { b -> b.items.flatMap { listOfNotNull(it.exerciseId, it.replacedFrom) } }.toSet() +
            s.sets.map { it.exerciseId }
        compare(w, s.sets, ids.mapNotNull { app.exercise(it) }.associateBy { it.id }, s.previous, bodyweight)
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
    val c = rememberLocalComparison(app, w)
    LaunchedEffect(Unit) { app.progress.loadPhotos() }
    var text by remember(w.id) { mutableStateOf(w.comment.orEmpty()) }

    fun leave(to: Screen) {
        val done = app.session?.workout ?: return
        app.act { close() }
        app.rememberWorkout(done)
        wake(false)
        app.root(to)
    }

    Page {
        Header("Тренировка завершена", app.workoutTitle(w))
        Txt(w.startedAt.date().long() + " · " + w.startedAt.clock() + "–" + w.finishedAt!!.clock(), T.kicker)
        Gap(12.dp)
        TotalsRow(c.totals, c.bodyweightKg)
        Gap(12.dp)
        OutcomeChips(c)

        Section("Упражнения")
        Txt("Сердечко — очень понравилось: агент чаще вернёт упражнение в план", T.small, Modifier.padding(bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val tints = tints(c.blocks.map { it.role })
            c.blocks.forEachIndexed { bi, b ->
                b.items.forEach { item ->
                    FinishItem(app, item, tints[bi]) { likeHaptic(!item.liked); app.act { like(b.blockId, item.itemId, !item.liked) } }
                }
            }
        }

        Section("Комментарий для агента")
        TextArea(text, {
            text = it
            app.act { comment(it) }
        }, "Как прошло, что болело, что поменять")

        Section("Вес после тренировки")
        WorkoutWeight(app, w)

        Section("Фото")
        val photos = app.progress.photos.filter { it.workoutId == w.id }
        if (photos.isNotEmpty()) {
            PhotoGrid(app, photos)
            Gap(8.dp)
        }
        PhotoButtons(app, w.id)

        Gap(22.dp)
        Btn("Готово", { leave(Screen.Home) }, Modifier.fillMaxWidth(), big = true)
        Gap(8.dp)
        Btn("Все замеры и прогресс", { leave(Screen.Measurements) }, Modifier.fillMaxWidth(), Kind.Ghost)
    }
}

@Composable
private fun FinishItem(app: AppState, item: ItemComparison, tint: Tint, onLike: () -> Unit) {
    val ex = app.exercise(item.exerciseId)
    val out = item.outcome
    val grey = out == ItemOutcome.SKIPPED || out == ItemOutcome.REMOVED
    Surface(Modifier.fillMaxWidth(), radius = 12.dp) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Thumb(app, ex, tint, 40.dp, grey = grey)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(item.name ?: ex?.nameRu ?: "Упражнение", ts(14.sp, if (grey) C.Muted else C.Text, FontWeight.SemiBold), maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Ico(out.icon(), 13.dp, out.color())
                    Txt(factLine(item, ex?.recordType ?: RecordType.WEIGHT_REPS), ts(12.sp, C.Muted), Modifier.weight(1f, fill = false), maxLines = 1)
                    item.sets.mapNotNull { it.fact }.forEach { RateDot(it.rating, 7.dp) }
                }
            }
            IconBtn(Icon.Heart, onLike, 44.dp, if (item.liked) C.Heart else C.Dash, fill = item.liked, iconSize = 22.dp,
                label = "Сердечко: ${item.name ?: ex?.nameRu ?: "упражнение"}")
        }
    }
}

/** Взвешивание этой тренировки: по нему считан её объём, в истории оно рядом с ней. */
@Composable
fun WorkoutWeight(app: AppState, w: Workout) {
    val mine = app.cache.measurements.filter { it.type == BODYWEIGHT && it.workoutId == w.id && !it.deleted }.maxByOrNull { it.measuredAt }
    var value by remember(w.id, mine?.value) { mutableStateOf(mine?.value?.let(::num).orEmpty()) }
    val before = app.cache.measurements.filter { it.type == BODYWEIGHT && !it.deleted && it.workoutId != w.id && it.measuredAt <= w.startedAt }
        .maxByOrNull { it.measuredAt }
    val parsed = parseNum(value)?.takeIf { it in 20.0..400.0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(56.dp).height(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Ico(Icon.Scale, 26.dp, if (mine != null) C.Lime else C.Muted) }
        NumField(
            value, { value = it }, Modifier.weight(1f).height(48.dp), decimal = true,
            placeholder = before?.let { num(it.value) } ?: "кг", style = ts(18.sp, weight = FontWeight.SemiBold), unit = "кг",
        )
        Btn(
            if (mine != null && parsed == mine.value) "Записан" else if (mine != null) "Исправить" else "Записать",
            {
                val v = parsed ?: return@Btn
                app.saveMeasurements(listOf(mine?.copy(value = v) ?: Measurement(newId(), BODYWEIGHT, v, "kg", Clock.System.now(), workoutId = w.id)))
            },
            Modifier.width(112.dp), enabled = parsed != null && parsed != mine?.value,
        )
    }
    Gap(6.dp)
    Txt(
        when {
            mine != null -> "Привязан к этой тренировке: объём с собственным весом считан с ${num(mine.value)} кг"
            before != null -> "Пока объём считан с прошлого взвешивания — ${num(before.value)} кг, ${before.measuredAt.date().dayMonth()}"
            else -> "Взвешиваний ещё не было: подходы с собственным весом не входят в объём"
        },
        T.small,
    )
}
