package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.math.round
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Текст для агента (ADR 0014): факты плотно, без повторяющихся ключей JSON. Легенда — AGENT_TEXT_LEGEND

/** Легенда текстового вывода: в `--help` команд и в скилле. */
const val AGENT_TEXT_LEGEND = """Оценки: ↑ легко, ✓ норма, ✕ тяжело, без значка — не оценено. — пропущен, + сверх плана.
Подходы с одной нагрузкой — группой: «12кг: 10✓ 10✕ · 14кг: 8✓»; вес — одного снаряда, +5кг — доп. вес.
Цель: «3× 12кг 8–10 r3» — три подхода, 8–10 повторов, запас 3. Σ — сумма повторов или секунд.
Тренд лучшего подхода к прошлой тренировке: ▲ рост, = так же, ▼ спад."""

internal fun num(d: Double): String {
    val r = round(d * 100) / 100
    val l = r.toLong()
    return if (r == l.toDouble()) l.toString() else r.toString()
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

/** `11.11` */
fun LocalDate.dm(): String = "${pad(day)}.${pad(month.number)}"

/** `11.11.2026` */
fun LocalDate.dmy(): String = "${dm()}.$year"

private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

fun LocalDate.weekday(): String = WEEKDAYS[dayOfWeek.ordinal]

fun Rating?.mark(): String = when (this) {
    Rating.EASY -> "↑"
    Rating.NORMAL -> "✓"
    Rating.HARD -> "✕"
    null -> ""
}

private fun Trend?.mark(): String = when (this) {
    Trend.UP -> " ▲"
    Trend.SAME -> " ="
    Trend.DOWN -> " ▼"
    null -> ""
}

private fun quote(s: String) = "«${s.trim().replace(Regex("\\s*\\n\\s*"), " / ")}»"

private fun DoneSet.load(): String? = when (recordType) {
    RecordType.WEIGHT_REPS, RecordType.WEIGHT_TIME -> weight?.let { "${num(it)}кг" }
    RecordType.REPS -> extraWeight?.takeIf { it > 0 }?.let { "+${num(it)}кг" }
    RecordType.TIME -> null
}

private fun DoneSet.value(): String = when (recordType) {
    RecordType.TIME, RecordType.WEIGHT_TIME -> seconds?.let { "${it}с" } ?: "?"
    RecordType.REPS, RecordType.WEIGHT_REPS -> reps?.toString() ?: "?"
}

/** Подход одним токеном с нагрузкой: `+5кг 8✓`. */
fun DoneSet.text(): String = listOfNotNull(load(), value() + rating.mark()).joinToString(" ")

/** Токен подхода: нагрузка группирует подходы; пропущенный без нагрузки цели встаёт в текущую группу. */
private class Token(val load: String?, val text: String, val skipped: Boolean = false)

private fun DoneSet.token(prefix: String = "") =
    Token(load(), prefix + value() + rating.mark() + (note?.takeIf { it.isNotBlank() }?.let { " " + quote(it) } ?: ""))

private fun tokens(ts: List<Token>): String {
    val groups = mutableListOf<Pair<String?, MutableList<String>>>()
    for (t in ts) {
        val last = groups.lastOrNull()
        if (last != null && (last.first == t.load || t.skipped && t.load == null)) last.second += t.text
        else groups += t.load to mutableListOf(t.text)
    }
    return groups.joinToString(" · ") { (load, items) -> (load?.let { "$it: " } ?: "") + items.joinToString(" ") }
}

/** Подходы подряд с одной нагрузкой — одной группой: `12кг: 10✕ 10✓ · 14кг: 8✓`. */
fun renderSets(sets: List<DoneSet>): String = tokens(sets.map { it.token() })

private fun TargetSet.load(): String? = weight?.let { "${num(it)}кг" } ?: extraWeight?.takeIf { it > 0 }?.let { "+${num(it)}кг" }

private fun TargetSet.text(): String {
    val load = load()
    val amount = seconds?.let { "${it}с" } ?: when {
        repsMin != null && repsMax != null && repsMin != repsMax -> "$repsMin–$repsMax"
        else -> (repsMin ?: repsMax)?.toString()
    }
    return listOfNotNull(load, amount, rir?.let { "r$it" }).joinToString(" ").ifEmpty { "свободно" }
}

/** Цели подряд одинаковые — с множителем: `3× 12кг 8–10 r3`, разные — через запятую. */
fun renderTargets(targets: List<TargetSet>): String {
    val runs = mutableListOf<Pair<String, Int>>()
    for (t in targets.map { it.text() }) {
        if (runs.lastOrNull()?.first == t) runs[runs.lastIndex] = t to runs.last().second + 1 else runs += t to 1
    }
    return runs.joinToString(", ") { (t, n) -> if (n > 1) "$n× $t" else t }
}

private fun total(sets: List<DoneSet>): String? {
    if (sets.size < 2) return null
    val secs = sets.mapNotNull { it.seconds }
    val reps = sets.mapNotNull { it.reps }
    return when {
        sets.first().recordType.usesSeconds && secs.isNotEmpty() -> "Σ${secs.sum()}с"
        reps.isNotEmpty() -> "Σ${reps.sum()}"
        else -> null
    }
}

private fun minutes(from: Instant, to: Instant) = (to - from).inWholeMinutes

private val ROLE = mapOf(BlockRole.WARMUP to "разминка", BlockRole.MAIN to "основной", BlockRole.COOLDOWN to "заминка")

/**
 * Тренировка для агента: шапка (день, длительность, вес, итоги, замеры, комментарий), блоки с
 * минутами и темпом прохода, по строке на упражнение — факт, цель, прошлый результат, отметки
 * владельца. [names] — названия для замен; [planNotes] — добавить заметки плана.
 */
fun Comparison.toText(names: (Uuid) -> String? = { null }, planNotes: Boolean = false): String = buildString {
    val day = date
    val head = listOfNotNull(
        day?.let { "${it.weekday()} ${it.dmy()}" } ?: startedAt.toString(),
        totals.durationSec?.let { "${it / 60} мин" } ?: "не завершена",
        bodyweightKg?.let { "вес ${num(it)}" },
        planVersion?.let { "план v$it" },
        "тренировка $workoutId",
    )
    appendLine(head.joinToString(" · "))
    val t = totals
    appendLine(
        listOfNotNull(
            "подходов ${t.setsDone} из ${t.setsPlanned}",
            t.setsSkipped.takeIf { it > 0 }?.let { "пропущено $it" },
            t.setsAdded.takeIf { it > 0 }?.let { "сверх плана $it" },
            "рабочих ${t.workSets}",
            "объём ${num(round(t.volumeKg))} кг" + (t.volumeUnknownSets.takeIf { it > 0 }?.let { " (+$it без веса)" } ?: ""),
            t.timeUnderLoadSec.takeIf { it > 0 }?.let { "под нагрузкой ${it}с" },
        ).joinToString(" · "),
    )
    if (measurements.isNotEmpty()) appendLine("замеры: " + measurements.joinToString(", ") { "${it.type} ${num(it.value)} ${it.unit}" })
    comment?.takeIf { it.isNotBlank() }?.let { appendLine("комментарий: ${quote(it)}") }

    val prev = previous.associateBy { it.exerciseId }
    var blockStart = startedAt
    for (b in blocks) {
        val sets = b.items.flatMap { it.sets }
        val doneAt = sets.mapNotNull { it.fact?.doneAt }
        val passes = sets.filter { it.fact != null }.maxOfOrNull { it.setNo } ?: 0
        val end = doneAt.maxOrNull()
        val span = end?.let {
            val m = minutes(blockStart, it)
            val perPass = if (passes > 1) " (${m / passes}/проход)" else ""
            " · ${minutes(startedAt, blockStart)}–${minutes(startedAt, it)} мин$perPass"
        } ?: ""
        appendLine("[${ROLE.getValue(b.role)}] ${b.title ?: ""} · $passes прох.$span".replace("]  ·", "] ·"))
        end?.let { blockStart = it }
        for (item in b.items) appendItem(item, prev[item.exerciseId], names, planNotes)
    }
    if (unplaced.isNotEmpty()) appendLine("вне раскладки: " + unplaced.joinToString(" ") { it.text() })
}.trimEnd()

private fun StringBuilder.appendItem(item: ItemComparison, prev: PreviousResult?, names: (Uuid) -> String?, planNotes: Boolean) {
    val flags = listOfNotNull(
        "♥".takeIf { item.liked },
        "(на сторону)".takeIf { item.sets.any { it.fact?.perSide == true } },
        "(добавлено)".takeIf { item.added },
        "(убрано)".takeIf { item.removed },
        item.replacedFrom?.let { "(замена: вместо ${names(it) ?: it})" },
    )
    val tokens = item.sets.map { s ->
        when (s.status) {
            SetStatus.SKIPPED -> Token(s.target?.load(), "—", skipped = true)
            SetStatus.ADDED -> s.fact!!.token("+")
            SetStatus.DONE -> s.fact!!.let { f -> if (f.exerciseId == null) f.token() else f.token().let { Token("до замены ${it.load ?: ""}".trim(), it.text) } }
        }
    }
    val parts = listOfNotNull(
        tokens(tokens),
        item.sets.mapNotNull { it.target }.takeIf { it.isNotEmpty() }?.let { "цель ${renderTargets(it)}" },
        prev?.let { "было ${renderSets(it.sets)}" },
    )
    appendLine("  ${(listOf(item.name ?: item.exerciseId.toString()) + flags).joinToString(" ")}: ${parts.joinToString(" | ")}")
    item.comment?.takeIf { it.isNotBlank() }?.let { appendLine("    владелец: ${quote(it)}") }
    if (planNotes) item.note?.takeIf { it.isNotBlank() }?.let { appendLine("    план: ${quote(it)}") }
}

private val TYPE = mapOf(
    RecordType.WEIGHT_REPS to "вес×повторы",
    RecordType.REPS to "повторы",
    RecordType.TIME to "время",
    RecordType.WEIGHT_TIME to "вес×время",
)

/** Тренировок без нового рекорда, после которых говорим «плато». */
const val PLATEAU_SESSIONS = 3

/**
 * История упражнений: шапка с рекордом и длиной плато, по строке на тренировку из окна, лучшая
 * за каждый месяц до окна, решения журнала. Строка тренировки: день, подходы, Σ, тренд, цель.
 */
fun List<ExerciseHistory>.toText(journalLimit: Int = 5): String = joinToString("\n\n") { h ->
    buildString {
        val head = listOfNotNull(
            h.name ?: h.exerciseId.toString(),
            TYPE.getValue(h.recordType) + if (h.perSide) ", на сторону" else "",
            if (h.sessions == 0) "не делал" else "${h.sessions} трен. с ${h.first!!.dmy()}",
            "id ${h.exerciseId}",
        )
        appendLine(head.joinToString(" · "))
        h.record?.let { r ->
            val plateau = if (h.sinceRecord >= PLATEAU_SESSIONS) " — плато" else ""
            appendLine("  рекорд ${r.date.dm()}: ${r.best?.text() ?: "?"} · после него ${h.sinceRecord} трен.$plateau")
        }
        for (s in h.recent) appendSession(s, h.recordType)
        for (m in h.earlier) {
            val b = m.best
            appendLine("  ${m.month}: ${m.sessions} трен., лучшая ${b.date.dm()} ${renderSets(b.sets)}")
        }
        // Действующие решения — все, пересмотренные — сколько влезет в лимит
        val (active, revised) = h.journal.partition { it.revisedBy == null }
        for (j in active + revised.take((journalLimit - active.size).coerceAtLeast(0))) {
            val mark = if (j.revisedBy != null) " (пересмотрено)" else ""
            appendLine("  журнал ${j.date.dm()}$mark: ${quote(j.decision)} — почему: ${quote(j.reason)} · пересмотр: ${quote(j.review)}")
        }
    }.trimEnd()
}

private fun StringBuilder.appendSession(s: HistorySession, type: RecordType) {
    val line = listOfNotNull(
        renderSets(s.sets),
        total(s.sets),
    ).joinToString(" ") + s.trend.mark() + (if (s.liked) " ♥" else "")
    val target = s.targets.takeIf { it.isNotEmpty() }?.let { " | цель ${renderTargets(it)}" } ?: ""
    appendLine("  ${s.date.dm()} $line$target")
    s.comment?.let { appendLine("      владелец: ${quote(it)}") }
}

private val GROUP = mapOf(
    BodyGroup.CHEST to "грудь",
    BodyGroup.SHOULDERS to "плечи",
    BodyGroup.ARMS to "руки",
    BodyGroup.CORE to "кор",
    BodyGroup.LEGS to "ноги",
    BodyGroup.GLUTES to "ягодицы",
    BodyGroup.BACK to "спина",
)

/** Недели, старые сначала: по строке на неделю. */
fun List<WeekSummary>.toText(): String = joinToString("\n") { w ->
    val period = "${w.from.dm()}–${w.to.dm()}"
    if (w.workouts == 0) return@joinToString "$period: нет тренировок"
    val unrated = w.workSets - w.easy - w.normal - w.hard
    listOfNotNull(
        "$period: ${w.workouts} трен. (${w.minutes.joinToString(", ")} мин)",
        "рабочих ${w.workSets}: ↑${w.easy} ✓${w.normal} ✕${w.hard}" + (if (unrated > 0) " без оценки $unrated" else ""),
        "сверх плана ${w.added}, пропущено ${w.skipped}",
        "объём ${num(round(w.volumeKg / 100) / 10)} т",
        w.bodyweightKg?.let { "вес ${num(it)}" },
        w.groups.filter { it.sets > 0 }.joinToString(" ") { "${GROUP.getValue(it.group)} ${num(it.sets)}" }.ifEmpty { null },
    ).joinToString(" · ")
}

/**
 * Журнал тренера, новые сначала: запись — шапка с решением, обоснование, условие пересмотра,
 * ссылки. [names] — названия упражнений.
 */
fun List<JournalEntry>.toText(names: (Uuid) -> String? = { null }): String = joinToString("\n") { j ->
    val about = j.exerciseIds.map { names(it) ?: it.toString() }.takeIf { it.isNotEmpty() }?.joinToString(", ", " [", "]") ?: ""
    val state = when {
        j.deletedAt != null -> " (в корзине)"
        j.revisedBy != null -> " (пересмотрено)"
        else -> ""
    }
    val links = listOfNotNull(
        "id ${j.id}",
        j.revises?.let { "пересматривает $it" },
        j.revisedBy?.let { "пересмотрено записью $it" },
        j.workoutId?.let { "тренировка $it" },
        j.planId?.let { "план $it" },
    )
    buildString {
        appendLine("${j.date.dmy()}$about$state решение: ${quote(j.decision)}")
        appendLine("  почему: ${quote(j.reason)}")
        appendLine("  пересмотр: ${quote(j.review)}")
        append("  ${links.joinToString(" · ")}")
    }
}
