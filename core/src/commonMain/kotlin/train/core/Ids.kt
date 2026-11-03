package train.core

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** ID записи — UUIDv7, его генерирует клиент: без этого не работает офлайн. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): Uuid = Uuid.generateV7()
