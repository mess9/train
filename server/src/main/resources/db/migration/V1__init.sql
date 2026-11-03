-- Схема по ADR 0006: тело плана и раскладка тренировки — jsonb, подходы — строки.
-- Синхронизируемые записи (workout, workout_set, measurement) несут rev и op_id (ADR 0007).

create table app_user (
    id         uuid primary key,
    email      text        not null unique,
    name       text,
    profile    jsonb       not null default '{}',
    created_at timestamptz not null default now()
);

create table api_key (
    id         uuid primary key,
    user_id    uuid        not null references app_user,
    key_hash   text        not null unique,
    label      text,
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

-- owner_user_id null — общий справочник
create table exercise (
    id                uuid primary key,
    owner_user_id     uuid references app_user,
    source_id         text,
    name_ru           text        not null,
    name_en           text,
    muscles_primary   jsonb       not null default '[]',
    muscles_secondary jsonb       not null default '[]',
    equipment         text,
    implements        int         not null default 1,
    bodyweight_factor double precision,
    record_type       text        not null,
    per_side          boolean     not null default false,
    media_key         text,
    instructions      jsonb       not null default '[]',
    created_at        timestamptz not null default now(),
    deleted_at        timestamptz
);

-- started_version < version — тренировку начали со старой версии, правка не применилась
create table plan (
    id              uuid primary key,
    user_id         uuid        not null references app_user,
    date            date        not null,
    title           text,
    version         int         not null,
    started_at      timestamptz,
    started_version int,
    updated_at      timestamptz not null default now(),
    deleted_at      timestamptz
);
create index plan_user_date on plan (user_id, date);

-- Версии неизменяемы
create table plan_revision (
    plan_id    uuid        not null references plan on delete cascade,
    version    int         not null,
    body       jsonb       not null,
    created_at timestamptz not null default now(),
    primary key (plan_id, version)
);

create table workout (
    id           uuid primary key,
    user_id      uuid        not null references app_user,
    plan_id      uuid references plan on delete set null,
    plan_version int,
    started_at   timestamptz not null,
    finished_at  timestamptz,
    comment      text,
    layout       jsonb       not null,
    received_at  timestamptz not null,
    rev          bigint      not null,
    op_id        uuid        not null,
    deleted      boolean     not null default false
);
create index workout_user_started on workout (user_id, started_at desc);

create table workout_set (
    id           uuid primary key,
    workout_id   uuid        not null references workout on delete cascade,
    user_id      uuid        not null references app_user,
    block_id     uuid        not null,
    item_id      uuid        not null,
    set_no       int         not null,
    target_id    uuid,
    exercise_id  uuid        not null references exercise,
    record_type  text        not null,
    per_side     boolean     not null,
    weight       double precision,
    reps         int,
    extra_weight double precision,
    seconds      int,
    rating       text,
    note         text,
    done         boolean     not null,
    done_at      timestamptz,
    received_at  timestamptz not null,
    rev          bigint      not null,
    op_id        uuid        not null,
    deleted      boolean     not null default false
);
-- «Прошлый результат» упражнения
create index workout_set_last on workout_set (user_id, exercise_id, done_at desc);
create index workout_set_workout on workout_set (workout_id);

create table measurement (
    id          uuid primary key,
    user_id     uuid             not null references app_user,
    type        text             not null,
    value       double precision not null,
    unit        text             not null,
    measured_at timestamptz      not null,
    workout_id  uuid references workout on delete set null,
    received_at timestamptz      not null,
    rev         bigint           not null,
    op_id       uuid             not null,
    deleted     boolean          not null default false
);
create index measurement_user_type on measurement (user_id, type, measured_at desc);

-- Перезаписанные версии синхронизируемых записей
create table revision_log (
    id          bigserial primary key,
    entity      text        not null,
    entity_id   uuid        not null,
    rev         bigint      not null,
    data        jsonb       not null,
    replaced_at timestamptz not null default now()
);
