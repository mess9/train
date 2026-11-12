package train.core

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Запись журнала тренера (ADR 0014): решение по тренировке, упражнению или программе, его
 * обоснование и условие пересмотра. Пишет агент после разбора и при плане; следующая сессия
 * продолжает решения, а не выводит их заново. Запись про упражнение ([exerciseIds]) видна в его
 * истории. Решение не переписывают: пересмотр — новая запись с [revises]; старая становится
 * пересмотренной ([revisedBy]) и из действующих уходит. Телефон журнал не синхронизирует.
 */
@Serializable
data class JournalEntry(
    val id: Uuid,
    /** День, к которому относится решение: обычно день разобранной тренировки. */
    val date: LocalDate,
    /** Что решено. */
    val decision: String,
    /** Почему: факт (цифры, оценки), слова владельца, правило профиля. */
    val reason: String,
    /** Когда пересмотреть: условие по факту или дата. */
    val review: String,
    val workoutId: Uuid? = null,
    val planId: Uuid? = null,
    val exerciseIds: List<Uuid> = emptyList(),
    /** Какое решение эта запись пересматривает. */
    val revises: Uuid? = null,
    /** Запись, которая пересмотрела это решение; считает сервер. `null` — решение действует. */
    val revisedBy: Uuid? = null,
    val createdAt: Instant,
    /** Не `null` — запись в корзине. */
    val deletedAt: Instant? = null,
)

/** Создать запись или исправить опечатку в ней. Все три текста обязательны. */
@Serializable
data class JournalPut(
    val date: LocalDate,
    val decision: String,
    val reason: String,
    val review: String,
    val workoutId: Uuid? = null,
    val planId: Uuid? = null,
    val exerciseIds: List<Uuid> = emptyList(),
    val revises: Uuid? = null,
)
