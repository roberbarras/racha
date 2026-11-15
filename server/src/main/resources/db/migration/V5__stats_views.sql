-- §8: what a future admin panel reads, as plain views over the stored data (no extra writes, always current).
-- Client events (analytics_events.type): 'app_open', 'watch' (one viewing session), 'resource_view',
-- 'feature' (properties.name: pip, ab_loop, bookmark, screenshot…), 'sync_failed' (properties.code).
-- The server validates the shape of 'watch' events, so the casts below are safe.

-- Users with their current mode (the synced setting wins over the column) and platforms.
create view stats_users as
select u.id as user_id,
       u.email,
       u.created_at,
       coalesce((select s.data ->> 'value' from app_settings s
                 where s.user_id = u.id and s.id = 'user:mode.current' and not s.deleted), u.mode) as mode,
       (select string_agg(distinct d.platform, ',' order by d.platform) from devices d where d.user_id = u.id) as platforms,
       (select max(d.last_seen_at) from devices d where d.user_id = u.id) as last_seen_at
from users u;

create view stats_users_by_platform as
select platform, count(distinct user_id) as users, count(*) as devices
from devices
group by platform;

create view stats_users_by_mode as
select mode, count(*) as users
from stats_users
group by mode;

-- Active = opened the app or watched something that day (UTC).
create view stats_active_users_daily as
select day, count(distinct user_id) as users
from (select user_id, (occurred_at at time zone 'UTC')::date as day from analytics_events
      union all
      select user_id, (watched_at at time zone 'UTC')::date from history where not deleted and watched_at is not null) a
group by day;

-- One row per viewing session (§8 "eventos de reproducción").
create view stats_watch as
select e.id,
       e.user_id,
       e.device_id,
       d.platform,
       e.occurred_at,
       e.properties ->> 'elementId'                    as element_id,
       e.properties ->> 'itemId'                       as item_id,
       e.properties ->> 'categoryId'                   as category_id,
       (e.properties ->> 'startedAt')::timestamptz     as started_at,
       (e.properties ->> 'endedAt')::timestamptz       as ended_at,
       (e.properties ->> 'positionSec')::bigint        as position_sec,
       (e.properties ->> 'completed')::boolean         as completed,
       (e.properties ->> 'playbackSpeed')::numeric     as playback_speed,
       e.properties ->> 'subtitlesLang'                as subtitles_lang,
       (e.properties ->> 'seconds')::bigint            as seconds
from analytics_events e
left join devices d on d.id = e.device_id
where e.type = 'watch' and jsonb_typeof(e.properties -> 'seconds') = 'number';

create view stats_watch_hours_by_user as
select user_id, round(sum(seconds) / 3600.0, 2) as hours, count(*) as sessions
from stats_watch
group by user_id;

create view stats_watch_hours_by_category as
select w.user_id, w.category_id, c.data ->> 'name' as category_name, round(sum(w.seconds) / 3600.0, 2) as hours
from stats_watch w
left join categories c on c.user_id = w.user_id and c.id = w.category_id
group by w.user_id, w.category_id, c.data ->> 'name';

create view stats_watch_hours_by_item as
select w.user_id, w.item_id, i.data ->> 'title' as title, i.category_id, round(sum(w.seconds) / 3600.0, 2) as hours,
       count(distinct w.element_id) as elements_watched
from stats_watch w
left join library_items i on i.user_id = w.user_id and i.id = w.item_id
group by w.user_id, w.item_id, i.data ->> 'title', i.category_id;

-- Titles (courses/series) with something watched, and how far each one is.
create view stats_titles as
select e.user_id, e.item_id, i.data ->> 'title' as title,
       count(*) filter (where p.completed) as completed_elements,
       count(*) as elements,
       max(h.last_watched_at) as last_watched_at
from playable_elements e
join library_items i on i.user_id = e.user_id and i.id = e.item_id
left join progress p on p.user_id = e.user_id and p.id = e.id and not p.deleted
left join (select user_id, element_id, max(watched_at) as last_watched_at from history where not deleted group by user_id, element_id) h
       on h.user_id = e.user_id and h.element_id = e.id
where not e.deleted and not i.deleted
group by e.user_id, e.item_id, i.data ->> 'title'
having max(h.last_watched_at) is not null or count(*) filter (where p.completed) > 0;

create view stats_completed_elements as
select user_id, count(*) as completed
from progress
where completed and not deleted
group by user_id;

-- Every streak (consecutive days with something watched, UTC), not only the current one.
create view stats_streaks as
with days as (
    select distinct user_id, (watched_at at time zone 'UTC')::date as day
    from history where not deleted and watched_at is not null
), runs as (
    select user_id, day, day - (row_number() over (partition by user_id order by day))::int as run
    from days
)
select user_id, min(day) as started, max(day) as ended, count(*) as days
from runs
group by user_id, run;

-- Sync failures: changes the server refused, and failed rounds the clients reported.
create view stats_sync_failures as
select user_id, device_id, received_at as occurred_at, 'invalid_changes' as kind, invalid::text as detail
from sync_events where invalid > 0
union all
select user_id, device_id, occurred_at, 'client_round', properties ->> 'code'
from analytics_events where type = 'sync_failed';

create view stats_feature_usage as
select properties ->> 'name' as feature, count(*) as uses, count(distinct user_id) as users, max(occurred_at) as last_used_at
from analytics_events
where type = 'feature'
group by properties ->> 'name';

create view stats_errors_by_version as
select platform, app_version, count(*) as errors, count(distinct user_id) as users, max(occurred_at) as last_at
from errors
group by platform, app_version;
