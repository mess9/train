-- Итерация 2: админ и общий справочник (ADR 0011), день тренировки по часам телефона,
-- фото прогресса (ADR 0010). Сердечко, заметка к упражнению и рост — в jsonb (layout, profile).

-- Админ заводит пользователей и ведёт общий справочник
alter table app_user add column admin boolean not null default false;

-- До V2 пользователь один — владелец: он админ, а его справочник становится общим
update app_user set admin = true where (select count(*) from app_user) = 1;
update exercise set owner_user_id = null
where owner_user_id is not null and (select count(*) from app_user) = 1;

-- День тренировки — дата старта в поясе телефона. utc_offset_sec — смещение телефона при старте;
-- у старых записей его нет, день считается в поясе сессии: JDBC ставит его по поясу JVM, как у сервера
alter table workout add column utc_offset_sec int;
alter table workout add column start_date date;
update workout set start_date = (started_at at time zone current_setting('TimeZone'))::date;
alter table workout alter column start_date set not null;
create index workout_user_date on workout (user_id, start_date desc);

-- Замеры тренировки: workout_id был с V1
create index measurement_workout on measurement (workout_id) where workout_id is not null;

-- Фото: файл на диске (train.photos.path)/<user_id>/<id>, здесь — описание.
-- deleted_at не null — в корзине; файл удаляется при очистке корзины
create table photo (
    id           uuid primary key,
    user_id      uuid        not null references app_user,
    taken_at     timestamptz not null,
    content_type text        not null,
    size         bigint      not null,
    sha256       text        not null,
    pose         text,
    note         text,
    workout_id   uuid references workout on delete set null,
    created_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create index photo_user_taken on photo (user_id, taken_at desc);
