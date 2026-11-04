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
`skills/gym/` — скилл для агента: рабочий цикл и формат плана; подключается через
`skillshare link skills/gym --name gym`.

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

API требует заголовок `X-Api-Key`. В dev уже есть пользователь `dev@local` с ключом `dev-key`
(он админ), датасет берётся из соседней папки `../exercises-dataset`, фото пишутся в
`server/build/photos`. В проде:

| Переменная | Что |
|---|---|
| `TRAIN_BOOTSTRAP_EMAIL`, `TRAIN_BOOTSTRAP_KEY` | первый пользователь и его ключ, создаются при старте |
| `TRAIN_SOURCE_PATH` | `exercises.json` датасета — для поиска и добавления упражнений |
| `TRAIN_MEDIA_PATH` | папка с картинками и гифками (`images/`, `videos/`) |
| `TRAIN_PHOTOS_PATH` | папка фото (ADR 0010); не задана — API фото отвечает `503` |
| `TRAIN_PHOTOS_MAX_BYTES` | предел файла фото, по умолчанию `15728640` (15 МБ) |
| `TRAIN_COMMIT`, `TRAIN_BUILD` | коммит и номер сборки: задаёт сборка образа, в `/api/info` (ADR 0009) |
| `TRAIN_ZONE` | часовой пояс для «сегодня» и дня старых тренировок без смещения телефона, например `Europe/Moscow` |
| `QUARKUS_DATASOURCE_JDBC_URL`, `_USERNAME`, `_PASSWORD` | Postgres |

## Docker

`compose.yaml` (проект `train`) поднимает `server` и `train_postgres` с томом `train-pgdata`.
Образ собирается из `Dockerfile`: Gradle-сборка с приложением внутри, тесты не запускаются.
Секреты лежат в env-файле, список переменных — в [train.env.example](train.env.example). Датасет
монтируется только для чтения: по умолчанию из `/mnt/safe_storage/data/train/exercises-dataset`,
другой путь задаёт `TRAIN_DATASET_DIR`.

Фото (ADR 0010) — файлы на диске, в контейнере `/photos`: bind-том из
`/mnt/safe_storage/data/train/photos` (другой путь — `TRAIN_PHOTOS_DIR`), сервер пишет туда от
пользователя `1000`. Файл — `<userId>/<photoId>`, описание — в базе. Папку создаёт владелец
сервера до первого деплоя, иначе Jenkins остановится на этапе Check storage:

```bash
sudo mkdir -p /mnt/safe_storage/data/train/photos
sudo chown 1000:1000 /mnt/safe_storage/data/train/photos
```

Бэкап фото — вместе с остальным `/mnt/safe_storage`; дамп базы без папки фото даёт описания
без файлов.

```bash
cp train.env.example train.env   # заполнить; train.env в .gitignore
docker network create proxy      # один раз: на сервере это сеть Traefik
docker compose --env-file train.env up -d --build
docker compose --env-file train.env ps   # server — healthy, http://127.0.0.1:7313
```

На сервере (shelezyaka) деплоит Jenkins-задача `train` по [Jenkinsfile](Jenkinsfile): тесты
`core`, `client`, `cli` в образе (`docker build --target test .`), push в `localhost:5000`,
`docker compose up` с секретом `train-env`, smoke-тест. Сборка образа передаёт коммит и номер
сборки — `/api/info` и `gym info` показывают `0.2.0+<коммит>`. Версию (semver) поднимают в
`gradle.properties`. Снаружи — `https://train.fil-lost.org`
через туннель Cloudflare и Traefik.

На сервере база попадает в ночные дампы shelezyaka (`train.sql.gz`). Дамп руками:

```bash
docker exec train-train_postgres-1 pg_dump -U train -Fc train > train-$(date +%F).dump
```

Восстановление — в пустую базу, с остановленным `server`:

```bash
docker exec -i train-train_postgres-1 pg_restore -U train -d train --clean --if-exists < train-2026-11-04.dump
```

## Пользователи

Первый пользователь (`TRAIN_BOOTSTRAP_EMAIL`) — админ (ADR 0011). Он заводит остальных и ведёт
общий справочник упражнений; у остальных к общему добавляются свои, всё прочее у каждого своё.

```bash
gym admin user add friend@example.com --name Друг --qr   # QR со ссылкой …/#key=… — в stderr
gym admin user list
```

Ключ нового пользователя показывается один раз. Его почту надо добавить и в список Cloudflare
Access — иначе телефон до сервера не дойдёт.
