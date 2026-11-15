-- Сон, пульс, шаги с браслета (ADR 0015): записи Health Connect как есть, вся запись — в data.
-- id — ID записи в источнике: повторная отправка заменяет запись. Ночи и дни считает core
create table health_record (
    user_id     uuid        not null references app_user,
    id          text        not null,
    type        text        not null,
    source      text        not null,
    start_at    timestamptz not null,
    end_at      timestamptz,
    data        jsonb       not null,
    received_at timestamptz not null default now(),
    primary key (user_id, id)
);
create index health_user_type_start on health_record (user_id, type, start_at);
create index health_user_start on health_record (user_id, start_at);

-- Дни с чужими данными (браслет носил другой человек): записи удаляются и больше не принимаются
create table health_exclusion (
    id         uuid primary key,
    user_id    uuid        not null references app_user,
    from_date  date        not null,
    to_date    date        not null,
    source     text,
    note       text,
    created_at timestamptz not null default now()
);
create index health_exclusion_user on health_exclusion (user_id);
