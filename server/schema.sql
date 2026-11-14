-- =====================================================================
-- 운전복복 서버 (Supabase)
-- Supabase 대시보드 → SQL Editor → 이 파일 전체를 붙여 넣고 Run.
-- 여러 번 실행해도 된다(있는 건 건너뛰거나 바꿔 쓴다).
--
-- 흐름
--   차량 앱  ──insert──▶ events   (출발·위치·도착·휴식·긴급·뒷좌석 …)
--   가족 앱  ──rpc────▶ family_sync(코드)  : 그 차의 새 알림·위치만 돌려준다
--   가족 앱  ──rpc────▶ send_cheer(코드, 프리셋) ─▶ cheers
--   차량 앱  ──rpc────▶ car_cheers(코드)   : 최근 2분 안의 응원 메시지
--
-- 개인정보: 실명·전화번호·계정을 저장하지 않는다. 차와 가족은 무작위 6자리 '차 코드'로만 이어진다.
-- 공개 키(publishable / anon)로는 events·cheers 표를 직접 읽을 수 없고, 코드를 아는 함수 호출로만 읽는다.
-- =====================================================================

-- 차 코드: 차량 앱이 만드는 6자리(헷갈리는 I, O, 0, 1 제외)
create or replace function public.ansim_valid_code(p text)
returns boolean
language sql immutable
as $$ select p ~ '^[A-HJ-NP-Z2-9]{6}$' $$;

-- ---------------------------------------------------------------------
-- 1) 차가 올리는 이벤트
-- ---------------------------------------------------------------------
create table if not exists public.events (
  id             bigint generated always as identity primary key,
  car_code       text not null check (public.ansim_valid_code(car_code)),
  type           text not null check (type in ('notice', 'location')),
  kind           text check (kind is null or kind ~ '^[A-Z_]{2,40}$'),
  level          text check (level is null or level in ('INFO', 'CAUTION', 'EMERGENCY')),
  title          text check (title is null or char_length(title) <= 200),
  detail         text check (detail is null or char_length(detail) <= 1000),
  context        jsonb not null default '[]'::jsonb check (jsonb_typeof(context) = 'array'),
  lat            double precision check (lat is null or lat between -90 and 90),
  lng            double precision check (lng is null or lng between -180 and 180),
  destination    text check (destination is null or char_length(destination) <= 200),
  occurred_at_ms bigint not null check (occurred_at_ms > 0),
  received_at    timestamptz not null default now(),
  -- 알림이면 종류·단계·제목이 있어야 하고, 위치면 좌표가 있어야 한다.
  constraint events_shape check (
    (type = 'notice' and kind is not null and level is not null and title is not null)
    or (type = 'location' and lat is not null and lng is not null)
  )
);

create index if not exists events_code_id on public.events (car_code, id desc);
create index if not exists events_code_type_id on public.events (car_code, type, id desc);

alter table public.events enable row level security;

-- 공개 키로는 '추가'만. 읽기는 아래 함수로만.
revoke all on table public.events from anon, authenticated;
grant insert (car_code, type, kind, level, title, detail, context, lat, lng, destination, occurred_at_ms)
  on table public.events to anon, authenticated;

drop policy if exists "car inserts events" on public.events;
create policy "car inserts events" on public.events
  for insert to anon, authenticated
  with check (true); -- 모양 검사는 위 check 제약이 한다

-- ---------------------------------------------------------------------
-- 2) 가족이 보내는 응원 메시지 (프리셋만. 차에서는 녹음해 둔 안내음으로 재생)
-- ---------------------------------------------------------------------
create table if not exists public.cheers (
  id         bigint generated always as identity primary key,
  car_code   text not null check (public.ansim_valid_code(car_code)),
  preset     text not null check (preset in ('SAFE', 'SLOW', 'CALL', 'LOVE')),
  created_at timestamptz not null default now()
);

create index if not exists cheers_code_id on public.cheers (car_code, id desc);

alter table public.cheers enable row level security;
revoke all on table public.cheers from anon, authenticated;

-- ---------------------------------------------------------------------
-- 3) 가족 앱: 새 알림·위치 받아 오기
--    p_after_id = 마지막으로 받은 id (처음엔 0 → 최근 알림 50개, 최근 위치 300개)
-- ---------------------------------------------------------------------
create or replace function public.family_sync(p_code text, p_after_id bigint default 0)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  with c as (
    select upper(trim(p_code)) as code
  ),
  n as (
    select e.id, e.kind, e.level, e.title, e.detail, e.context, e.lat, e.lng, e.occurred_at_ms, e.received_at
    from public.events e, c
    where public.ansim_valid_code(c.code)
      and e.car_code = c.code and e.type = 'notice' and e.id > coalesce(p_after_id, 0)
    order by e.id desc
    limit 50
  ),
  l as (
    select e.id, e.lat, e.lng, e.destination, e.occurred_at_ms, e.received_at
    from public.events e, c
    where public.ansim_valid_code(c.code)
      and e.car_code = c.code and e.type = 'location' and e.id > coalesce(p_after_id, 0)
    order by e.id desc
    limit 300
  )
  select jsonb_build_object(
    'notices', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', n.id, 'kind', n.kind, 'level', n.level, 'title', n.title, 'detail', n.detail,
        'context', n.context, 'lat', n.lat, 'lng', n.lng, 'at', n.occurred_at_ms,
        'received_at', n.received_at
      ) order by n.id) from n), '[]'::jsonb),
    'locations', coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', l.id, 'lat', l.lat, 'lng', l.lng, 'destination', l.destination, 'at', l.occurred_at_ms,
        'received_at', l.received_at
      ) order by l.id) from l), '[]'::jsonb),
    'last_id', coalesce((
      select max(e.id) from public.events e, c where e.car_code = c.code
    ), coalesce(p_after_id, 0)),
    'known', exists (select 1 from public.events e, c where e.car_code = c.code),
    'server_ms', (extract(epoch from now()) * 1000)::bigint
  )
$$;

-- ---------------------------------------------------------------------
-- 4) 가족 앱: 응원 메시지 보내기 (같은 차에 10초에 한 번까지)
-- ---------------------------------------------------------------------
create or replace function public.send_cheer(p_code text, p_preset text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
  v_code text := upper(trim(p_code));
  v_last timestamptz;
  v_id   bigint;
begin
  if not public.ansim_valid_code(v_code) then
    return jsonb_build_object('ok', false, 'reason', 'invalid_code');
  end if;
  if p_preset is null or p_preset not in ('SAFE', 'SLOW', 'CALL', 'LOVE') then
    return jsonb_build_object('ok', false, 'reason', 'invalid_preset');
  end if;
  select max(created_at) into v_last from public.cheers where car_code = v_code;
  if v_last is not null and v_last > now() - interval '10 seconds' then
    return jsonb_build_object('ok', false, 'reason', 'too_soon');
  end if;
  insert into public.cheers (car_code, preset) values (v_code, p_preset) returning id into v_id;
  return jsonb_build_object('ok', true, 'id', v_id);
end
$$;

-- ---------------------------------------------------------------------
-- 5) 차량 앱: 새 응원 메시지 받기 (2분 넘은 건 버린다. 늦게 들으면 의미가 없어서)
-- ---------------------------------------------------------------------
create or replace function public.car_cheers(p_code text, p_after_id bigint default 0)
returns table (id bigint, preset text, created_at timestamptz)
language sql
stable
security definer
set search_path = public
as $$
  select ch.id, ch.preset, ch.created_at
  from public.cheers ch
  where ch.car_code = upper(trim(p_code))
    and ch.id > coalesce(p_after_id, 0)
    and ch.created_at > now() - interval '2 minutes'
  order by ch.id
  limit 20
$$;

-- 함수는 공개 키로 부를 수 있게(기본 PUBLIC 권한은 걷어 낸다)
revoke execute on function public.family_sync(text, bigint) from public;
revoke execute on function public.send_cheer(text, text) from public;
revoke execute on function public.car_cheers(text, bigint) from public;
grant execute on function public.family_sync(text, bigint) to anon, authenticated;
grant execute on function public.send_cheer(text, text) to anon, authenticated;
grant execute on function public.car_cheers(text, bigint) to anon, authenticated;
grant execute on function public.ansim_valid_code(text) to anon, authenticated;

-- ---------------------------------------------------------------------
-- 6) (선택) 30일 지난 기록 지우기. Database → Extensions에서 pg_cron을 켠 뒤 아래 두 줄의 주석을 풀고 실행.
-- ---------------------------------------------------------------------
-- select cron.schedule('ansim-purge-events', '0 4 * * *', $$delete from public.events where received_at < now() - interval '30 days'$$);
-- select cron.schedule('ansim-purge-cheers', '10 4 * * *', $$delete from public.cheers where created_at < now() - interval '1 day'$$);
