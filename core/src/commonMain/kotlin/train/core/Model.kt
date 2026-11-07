package train.core

import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Duration.Companion.hours
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
    /** Из общего справочника — его видят все, правит админ; иначе — своё упражнение (ADR 0011). */
    val shared: Boolean = false,
)

/** Профиль: его читает агент, когда подбирает веса и упражнения. */
@Serializable
data class Profile(
    val name: String? = null,
    /** Оборудование свободным текстом: блины, грифы, потолок веса. */
    val equipment: String? = null,
    val notes: String? = null,
    val vibration: Boolean = false,
    /** Рост, см. В объём не входит (в нём нет расстояния), агент видит его рядом с весом тела. */
    val heightCm: Double? = null,
    /** Дата рождения: возраст для процента жира по складкам ([bodyFatJp3]). */
    val birthDate: LocalDate? = null,
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

/** Тип замера собственного веса: в объём идёт [bodyweightFor]. */
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
    /** Замер сделан на этой тренировке — обычно сразу после неё ([workoutForMeasurement]). */
    val workoutId: Uuid? = null,
    override val rev: Long = 0,
    override val deleted: Boolean = false,
) : SyncRecord {
    override fun withRev(rev: Long) = copy(rev = rev)
}

/**
 * Вес тела для объёма тренировки: взвешивание на этой тренировке, иначе последнее до её старта.
 * Взвешивание после тренировки ближе к весу на ней, чем недельной давности.
 */
fun bodyweightFor(workout: Workout, measurements: List<Measurement>): Double? {
    val weights = measurements.filter { it.type == BODYWEIGHT && !it.deleted }
    return (weights.filter { it.workoutId == workout.id }.maxByOrNull { it.measuredAt }
        ?: weights.filter { it.measuredAt <= workout.startedAt }.maxByOrNull { it.measuredAt })?.value
}

/** Сколько после завершения замер ещё относится к тренировке. */
val MEASUREMENT_AFTER_WORKOUT = 3.hours

/**
 * К какой тренировке привязать замер в момент [at]: к идущей или к завершённой не раньше
 * [MEASUREMENT_AFTER_WORKOUT] назад, самой поздней. `null` — замер сам по себе.
 */
fun workoutForMeasurement(workouts: Collection<Workout>, at: Instant): Uuid? = workouts
    .filter { !it.deleted && it.startedAt <= at && (it.finishedAt == null || at - it.finishedAt <= MEASUREMENT_AFTER_WORKOUT) }
    .filter { it.finishedAt != null || at - it.startedAt <= 12.hours }
    .maxByOrNull { it.startedAt }?.id
