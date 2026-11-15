-- Telemetry kept as received for the future admin panel (§8).
create table analytics_events (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    device_id   uuid,
    type        text        not null,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    properties  jsonb       not null default '{}'
);
create index analytics_events_user on analytics_events (user_id, occurred_at);
create index analytics_events_type on analytics_events (type, occurred_at);

create table errors (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    device_id   uuid,
    platform    text,
    app_version text,
    message     text        not null,
    stack_trace text,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    context     jsonb       not null default '{}'
);
create index errors_user on errors (user_id, occurred_at);
