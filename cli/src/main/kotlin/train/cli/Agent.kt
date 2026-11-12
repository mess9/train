package train.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import train.client.ApiClient
import train.core.AGENT_TEXT_LEGEND
import train.core.Comparison
import train.core.JournalEntry
import train.core.JournalPut
import train.core.Workout
import train.core.dm
import train.core.dmy
import train.core.newId
import train.core.toText
import train.core.weekday
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Чтение для агента — текстом из core (ADR 0014): факты плотно, без ключей JSON. --json — как раньше

private fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** Команда с текстовым выводом для агента и `--json` для скриптов. */
abstract class TextCommand(name: String, about: String) : ApiCommand(name, about) {
    protected val json by option("--json", help = "JSON вместо текста").flag()
}

/** Названия упражнений по id: то, чего нет в самом ответе (замены, журнал). */
private suspend fun names(api: ApiClient, ids: Collection<Uuid>): Map<Uuid, String> =
    ids.toSet().mapNotNull { id -> runCatching { id to api.exercise(id).nameRu }.getOrNull() }.toMap()

private suspend fun compareText(api: ApiClient, c: Comparison, planNotes: Boolean): String {
    val items = c.blocks.flatMap { it.items }
    val names = names(api, items.mapNotNull { it.replacedFrom })
    return c.toText({ names[it] }, planNotes)
}

class CompareCmd : TextCommand("compare", "План против факта по тренировке: подходы, итоги, прошлые результаты") {
    private val id by argument("workoutId").uuid()
    private val notes by option("--notes", help = "с заметками плана к упражнениям").flag()

    override fun help(context: Context) = super.help(context) + "\n\n" + AGENT_TEXT_LEGEND

    override suspend fun exec() {
        val c = api.compare(id)
        if (json) out(c) else echo(compareText(api, c, notes))
    }
}

class HistoryCmd : TextCommand(
    "history",
    "История упражнений: последние тренировки целиком, ранние — лучшая за месяц, рекорд, плато, решения журнала. " +
        "Без id — упражнения основных блоков за месяц",
) {
    private val ids by argument("exerciseId").uuid().multiple()
    private val recent by option("--recent", help = "сколько последних тренировок целиком, по умолчанию 8").int().restrictTo(1, 100)
    private val since by option("--since", help = "вместо --recent: все тренировки с этого дня целиком").date()

    override fun help(context: Context) = super.help(context) + "\n\n" + AGENT_TEXT_LEGEND

    override suspend fun exec() {
        val h = api.history(ids, recent, since)
        if (json) out(h) else echo(h.toText().ifEmpty { "нет истории" })
    }
}

class WeeksCmd : TextCommand("weeks", "Недели с понедельника: тренировки, минуты, оценки, объём, вес, группы мышц. По умолчанию восемь") {
    private val from by option("--from", help = "день первой недели").date()
    private val to by option("--to", help = "день последней недели").date()

    override suspend fun exec() {
        val w = api.weeks(from, to)
        if (json) out(w) else echo(w.toText())
    }
}


// Журнал тренера

class JournalAdd : TextCommand(
    "add",
    "Записать решение: что решено, почему и когда пересмотреть — все три обязательны. Решение не переписывают: " +
        "пересмотр — новая запись с --revises; --id — только исправить опечатку",
) {
    private val decision by option("--decision", help = "что решено").required()
    private val reason by option("--reason", help = "почему: цифры, оценки, слова владельца, правило профиля").required()
    private val review by option("--review", help = "когда пересмотреть: условие по факту или дата").required()
    private val revises by option("--revises", help = "id решения, которое эта запись пересматривает").uuid()
    private val date by option("--date", help = "день решения, по умолчанию сегодня").date()
    private val workout by option("--workout", help = "разобранная тренировка").uuid()
    private val plan by option("--plan", help = "план, к которому решение").uuid()
    private val exercises by option("--exercise", help = "упражнение; можно несколько — запись попадёт в их историю").uuid().multiple()
    private val id by option("--id", help = "id записи — исправить опечатку; по умолчанию новый").uuid()

    override suspend fun exec() {
        val e = api.putJournal(id ?: newId(), JournalPut(date ?: today(), decision, reason, review, workout, plan, exercises, revises))
        if (json) out(e) else names(api, e.exerciseIds).let { names -> echo(listOf(e).toText { names[it] }) }
    }
}

class JournalList : TextCommand("list", "Журнал тренера, новые сначала") {
    private val from by option("--from").date()
    private val to by option("--to").date()
    private val exercise by option("--exercise", help = "только про это упражнение").uuid()
    private val active by option("--active", help = "только действующие решения, без пересмотренных").flag()
    private val deleted by option("--deleted", help = "только из корзины").flag()
    private val limit by option("--limit").int().default(30)

    override suspend fun exec() {
        val list = api.journal(from, to, exercise, limit, active, deleted)
        if (json) out(list) else {
            val names = names(api, list.flatMap { it.exerciseIds })
            echo(list.toText { names[it] }.ifEmpty { "журнал пуст" })
        }
    }
}

class JournalRm : ApiCommand("rm", "Запись журнала — в корзину. Пересмотреть решение — journal add --revises") {
    private val id by argument("id").uuid()
    override suspend fun exec() {
        api.deleteJournal(id)
    }
}

class JournalRestore : TextCommand("restore", "Вернуть запись журнала из корзины") {
    private val id by argument("id").uuid()
    override suspend fun exec() {
        val e = api.restoreJournal(id)
        if (json) out(e) else names(api, e.exerciseIds).let { names -> echo(listOf(e).toText { names[it] }) }
    }
}

/** Сколько неразобранных тренировок, кроме последней, `brief` показывает целиком. */
private const val BRIEF_UNREVIEWED = 2

/**
 * Всё для плана и разбора за один вызов, окно — [days] дней (по умолчанию месяц): профиль, планы,
 * журнал (действующие решения любой давности и все записи окна), недели, последняя тренировка и
 * неразобранные — те, на которые нет записи журнала, — и история упражнений программы.
 */
class BriefCmd : ApiCommand("brief", "Сводка для агента перед планом или разбором: профиль, планы, журнал, недели, тренировки, история") {
    private val days by option("--days", help = "окно журнала, тренировок и истории, по умолчанию 30").int().restrictTo(1, 365).default(30)
    private val weeks by option("--weeks", help = "сколько недель, по умолчанию 8").int().restrictTo(1, 104).default(8)

    override fun help(context: Context) = super.help(context) + "\n\n" + AGENT_TEXT_LEGEND

    override suspend fun exec() {
        val today = today()
        val since = today.minus(DatePeriod(days = days - 1))
        val profile = api.profile()
        val plans = api.plans(from = today.minus(DatePeriod(days = 7)))
        val entries = (api.journal(active = true) + api.journal(from = since))
            .distinctBy { it.id }.sortedWith(compareByDescending<JournalEntry> { it.date }.thenByDescending { it.createdAt })
        val ws = api.weeks(today.minus(DatePeriod(days = 7 * (weeks - 1))), today)
        val workouts = api.workouts(from = since).filter { !it.deleted && it.finishedAt != null }.sortedByDescending { it.startedAt }
        val reviewed = entries.mapNotNull { it.workoutId }.toSet()
        val unreviewed = workouts.drop(1).filter { it.id !in reviewed }
        val history = api.history(since = since)
        val names = names(api, entries.flatMap { it.exerciseIds })

        suspend fun workout(w: Workout): String {
            val mark = if (w.id in reviewed) "" else " · не разобрана"
            return "### ${(w.date?.dmy() ?: "")}$mark\n" + compareText(api, api.compare(w.id), planNotes = false)
        }

        val sections = listOf(
            "## профиль" to listOfNotNull(
                profile.heightCm?.let { "рост ${it.toInt()} см" },
                profile.equipment?.let { "оборудование: $it" },
                profile.notes?.let { "заметки:\n$it" },
            ).joinToString("\n"),
            "## планы" to plans.sortedBy { it.date }.joinToString("\n") {
                "${it.date.weekday()} ${it.date.dm()} «${it.title ?: "без названия"}» ${it.status.name.lowercase()} v${it.version} · id ${it.id}"
            },
            "## журнал: действующие решения и записи за $days дн." to entries.toText { names[it] },
            "## недели" to ws.toText(),
            "## тренировки: последняя и неразобранные" to buildList {
                workouts.firstOrNull()?.let { add(workout(it)) }
                unreviewed.take(BRIEF_UNREVIEWED).forEach { add(workout(it)) }
                unreviewed.drop(BRIEF_UNREVIEWED).takeIf { it.isNotEmpty() }?.let { rest ->
                    add("ещё не разобраны: " + rest.joinToString(", ") { "${it.date?.dmy()} ${it.id}" })
                }
            }.joinToString("\n\n"),
            // Журнал — уже выше целиком: в истории не повторять
            "## история упражнений программы за $days дн." to history.map { it.copy(journal = emptyList()) }.toText(),
        )
        echo(sections.joinToString("\n\n") { (title, body) -> "$title\n${body.ifEmpty { "—" }}" })
    }
}
