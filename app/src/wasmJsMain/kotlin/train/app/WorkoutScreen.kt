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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import train.core.BlockPasses
import train.core.BlockRole
import train.core.Exercise
import train.core.LayoutBlock
import train.core.LayoutItem
import train.core.Rating
import train.core.RecordType
import train.core.RestTimer
import train.core.SetFact
import train.core.SetRow
import train.core.SetValues
import train.core.addBlock
import train.core.addItem
import train.core.addSet
import train.core.edit
import train.core.finish
import train.core.hasCooldown
import train.core.itemComment
import train.core.last
import train.core.like
import train.core.move
import train.core.rate
import train.core.removeItem
import train.core.removeSet
import train.core.replace
import train.core.restTimer
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

    /** Блок, которого нет в плане: ещё круг или заминка — сразу с выбранным упражнением. */
    data class NewBlock(val role: BlockRole) : WSheet

    /** Заметка владельца к упражнению. */
    data class Comment(val blockId: Uuid, val itemId: Uuid) : WSheet

    data class ConfirmFinish(val undone: Int) : WSheet

    /** Какой выполненный подход удалить. */
    data class RemoveDone(val blockId: Uuid, val itemId: Uuid) : WSheet
}

/** Раскрытые карточки — в сохраняемом состоянии экрана: вернулся с «Техники», они раскрыты. */
private val openSaver = listSaver<Set<Uuid>, String>({ it.map(Uuid::toString) }, { it.map(Uuid::parse).toSet() })

private val keysSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

/** Ключ строки подхода: упражнение раскладки и номер. */
private fun rowKey(item: LayoutItem, setNo: Int) = "${item.id}-$setNo"

/** Подходов в круге, который добавлен на ходу: как обычно в планах. */
private const val EXTRA_PASSES = 3

@Composable
fun WorkoutScreen(app: AppState) {
    val s = app.session
    val w = s?.active
    if (w == null) {
        LaunchedEffect(Unit) { app.root(Screen.Home) }
        return
    }
    val now = rememberNow()
    var open by rememberSaveable(stateSaver = openSaver) { mutableStateOf(setOf<Uuid>()) }
    // Выполненные подходы, открытые для правки: строка снова с полями и оценками
    var editing by rememberSaveable(stateSaver = keysSaver) { mutableStateOf(setOf<String>()) }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<WSheet?>(null) }

    // Сигнал конца отдыха и таймера — в Main.kt: он нужен и на «Технике»
    val rest = app.rest

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
                Modifier.fillMaxWidth().background(C.Bar).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconBtn(Icon.Down, { app.root(Screen.Home) }, label = "Свернуть тренировку")
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
                IconBtn(Icon.Timer, { sheet = WSheet.Tools }, label = "Таймер и секундомер")
                Btn("Завершить", {
                    val undone = undoneCount(passes)
                    if (undone > 0) sheet = WSheet.ConfirmFinish(undone) else finishNow()
                })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 28.dp)) {
                if (reorder) {
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Field).padding(horizontal = 14.dp, vertical = 12.dp),
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
                    BlockDivider(title, sub, tints[i], top = 14.dp, bottom = 8.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bp.items.forEachIndexed { pos, item ->
                            ExerciseCard(
                                app, bp, item, tints[i],
                                expanded = item.id in open && !reorder,
                                reorder = reorder,
                                first = pos == 0,
                                last = pos == bp.items.lastIndex,
                                editing = editing,
                                onEdit = { key -> editing = if (key in editing) editing - key else editing + key },
                                onToggle = { open = if (item.id in open) open - item.id else open + item.id },
                                onMenu = { sheet = WSheet.Menu(b.id, item.id) },
                                onRate = { row, r ->
                                    editing = editing - rowKey(item, row.setNo)
                                    rate(app, b, item, row, r)
                                },
                            )
                        }
                        if (!reorder) DashedButton("+ упражнение в «$title»") { sheet = WSheet.Add(b.id, null) }
                    }
                }
                if (!reorder) {
                    // Незапланированное: ещё круг (встаёт перед заминкой) и заминка, если её нет в плане
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { DashedButton("+ круг") { sheet = WSheet.NewBlock(BlockRole.MAIN) } }
                        if (!s.hasCooldown) Box(Modifier.weight(1f)) { DashedButton("+ заминка") { sheet = WSheet.NewBlock(BlockRole.COOLDOWN) } }
                    }
                }
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
                        MenuSheet(
                            app, b, item,
                            expanded = item.id in open,
                            onClose = { sheet = it },
                            onReorder = { reorder = true; sheet = null },
                            onExpand = { on -> open = if (on) open + item.id else open - item.id },
                        )
                    }
                }
            }
            is WSheet.Add -> Sheet({ sheet = null }) {
                AddSheet(app, if (sh.itemId == null) "Добавить упражнение" else "Заменить на…") { ex, prev ->
                    app.act { if (sh.itemId == null) addItem(sh.blockId, ex, prev) else replace(sh.blockId, sh.itemId, ex, prev) }
                    sheet = null
                }
            }
            is WSheet.NewBlock -> Sheet({ sheet = null }) {
                val cool = sh.role == BlockRole.COOLDOWN
                AddSheet(app, if (cool) "Заминка: первое упражнение" else "Новый круг: первое упражнение") { ex, prev ->
                    // Заминка — один подход растяжки; круг — три прохода, остальные упражнения подстроятся
                    app.act { addBlock(sh.role, ex, prev, sets = if (cool) 1 else EXTRA_PASSES) }
                    sheet = null
                }
            }
            is WSheet.Comment -> {
                val b = s.block(sh.blockId)
                val item = b?.items?.firstOrNull { it.id == sh.itemId }
                if (b == null || item == null) {
                    sheet = null
                } else {
                    Sheet({ sheet = null }) { CommentSheet(app, b, item) { sheet = null } }
                }
            }
            is WSheet.RemoveDone -> {
                val b = s.block(sh.blockId)
                val item = b?.items?.firstOrNull { it.id == sh.itemId }
                val done = if (b == null || item == null) emptyList() else BlockPasses(b, s.sets).rows(item).filter { it.done }
                if (b == null || item == null || done.isEmpty()) {
                    sheet = null
                } else {
                    Sheet({ sheet = null }) { RemoveDoneSheet(app, b, item, done) { sheet = null } }
                }
            }
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

/**
 * Оценка = отметка. Новая отметка запускает отдых (по плану или открытый в круге) и остаётся видна
 * в свёрнутой карточке, снятая — убирает отдых.
 */
private fun rate(app: AppState, b: LayoutBlock, item: LayoutItem, row: SetRow, r: Rating) {
    val now = Clock.System.now()
    app.act { rate(b.id, item.id, row.setNo, r, now) }
    when {
        !row.done -> {
            app.justRated = item.id to row.setNo
            val s = app.session ?: return
            val block = s.block(b.id) ?: return
            app.rest = BlockPasses(block, s.sets).restTimer(item, row.setNo, now)
        }
        row.fact?.rating == r -> {
            app.rest = null
            if (app.justRated == item.id to row.setNo) app.justRated = null
        }
    }
}

/**
 * Компактная карточка упражнения: маленькая картинка, название и план одной строкой, точки
 * подходов, кебаб. Под ней — строки подходов колонками «кг / повт» и оценка справа. Выполненное
 * упражнение — серое и без строк: переоткрыть — тапом по названию или через кебаб.
 */
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
    editing: Set<String>,
    onEdit: (String) -> Unit,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onRate: (SetRow, Rating) -> Unit,
) {
    val s = app.session ?: return
    val ex = app.exercise(item.exerciseId)
    val rows = bp.rows(item)
    val next = bp.next(item)
    val complete = next == null && rows.isNotEmpty()
    val grey = complete && !expanded
    val shown = when {
        reorder -> emptyList()
        expanded -> rows
        else -> bp.collapsedRows(item, app.justRated?.takeIf { it.first == item.id }?.second)
    }
    val replacedName = item.replacedFrom?.let { app.exercise(it)?.nameRu }
    val meta = if (complete) rows.mapNotNull { it.fact }.joinToString(" · ") { short(it.recordType, it.values) } else itemMeta(item, replacedName)
    Surface(Modifier.fillMaxWidth(), if (grey) C.DoneCard else tint.card, if (grey) C.DoneLine else tint.line, 14.dp) {
        Column {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb(app, ex, tint, 40.dp, Modifier.clip(RoundedCornerShape(8.dp)).tap { app.go(Screen.Technique(item.exerciseId)) }, grey = grey)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).tapQuiet(onToggle).padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(ex?.nameRu ?: "Упражнение не в справочнике", ts(15.sp, if (grey) C.Done else C.Text, FontWeight.SemiBold), Modifier.weight(1f, fill = false), maxLines = 1)
                        if (item.liked) Ico(Icon.Heart, 14.dp, C.Heart, 1.5f, fill = true)
                    }
                    Txt(meta, ts(12.sp, if (grey) C.Done else C.Muted), maxLines = 1)
                    item.comment?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Ico(Icon.Note, 12.dp, if (grey) C.Done else C.Muted, 2f)
                            Txt(it, ts(12.sp, if (grey) C.Done else C.Soft), maxLines = 2)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    rows.forEach { r ->
                        when {
                            r.done -> RateDot(r.fact?.rating, 8.dp)
                            r == next -> Dot(C.Lime, ring = true)
                            else -> Dot(C.Border)
                        }
                    }
                }
                if (reorder) {
                    if (!first) IconBtn(Icon.Up, { app.act { move(bp.block.id, item.id, -1) } }, 34.dp, label = "Выше: ${ex?.nameRu.orEmpty()}") else Box(Modifier.size(34.dp))
                    if (!last) IconBtn(Icon.Down, { app.act { move(bp.block.id, item.id, 1) } }, 34.dp, label = "Ниже: ${ex?.nameRu.orEmpty()}") else Box(Modifier.size(34.dp))
                } else {
                    IconBtn(Icon.More, onMenu, 34.dp, label = "Меню: ${ex?.nameRu.orEmpty()}")
                }
            }
            if (shown.isNotEmpty() || expanded) {
                Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (expanded) RowHead()
                    shown.forEach { row ->
                        val key = rowKey(item, row.setNo)
                        val of = if (expanded) null else rows.size
                        if (row.done && key !in editing) {
                            DoneSetLine(
                                row.setNo, row.fact?.doneAt?.clock(), row.fact?.recordType ?: item.recordType, row.values(s.previous), row.fact?.rating,
                                Modifier.clip(RoundedCornerShape(10.dp)).tap { onEdit(key) },
                                prev = short(item.recordType, row.last(s.previous)?.values),
                            )
                        } else {
                            SetLine(app, bp.block, item, row, row == next, s.previous, onRate, of, onNo = if (row.done) ({ onEdit(key) }) else null)
                        }
                    }
                    if (expanded) {
                        Box(
                            Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Field)
                                .tap { app.act { addSet(bp.block.id, item.id) } }.padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt("+ подход", ts(14.sp, weight = FontWeight.SemiBold)) }
                    }
                }
            }
        }
    }
}

private val headStyle = ts(10.sp, C.Faint, FontWeight.Medium)

/** Шапка раскрытой карточки: единицы — под числами в полях, здесь только «было» и подсказка. */
@Composable
private fun RowHead() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt("№", headStyle, Modifier.width(NoW), TextAlign.Center)
        Txt("БЫЛО", headStyle, Modifier.width(PrevW))
        Txt("ОЦЕНКА = ГОТОВО", headStyle, Modifier.weight(1f), TextAlign.End, maxLines = 1)
    }
}

/**
 * Строка подхода с полями и оценками: следующий, невыполненный или открытый для правки
 * выполненный. `onNo` — тап по номеру: свернуть правку обратно в серую строку.
 */
@Composable
private fun SetLine(
    app: AppState,
    block: LayoutBlock,
    item: LayoutItem,
    row: SetRow,
    next: Boolean,
    previous: List<SetFact>,
    onRate: (SetRow, Rating) -> Unit,
    /** Свёрнутая карточка: всего подходов — видно, что строк больше, чем показано. */
    of: Int? = null,
    onNo: (() -> Unit)? = null,
) {
    val v = row.values(previous)
    val shape = RoundedCornerShape(10.dp)
    val type = row.fact?.recordType ?: item.recordType
    fun edit(change: SetValues.() -> SetValues) = app.act { edit(block.id, item.id, row.setNo, v.change()) }
    Row(
        Modifier.fillMaxWidth().clip(shape).then(if (next) Modifier.background(C.NextBg).border(1.dp, C.NextLine, shape) else Modifier).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val sub = row.fact?.takeIf { it.done }?.doneAt?.clock() ?: of?.let { "из $it" } ?: if (row.target == null) "доп" else null
        Box(if (onNo != null) Modifier.clip(RoundedCornerShape(8.dp)).tap(onNo) else Modifier) { SetNo(row.setNo, sub) }
        Txt(short(type, row.last(previous)?.values), ts(11.sp, C.Muted), Modifier.width(PrevW), maxLines = 2)
        val key = rowKey(item, row.setNo)
        when (type) {
            RecordType.WEIGHT_REPS -> {
                ValueField("$key-w", v.weight, true, "кг") { x -> edit { copy(weight = x) } }
                ValueField("$key-r", v.reps?.toDouble(), false, "повт") { x -> edit { copy(reps = x?.toInt()) } }
            }
            RecordType.REPS -> {
                ValueField("$key-r", v.reps?.toDouble(), false, "повт") { x -> edit { copy(reps = x?.toInt()) } }
                ValueField("$key-x", v.extraWeight, true, "+кг", "+") { x -> edit { copy(extraWeight = x) } }
            }
            RecordType.TIME -> SecondsField("$key-s", v.seconds) { x -> edit { copy(seconds = x) } }
            RecordType.WEIGHT_TIME -> {
                ValueField("$key-w", v.weight, true, "кг") { x -> edit { copy(weight = x) } }
                SecondsField("$key-s", v.seconds) { x -> edit { copy(seconds = x) } }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End)) {
            Rating.entries.forEach { r -> RateBtn(r, row.done && row.fact?.rating == r) { onRate(row, r) } }
        }
    }
}

private val fieldStyle = ts(16.sp, weight = FontWeight.SemiBold)

/** Поле значения: текст живёт в поле, пока набирается («12,»), в сессию уходит число. */
@Composable
private fun ValueField(id: String, value: Double?, decimal: Boolean, unit: String, placeholder: String = "", onValue: (Double?) -> Unit) {
    var text by remember(id) { mutableStateOf(value?.let(::num).orEmpty()) }
    LaunchedEffect(value) { if (parseNum(text) != value) text = value?.let(::num).orEmpty() }
    NumField(
        text,
        { t ->
            text = t
            val x = parseNum(t)
            if (t.isEmpty() || x != null) onValue(x)
        },
        Modifier.size(CellW, 40.dp),
        decimal,
        placeholder,
        fieldStyle,
        unit = unit,
    )
}

/** Время: набор «как на микроволновке» — `130` → `1:30`. */
@Composable
private fun SecondsField(id: String, value: Int?, onValue: (Int?) -> Unit) =
    TimeField(id, value, onValue, Modifier.size(CellW, 40.dp), fieldStyle, unit = "мин")

/** Кнопка оценки: невыбранная — тёмная в оттенке своего цвета, выбранная — залита им. */
@Composable
private fun RowScope.RateBtn(r: Rating, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.weight(1f).widthIn(max = 56.dp).height(40.dp).clip(shape)
            .background(if (on) r.color() else r.dim())
            .then(if (on) Modifier else Modifier.border(1.dp, r.color().copy(alpha = 0.35f), shape))
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(r.label(), ts(10.sp, if (on) r.onColor() else r.color(), FontWeight.SemiBold), align = TextAlign.Center, maxLines = 1)
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
            .tap(onClick).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(label, ts(14.sp, C.Muted), align = TextAlign.Center) }
}

@Composable
private fun RestBar(app: AppState, rest: RestTimer, now: Instant) {
    val until = rest.until
    if (until == null) {
        // Открытый отдых: в плане его нет — сколько прошло
        Column(Modifier.fillMaxWidth().background(C.Bar)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border))
            Row(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(rest.label, ts(13.sp, C.Muted), Modifier.weight(1f))
                Txt(mmss(now - rest.since), ts(22.sp, weight = FontWeight.Bold), Modifier.padding(end = 6.dp))
                Btn("Скрыть", { app.rest = null }, kind = Kind.Chip)
            }
        }
        return
    }
    val left = until - now
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
                Btn("−15", { app.rest = rest.shift(-15, Clock.System.now()) }, kind = Kind.Chip)
                Btn("+15", { app.rest = rest.shift(15, Clock.System.now()) }, kind = Kind.Chip)
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
        val running = t.timerRunning
        val leftMs = t.timerLeftMs(now)
        val finished = t.timerFinished(now)
        Txt(mmss((leftMs + 999).milliseconds), big, Modifier.fillMaxWidth().padding(vertical = 6.dp), TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            presets.forEach { p ->
                Btn(mmss(p.seconds), { app.tools = t.timerPreset(p) }, kind = if (t.timerDur == p) Kind.ChipOn else Kind.Chip)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Btn("−15 с", { app.tools = t.timerShift(-15, Clock.System.now()) }, kind = Kind.Ghost)
            Btn(if (finished) "Заново" else if (running) "Пауза" else "Старт", { app.tools = t.timerToggle(Clock.System.now()) }, Modifier.widthIn(min = 140.dp))
            Btn("+15 с", { app.tools = t.timerShift(15, Clock.System.now()) }, kind = Kind.Ghost)
        }
    } else {
        val ms = t.stopwatchMs(now)
        Txt(mmss(ms.milliseconds), big, Modifier.fillMaxWidth().padding(vertical = 6.dp), TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Btn("Сброс", { app.tools = t.stopwatchReset() }, kind = Kind.Ghost)
            Btn(if (t.swStart != null) "Стоп" else "Старт", { app.tools = t.stopwatchToggle(Clock.System.now()) }, Modifier.widthIn(min = 140.dp))
        }
    }
}

@Composable
private fun MenuItem(label: String, color: Color = C.Text, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(onClick)) {
        Txt(label, ts(weight = FontWeight.Medium, color = color), Modifier.padding(horizontal = 4.dp, vertical = 13.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))
    }
}

@Composable
private fun MenuSheet(
    app: AppState,
    b: LayoutBlock,
    item: LayoutItem,
    expanded: Boolean,
    onClose: (WSheet?) -> Unit,
    onReorder: () -> Unit,
    onExpand: (Boolean) -> Unit,
) {
    val ex = app.exercise(item.exerciseId)
    val rows = app.session?.let { BlockPasses(b, it.sets).rows(item) }.orEmpty()
    val complete = rows.isNotEmpty() && rows.all { it.done }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(ex?.nameRu ?: "Упражнение", ts(17.sp, weight = FontWeight.Bold), Modifier.weight(1f))
        // Сердечко — сразу в шапке меню: одно касание, лист остаётся открытым
        IconBtn(Icon.Heart, { app.act { like(b.id, item.id, !item.liked) } }, 40.dp, if (item.liked) C.Heart else C.Muted, fill = item.liked,
            label = if (item.liked) "Убрать сердечко" else "Поставить сердечко")
    }
    Column {
        if (complete || expanded) {
            MenuItem(if (expanded) "Свернуть подходы" else "Открыть подходы — изменить") {
                onExpand(!expanded)
                onClose(null)
            }
        }
        MenuItem(if (item.liked) "Убрать сердечко" else "Очень понравилось") { app.act { like(b.id, item.id, !item.liked) } }
        MenuItem(if (item.comment == null) "Заметка к упражнению…" else "Изменить заметку…") { onClose(WSheet.Comment(b.id, item.id)) }
        MenuItem("Техника и мышцы") {
            onClose(null)
            app.go(Screen.Technique(item.exerciseId))
        }
        MenuItem("Заменить упражнение") { onClose(WSheet.Add(b.id, item.id)) }
        MenuItem("Добавить подход") {
            app.act { addSet(b.id, item.id) }
            onExpand(true)
            onClose(null)
        }
        if (rows.any { !it.done }) {
            MenuItem("Убрать невыполненный подход") {
                app.act { removeSet(b.id, item.id) }
                onClose(null)
            }
        }
        if (rows.any { it.done }) {
            MenuItem("Удалить выполненный подход…") { onClose(WSheet.RemoveDone(b.id, item.id)) }
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
        MenuItem("Убрать из тренировки", C.Danger) {
            app.act { removeItem(b.id, item.id) }
            onClose(null)
        }
    }
}

/** Заметка к упражнению для агента: что заметил — техника, ощущения, что поменять. */
@Composable
private fun CommentSheet(app: AppState, b: LayoutBlock, item: LayoutItem, onDone: () -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.comment.orEmpty()) }
    Txt("Заметка к упражнению", ts(17.sp, weight = FontWeight.Bold))
    Txt(app.exercise(item.exerciseId)?.nameRu ?: "Упражнение", T.meta)
    TextArea(text, { text = it }, "Техника, ощущения, что поменять — прочитает агент")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.comment != null) {
            Btn("Удалить", {
                app.act { itemComment(b.id, item.id, "") }
                onDone()
            }, Modifier.weight(1f), Kind.Ghost)
        }
        Btn("Сохранить", {
            app.act { itemComment(b.id, item.id, text) }
            onDone()
        }, Modifier.weight(1f))
    }
}

@Composable
private fun RemoveDoneSheet(app: AppState, b: LayoutBlock, item: LayoutItem, done: List<SetRow>, onDone: () -> Unit) {
    val ex = app.exercise(item.exerciseId)
    Txt("Удалить подход", ts(17.sp, weight = FontWeight.Bold))
    Txt("${ex?.nameRu ?: "Упражнение"} · в разборе плановый подход станет пропущенным", ts(14.sp, C.Soft))
    Column {
        done.forEach { row ->
            val f = row.fact
            val label = listOfNotNull(
                "Подход ${row.setNo}",
                f?.let { short(it.recordType, it.values) },
                f?.rating?.label(),
                f?.doneAt?.clock(),
            ).joinToString(" · ")
            MenuItem(label, C.Danger) {
                app.act { removeSet(b.id, item.id, row.setNo) }
                if (app.justRated == item.id to row.setNo) app.justRated = null
                onDone()
            }
        }
    }
}

/**
 * Выбор упражнения из справочника: сначала недавние. Поле — первым в листе, лист — над экранной
 * клавиатурой (Main.kt поднимает весь экран на её высоту): результаты видны, пока набираешь.
 */
@Composable
private fun AddSheet(app: AppState, title: String, onPick: (Exercise, List<SetFact>) -> Unit) {
    var q by remember { mutableStateOf("") }
    val lastSets = app.cache.lastSets
    Txt(title, ts(17.sp, weight = FontWeight.Bold))
    TextArea(q, { q = it }, "Поиск по справочнику", singleLine = true, minHeight = 46.dp)
    val query = q.trim().lowercase()
    val list: List<Exercise> = if (query.isEmpty()) {
        val recent = lastSets.filter { it.done && !it.deleted }.groupBy { it.exerciseId }
            .mapValues { (_, v) -> v.mapNotNull { it.doneAt }.maxOrNull() }
            .entries.sortedByDescending { it.value }.map { it.key }
        recent.mapNotNull { id -> app.cache.exercises.firstOrNull { it.id == id } }.take(15)
            .ifEmpty { app.cache.exercises.take(15) }
    } else {
        app.cache.exercises.filter { query in it.nameRu.lowercase() || it.nameEn?.lowercase()?.contains(query) == true }.take(40)
    }
    Txt(if (query.isEmpty()) "НЕДАВНИЕ" else "НАЙДЕНО", T.kicker)
    if (list.isEmpty()) Txt(if (app.cache.exercises.isEmpty()) "Справочник ещё не загружен" else "Ничего не нашлось", T.meta)
    Column {
        list.forEach { ex ->
            val prev = lastSets.filter { it.exerciseId == ex.id && it.done && !it.deleted }.sortedBy { it.setNo }
            Row(
                Modifier.fillMaxWidth().tap { onPick(ex, prev) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Thumb(app, ex, null, 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(ex.nameRu, T.strong, maxLines = 1)
                    val day = prev.firstNotNullOfOrNull { it.doneAt }
                    if (day != null) {
                        Txt("${day.date().dayMonth()} · ${prev.take(4).joinToString(", ") { short(it.recordType, it.values) }}", T.meta, maxLines = 1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Field))
        }
    }
}
