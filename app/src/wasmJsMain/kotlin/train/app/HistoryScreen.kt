package train.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.client.Link
import train.core.BODYWEIGHT
import train.core.ItemComparison
import train.core.ItemOutcome
import train.core.Measurement
import train.core.RecordType
import train.core.SetStatus
import train.core.SetValues
import train.core.Workout
import train.core.outcome
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// История: список карточками и разбор одной тренировки — упражнение с картинкой, подходы строками

private val monthsNom = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

/** Значок и текст рядом — метка в строке карточки. */
@Composable
fun IconText(icon: Icon, text: String, color: Color = C.Muted, fill: Boolean = false, iconColor: Color = color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ico(icon, 14.dp, iconColor, fill = fill)
        Txt(text, ts(12.sp, color, FontWeight.Medium), maxLines = 1)
    }
}

private fun Workout.minutes() = finishedAt?.let { (it - startedAt).inWholeMinutes.toInt() }

/** Взвешивание тренировки, если было. */
fun AppState.weightOf(w: Workout): Measurement? =
    cache.measurements.filter { it.type == BODYWEIGHT && it.workoutId == w.id && !it.deleted }.maxByOrNull { it.measuredAt }

@Composable
fun History(app: AppState) {
    val list = app.cache.workouts.filter { !it.deleted && it.finishedAt != null }.sortedByDescending { it.startedAt }
    LaunchedEffect(Unit) { app.progress.loadPhotos() }
    Page {
        Header("Что было", "История")
        StatusTags(app)
        Gap(8.dp)
        if (list.isEmpty()) Txt(if (app.cache.savedAt == null) "История ещё не загружена" else "Пока пусто", T.meta)
        list.groupBy { (it.date ?: it.startedAt.date()).let { d -> d.year to d.month.ordinal } }.forEach { (ym, ws) ->
            Section("${monthsNom[ym.second]} ${ym.first}")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ws.forEach { w -> HistoryCard(app, w) }
            }
        }
    }
}

@Composable
private fun HistoryCard(app: AppState, w: Workout) {
    val items = w.layout.blocks.flatMap { it.items }.filterNot { it.removed }
    val hearts = items.count { it.liked }
    val weight = app.weightOf(w)
    val photos = app.progress.photos.count { it.workoutId == w.id }
    Surface(Modifier.fillMaxWidth().tap { app.go(Screen.HistoryDetail(w.id)) }, radius = 14.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt((w.date ?: w.startedAt.date()).short() + " · " + w.startedAt.clock(), T.kicker)
                Txt(app.workoutTitle(w), T.strong, maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    w.minutes()?.let { IconText(Icon.Clock, "$it мин") }
                    IconText(Icon.Dumbbell, "${items.size} упр")
                    if (hearts > 0) IconText(Icon.Heart, "$hearts", iconColor = C.Heart, fill = true)
                    weight?.let { IconText(Icon.Scale, num(it.value) + " кг") }
                    if (photos > 0) IconText(Icon.Camera, "$photos")
                    if (!w.comment.isNullOrBlank()) IconText(Icon.Note, "отзыв")
                }
            }
            Ico(Icon.Next, 18.dp, C.Faint)
        }
    }
}

@Composable
fun HistoryDetail(app: AppState, id: Uuid) {
    val w = app.cache.workouts.firstOrNull { it.id == id }
    LaunchedEffect(id) {
        app.loadComparison(id)
        app.progress.loadPhotos()
    }
    val c = app.comparisons[id]
    Page {
        BackLink("История", app::back)
        if (w == null) {
            Txt("Тренировка не найдена", T.h1)
            return@Page
        }
        Txt((w.date ?: w.startedAt.date()).long() + " · " + w.startedAt.clock() + (w.finishedAt?.let { "–" + it.clock() } ?: ""), T.kicker)
        Txt(app.workoutTitle(w), T.h1, Modifier.padding(top = 2.dp))
        Gap(12.dp)
        if (c != null) {
            TotalsRow(c.totals)
            Gap(10.dp)
            OutcomeChips(c)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Время", w.minutes()?.let { hms((it * 60L).seconds) } ?: "—", Modifier.weight(1f), Icon.Clock)
                StatTile("Упражнений", "${w.layout.blocks.sumOf { b -> b.items.count { !it.removed } }}", Modifier.weight(1f), Icon.Dumbbell)
            }
        }

        // Замеры этой тренировки: с сервера в разборе, до него — из кеша телефона
        val ms = (c?.measurements ?: app.cache.measurements.filter { it.workoutId == id && !it.deleted }).sortedBy { it.type != BODYWEIGHT }
        if (ms.isNotEmpty()) {
            Gap(12.dp)
            Surface(Modifier.fillMaxWidth(), C.Field, radius = 12.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(Icon.Scale, 20.dp, C.Lime)
                    Column(Modifier.weight(1f)) {
                        Txt(ms.joinToString(" · ") { measureText(it) }, ts(14.sp, C.Text, FontWeight.SemiBold))
                        Txt("Записано после тренировки" + if (ms.any { it.type == BODYWEIGHT }) " · объём с этим весом" else "", T.small)
                    }
                }
            }
        }
        w.comment?.takeIf { it.isNotBlank() }?.let {
            Gap(10.dp)
            Surface(Modifier.fillMaxWidth(), C.Field, radius = 12.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(Icon.Note, 18.dp, C.Muted)
                    Txt(it, ts(14.sp, C.Soft), Modifier.weight(1f))
                }
            }
        }
        val photos = app.progress.photos.filter { it.workoutId == id }
        if (photos.isNotEmpty()) {
            Section("Фото")
            PhotoGrid(app, photos)
        }

        if (c == null) {
            Section("Упражнения")
            Txt(if (app.link == Link.ONLINE) "Загружаю разбор…" else "Разбор придёт с сервера, когда будет связь", T.meta)
            return@Page
        }
        val tints = tints(c.blocks.map { it.role })
        c.blocks.forEachIndexed { bi, b ->
            if (b.items.isEmpty()) return@forEachIndexed
            BlockDivider(blockTitle(b.role, b.title, bi), "${b.items.size} упр", tints[bi])
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                b.items.forEach { ExerciseCard(app, it, tints[bi]) }
            }
        }
        if (c.unplaced.isNotEmpty()) {
            Gap(8.dp)
            Txt("Ещё ${c.unplaced.size} ${plural(c.unplaced.size, "подход", "подхода", "подходов")} вне раскладки", T.meta)
        }
    }
}

private val mlabels = mapOf(BODYWEIGHT to "Вес", "waist" to "Талия", "chest" to "Грудь", "biceps" to "Бицепс")

fun measureText(m: Measurement) =
    "${mlabels[m.type] ?: m.type} ${num(m.value)} ${if (m.unit == "kg") "кг" else if (m.unit == "cm") "см" else m.unit}"

@Composable
private fun ExerciseCard(app: AppState, item: ItemComparison, tint: Tint) {
    val ex = app.exercise(item.exerciseId)
    val type = item.sets.firstNotNullOfOrNull { it.fact?.recordType } ?: ex?.recordType ?: RecordType.WEIGHT_REPS
    val out = item.outcome
    val grey = out == ItemOutcome.SKIPPED || out == ItemOutcome.REMOVED
    Surface(Modifier.fillMaxWidth(), radius = 14.dp) {
        Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Thumb(app, ex, tint, 48.dp, grey = grey)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Txt(item.name ?: ex?.nameRu ?: "Упражнение", ts(15.sp, if (grey) C.Muted else C.Text, FontWeight.SemiBold), maxLines = 2)
                    when (out) {
                        ItemOutcome.ADDED -> IconText(Icon.Plus, "вне плана", C.Easy)
                        ItemOutcome.REPLACED -> IconText(Icon.Swap, "вместо «${item.replacedFrom?.let { app.exercise(it)?.nameRu } ?: "?"}»", C.Normal)
                        ItemOutcome.REMOVED -> IconText(Icon.Close, "убрано из тренировки", C.Faint)
                        ItemOutcome.SKIPPED -> IconText(Icon.Skip, "пропущено", C.Faint)
                        ItemOutcome.PARTIAL -> IconText(Icon.Check, "${item.sets.count { it.fact != null }} из ${item.sets.size} подходов", C.Normal)
                        ItemOutcome.DONE -> IconText(Icon.Check, "${item.sets.size} ${plural(item.sets.size, "подход", "подхода", "подходов")}", C.Easy)
                    }
                }
                if (item.liked) Ico(Icon.Heart, 22.dp, C.Heart, fill = true)
            }
            if (item.sets.isNotEmpty()) Gap(6.dp)
            item.sets.forEach { sc ->
                val f = sc.fact
                if (f != null) {
                    DoneSetLine(
                        sc.setNo, if (sc.status == SetStatus.ADDED) "доп" else null, f.recordType,
                        SetValues(f.weight, f.reps, f.extraWeight, f.seconds), f.rating, Modifier.padding(horizontal = 8.dp), done = false,
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SetNo(sc.setNo, null, C.Faint)
                        Txt("пропущен · план ${target(type, sc.target)}", ts(13.sp, C.Faint), Modifier.weight(1f))
                        Ico(Icon.Skip, 14.dp, C.Faint)
                        Box(Modifier.width(6.dp))
                    }
                }
                f?.note?.takeIf { it.isNotBlank() }?.let { Txt(it, ts(12.sp, C.Muted), Modifier.padding(start = 40.dp, end = 12.dp, bottom = 2.dp)) }
            }
            item.note?.takeIf { it.isNotBlank() }?.let {
                Txt("План: $it", T.small, Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
            }
            item.comment?.takeIf { it.isNotBlank() }?.let {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ico(Icon.Note, 14.dp, C.Muted)
                    Txt(it, ts(13.sp, C.Soft), Modifier.weight(1f))
                }
            }
        }
    }
}
