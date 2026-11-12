# Датасет упражнений

Справочник упражнений живёт в базе сервера, и свежая база пуста: упражнения заводит агент через
`gym exercise add`. Датасет нужен как источник для таких записей. Добавленное из него упражнение
получает английское название, мышцы, снаряд, русские шаги техники, картинку и гифку. Без
датасета сервер работает, но `gym exercise search` всегда пустой, и каждое упражнение приходится
описывать руками, а картинку искать в интернете и загружать `gym exercise media` (см.
`.claude/skills/gym/catalog.md`).

Датасет в репозиторий не входит: картинки и гифки — © Gym visual (поле `attribution`), они не
под GPL этого кода. Не коммитьте их сюда.

## Проверить, подключён ли

```bash
gym info    # "dataset": {"exercises": 1324, "media": true}
```

- `exercises: 0` — сервер не нашёл `exercises.json`. При старте он пишет в лог «Источник
  упражнений не найден».
- `media: false` — нет папки медиа: упражнения из датасета добавятся, но без картинок.
- Поля `dataset` нет — сервер старше этой проверки.

## Где взять

[hasaneyldrm/exercises-dataset](https://github.com/hasaneyldrm/exercises-dataset) на GitHub:
1324 упражнения, шаги на 10 языках, около 160 МБ (гифки — 127 МБ).

| Где запущен сервер | Куда положить | Что настроить |
|---|---|---|
| `quarkusDev` | клон рядом с репозиторием: `../exercises-dataset` | ничего — `%dev` в `application.properties` |
| `docker compose` | любой каталог на хосте | `TRAIN_DATASET_DIR` (по умолчанию `/mnt/safe_storage/data/train/exercises-dataset`), монтируется в `/dataset` только для чтения |
| другой запуск | любой каталог | `TRAIN_SOURCE_PATH` — путь к `exercises.json`, `TRAIN_MEDIA_PATH` — корень с `images/` и `videos/` |

```bash
git clone --depth 1 https://github.com/hasaneyldrm/exercises-dataset ../exercises-dataset
```

Датасет читается один раз, при первом поиске или добавлении. Новый файл подхватывается только
после перезапуска сервера.

## Структура

```
exercises-dataset/
  data/exercises.json      ← TRAIN_SOURCE_PATH
  images/0001-2gPfomN.jpg  ← TRAIN_MEDIA_PATH = корень
  videos/0001-2gPfomN.gif
```

`exercises.json` — массив записей. Сервер читает только эти поля, остальные пропускает:

| Поле | Куда идёт |
|---|---|
| `id` | `sourceId` упражнения, аргумент `gym exercise add <sourceId>`; обязательно |
| `name` | `nameEn` и поиск; обязательно, английский |
| `target` | основная мышца (`musclesPrimary`) |
| `secondary_muscles` | вспомогательные мышцы (`musclesSecondary`) |
| `equipment` | снаряд |
| `body_part`, `muscle_group` | только поиск |
| `image` | `mediaKey` — имя файла без папки и расширения: `images/0001-2gPfomN.jpg` → `0001-2gPfomN` |
| `instruction_steps.ru` | шаги техники, массив строк |

Минимальный рабочий пример — тестовый датасет `server/src/test/resources/dataset/`.

Медиа отдаёт `/media/<mediaKey>.jpg` и `.gif`. Свои картинки (`mediaKey` — `own-…`) лежат не в
датасете, а в хранилище фото: `<TRAIN_PHOTOS_PATH>/exercises/`, один файл на оба расширения. Файл ищется в корне медиа, затем в `images/` (jpg)
или `videos/` (gif). Имя обязано быть вида `NNNN-буквыцифры`, иначе 404. Картинки в базу не
копируются, их отдают с диска при каждом показе: убрали датасет — картинки пропали у всех
упражнений. Шаги, мышцы и названия копируются в базу при `exercise add` и остаются.

## Свой датасет

Подойдёт любой JSON в этом формате: свои упражнения, перевод, другой набор картинок.

- Мышцы (`target`, `secondary_muscles`) — английские названия из словаря `REGIONS` в
  `core/src/commonMain/kotlin/train/core/Stats.kt`. С незнакомым названием упражнение не попадёт
  на силуэт мышц и в розу нагрузки.
- Медиа — пара `NNNN-ключ.jpg` / `NNNN-ключ.gif` с тем же `mediaKey`.
