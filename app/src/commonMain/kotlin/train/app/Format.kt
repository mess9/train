package train.app

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import train.core.BlockRole
import train.core.LayoutBlock
import train.core.LayoutItem
import train.core.RecordType
import train.core.SetFact
import train.core.SetValues
import train.core.TargetSet
import train.core.TimeInput
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Instant

private val zone = TimeZone.currentSystemDefault()

fun Instant.date(): LocalDate = toLocalDateTime(zone).date

private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val monthsShort = listOf("янв", "февр", "марта", "апр", "мая", "июня", "июля", "авг", "сент", "окт", "нояб", "дек")
private val days = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
private val daysShort = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private val DayOfWeek.index get() = ordinal

/** «Вторник, 3 ноября» */
fun LocalDate.long() = "${days[dayOfWeek.index]}, $day ${months[month.ordinal]}"

/** «Вт, 3 нояб» */
fun LocalDate.short() = "${daysShort[dayOfWeek.index]}, $day ${monthsShort[month.ordinal]}"

/** «3 ноября» */
fun LocalDate.dayMonth() = "$day ${months[month.ordinal]}"

/** «Вт» */
fun LocalDate.weekday() = daysShort[dayOfWeek.index]

private val monthsAlone = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** «ноя» — месяц сам по себе, подпись столбика */
fun LocalDate.monthAlone() = monthsAlone[month.ordinal]

/** «3.11» — коротко для осей */
fun LocalDate.dm() = "$day.${(month.ordinal + 1).toString().padStart(2, '0')}"

/** «14:05» по часам телефона */
fun Instant.clock(): String = toLocalDateTime(zone).let { "${it.hour.pad()}:${it.minute.pad()}" }

private fun Int.pad() = toString().padStart(2, '0')

/** «1:30» */
fun mmss(d: Duration): String {
    val t = d.inWholeSeconds.coerceAtLeast(0)
    return "${t / 60}:${(t % 60).toInt().pad()}"
}

/** «1:02:03» или «2:03» */
fun hms(d: Duration): String {
    val t = d.inWholeSeconds.coerceAtLeast(0)
    val h = t / 3600
    val m = ((t % 3600) / 60).toInt()
    val s = (t % 60).toInt()
    return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val a = n % 10
    val b = n % 100
    return when {
        a == 1 && b != 11 -> one
        a in 2..4 && (b < 12 || b > 14) -> few
        else -> many
    }
}

/** 14 → «14», 12.5 → «12,5» */
fun num(x: Double): String {
    val r = (x * 100).roundToLong()
    if (r % 100 == 0L) return (r / 100).toString()
    val s = (abs(r) / 100).toString() + "," + (abs(r) % 100).toString().padStart(2, '0').trimEnd('0')
    return if (r < 0) "-$s" else s
}

/** «1 234» — тысячи через пробел */
fun thousands(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()

fun parseNum(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

fun blockTitle(role: BlockRole, title: String?, index: Int): String = title ?: when (role) {
    BlockRole.WARMUP -> "Разминка"
    BlockRole.COOLDOWN -> "Заминка"
    BlockRole.MAIN -> "Блок ${index + 1}"
}

/** Время подхода: до минуты — «45 с», дальше — «1:30» (п. 14 владельца: не секундами). */
fun secs(s: Int): String = if (s < 60) "$s с" else TimeInput.clock(s)

/** Цель подхода: «10–12 × 14 кг», «15 повт», «45 с», «1:30»; без цели — «без плана». */
fun target(type: RecordType, s: TargetSet?): String {
    if (s == null) return "без плана"
    val reps = when {
        s.repsMin == null && s.repsMax == null -> null
        s.repsMin == null || s.repsMax == null || s.repsMin == s.repsMax -> (s.repsMax ?: s.repsMin).toString()
        else -> "${s.repsMin}–${s.repsMax}"
    }
    val parts = buildList {
        if (type.usesReps && reps != null) add(if (type.usesWeight) reps else "$reps повт")
        if (type.usesSeconds && s.seconds != null) add(secs(s.seconds!!))
        if (type.usesWeight) s.weight?.let { add("${num(it)} кг") }
        if (type.usesExtraWeight) s.extraWeight?.let { add("+${num(it)} кг") }
    }
    return parts.joinToString(" × ").ifEmpty { "без плана" }
}

/**
 * Строка плана под названием упражнения в тренировке: «10–12 × 14 кг · отдых 20 с». Одна строка
 * в компактной карточке — без слова «План».
 */
fun itemMeta(item: LayoutItem, replacedName: String?): String {
    if (item.added) return "Добавлено, не из плана"
    if (item.replacedFrom != null) return "Замена «${replacedName ?: "?"}»"
    val targets = item.sets.map { target(item.recordType, it) }.distinct()
    var t = when (targets.size) {
        0 -> "без плана"
        1 -> targets[0]
        else -> "пирамида"
    }
    if (item.perSide) t += " · на сторону"
    if (item.restSec != null && item.restSec!! > 0) t += " · отдых ${secs(item.restSec!!)}"
    return t
}

/** Цель упражнения в экране плана: «3 × 10–12 × 14 кг · на сторону». В круге подходов не пишем. */
fun planTarget(block: LayoutBlock, item: LayoutItem, passes: Int): String {
    val targets = item.sets.map { target(item.recordType, it) }.distinct()
    val t = if (targets.size > 1) "пирамида" else target(item.recordType, item.sets.firstOrNull())
    return (if (passes > 1 || item.sets.isEmpty()) "" else "${item.sets.size} × ") + t + (if (item.perSide) " · на сторону" else "")
}

/** Коротко: «14×12», «15 повт», «45 с»; нет значений — «—». */
fun short(type: RecordType, v: SetValues?): String {
    if (v == null) return "—"
    return when (type) {
        RecordType.WEIGHT_REPS -> v.weight?.let { w -> v.reps?.let { "${num(w)}×$it" } } ?: v.reps?.let { "$it повт" } ?: "—"
        RecordType.REPS -> v.reps?.let { r -> "$r повт" + (v.extraWeight?.let { " +${num(it)}" } ?: "") } ?: "—"
        RecordType.TIME -> v.seconds?.let(::secs) ?: "—"
        RecordType.WEIGHT_TIME -> v.weight?.let { w -> v.seconds?.let { "${num(w)}×${secs(it)}" } } ?: v.seconds?.let(::secs) ?: "—"
    }
}

/** «10 кг × 12» — для «Последний раз». */
fun SetFact.pretty(): String = when (recordType) {
    RecordType.WEIGHT_REPS -> listOfNotNull(weight?.let { "${num(it)} кг" }, reps?.toString()).joinToString(" × ")
    else -> short(recordType, values)
}

/** Мышцы в датасете названы по-английски; незнакомое название показываем как есть. */
fun muscle(name: String): String = Muscles[name.lowercase()] ?: name

private val Muscles = mapOf(
    "abs" to "пресс", "lower abs" to "низ пресса", "obliques" to "косые мышцы живота", "core" to "кор",
    "pectorals" to "грудные", "chest" to "грудные", "upper chest" to "верх груди", "serratus anterior" to "зубчатые",
    "delts" to "дельты", "deltoids" to "дельты", "shoulders" to "плечи", "rear deltoids" to "задние дельты",
    "rotator cuff" to "вращательная манжета", "biceps" to "бицепс", "triceps" to "трицепс", "brachialis" to "брахиалис",
    "forearms" to "предплечья", "wrists" to "запястья", "wrist flexors" to "сгибатели запястья",
    "wrist extensors" to "разгибатели запястья", "hands" to "кисти", "grip muscles" to "хват",
    "lats" to "широчайшие", "latissimus dorsi" to "широчайшие", "upper back" to "верх спины", "back" to "спина",
    "lower back" to "поясница", "spine" to "разгибатели спины", "traps" to "трапеции", "trapezius" to "трапеции",
    "rhomboids" to "ромбовидные", "levator scapulae" to "мышца, поднимающая лопатку", "neck" to "шея",
    "sternocleidomastoid" to "грудино-ключично-сосцевидная",
    "glutes" to "ягодицы", "hip flexors" to "сгибатели бедра", "quads" to "квадрицепсы", "quadriceps" to "квадрицепсы",
    "hamstrings" to "бицепс бедра", "adductors" to "приводящие", "inner thighs" to "внутренняя поверхность бедра",
    "groin" to "пах", "abductors" to "отводящие", "calves" to "икры", "soleus" to "камбаловидная", "shins" to "голени",
    "ankles" to "голеностоп", "ankle stabilizers" to "стабилизаторы голеностопа", "feet" to "стопы",
    "cardiovascular system" to "сердечно-сосудистая система",
)
