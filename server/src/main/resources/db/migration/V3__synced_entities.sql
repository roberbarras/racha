-- Synchronised entities (ADR-0012). Every table: per-user rows keyed by the client id, the client time of the last
-- change (last writer wins), a tombstone flag and the server sequence that orders pulls. Columns used by queries are
-- typed; the rest of the client fields travel in "data".
create sequence sync_seq;

create table categories (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table category_roots (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    category_id text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table library_items (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    category_id text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table sections (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    item_id text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table playable_elements (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    item_id text, duration_sec bigint,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table subtitles (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    element_id text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
-- id = element id. max_position_sec only grows (§6.3: progress is never lost).
create table progress (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    position_sec bigint not null default 0, duration_sec bigint, completed boolean not null default false,
    max_position_sec bigint not null default 0,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table bookmarks (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    element_id text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
-- Append-only: an entry never changes once written.
create table history (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    element_id text, watched_at timestamptz, position_sec bigint,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create table favorites (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
-- id = "user:<key>" for per-user settings or "device:<deviceId>:<key>" for per-device ones (§7.3).
create table app_settings (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    device_id uuid,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);

-- Pull scans one user's rows after a cursor, in sequence order.
create index categories_pull on categories (user_id, seq);
create index category_roots_pull on category_roots (user_id, seq);
create index library_items_pull on library_items (user_id, seq);
create index sections_pull on sections (user_id, seq);
create index playable_elements_pull on playable_elements (user_id, seq);
create index subtitles_pull on subtitles (user_id, seq);
create index progress_pull on progress (user_id, seq);
create index bookmarks_pull on bookmarks (user_id, seq);
create index history_pull on history (user_id, seq);
create index favorites_pull on favorites (user_id, seq);
create index app_settings_pull on app_settings (user_id, seq);

-- Audit of pushes (§6.2 sync_events), also used for "sync failures" stats (§8).
create table sync_events (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    device_id   uuid,
    received_at timestamptz not null,
    applied     int         not null,
    stale       int         not null,
    invalid     int         not null
);
create index sync_events_user on sync_events (user_id, received_at);
