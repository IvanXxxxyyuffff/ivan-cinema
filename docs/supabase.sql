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
