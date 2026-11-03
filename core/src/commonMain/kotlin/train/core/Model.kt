package train.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Что измеряется в подходе. Дистанция — потом. */
@Serializable
enum class RecordType {
    WEIGHT_REPS,
    /** Повторы, опционально с доп. весом (`extraWeight`). */
    REPS,
    TIME,
    WEIGHT_TIME;

    val usesWeight get() = this == WEIGHT_REPS || this == WEIGHT_TIME
    val usesReps get() = this == WEIGHT_REPS || this == REPS
    val usesExtraWeight get() = this == REPS
    val usesSeconds get() = this == TIME || this == WEIGHT_TIME
}

@Serializable
enum class Rating { EASY, NORMAL, HARD }

/** Роль — свойство места в тренировке, не упражнения. */
@Serializable
enum class BlockRole { WARMUP, MAIN, COOLDOWN }

/** Упражнение справочника. */
@Serializable
data class Exercise(
    val id: Uuid,
    val nameRu: String,
    val nameEn: String? = null,
    /** ID записи в источнике (exercises-dataset). */
    val sourceId: String? = null,
    val musclesPrimary: List<String> = emptyList(),
    val musclesSecondary: List<String> = emptyList(),
    val equipment: String? = null,
    /** Снарядов: 1 или 2 (две гантели). Вес в подходе — одного снаряда. */
    val implements: Int = 1,
    /** Тип записи по умолчанию; в плане и тренировке меняется на месте. */
    val recordType: RecordType = RecordType.WEIGHT_REPS,
    /** Повторы «на сторону» — на каждую сторону. */
    val perSide: Boolean = false,
    /**
     * Какую долю собственного веса поднимаешь в повторе: отжимания ≈ 0,65, подтягивания ≈ 1,
     * брусья ≈ 0,9, присед без веса ≈ 0,7. Входит в объём. `null` — собственный вес не считается.
     */
    val bodyweightFactor: Double? = null,
    val mediaKey: String? = null,
    val instructions: List<String> = emptyList(),
)

/** Профиль: его читает агент, когда подбирает веса и упражнения. */
@Serializable
data class Profile(
    val name: String? = null,
    /** Оборудование свободным текстом: блины, грифы, потолок веса. */
    val equipment: String? = null,
    val notes: String? = null,
    val vibration: Boolean = false,
)

/**
 * Запись, которую телефон синхронизирует по ADR 0007: `rev` считает сервер.
 * В очереди хранится полиморфно; дискриминатор не `type` — это поле есть у замера.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
sealed interface SyncRecord {
    val id: Uuid
    val rev: Long
    val deleted: Boolean

    fun withRev(rev: Long): SyncRecord
}

/** Тело `PUT` синхронизируемой записи. Новая запись — `baseRev = 0`. */
@Serializable
data class SyncPut<T>(val data: T, val baseRev: Long, val opId: Uuid)

/** Тип замера собственного веса: последний до тренировки идёт в объём. */
const val BODYWEIGHT = "weight"

@Serializable
@SerialName("measurement")
data class Measurement(
    override val id: Uuid,
    /** Например [BODYWEIGHT], `waist`. */
    val type: String,
    val value: Double,
    val unit: String,
    val measuredAt: Instant,
    val workoutId: Uuid? = null,
    override val rev: Long = 0,
    override val deleted: Boolean = false,
) : SyncRecord {
    override fun withRev(rev: Long) = copy(rev = rev)
}
