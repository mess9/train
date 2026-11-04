package train.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import train.core.Plan
import train.core.PlanStatus
import train.core.finish
import train.core.forgottenEnd
import train.core.planStatus
import train.core.snapshot
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Текущее время, раз в секунду. */
@Composable
fun rememberNow(): Instant {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(1000 - Clock.System.now().toEpochMilliseconds() % 1000)
            value = Clock.System.now()
        }
    }
    return now
}

private fun Plan.passes(i: Int) = body.blocks[i].items.maxOfOrNull { it.sets.size } ?: 0

private fun Plan.blocksLine() = body.blocks.mapIndexed { i, b ->
    blockTitle(b.role, b.title, i) + (passes(i).takeIf { it > 1 && b.items.size > 1 }?.let { " × $it" } ?: "")
}.joinToString(" · ")

private fun Plan.stats(): String {
    val ex = body.blocks.sumOf { it.items.size }
    val sets = body.blocks.sumOf { b -> b.items.sumOf { it.sets.size } }
    return "$ex ${plural(ex, "упражнение", "упражнения", "упражнений")} · $sets ${plural(sets, "подход", "подхода", "подходов")}"
}

@Composable
fun Home(app: AppState) {
    val now = rememberNow()
    val today = now.date()
    val s = app.session
    val active = s?.active
    var forgottenDismissed by remember { mutableStateOf(false) }
    val plans = app.cache.plans.filter { it.deletedAt == null }
        .map { it to planStatus(it.date, it.startedAt, today) }

    Page {
        Header(today.long(), "Тренировки") { IconBtn(Icon.Gear, { app.go(Screen.Settings) }, color = C.Muted) }
        StatusTags(app)

        if (active != null) {
            val end = s.forgottenEnd(now)
            if (end != null && !forgottenDismissed) {
                Gap(12.dp)
                Surface(Modifier.fillMaxWidth(), Color(0xFF1E2414), Color(0xFF3B4A16), 16.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val since = active.startedAt.let { if (it.date() == today) it.clock() else it.date().dayMonth() }
                        Txt("Тренировка с $since не закрыта", T.strong)
                        Txt("Закрыть её временем последней отметки + 5 минут — ${end.clock()}?", T.meta)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Закрыть", {
                                app.act { finish(end) }
                                wake(false)
                                app.go(Screen.Finish)
                            }, Modifier.weight(1f))
                            Btn("Продолжить", { forgottenDismissed = true }, Modifier.weight(1f), Kind.Ghost)
                        }
                    }
                }
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Lime)
                    .tap { app.go(Screen.Workout) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(C.OnLime))
                Txt("Тренировка идёт · ${hms(now - active.startedAt)}", ts(color = C.OnLime, weight = FontWeight.SemiBold), Modifier.weight(1f))
                Txt("Вернуться", ts(color = C.OnLime, weight = FontWeight.SemiBold).copy(textDecoration = TextDecoration.Underline))
            }
        } else if (s?.workout != null) {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Field)
                    .tap { app.go(Screen.Finish) }.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Txt("Тренировка завершена — итоги и комментарий", T.strong, Modifier.weight(1f))
                Txt("Открыть", ts(color = C.Lime, weight = FontWeight.Bold))
            }
        }

        Section("Сегодня")
        val todays = plans.filter { it.first.date == today }
        val open = todays.filter { it.second != PlanStatus.STARTED || it.first.id == active?.planId }
        val done = todays.filter { it.second == PlanStatus.STARTED && it.first.id != active?.planId }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            open.forEach { (p, _) -> TodayCard(p, p.id == active?.planId) { app.go(Screen.PlanView(p.id)) } }
            done.forEach { (p, _) -> DoneCard(app, p) }
            if (todays.isEmpty()) {
                Txt(if (app.cache.savedAt == null) "Планы ещё не загружены" else "На сегодня плана нет", T.meta)
                if (active == null) Btn("Начать без плана", { app.startWorkout(null) }, Modifier.fillMaxWidth(), Kind.Ghost)
            }
        }

        val upcoming = plans.filter { it.first.date > today && it.second == PlanStatus.PLANNED }.sortedBy { it.first.date }.take(6)
        if (upcoming.isNotEmpty()) {
            Section("Ближайшие")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                upcoming.forEach { (p, _) -> PlanRow(p.date.short(), p.title ?: "Тренировка", { Txt(p.stats(), T.meta) }) { app.go(Screen.PlanView(p.id)) } }
            }
        }
        val overdue = plans.filter { it.second == PlanStatus.OVERDUE }.sortedByDescending { it.first.date }.take(6)
        if (overdue.isNotEmpty()) {
            Section("Просрочено")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                overdue.forEach { (p, _) -> PlanRow(p.date.short(), p.title ?: "Тренировка", { LateTag("ждёт агента") }) { app.go(Screen.PlanView(p.id)) } }
            }
        }
    }
}

@Composable
private fun TodayCard(p: Plan, going: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().tap(onClick), border = C.Lime, radius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(p.title ?: "Тренировка", ts(20.sp, weight = FontWeight.Bold), Modifier.weight(1f))
                if (going) AccentTag("идёт")
            }
            Txt(p.blocksLine(), ts(14.sp, C.Soft))
            Txt("${p.stats()} · план от агента", T.meta)
        }
    }
}

@Composable
private fun DoneCard(app: AppState, p: Plan) {
    val w = app.cache.workouts.firstOrNull { it.planId == p.id }
    val minutes = w?.finishedAt?.let { ((it - w.startedAt).inWholeMinutes).toInt() }
    Surface(Modifier.fillMaxWidth().then(if (w != null) Modifier.tap { app.go(Screen.HistoryDetail(w.id)) } else Modifier), border = C.Border, radius = 18.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(p.title ?: "Тренировка", ts(20.sp, weight = FontWeight.Bold), Modifier.weight(1f))
                // Начата на другом устройстве и не закрыта — вести её отсюда MVP не умеет
                if (w?.finishedAt == null) Tag("не закончена") else AccentTag("выполнено")
            }
            Txt((minutes?.let { "$it мин · " } ?: "") + "агент проанализирует и предложит следующие", T.meta)
        }
    }
}

@Composable
fun PlanRow(date: String, title: String, trailing: @Composable () -> Unit, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card)
            .then(if (onClick != null) Modifier.tap(onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt(date, ts(13.sp, C.Muted, FontWeight.Medium), Modifier.width(76.dp))
        Txt(title, T.strong, Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun BlockDivider(title: String, sub: String, tint: Tint, top: Dp = 22.dp, bottom: Dp = 10.dp) {
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = bottom), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(title, ts(17.sp, tint.tint, FontWeight.Bold))
        if (sub.isNotEmpty()) Txt(sub, T.meta)
        Box(Modifier.weight(1f).height(1.dp).background(tint.line))
    }
}

@Composable
fun PlanView(app: AppState, id: Uuid) {
    val p = app.plan(id)
    val today = app.today()
    Page {
        BackLink("Назад", app::back)
        if (p == null) {
            Txt("План не найден", T.h1)
            return@Page
        }
        val status = planStatus(p.date, p.startedAt, today)
        val whenText = if (p.date == today) "Сегодня" else p.date.short()
        Txt("$whenText · план от агента", T.kicker)
        Txt(p.title ?: "Тренировка", T.h1, Modifier.padding(top = 2.dp))
        p.body.note?.let {
            Gap(12.dp)
            Surface(Modifier.fillMaxWidth(), C.Field, radius = 12.dp) { Txt(it, ts(14.sp, C.Soft), Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) }
        }
        val layout = remember(p) { p.body.snapshot(app::exercise) }
        val tints = tints(layout.blocks.map { it.role })
        layout.blocks.forEachIndexed { i, b ->
            val passes = b.items.maxOfOrNull { it.sets.size } ?: 0
            val circuit = passes > 1 && b.items.size > 1
            val sub = if (circuit) "× $passes ${plural(passes, "проход", "прохода", "проходов")}" + (b.restAfterPassSec?.let { " · отдых $it с" } ?: "") else ""
            BlockDivider(blockTitle(b.role, b.title, i), sub, tints[i])
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.items.forEach { item ->
                    val ex = app.exercise(item.exerciseId)
                    Surface(Modifier.fillMaxWidth(), tints[i].card, tints[i].line, 12.dp) {
                        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Thumb(app, ex, tints[i], 40.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Txt(ex?.nameRu ?: "Упражнение не в справочнике", T.strong)
                                Txt(planTarget(b, item, if (circuit) passes else 1), T.meta)
                                item.note?.let { Txt(it, T.small) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val active = app.session?.active
        when {
            active != null -> Btn(if (active.planId == p.id) "Вернуться к тренировке" else "Идёт другая тренировка — вернуться", { app.go(Screen.Workout) }, Modifier.fillMaxWidth(), big = true)
            status == PlanStatus.STARTED -> Txt("Тренировка по этому плану уже была", T.meta)
            else -> Btn("Начать тренировку", { app.startWorkout(p) }, Modifier.fillMaxWidth(), big = true)
        }
    }
}
