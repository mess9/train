# train

Журнал тренировок: агент пишет план через CLI, владелец проходит его на телефоне (PWA,
работает без сети), агент получает сравнение плана с фактом. Концепция — [DESIGN.md](DESIGN.md),
термины — [CONTEXT.md](CONTEXT.md), план работ — [docs/PLAN.md](docs/PLAN.md).

| Модуль | Что |
|---|---|
| `core` | Kotlin Multiplatform (jvm + wasmJs): DTO и общая логика |
| `client` | API-клиент на Ktor для `cli` и `app`; серверу не нужен |
| `server` | Quarkus: API и раздача PWA |
| `cli` | `gym` — CLI для агента, вывод JSON |
| `app` | Compose Multiplatform, wasmJs — PWA для телефона |

`spike/` — отдельная проверка Compose/Wasm на телефоне (шаг 0 плана), в сборку не входит.

## Запуск

Нужны JDK 21 и Docker (Dev Services поднимают Postgres в dev и тестах).

```bash
./gradlew build                  # всё + тесты
./gradlew :server:quarkusDev     # API и приложение на http://localhost:7313
./gradlew :cli:installDist       # CLI → cli/build/install/gym/bin/gym
cli/build/install/gym/bin/gym info
```

Сборка сервера сама собирает `app` и кладёт его в `META-INF/resources`. Адрес сервера для CLI —
`--url` или `GYM_URL`.

Описание API — `/q/openapi`, здоровье — `/q/health`.
