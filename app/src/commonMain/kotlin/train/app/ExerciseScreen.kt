package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import train.client.Link
import train.core.DoneSet
import train.core.Exercise
import train.core.ExerciseHistory
import train.core.HistorySession
import train.core.RecordType
import train.core.ScoreUnit
import train.core.SetValues
import train.core.Trend
import train.core.scoredSet
import train.core.comparableTrend
import train.core.hasComparablePlateau
import kotlin.uuid.Uuid

/** Одинаковая подпись у ссылки на фото и у названия — в том числе когда медиа нет. */
fun Modifier.exerciseLink(app: AppState, id: Uuid, name: String?) = clip(RoundedCornerShape(8.dp))
    .tap { app.go(Screen.Exercise(id)) }
    .semantics { contentDescription = "Упражнение и прогресс: ${name ?: "Упражнение"}" }

@Composable
fun ExerciseScreen(app: AppState, exerciseId: Uuid) {
    val ex = app.exercise(exerciseId)
    LaunchedEffect(exerciseId) { app.progress.loadExercise(exerciseId) }
    val loading = app.progress.exerciseLoading[exerciseId] == true
    val failed = app.progress.exerciseFailed[exerciseId] == true
    val history = app.progress.exercises[exerciseId]
    LaunchedEffect(exerciseId, app.link) {
        if (app.link == Link.ONLINE && failed) app.progress.loadExercise(exerciseId)
    }
    Page {
        Row(
            Modifier.fillMaxWidth().tap(app::back).semantics { contentDescription = "Назад из упражнения" }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Ico(Icon.Back, 18.dp, C.Muted)
            Txt(if (app.stack.dropLast(1).lastOrNull() == Screen.Workout) "К тренировке" else "Назад", T.meta)
        }
        val mediaKey = ex?.mediaKey
        if (mediaKey != null) Gif(app, mediaKey, Modifier.fillMaxWidth().aspectRatio(1f))
        else Column(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(C.Card),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Ico(Icon.Dumbbell, 36.dp, C.Muted, 1.6f)
            Txt("Медиа для этого упражнения нет", T.meta)
        }
        Gap(14.dp)
        Txt(ex?.nameRu ?: history?.name ?: "Упражнение не в справочнике", T.h1)
        when {
            // Загруженная история остаётся на экране и без связи: link общий, его роняет любой запрос.
            history != null -> {
                if (failed || app.link != Link.ONLINE) Txt("Показана последняя загруженная история — обновится, когда будет связь", T.small, Modifier.padding(top = 8.dp))
                ExerciseHistoryView(app, ex, history)
            }
            failed || app.link != Link.ONLINE -> {
                Gap(12.dp)
                Txt("История упражнения считается на сервере — придёт, когда будет связь", T.meta)
                Btn("Обновить историю", { app.progress.loadExercise(exerciseId) }, kind = Kind.Ghost, enabled = !loading)
            }
            else -> Txt("Загружаю историю упражнения…", T.meta, Modifier.padding(top = 12.dp))
        }
        Section("Мышцы")
        val muscles = ex?.musclesPrimary.orEmpty().joinToString(", ", transform = ::muscle).ifEmpty { "не указаны" } +
            (ex?.musclesSecondary?.takeIf { it.isNotEmpty() }?.let { " · вспомогательные: " + it.joinToString(", ", transform = ::muscle) } ?: "")
        Txt(muscles, ts(15.sp, C.Soft))
        Section("Как делать")
        if (ex?.instructions.isNullOrEmpty()) Txt("Описания нет", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ex?.instructions.orEmpty().forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("${i + 1}.", ts(15.sp, C.Muted, FontWeight.SemiBold), Modifier.width(22.dp))
                    Txt(step, ts(15.sp, C.Soft), Modifier.weight(1f))
                }
            }
        }
    }
}

private fun DoneSet.exerciseText(ex: Exercise?): String {
    val implements = if (ex != null && ex.implements > 1 && weight != null &&
        recordType in listOf(RecordType.WEIGHT_REPS, RecordType.WEIGHT_TIME)) "${ex.implements}×" else ""
    return implements + short(recordType, SetValues(weight, reps, extraWeight, seconds)) + if (perSide) " на сторону" else ""
}

@Composable
private fun ExerciseHistoryView(app: AppState, ex: Exercise?, history: ExerciseHistory) {
    val record = history.record
    val best = record?.best
    if (best != null) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(Icon.Trophy, 18.dp, C.Record)
            Txt("Рекорд ${best.exerciseText(ex)} · ${record.date.dayMonth()} ${record.date.year}", T.strong, Modifier.weight(1f))
        }
        if (history.sinceRecord == 0) Txt("На последней тренировке", T.meta, Modifier.padding(top = 4.dp))
        else if (history.sinceRecord >= 3 && history.hasComparablePlateau) Txt("Плато ${history.sinceRecord} ${plural(history.sinceRecord, "тренировка", "тренировки", "тренировок")}", T.meta, Modifier.padding(top = 4.dp))
    }
    if (history.sessions == 0) {
        Txt("Выполненных подходов ещё нет", T.meta, Modifier.padding(top = 12.dp))
        return
    }
    val scored = history.recent.mapNotNull { session ->
        val score = session.score?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val best = session.best ?: return@mapNotNull null
        val unit = session.scoreUnit ?: scoredSet(best, ex?.bodyweightFactor, session.bodyweightKg)?.unit ?: return@mapNotNull null
        Triple(session, score, unit)
    }
    Section("Прогресс")
    if (scored.isEmpty()) Txt("Для графика не хватает значений подходов", T.meta)
    else {
        val units = scored.map { it.third }.toSet()
        var selectedUnit by rememberSaveable(history.exerciseId.toString()) { mutableStateOf<String?>(null) }
        val unit = units.firstOrNull { it.name == selectedUnit } ?: units.first()
        val visible = scored.filter { it.third == unit }
        if (units.size > 1) {
            Txt("В истории разные показатели — выбери график", T.small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                units.forEach { option ->
                    Btn(option.axis(), { selectedUnit = option.name }, kind = if (option == unit) Kind.ChipOn else Kind.Chip)
                }
            }
            Gap(8.dp)
        }
        Txt(unit.axis(), T.meta)
        LineChart(visible.map { (session, score, _) ->
            ChartPoint(session.date.atStartOfDayIn(TimeZone.currentSystemDefault()), score, true, session.best!!.exerciseText(ex))
        }, unit.axis(), title = "Прогресс упражнения")
        Txt(unit.explanation(), T.small, Modifier.padding(top = 6.dp))
        if (visible.size == 1) Txt("Одна тренировка — линия появится со второй", T.small, Modifier.padding(top = 4.dp))
        if (history.sessions > history.recent.size) Txt("На графике последние ${history.recent.size} тренировок; более ранние — по месяцам ниже", T.small, Modifier.padding(top = 4.dp))
    }
    Section("Последние")
    var count by rememberSaveable(history.exerciseId.toString()) { mutableStateOf(8) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        history.recent.take(count).forEach { ExerciseSessionRow(app, ex, it) }
        if (count < history.recent.size) Btn("Ещё тренировки (${history.recent.size - count})", { count += 8 }, Modifier.fillMaxWidth(), Kind.Ghost)
    }
    if (history.earlier.isNotEmpty()) {
        Section("Раньше")
        Txt("Лучшая тренировка каждого месяца", T.small, Modifier.padding(bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.earlier.forEach { month ->
                ExerciseSessionRow(app, ex, month.best, "${LocalDate.parse("${month.month}-01").monthAlone()} ${month.month.take(4)} · ${month.sessions} ${plural(month.sessions, "тренировка", "тренировки", "тренировок")}")
            }
        }
    }
}

private fun ScoreUnit.axis() = when (this) {
    ScoreUnit.ESTIMATED_KG -> "≈ кг на 1 повтор"
    ScoreUnit.REPS -> "Повторы"
    ScoreUnit.REPS_PLUS_KG -> "Повторы + доп. вес"
    ScoreUnit.SECONDS -> "Секунды"
    ScoreUnit.KG_SECONDS -> "кг × с"
}

private fun ScoreUnit.explanation() = when (this) {
    ScoreUnit.ESTIMATED_KG -> "Лучший подход тренировки по оценке Эпли: вес × (1 + повторы / 30). Для упражнений с долей веса тела учитывается свой вес и дополнительный груз. Это оценка, а не измеренный максимум."
    ScoreUnit.REPS -> "Лучший подход тренировки по числу повторов. Если для упражнения задана доля веса тела, но взвешиваний нет, оценка считается без своего веса."
    ScoreUnit.REPS_PLUS_KG -> "Оценка истории: повторы + дополнительный вес в кг. Свой вес не учтён: доля веса тела не задана или нет взвешивания."
    ScoreUnit.SECONDS -> "Лучший подход тренировки по длительности в секундах."
    ScoreUnit.KG_SECONDS -> "Лучший подход тренировки по произведению веса одного снаряда на время в секундах."
}

@Composable
private fun ExerciseSessionRow(app: AppState, ex: Exercise?, session: HistorySession, month: String? = null) {
    val trend = when (session.comparableTrend) { Trend.UP -> "▲"; Trend.SAME -> "="; Trend.DOWN -> "▼"; null -> "" }
    val trendText = when (session.comparableTrend) { Trend.UP -> "выше прошлой"; Trend.SAME -> "так же"; Trend.DOWN -> "ниже прошлой"; null -> "нет сравнения" }
    val facts = session.sets.joinToString(" · ") { it.exerciseText(ex) }
    val date = "${session.date.dayMonth()} ${session.date.year}"
    Surface(Modifier.fillMaxWidth().tap { app.go(Screen.HistoryDetail(session.workoutId)) }.semantics {
        contentDescription = "Тренировка упражнения: $date, $facts, $trendText" +
            (if (session.liked) ", понравилось" else "") + (if (session.disliked) ", не понравилось" else "")
    }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            month?.let { Txt(it, T.kicker) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(date, T.strong, Modifier.weight(1f))
                Txt(trend, T.meta)
                if (session.liked) Ico(Icon.Heart, 16.dp, C.Heart, fill = true)
                if (session.disliked) Ico(Icon.Dislike, 16.dp, C.Dislike, fill = true)
                Ico(Icon.Next, 14.dp, C.Muted)
            }
            Txt(facts.ifEmpty { "Нет записанных значений" }, T.meta)
        }
    }
}
