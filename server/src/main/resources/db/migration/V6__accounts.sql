-- H13: administrators and password recovery by e-mail.
alter table users add column role text not null default 'user' check (role in ('user', 'admin'));

-- An existing server's oldest account administers it (RACHA_ADMIN_EMAIL can name another one at start-up).
update users set role = 'admin' where id = (select id from users order by created_at, id limit 1);

-- One row per recovery code sent; only its SHA-256 is kept. A new request voids the previous ones.
create table password_resets (
    id             uuid primary key,
    user_id        uuid        not null references users (id) on delete cascade,
    code_hash      bytea       not null,
    created_at     timestamptz not null,
    expires_at     timestamptz not null,
    used_at        timestamptz,
    requested_from text
);
create index password_resets_user on password_resets (user_id, created_at desc);
