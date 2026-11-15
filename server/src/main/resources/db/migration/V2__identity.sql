-- Identity (ADR-0006): users, their devices, refresh-token sessions and (future) OAuth links.
create table users (
    id            uuid primary key,
    email         text        not null unique,          -- stored lower-case
    password_hash text        not null,
    display_name  text,
    mode          text        not null default 'study' check (mode in ('study', 'leisure')),
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    disabled      boolean     not null default false
);

create table devices (
    id           uuid primary key,
    user_id      uuid        not null references users (id) on delete cascade,
    name         text        not null,
    platform     text        not null check (platform in ('android', 'linux')),
    app_version  text,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null
);
create index devices_user on devices (user_id);

-- One row per refresh token. Rotation marks the old row used; presenting a used token again revokes the family.
create table sessions (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    device_id  uuid references devices (id) on delete cascade,
    family_id  uuid        not null,
    token_hash bytea       not null unique,              -- SHA-256 of the opaque token
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    revoked_at timestamptz
);
create index sessions_family on sessions (family_id);

create table oauth_accounts (
    provider   text        not null,
    subject    text        not null,
    user_id    uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null,
    primary key (provider, subject)
);
