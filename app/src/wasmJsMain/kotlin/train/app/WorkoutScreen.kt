package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import train.core.BlockPasses
import train.core.Exercise
import train.core.LayoutBlock
import train.core.LayoutItem
import train.core.Rating
import train.core.RecordType
import train.core.RestKind
import train.core.SetRow
import train.core.SetValues
import train.core.addItem
import train.core.addSet
import train.core.edit
import train.core.finish
import train.core.last
import train.core.move
import train.core.rate
import train.core.removeItem
import train.core.removeSet
import train.core.replace
import train.core.setRecordType
import train.core.values
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private sealed interface WSheet {
    data object Tools : WSheet

    data class Menu(val blockId: Uuid, val itemId: Uuid) : WSheet

    /** `itemId` — заменить это упражнение, `null` — добавить в блок. */
    data class Add(val blockId: Uuid, val itemId: Uuid?) : WSheet

    data class ConfirmFinish(val undone: Int) : WSheet
}

@Composable
fun WorkoutScreen(app: AppState) {
    val s = app.session
    val w = s?.active
    if (w == null) {
        LaunchedEffect(Unit) { app.root(Screen.Home) }
        return
    }
    val now = rememberNow()
    var open by remember { mutableStateOf(setOf<Uuid>()) }
    var reorder by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<WSheet?>(null) }

    val rest = app.rest
    LaunchedEffect(rest) {
        if (rest == null) return@LaunchedEffect
        val left = rest.until - Clock.System.now()
        if (left.isPositive()) {
            delay(left)
            buzz()
        }
    }
    val timerUntil = app.tools.timerUntil
    LaunchedEffect(timerUntil) {
        if (timerUntil == null) return@LaunchedEffect
        val left = timerUntil - Clock.System.now()
        if (left.isPositive()) {
            delay(left)
            buzz()
        }
    }

    fun finishNow() {
        app.act { finish(Clock.System.now()) }
        app.rest = null
        wake(false)
        app.replace(Screen.Finish)
    }

    val passes = s.passes()
    val tints = tints(w.layout.blocks.map { it.role })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Шапка
            Row(
                Modifier.fillMaxWidth().background(C.Bar).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconBtn(Icon.Down, { app.root(Screen.Home) })
                Column(Modifier.weight(1f)) {
                    Txt(app.workoutTitle(w), ts(13.sp, C.Muted, FontWeight.Medium), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(hms(now - w.startedAt), ts(22.sp, weight = FontWeight.Bold))
                        val n = s.outbox.size
                        if (n > 0 || app.paused) Box(Modifier.clip(RoundedCornerShape(8.dp)).tap { app.go(Screen.Settings) }) {
                            Tag(if (app.paused) "пауза · $n" else "в очереди · $n")
                        }
                    }
                }
                IconBtn(Icon.Timer, { sheet = WSheet.Tools })
                Btn("Завершить", {
                    val undone = undoneCount(passes)
                    if (undone > 0) sheet = WSheet.ConfirmFinish(undone) else finishNow()
                })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
                if (reorder) {
                    Row(
                        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt("Режим порядка", T.strong, Modifier.weight(1f))
                        Txt("Готово", ts(color = C.Lime, weight = FontWeight.Bold), Modifier.clip(RoundedCornerShape(8.dp)).tap { reorder = false }.padding(4.dp))
                    }
                }
                passes.forEachIndexed { i, bp ->
                    val b = bp.block
                    val title = blockTitle(b.role, b.title, i)
                    val circuit = bp.passCount > 1 && bp.items.size > 1
                    val sub = when {
                        bp.items.isNotEmpty() && bp.finished -> "готово"
                        circuit -> "× ${bp.passCount} · проход ${bp.currentPass} из ${bp.passCount}"
                        else -> ""
                    }
                    BlockDivider(title, sub, tints[i])
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        bp.items.forEachIndexed { pos, item ->
                            ExerciseCard(
                                app, bp, item, tints[i],
                                expanded = item.id in open && !reorder,
                                reorder = reorder,
                                first = pos == 0,
                                last = pos == bp.items.lastIndex,
                                onToggle = { open = if (item.id in open) open - item.id else open + item.id },
                                onMenu = { sheet = WSheet.Menu(b.id, item.id) },
                                onRate = { row, r -> rate(app, b, item, row, r) },
                            )
                        }
                        if (!reorder) DashedButton("+ упражнение в «$title»") { sheet = WSheet.Add(b.id, null) }
                    }
                }
                Gap(22.dp)
                Btn("Завершить тренировку", {
                    val undone = undoneCount(passes)
                    if (undone > 0) sheet = WSheet.ConfirmFinish(undone) else finishNow()
                }, Modifier.fillMaxWidth(), big = true)
            }

            if (rest != null) RestBar(app, rest, now)
        }

        when (val sh = sheet) {
            null -> {}
            WSheet.Tools -> Sheet({ sheet = null }) { ToolsSheet(app, now) }
            is WSheet.Menu -> {
                val b = s.block(sh.blockId)
                val item = b?.items?.firstOrNull { it.id == sh.itemId }
                if (b == null || item == null) {
                    sheet = null
                } else {
                    Sheet({ sheet = null }) {
                        MenuSheet(app, b, item, onClose = { sheet = it }, onReorder = { reorder = true; sheet = null }, onAddSet = { open = open + item.id })
                    }
                }
            }
            is WSheet.Add -> Sheet({ sheet = null }) { AddSheet(app, sh) { sheet = null } }
            is WSheet.ConfirmFinish -> Sheet({ sheet = null }) {
                Txt("Завершить тренировку?", ts(17.sp, weight = FontWeight.Bold))
                Txt(
                    "Не отмечено ${sh.undone} ${plural(sh.undone, "подход", "подхода", "подходов")} — в разборе они будут пропущенными.",
                    ts(14.sp, C.Soft),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Продолжить", { sheet = null }, Modifier.weight(1f), Kind.Ghost)
                    Btn("Завершить", { sheet = null; finishNow() }, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun undoneCount(passes: List<BlockPasses>) = passes.sumOf { bp -> bp.items.sumOf { item -> bp.rows(item).count { !it.done } } }

/** Оценка = отметка. Новая отметка запускает отдых по плану, снятая — убирает его. */
private fun rate(app: AppState, b: LayoutBlock, item: LayoutItem, row: SetRow, r: Rating) {
    val now = Clock.System.now()
    app.act { rate(b.id, item.id, row.setNo, r, now) }
    when {
        !row.done -> {
            val s = app.session ?: return
            val block = s.block(b.id) ?: return
            val rest = BlockPasses(block, s.sets).restAfter(item, row.setNo)
            val sec = rest.seconds?.takeIf { it > 0 }
            app.rest = sec?.let {
                // Проход бывает только в круге: у одиночного упражнения «после прохода» звучит странно
                val circuit = block.items.count { i -> !i.removed } > 1
                val label = if (rest.kind == RestKind.AFTER_PASS && circuit) "Отдых после прохода по плану · $it с" else "Отдых по плану · $it с"
                RestState(now + it.seconds, it, label)
            }
        }
        row.fact?.rating == r -> app.rest = null
    }
}

@Composable
private fun ExerciseCard(
    app: AppState,
    bp: BlockPasses,
    item: LayoutItem,
    tint: Tint,
    expanded: Boolean,
    reorder: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onRate: (SetRow, Rating) -> Unit,
) {
    val s = app.session ?: return
    val ex = app.exercise(item.exerciseId)
    val rows = bp.rows(item)
    val next = rows.firstOrNull { !it.done }
    val shown = when {
        expanded -> rows
        reorder || next == null -> emptyList()
        else -> listOf(next)
    }
    val replacedName = item.replacedFrom?.let { app.exercise(it)?.nameRu }
    Surface(Modifier.fillMaxWidth().alpha(if (next == null && !expanded) 0.62f else 1f), tint.card, tint.line, 16.dp) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb(app, ex, tint, modifier = Modifier.clip(RoundedCornerShape(10.dp)).tap { app.go(Screen.Technique(item.exerciseId)) })
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).tapQuiet(onToggle).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(ex?.nameRu ?: "Упражнение не в справочнике", ts(16.sp, weight = FontWeight.SemiBold))
                    Txt(itemMeta(item, replacedName), ts(13.sp, C.Muted))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEach { r ->
                        when {
                            r.done -> Dot(r.fact?.rating?.color() ?: C.Normal)
                            r == next -> Dot(C.Lime, ring = true)
                            else -> Dot(C.Border)
                        }
                    }
                }
                if (reorder) {
                    if (!first) IconBtn(Icon.Up, { app.act { move(bp.block.id, item.id, -1) } }, 36.dp) else Box(Modifier.size(36.dp))
                    if (!last) IconBtn(Icon.Down, { app.act { move(bp.block.id, item.id, 1) } }, 36.dp) else Box(Modifier.size(36.dp))
                } else {
                    IconBtn(Icon.More, onMenu, 36.dp)
                }
            }
            if (shown.isNotEmpty() || expanded) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (expanded) RowHead(item.recordType)
                    shown.forEach { row ->
                        SetLine(app, bp.block, item, row, row == next, s.previous, onRate)
                    }
                    if (expanded) {
                        Box(
                            Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Field)
                                .tap { app.act { addSet(bp.block.id, item.id) } }.padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt("+ подход", ts(14.sp, weight = FontWeight.SemiBold)) }
                    }
                }
            }
        }
    }
}

private val headStyle = ts(11.sp, C.Faint, FontWeight.Medium)

/** Поля строки по типу записи: (подпись, значение, дробное). */
private fun fields(type: RecordType): List<String> = when (type) {
    RecordType.WEIGHT_REPS -> listOf("кг", "повт")
    RecordType.REPS -> listOf("повт", "+кг")
    RecordType.TIME -> listOf("сек")
    RecordType.WEIGHT_TIME -> listOf("кг", "сек")
}

@Composable
private fun RowHead(type: RecordType) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt("№", headStyle, Modifier.width(26.dp), TextAlign.Center)
        Txt("БЫЛО", headStyle, Modifier.width(38.dp))
        fields(type).forEach { Txt(it.uppercase(), headStyle, Modifier.width(50.dp), TextAlign.Center) }
        Txt("ОЦЕНКА = ГОТОВО", headStyle, Modifier.weight(1f), TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun SetLine(app: AppState, block: LayoutBlock, item: LayoutItem, row: SetRow, next: Boolean, previous: List<train.core.SetFact>, onRate: (SetRow, Rating) -> Unit) {
    val v = row.values(previous)
    val shape = RoundedCornerShape(12.dp)
    val type = item.recordType
    fun edit(change: SetValues.() -> SetValues) = app.act { edit(block.id, item.id, row.setNo, v.change()) }
    Row(
        Modifier.fillMaxWidth().clip(shape).then(if (next) Modifier.background(C.NextBg).border(1.dp, C.NextLine, shape) else Modifier).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.width(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(row.setNo.toString(), ts(14.sp, weight = FontWeight.Bold))
            val t = row.fact?.takeIf { it.done }?.doneAt?.clock() ?: if (row.target == null) "доп" else null
            if (t != null) Txt(t, ts(10.sp, C.Faint))
        }
        Txt(short(type, row.last(previous)?.values), ts(12.sp, C.Muted), Modifier.width(38.dp), maxLines = 2)
        val key = "${item.id}-${row.setNo}"
        val done = row.done
        when (type) {
            RecordType.WEIGHT_REPS -> {
                ValueField("$key-w", v.weight, true, done) { x -> edit { copy(weight = x) } }
                ValueField("$key-r", v.reps?.toDouble(), false, done) { x -> edit { copy(reps = x?.toInt()) } }
            }
            RecordType.REPS -> {
                ValueField("$key-r", v.reps?.toDouble(), false, done) { x -> edit { copy(reps = x?.toInt()) } }
                ValueField("$key-x", v.extraWeight, true, done, "+") { x -> edit { copy(extraWeight = x) } }
            }
            RecordType.TIME -> ValueField("$key-s", v.seconds?.toDouble(), false, done) { x -> edit { copy(seconds = x?.toInt()) } }
            RecordType.WEIGHT_TIME -> {
                ValueField("$key-w", v.weight, true, done) { x -> edit { copy(weight = x) } }
                ValueField("$key-s", v.seconds?.toDouble(), false, done) { x -> edit { copy(seconds = x?.toInt()) } }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            Rating.entries.forEach { r -> RateBtn(r, row.done && row.fact?.rating == r) { onRate(row, r) } }
        }
    }
}

/** Поле значения: текст живёт в поле, пока набирается («12,»), в сессию уходит число. */
@Composable
private fun ValueField(id: String, value: Double?, decimal: Boolean, done: Boolean, placeholder: String = "", onValue: (Double?) -> Unit) {
    var text by remember(id) { mutableStateOf(value?.let(::num).orEmpty()) }
    LaunchedEffect(value) { if (parseNum(text) != value) text = value?.let(::num).orEmpty() }
    NumField(
        text,
        { t ->
            text = t
            val x = parseNum(t)
            if (t.isEmpty() || x != null) onValue(x)
        },
        Modifier.size(50.dp, 40.dp),
        decimal,
        placeholder,
        ts(16.sp, if (done) C.Muted else C.Text, FontWeight.SemiBold),
    )
}

@Composable
private fun RowScope.RateBtn(r: Rating, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.weight(1f, fill = false).widthIn(max = 46.dp).height(40.dp).clip(shape)
            .then(if (on) Modifier.background(r.color()) else Modifier.border(1.dp, C.Border, shape))
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(r.label(), ts(11.sp, if (on) r.onColor() else C.Soft, FontWeight.SemiBold), align = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun DashedButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(
                    C.Dash,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .tap(onClick).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, ts(14.sp, C.Muted), align = TextAlign.Center) }
}

@Composable
private fun RestBar(app: AppState, rest: RestState, now: Instant) {
    val left = rest.until - now
    Column(Modifier.fillMaxWidth().background(C.Bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(rest.label, ts(13.sp, C.Muted), Modifier.weight(1f))
                Txt(
                    if (left.isPositive()) mmss(left + 999.milliseconds) else "пора",
                    ts(22.sp, if (left.isPositive()) C.Text else C.Lime, FontWeight.Bold),
                    Modifier.padding(end = 6.dp),
                )
                Btn("−15", { app.rest = rest.copy(until = rest.until - 15.seconds) }, kind = Kind.Chip)
                Btn("+15", {
                    val base = maxOf(rest.until, Clock.System.now())
                    app.rest = rest.copy(until = base + 15.seconds, total = rest.total + 15)
                }, kind = Kind.Chip)
                Btn("Скрыть", { app.rest = null }, kind = Kind.Chip)
            }
            val frac = (left.inWholeMilliseconds / (rest.total * 1000f)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Border)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(C.Lime))
            }
        }
    }
}

private val presets = listOf(30, 60, 90, 120, 180)

@Composable
private fun ToolsSheet(app: AppState, now: Instant) {
    val t = app.tools
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field).padding(4.dp)) {
        listOf("Таймер", "Секундомер").forEachIndexed { i, label ->
            val on = t.tab == i
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).then(if (on) Modifier.background(C.Dash) else Modifier)
                    .tap { app.tools = t.copy(tab = i) }.padding(10.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, ts(weight = FontWeight.SemiBold, color = if (on) C.Text else C.Muted)) }
        }
    }
    val big = ts(64.sp, weight = FontWeight.Bold)
    if (t.tab == 0) {
        val running = t.timerUntil != null
        val leftMs = t.timerUntil?.let { (it - now).inWholeMilliseconds.coerceAtLeast(0) } ?: (t.timerLeft * 1000L)
        val finished = running && leftMs <= 0
        Txt(mmss((leftMs + 999).milliseconds), big, Modifier.fillMaxWidth().padding(vertical = 6.dp), TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            presets.forEach { p ->
                Btn(mmss(p.seconds), { app.tools = t.copy(timerDur = p, timerLeft = p, timerUntil = null) }, kind = if (t.timerDur == p) Kind.ChipOn else Kind.Chip)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Btn("−15 с", {
                app.tools = if (t.timerUntil != null) t.copy(timerUntil = t.timerUntil - 15.seconds) else t.copy(timerLeft = (t.timerLeft - 15).coerceAtLeast(0))
            }, kind = Kind.Ghost)
            Btn(if (finished) "Заново" else if (running) "Пауза" else "Старт", {
                val n = Clock.System.now()
                app.tools = when {
                    finished -> t.copy(timerUntil = n + t.timerDur.seconds)
                    running -> t.copy(timerUntil = null, timerLeft = ((leftMs + 500) / 1000).toInt())
                    else -> {
                        val left = if (t.timerLeft > 0) t.timerLeft else t.timerDur
                        t.copy(timerLeft = left, timerUntil = n + left.seconds)
                    }
                }
            }, Modifier.widthIn(min = 140.dp))
            Btn("+15 с", {
                app.tools = if (t.timerUntil != null) t.copy(timerUntil = maxOf(t.timerUntil, Clock.System.now()) + 15.seconds) else t.copy(timerLeft = t.timerLeft + 15)
            }, kind = Kind.Ghost)
        }
    } else {
        val ms = t.swAcc + (t.swStart?.let { (now - it).inWholeMilliseconds } ?: 0)
        Txt(mmss(ms.milliseconds), big, Modifier.fillMaxWidth().padding(vertical = 6.dp), TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Btn("Сброс", { app.tools = t.copy(swAcc = 0, swStart = t.swStart?.let { Clock.System.now() }) }, kind = Kind.Ghost)
            Btn(if (t.swStart != null) "Стоп" else "Старт", {
                val n = Clock.System.now()
                app.tools = if (t.swStart != null) t.copy(swAcc = t.swAcc + (n - t.swStart).inWholeMilliseconds, swStart = null) else t.copy(swStart = n)
            }, Modifier.widthIn(min = 140.dp))
        }
    }
}

@Composable
private fun MenuItem(label: String, color: Color = C.Text, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(onClick)) {
        Txt(label, ts(weight = FontWeight.Medium, color = color), Modifier.padding(horizontal = 4.dp, vertical = 14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))
    }
}

@Composable
private fun MenuSheet(app: AppState, b: LayoutBlock, item: LayoutItem, onClose: (WSheet?) -> Unit, onReorder: () -> Unit, onAddSet: () -> Unit) {
    val ex = app.exercise(item.exerciseId)
    Txt(ex?.nameRu ?: "Упражнение", ts(17.sp, weight = FontWeight.Bold))
    Column {
        MenuItem("Техника и мышцы") {
            onClose(null)
            app.go(Screen.Technique(item.exerciseId))
        }
        MenuItem("Заменить упражнение") { onClose(WSheet.Add(b.id, item.id)) }
        MenuItem("Добавить подход") {
            app.act { addSet(b.id, item.id) }
            onAddSet()
            onClose(null)
        }
        MenuItem("Убрать невыполненный подход") {
            app.act { removeSet(b.id, item.id) }
            onClose(null)
        }
        when (item.recordType) {
            RecordType.REPS -> MenuItem("Записывать временем вместо повторов") {
                app.act { setRecordType(b.id, item.id, RecordType.TIME) }
                onClose(null)
            }
            RecordType.TIME -> MenuItem("Записывать повторами вместо времени") {
                app.act { setRecordType(b.id, item.id, RecordType.REPS) }
                onClose(null)
            }
            else -> {}
        }
        MenuItem("Изменить порядок", onClick = onReorder)
        MenuItem("Убрать из тренировки", C.Hard) {
            app.act { removeItem(b.id, item.id) }
            onClose(null)
        }
    }
}

@Composable
private fun AddSheet(app: AppState, sh: WSheet.Add, onDone: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val lastSets = app.cache.lastSets
    Txt(if (sh.itemId == null) "Добавить упражнение" else "Заменить на…", ts(17.sp, weight = FontWeight.Bold))
    TextArea(q, { q = it }, "Поиск по справочнику", singleLine = true, minHeight = 46.dp)
    val query = q.trim().lowercase()
    val list: List<Exercise> = if (query.isEmpty()) {
        val recent = lastSets.filter { it.done && !it.deleted }.groupBy { it.exerciseId }
            .mapValues { (_, v) -> v.mapNotNull { it.doneAt }.maxOrNull() }
            .entries.sortedByDescending { it.value }.map { it.key }
        recent.mapNotNull { id -> app.cache.exercises.firstOrNull { it.id == id } }.take(15)
    } else {
        app.cache.exercises.filter { query in it.nameRu.lowercase() || it.nameEn?.lowercase()?.contains(query) == true }.take(40)
    }
    Txt(if (query.isEmpty()) "НЕДАВНИЕ" else "НАЙДЕНО", T.kicker)
    if (list.isEmpty()) Txt(if (app.cache.exercises.isEmpty()) "Справочник ещё не загружен" else "Ничего не нашлось", T.meta)
    Column {
        list.forEach { ex ->
            val prev = lastSets.filter { it.exerciseId == ex.id && it.done && !it.deleted }.sortedBy { it.setNo }
            Column(
                Modifier.fillMaxWidth().tap {
                    app.act {
                        if (sh.itemId == null) addItem(sh.blockId, ex, prev) else replace(sh.blockId, sh.itemId, ex, prev)
                    }
                    onDone()
                }.padding(horizontal = 4.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Txt(ex.nameRu, T.strong)
                val day = prev.firstNotNullOfOrNull { it.doneAt }
                if (day != null) {
                    Txt("Последний раз: ${day.date().dayMonth()} · ${prev.take(4).joinToString(", ") { short(it.recordType, it.values) }}", T.meta)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))
        }
    }
}
