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

Сборка сервера сама собирает `app` и кладёт его в `META-INF/resources`. При запущенном
`quarkusDev` правка приложения — `./gradlew :server:appDist`, затем две перезагрузки страницы:
первая ставит новый service worker, вторая берёт новую сборку.

CLI берёт настройки из флага `--url`, переменных окружения или файла `~/.config/gym/config`
(другой путь — `GYM_CONFIG`), в этом порядке:

| Переменная | В файле | Что |
|---|---|---|
| `GYM_URL` | `url=` | адрес сервера, по умолчанию `http://localhost:7313` |
| `GYM_API_KEY` | `key=` | ключ API |
| `GYM_CF_CLIENT_ID`, `GYM_CF_CLIENT_SECRET` | `cf_client_id=`, `cf_client_secret=` | сервисный токен Cloudflare Access |

Описание API — `/q/openapi`, здоровье — `/q/health`.

API требует заголовок `X-Api-Key`. В dev уже есть пользователь `dev@local` с ключом `dev-key`,
датасет берётся из соседней папки `../exercises-dataset`. В проде:

| Переменная | Что |
|---|---|
| `TRAIN_BOOTSTRAP_EMAIL`, `TRAIN_BOOTSTRAP_KEY` | первый пользователь и его ключ, создаются при старте |
| `TRAIN_SOURCE_PATH` | `exercises.json` датасета — для поиска и добавления упражнений |
| `TRAIN_MEDIA_PATH` | папка с картинками и гифками (`images/`, `videos/`) |
| `TRAIN_ZONE` | часовой пояс для «сегодня», например `Europe/Moscow` |
| `QUARKUS_DATASOURCE_JDBC_URL`, `_USERNAME`, `_PASSWORD` | Postgres |
