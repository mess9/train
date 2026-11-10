package train.core

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

// ID блоков, упражнений и целей необязательны во входном JSON: агенту не нужно их генерировать.

/** Целевой подход. Всё необязательно: чего нет в плане, предзаполнится из прошлого факта. */
@Serializable
data class TargetSet(
    val id: Uuid = newId(),
    /** Вес одного снаряда, кг. */
    val weight: Double? = null,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val extraWeight: Double? = null,
    val seconds: Int? = null,
    /** Запас повторов. */
    val rir: Int? = null,
)

/** Упражнение в блоке плана со своим списком целевых подходов. */
@Serializable
data class BlockItem(
    val id: Uuid = newId(),
    val exerciseId: Uuid,
    /** `null` — как у упражнения в справочнике. */
    val recordType: RecordType? = null,
    val perSide: Boolean? = null,
    val sets: List<TargetSet>,
    /** Отдых после подхода этого упражнения, если проход не закрыт. */
    val restSec: Int? = null,
    val note: String? = null,
)

/** Блок: упражнения × проходы. Проходов столько, сколько подходов в самом длинном списке. */
@Serializable
data class Block(
    val id: Uuid = newId(),
    val role: BlockRole = BlockRole.MAIN,
    val title: String? = null,
    val items: List<BlockItem>,
    /** Отдых после закрытого прохода. `null` — отдых упражнения. */
    val restAfterPassSec: Int? = null,
)

@Serializable
data class PlanBody(val blocks: List<Block>, val note: String? = null)

/**
 * Переставить упражнение внутри блока: с места [from] на [to], остальные сдвигаются. Цели,
 * ID и заметки не меняются. Неверные индексы — тело как было.
 */
fun PlanBody.moveItem(blockId: Uuid, from: Int, to: Int): PlanBody = copy(
    blocks = blocks.map { b ->
        if (b.id != blockId || from == to || from !in b.items.indices || to !in b.items.indices) b
        else b.copy(items = b.items.toMutableList().apply { add(to, removeAt(from)) })
    },
)

/**
 * Тело `PUT /plans/{id}`: создать или заменить план, сервер поднимет версию.
 * [baseVersion] — от какой версии сделана правка: на сервере уже другая — `409` с текущей,
 * чужая правка не затирается. `null` — заменить как есть (агент).
 */
@Serializable
data class PlanPut(val date: LocalDate, val title: String? = null, val body: PlanBody, val baseVersion: Int? = null)

@Serializable
enum class PlanStatus { PLANNED, OVERDUE, STARTED }

@Serializable
data class Plan(
    val id: Uuid,
    /** Намерение, начать можно в любой день. */
    val date: LocalDate,
    val title: String? = null,
    val version: Int,
    val body: PlanBody,
    val status: PlanStatus,
    val startedAt: Instant? = null,
    /** С какой версии начата тренировка. Меньше `version` — правка не применилась (ADR 0007). */
    val startedVersion: Int? = null,
    /** Не `null` — план в корзине. */
    val deletedAt: Instant? = null,
)

fun planStatus(date: LocalDate, startedAt: Instant?, today: LocalDate): PlanStatus = when {
    startedAt != null -> PlanStatus.STARTED
    date < today -> PlanStatus.OVERDUE
    else -> PlanStatus.PLANNED
}
