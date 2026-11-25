# train

Журнал тренировок: сервер Quarkus, PWA на Compose Wasm (`app`), CLI `gym`, общая модель в `core`.

## Куда смотреть

- `CONTEXT.md` — глоссарий. Термины в коде и в разговоре — в его значении; читать первым при
  задаче про предметную область.
- `DESIGN.md` — концепция и экраны. `docs/adr/` — почему так: меняешь API, синхронизацию,
  формат плана, авторизацию — сначала соответствующий ADR (0005–0008).
- `.claude/skills/gym/` — правила агента-тренера, в клоне подхватывается сам. `SKILL.md` — план и
  разбор, темы — рядом: `catalog.md` (упражнения, картинки), `body.md` (замеры, фото), `admin.md`.
  Меняешь API планов, тренировок, справочника или замеров — сверь нужный файл.
- Сон, пульс, шаги из Health Connect на сервер шлёт APK (ADR 0015): `app/.../Health.android.kt`,
  экран — «Настройки» → «Сон и пульс». Ночи и дни считает `core/.../Health.kt`, агенту —
  `gym health days` и раздел в `brief`.
- `docs/dataset.md` — датасет упражнений (картинки, шаги): где взять, формат; в репо его нет.
- UI-примитивы — `app/.../Theme.kt` (`Page`, `Surface`, `Btn`, `Txt`, `T`, `C`, `tap`; `haptic` — в `Platform.kt`).
- `app` — общий код PWA и будущего APK (`docs/PLAN-android.md`): экраны и `AppState` в
  `commonMain`, браузер и система — только через `expect` в `Platform.kt`. PWA — `wasmJsMain`
  (`Bridge.kt` — `actual` поверх `window.bridge` из `index.html`, вход — `WebMain.kt`),
  Android — `androidMain` (`Platform.android.kt`, `AndroidHost.kt`), APK — модуль `android/`
  (вход, манифест, подпись).

## Тренировки владельца

Владелец — главный пользователь приложения: тренируется по планам в PWA, ты ведёшь его как
тренер через CLI `gym`. Тренировка, план, веса, замеры, фото, самочувствие, питание — сначала
skill `gym` (`.claude/skills/gym/SKILL.md`; `~/.claude/skills/gym` — ссылка на него через
skillshare, так что правка в репо = правка skill).

- Правила, оборудование, цели и ответы владельца — в `gym profile get` (`equipment`, `notes`);
  новое о предпочтениях и ограничениях дописывать туда. Личное (вес, фото, цели, здоровье) в
  файлы репо не писать: они уходят в Gitea под GPL — оно живёт на сервере и в auto-memory.
- История до 04.11.2026 импортирована из Hevy/GymKeeper (`import/`): время начала условное,
  оценок почти нет, фото без позы; «Болгарские выпады с гантелями» 12 кг там — одна гантель у
  груди, хотя в справочнике `implements 2`, объём по ним завышен вдвое.
- Контекст тренера — `gym brief`, разбор — `compare`/`history`/`weeks` текстом (ADR 0014): JSON
  тренировки до ~70 КБ, текст — в 5–6 раз меньше. Скрипт-сжималка не нужна; `--json` — для скриптов.
  Решения разбора — в `gym journal add`, а не только в ответе.
- В `brief` нет раздела «сон и восстановление», `gym health` — «no such command»: `installDist`
  старше репо. Пересобрать `./gradlew :cli:installDist` — без этого сон молча выпадает из разбора.
- Свежий `cli:installDist` со своими командами ходит в эндпоинты, которых на проде до деплоя
  нет (404) — проверять на `preview_start server`.
- Python в консоли: `PYTHONIOENCODING=utf-8`, иначе cp1251 падает на эмодзи из импорта.
  ID из `$(python …)` приходят с `\r` — цикл по ID вести внутри Python (`gym.bat` через
  `subprocess`).
- `java.exe` на Windows отдаёт `main` аргументы в cp1251: ✓ × → ♥ стали бы `?` без ошибки. `gym`
  берёт исходные через `GetCommandLineW` (`cli/.../WinArgs.kt`); не вышло — предупреждение в stderr
  про каждый аргумент с «?». Видишь его — значки в тексте словами или через JSON-файл.
- Фото с телефона (≈1126×2000) Read не открывает — уменьшить в scratchpad:
  `ImageOps.exif_transpose`, `thumbnail((800, 800))`.

## Сборка и проверка

- **Docker запущен** — без него не стартуют `:server:test` и `quarkusDev` (Postgres из Dev
  Services). Демона нет — `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`
  и ждать `docker info`.
- Тесты: `./gradlew :core:jvmTest :client:jvmTest :cli:test`, сервер — `:server:test --tests …`,
  приложение — `:app:compileKotlinWasmJs :app:compileAndroidMain`. Итог читать по `BUILD`/`^e:` или по XML в
  `*/build/test-results`: с `-q` вывод пустой и при успехе, и без прогона.
- Общий код `app` собирать под оба таргета: JVM ловит то, что wasm пропускает. Пример —
  `var paused` с `private set` рядом с `fun setPaused(…)`: «Platform declaration clash» только в
  `compileAndroidMain`.
- **В Jenkins только core, client, cli** — внутри `docker build --target test`. `server:test`
  там не идёт (нужен Docker в Docker): серверные тесты — локально, перед пушем. Коммит без
  файлов из `COPY` в `Dockerfile` (доки, `docs/`) берёт сборку и тесты из кэша слоёв.
  Исключение — `.claude/skills/gym/SKILL.md`: он включён через `.dockerignore` и копируется только
  в стадию `test`, потому что CLI-тест проверяет JSON-пример плана из skill. Правка примера
  должна перезапускать этот тест и в Docker.
- **Android**: модуль `android` включается, только если есть `ANDROID_HOME` или
  `local.properties`; в Jenkins его собирает только стадия `apk` в `Dockerfile` (там своё SDK).
  AGP 9 (Kotlin встроенный) объявлен в корне рядом с Kotlin-плагином: из одного
  `build.gradle.kts` модуля — «Could not create an instance of KotlinAndroidTarget». Quarkus — только в `server/build.gradle.kts`: в корне он поднимает JAXB
  до 4, и AGP падает на `javax/xml/bind/annotation/XmlSchema`.
- **APK**: `./gradlew :android:assembleRelease` → `android/build/outputs/apk/release/`. Подпись —
  `train.signing.store`, `storePassword`, `keyAlias` (по умолчанию `train`), `keyPassword` в
  `~/.gradle/gradle.properties`; без них APK неподписанный и не ставится. Плавность мерить только
  на release. `assembleDebug` — `train.app.debug`, ставится рядом с боевой.
- Health Connect выдаёт доступ, только если в манифесте есть экран «зачем данные»
  (`HealthRationaleActivity` и `activity-alias` для Android 14+). Проверка на эмуляторе
  `Medium_Phone_API_37.0`: «Настройки» → «Дать доступ» → два диалога, затем в
  `adb logcat | grep WM-` — `Worker result SUCCESS … HealthWorker`. Данных Health Connect там нет: «отправлено 0».
- **APK из Jenkins**: стадия `APK` идёт после деплоя и только при изменениях в коде APK (или с
  параметром `APK`). Она собирает `docker build --target apk-out --output type=local,dest=build/apk`
  и выкладывает релиз в Gitea с тегом versionName, оттуда его берёт Obtainium. Локально та же
  команда без `--secret` даёт `android-release-unsigned.apk`. В образе нет `.git`, поэтому
  `versionCode` и коммит передаются через `--build-arg TRAIN_VERSION_CODE/TRAIN_COMMIT`, иначе
  versionCode 1 и APK не встанет поверх установленного.
- APK на эмуляторе против `preview_start server`: `adb reverse tcp:7313 tcp:7313` и ссылка
  `http://127.0.0.1:7313/#key=dev-key`. На `10.0.2.2:7313` quarkusDev отвечает 400 (чужой
  `Host`), а HTTP без TLS разрешён только `localhost`/`127.0.0.1` (`network_security_config.xml`).
  `adb input text` кириллицу не вводит; `screencap` — 1080×2400, Read показывает уменьшенным.
- `AndroidHost.init` — в `Application.onCreate`, до любого `DeviceStorage`: каталог хранилища
  задаёт он. Activity цепляет `attach` — лаунчеры фото и «назад» регистрируются до `STARTED`.
- У `app` Android-таргет (`com.android.kotlin.multiplatform.library`), а в Docker-сборке SDK нет, и
  это нормально: AGP настраивает модуль без SDK, падает только Android-задача. Серверу и
  `server:appDist` нужен лишь wasm.
- e2e находит кнопку-пиктограмму по `contentDescription` (`aria-label`, в `lib.js` — `label`), а
  не по тексту. Без подписи кнопку не найти ни тесту, ни TalkBack, ни будущему драйверу Android.
- Браузерный мост доступности Compose не стирает текст и `aria-label` удалённого потомка у
  нажимаемого узла: «Не удалось загрузить фото» висит в кнопке и после загрузки картинки, а
  `getByText(...).toHaveCount(0)` считает и такие узлы. Меняющийся текст выносить из `tap`-узла
  либо задавать подпись `clearAndSetSemantics`/`contentDescription` самому (`PhotoCompare.CompareSlot`, `SetViews`).
- Выгрузка Health Connect (zip с SQLite) — недоверенный файл: отдельная пустая папка, Python
  `-I -X utf8` (`-I` игнорирует `PYTHONIOENCODING`). Личное из неё — только в scratchpad.
  Сами выгрузки (Health Connect, архив Mi Fitness) хранятся в `import/health/` главной копии:
  `/import/*/` в `.gitignore`, `import/` в `.dockerignore`. В worktree их нет. В архиве
  Mi Fitness главное — `hlth_center_fitness_data.csv` (Key, Time, Value-JSON; `Sid` — браслет).
- e2e: `npx playwright test` из `e2e/` — через PowerShell. В Git Bash `cd e2e` падает на хуке
  fnm («can't find the necessary environment variables»), команда после `&&` не идёт, а лог
  остаётся от прошлого прогона.
- Wasm: ошибка Ktor JS при отключённой сети (`Fail to fetch`) может быть `Throwable`, не
  `Exception`. В сетевом fallback перехватывать `Throwable`, перед ним — отдельно пробрасывать
  `CancellationException`. Необработанная ошибка из фонового `launch` может отменить UI scope:
  canvas застынет, дерево доступности останется от прошлого экрана. Проверка — старт целиком
  офлайн и отсутствие `pageerror` в браузерном тесте.
- Замок тренировки (ADR 0007) в превью: второе устройство — запросы с `X-Device` и
  `X-Device-Kind` (`take`, запись подхода — ждать `423`), ID браузера — `localStorage.device`.
  Без заголовка замок не проверяется — так пишут CLI и агент.
- Превью: `preview_start` `server` (порт 7313, ключ `dev-key`). База каждый запуск пустая —
  упражнения и план заводить через API. JSON с кириллицей — в файл и `curl --data @file`:
  из аргументов Git Bash кириллица приходит битой.
- Конфигурация `server` в `launch.json` собирает главную папку, не worktree. Из worktree —
  своя временная запись (не коммитить): `cmd /c "set PORT=7314&& <worktree>\gradlew.bat -p
  <worktree> :server:quarkusDev -Ddebug=false"` — порт и отладчик 5005 не спорят с главной.
- Сборка, пересобравшая jar `core` (`:core:jvmTest`, `:server:test`), ломает уже запущенный
  quarkusDev: на каждый запрос «Error restarting Quarkus … Invalid local file header signature»,
  сам не оживает. Свой — перезапустить; чужой (другой сессии) не трогать, поднять свой на 7314 той же записью.
- Правка `app` при запущенном сервере — `./gradlew :server:appDist`, затем две перезагрузки
  страницы (service worker).
- Приложение в превью открывать на `http://127.0.0.1:7313/#key=dev-key`: у `localhost` своё
  хранилище, там сессия от прошлой базы с неотправленным.
- Сессия от прошлой базы и на `127.0.0.1` («ключ другого пользователя», незакрытая тренировка):
  `localStorage.clear()` не хватит, приложение при выгрузке пишет её обратно. Очищать одним
  скриптом: `localStorage.clear(); Storage.prototype.setItem = () => {}; location.reload()`.
- «Открыто в другой вкладке», хотя вкладка одна: замок `active-workout` держит невидимый клиент
  (`await navigator.locks.query()` — чужой `clientId`), «Обновить» и переход не помогают. Открыть
  на другом origin (`localhost` ↔ `127.0.0.1`) и очистить его хранилище тем же скриптом.
- Жесты скриптом: холст Compose в shadow root —
  `[...document.querySelectorAll('div')].find(d => d.shadowRoot).shadowRoot.querySelector('canvas')`,
  в него `PointerEvent` `pointerdown`/`pointermove`/`pointerup` (`pointerType: 'touch'`).
  Долгое нажатие — пауза ≥ 700 мс между `down` и первым `move`.
  Клик мышью из `computer` по `tap` срабатывает не всегда — надёжнее такое касание. Координаты
  `clientX/Y` — CSS-пиксели: точку со скриншота умножить на `innerWidth` / ширину кадра скриншота
  (в превью 1024 / 800). Ctrl+A в поле Compose текст не выделяет — стирать Backspace.
- Скрытая панель превью рисует ~3 кадра/с (на телефоне 60): рывок «на один кадр» в ней не
  виден. Сдвиг, который должен встать в кадр вместе со сменой раскладки, — в обычном состоянии
  синхронно, а не в `scope.launch { animatable.snapTo }`: корутина приходит кадром позже.
  `requestAnimationFrame` там почти стоит — ждать появления DOM через `MutationObserver`.
- Свой жест внутри прокрутки (`Page`) после захвата поглощает **все** события пальца, и без
  сдвига тоже: на непоглощённом событии `verticalScroll` подхватывает жест и сразу едет на весь
  путь от первого касания. `detectDrag*` касания без сдвига пропускают — отсюда рывок списка на
  телефоне, который шлёт такие `pointermove`. В превью ловится `pointermove` с прежними координатами.
- Отладка на телефоне, где превью не воспроизводит: временный `<pre>` поверх экрана из
  `js("…")`, кто пишет состояние — `Snapshot.registerGlobalWriteObserver` + `new Error().stack`.
  Имена функций в стеке — только с `binaryenArgs.add("-g")` у задач `BinaryenExec` в
  `app/build.gradle.kts`; source map оптимизированной сборки покрывает лишь код проекта.

## Соглашения

- Комментарии, KDoc, тексты UI, коммиты — по-русски. Коммит: «Область: что сделано».
- Переводы строк — LF. Python на Windows пишет CRLF: `open(..., newline='')`.
  Рабочая копия бывает с CRLF (`text=auto`, `git ls-files --eol`): замену многострочного
  текста скриптом делать после `.replace('\r\n', '\n')`, иначе совпадений ноль. Эскейпы
  `\r`/`\n` в heredoc через Bash приходят настоящими переводами строк — такой скрипт писать
  файлом через Write.
- Пуш в `main` = деплой (вебхук Gitea → Jenkins).
- Неочевидное, найденное в работе (ловушка сборки, способ проверки, соглашение), — дописывать
  сюда в свой раздел; устаревшее — удалять.
