-- ============================================================
-- IVAN CINEMA · Supabase 观看记录表
-- 用法：Supabase 控制台 → SQL Editor → New query → 全选粘贴 → Run
-- 对应 APP 端 db/AppDb.kt 的 WatchEntry；主键 (user_id, vod_key)
-- ============================================================

create table if not exists public.watch_history (
    user_id       uuid    not null references auth.users (id) on delete cascade,
    vod_key       text    not null,
    name          text    not null default '',
    year          text    not null default '',
    pic           text    not null default '',
    source_api    text    not null default '',
    source_name   text    not null default '',
    line_index    integer not null default 0,
    episode_index integer not null default 0,
    episode_name  text    not null default '',
    position_ms   bigint  not null default 0,
    duration_ms   bigint  not null default 0,
    updated_at    bigint  not null default 0,
    primary key (user_id, vod_key)
);

-- 行级安全：必须开启，否则 anon key 能读到所有人的记录
alter table public.watch_history enable row level security;

-- 用户只能读写自己的行（auth.uid() = user_id）
drop policy if exists "watch_history_select_own" on public.watch_history;
create policy "watch_history_select_own" on public.watch_history
    for select using (auth.uid() = user_id);

drop policy if exists "watch_history_insert_own" on public.watch_history;
create policy "watch_history_insert_own" on public.watch_history
    for insert with check (auth.uid() = user_id);

drop policy if exists "watch_history_update_own" on public.watch_history;
create policy "watch_history_update_own" on public.watch_history
    for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- 说明：APP 端 upsert 用 Prefer: resolution=merge-duplicates，
-- 命中主键 (user_id, vod_key) 时走 update，因此上面 insert + update 两条策略都要有。


-- ============================================================
-- IVAN CINEMA · 共享源健康（多用户共同贡献 / 共同读取）
-- 对应 APP 端 data/SharedHealth.kt；与上面的观看记录表相互独立。
-- 这两张表刻意做成「共享写」：任何登录用户都能读写聚合值，
-- 写入只走下面的 RPC 自增，避免多端并发互相覆盖。
-- ============================================================

create table if not exists public.source_health (
    source_api text primary key,
    ok         integer not null default 0,
    fail       integer not null default 0,
    last_ok    bigint,
    last_fail  bigint
);

create table if not exists public.play_failure (
    source_api  text not null,
    vod_key     text not null,
    count       integer not null default 0,
    updated_at  bigint  not null default 0,
    primary key (source_api, vod_key)
);

-- 行级安全：两张表都是共享表，登录用户可读写，未登录一律拒绝
alter table public.source_health enable row level security;
alter table public.play_failure enable row level security;

drop policy if exists "source_health_select_shared" on public.source_health;
create policy "source_health_select_shared" on public.source_health
    for select using (auth.uid() is not null);

drop policy if exists "source_health_insert_shared" on public.source_health;
create policy "source_health_insert_shared" on public.source_health
    for insert with check (auth.uid() is not null);

drop policy if exists "source_health_update_shared" on public.source_health;
create policy "source_health_update_shared" on public.source_health
    for update using (auth.uid() is not null) with check (auth.uid() is not null);

drop policy if exists "play_failure_select_shared" on public.play_failure;
create policy "play_failure_select_shared" on public.play_failure
    for select using (auth.uid() is not null);

drop policy if exists "play_failure_insert_shared" on public.play_failure;
create policy "play_failure_insert_shared" on public.play_failure
    for insert with check (auth.uid() is not null);

drop policy if exists "play_failure_update_shared" on public.play_failure;
create policy "play_failure_update_shared" on public.play_failure
    for update using (auth.uid() is not null) with check (auth.uid() is not null);

-- 原子自增：PostgREST 的 upsert 只能整行替换，做不了 ok = ok + 1，
-- 所以把自增放在数据库侧（security invoker，仍受上面的 RLS 约束），
-- 多端并发也不会丢计数。对应 APP 端 SupabaseClient.upsertSourceHealth / bumpPlayFailure。
create or replace function public.report_source_health(ok_apis text[], fail_apis text[])
returns void
language sql
as $$
    insert into public.source_health (source_api, ok, fail, last_ok, last_fail)
    select a, 1, 0, (extract(epoch from clock_timestamp()) * 1000)::bigint, null
    from unnest(coalesce(ok_apis, '{}'::text[])) as a
    on conflict (source_api) do update
        set ok = public.source_health.ok + 1,
            last_ok = excluded.last_ok;

    insert into public.source_health (source_api, ok, fail, last_ok, last_fail)
    select a, 0, 1, null, (extract(epoch from clock_timestamp()) * 1000)::bigint
    from unnest(coalesce(fail_apis, '{}'::text[])) as a
    on conflict (source_api) do update
        set fail = public.source_health.fail + 1,
            last_fail = excluded.last_fail;
$$;

create or replace function public.bump_play_failure(p_source_api text, p_vod_key text)
returns void
language sql
as $$
    insert into public.play_failure (source_api, vod_key, count, updated_at)
    values (p_source_api, p_vod_key, 1, (extract(epoch from clock_timestamp()) * 1000)::bigint)
    on conflict (source_api, vod_key) do update
        set count = public.play_failure."count" + 1,
            updated_at = excluded.updated_at;
$$;

-- 只允许登录用户调用这两个写函数（表本身也已被 RLS 挡住匿名访问）
revoke execute on function public.report_source_health(text[], text[]) from public, anon;
revoke execute on function public.bump_play_failure(text, text) from public, anon;
grant execute on function public.report_source_health(text[], text[]) to authenticated;
grant execute on function public.bump_play_failure(text, text) to authenticated;
