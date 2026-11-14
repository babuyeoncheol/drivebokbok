-- 로컬 테스트용: Supabase에 원래 있는 역할만 흉내 낸다(Supabase에서는 실행하지 말 것).
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
grant usage on schema public to anon, authenticated;
-- Supabase는 새 표에 기본 권한을 넓게 준다. 그래도 우리 SQL이 막는지 보려고 똑같이 흉내 낸다.
alter default privileges in schema public grant all on tables to anon, authenticated;
alter default privileges in schema public grant all on functions to anon, authenticated;
