package train.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ответ API с ошибкой. `409` вместо этого отдаёт текущее состояние записи. */
@Serializable
data class ApiError(val error: String)

/** Владелец ключа: приложение привязывает к нему очередь (ADR 0008). */
@Serializable
data class Me(val userId: Uuid, val email: String, val name: String? = null)

@Serializable
data class KeyCreate(val label: String? = null)

/** Ключ показывается один раз, на сервере остаётся только хеш. */
@Serializable
data class KeyCreated(val id: Uuid, val key: String, val label: String? = null)

/** Ключ в списке — без самого ключа. `current` — тот, которым сделан запрос. */
@Serializable
data class ApiKey(
    val id: Uuid,
    val label: String? = null,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
    val current: Boolean = false,
)

/** Упражнение из источника (exercises-dataset); в справочник добавляется через [ExerciseCreate]. */
@Serializable
data class SourceExercise(
    val id: String,
    val name: String,
    val bodyPart: String? = null,
    val target: String? = null,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String? = null,
    val mediaKey: String? = null,
)

/** Добавить упражнение в справочник: из источника (`sourceId`) или своё. */
@Serializable
data class ExerciseCreate(
    val nameRu: String,
    val sourceId: String? = null,
    val nameEn: String? = null,
    val recordType: RecordType? = null,
    val implements: Int? = null,
    val perSide: Boolean? = null,
    val bodyweightFactor: Double? = null,
    val equipment: String? = null,
    val musclesPrimary: List<String>? = null,
    val musclesSecondary: List<String>? = null,
)

/** Правка справочника: меняются только переданные поля. */
@Serializable
data class ExercisePatch(
    val nameRu: String? = null,
    val nameEn: String? = null,
    val recordType: RecordType? = null,
    val implements: Int? = null,
    val perSide: Boolean? = null,
    val bodyweightFactor: Double? = null,
    val equipment: String? = null,
    val musclesPrimary: List<String>? = null,
    val musclesSecondary: List<String>? = null,
)

/** Тренировка вместе с подходами, включая удалённые: они прячут строки плана. */
@Serializable
data class WorkoutDetail(val workout: Workout, val sets: List<SetFact>)

@Serializable
data class Trash(
    val plans: List<Plan> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
)

@Serializable
data class Purged(val plans: Int, val workouts: Int, val measurements: Int)
