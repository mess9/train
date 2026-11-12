-- Журнал тренера (ADR 0014): решение агента, обоснование, условие пересмотра; ссылки на
-- тренировку, план, упражнения. Решение не переписывают: пересмотр — новая запись с revises.
-- Телефон журнал не синхронизирует. deleted_at не null — в корзине
create table journal_entry (
    id           uuid primary key,
    user_id      uuid        not null references app_user,
    date         date        not null,
    decision     text        not null,
    reason       text        not null,
    review       text        not null,
    workout_id   uuid references workout on delete set null,
    plan_id      uuid references plan on delete set null,
    exercise_ids uuid[]      not null default '{}',
    revises      uuid references journal_entry on delete set null,
    created_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create index journal_user_date on journal_entry (user_id, date desc);
create index journal_exercises on journal_entry using gin (exercise_ids);
create index journal_revises on journal_entry (revises) where revises is not null;
