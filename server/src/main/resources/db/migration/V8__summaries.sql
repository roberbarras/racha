-- H17: AI summaries of lessons and sections, synced like the rest of the user's data (id = the lesson's or section's id).
create table summaries (
    user_id uuid not null references users (id) on delete cascade, id text not null,
    target text,
    updated_at timestamptz not null, deleted boolean not null default false, seq bigint not null,
    data jsonb not null default '{}', primary key (user_id, id)
);
create index summaries_pull on summaries (user_id, seq);
