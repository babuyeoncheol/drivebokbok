-- 로컬 검증: 공개 키(anon)로 할 수 있는 것과 없는 것
\set ON_ERROR_STOP off
set role anon;

-- 1. 차가 알림·위치를 올린다(PostgREST가 만드는 것과 같은 모양의 다건 insert)
insert into public.events (car_code, type, kind, level, title, detail, context, lat, lng, destination, occurred_at_ms)
select car_code, type, kind, level, title, detail, context, lat, lng, destination, occurred_at_ms
from json_populate_recordset(null::public.events, '[
 {"car_code":"K7M2QX","type":"notice","kind":"DEPARTED","level":"INFO","title":"출발했어요","detail":"목적지: 집","context":["전조등 켬"],"lat":37.43,"lng":127.10,"destination":null,"occurred_at_ms":1730000000000},
 {"car_code":"K7M2QX","type":"location","kind":null,"level":null,"title":null,"detail":null,"context":[],"lat":37.42,"lng":127.105,"destination":"집","occurred_at_ms":1730000005000},
 {"car_code":"K7M2QX","type":"notice","kind":"REAR_SEAT_UNCHECKED","level":"CAUTION","title":"뒷좌석 확인이 안 됐어요","detail":"…","context":[],"lat":37.40,"lng":127.109,"destination":null,"occurred_at_ms":1730000600000}
]');
\echo '== 1 insert ok? (위에 INSERT 0 3 이면 성공)'

-- 2. 직접 읽기는 막혀야 한다
\echo '== 2 select (permission denied 여야 함)'
select count(*) from public.events;

-- 3. 잘못된 모양은 거절
\echo '== 3 bad rows (모두 거절)'
insert into public.events (car_code, type, occurred_at_ms, lat, lng) values ('k7m2qx', 'location', 1, 1, 1);
insert into public.events (car_code, type, occurred_at_ms) values ('K7M2QX', 'notice', 1);
insert into public.events (car_code, type, occurred_at_ms, lat, lng) values ('K7M2QX', 'location', 1, 91, 1);
insert into public.events (car_code, type, occurred_at_ms, lat, lng, received_at) values ('K7M2QX', 'location', 1, 1, 1, now());

-- 4. 고치기·지우기 막힘
\echo '== 4 update/delete (permission denied)'
update public.events set title = 'x';
delete from public.events;

-- 5. 가족 동기화
\echo '== 5 family_sync'
select jsonb_array_length(r->'notices') as notices, jsonb_array_length(r->'locations') as locations, r->'known' as known,
       (r->'last_id')::bigint > 0 as has_last_id
from (select public.family_sync('k7m2qx', 0) as r) s;
select jsonb_array_length(r->'notices') as after_last, r->'known' as known
from (select public.family_sync('K7M2QX', (public.family_sync('K7M2QX', 0)->>'last_id')::bigint) as r) s;
select r->'known' as other_code_known, jsonb_array_length(r->'notices') as n from (select public.family_sync('AAAAAA', 0) as r) s;
select r->'known' as bad_code from (select public.family_sync('''; drop table events; --', 0) as r) s;
select (public.family_sync('K7M2QX', 0)->'notices'->0->>'title') as first_title,
       (public.family_sync('K7M2QX', 0)->'locations'->0->>'destination') as dest;

-- 6. 응원 메시지
\echo '== 6 cheers'
select public.send_cheer('K7M2QX', 'SAFE') ->> 'ok' as first_ok;
select public.send_cheer('K7M2QX', 'LOVE') ->> 'reason' as second_reason;
select public.send_cheer('K7M2QX', 'HACK') ->> 'reason' as bad_preset;
select public.send_cheer('nope', 'SAFE') ->> 'reason' as bad_code;
select count(*) as car_gets, min(preset) as preset from public.car_cheers('K7M2QX', 0);
select count(*) as after_seen from public.car_cheers('K7M2QX', (select max(id) from public.car_cheers('K7M2QX', 0)));
\echo '== 6b cheers 직접 읽기 (permission denied)'
select * from public.cheers;
reset role;
