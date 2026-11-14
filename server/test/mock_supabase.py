#!/usr/bin/env python3
"""
로컬 검증용 가짜 Supabase REST(PostgREST 흉내). 실제 Postgres에 schema.sql을 올려 두고,
요청마다 psql로 'anon' 역할에서 실행한다. 그래서 권한·제약·함수는 진짜와 같게 검사된다.

  python3 mock_supabase.py --port 54321 --key sb_publishable_test --psql "psql -h /tmp -p 55432 -U postgres -d ansim"

지원: POST /rest/v1/events (배열 insert), POST /rest/v1/rpc/{family_sync|send_cheer|car_cheers}, GET /rest/v1/
키 규칙도 흉내 낸다: apikey가 없거나 다르면 401, sb_ 키를 Authorization: Bearer에 넣으면 401(JWT가 아니라서).
"""
import argparse
import json
import shlex
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None

EVENT_COLS = "car_code, type, kind, level, title, detail, context, lat, lng, destination, occurred_at_ms"

SQL = {
    "events": f"""
        set role anon;
        insert into public.events ({EVENT_COLS})
        select {EVENT_COLS} from json_populate_recordset(null::public.events, :'body'::json);
        select 'ok';
    """,
    "family_sync": """
        set role anon;
        select public.family_sync(p_code := (:'body'::json->>'p_code'), p_after_id := coalesce((:'body'::json->>'p_after_id')::bigint, 0));
    """,
    "send_cheer": """
        set role anon;
        select public.send_cheer(p_code := (:'body'::json->>'p_code'), p_preset := (:'body'::json->>'p_preset'));
    """,
    "car_cheers": """
        set role anon;
        select coalesce(json_agg(t), '[]'::json) from public.car_cheers(
          p_code := (:'body'::json->>'p_code'), p_after_id := coalesce((:'body'::json->>'p_after_id')::bigint, 0)) t;
    """,
}


def run_sql(sql, body):
    cmd = shlex.split(ARGS.psql) + ["-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-v", f"body={body}", "-f", "-"]
    p = subprocess.run(cmd, input=sql, capture_output=True, text=True, timeout=20)
    if p.returncode != 0:
        return False, p.stderr.strip()
    lines = [l for l in p.stdout.splitlines() if l.strip() and l.strip() != "SET"]
    return True, lines[-1] if lines else ""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        sys.stderr.write("[mock] " + (fmt % a) + "\n")

    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "apikey, authorization, content-type, prefer, x-client-info")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def reply(self, code, text, ctype="application/json"):
        data = text.encode("utf-8")
        self.send_response(code)
        self.cors()
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def auth_ok(self):
        key = self.headers.get("apikey")
        auth = self.headers.get("Authorization")
        if key != ARGS.key:
            self.reply(401, json.dumps({"message": "Invalid API key"}))
            return False
        if auth and ARGS.key.startswith("sb_") and auth.startswith("Bearer sb_"):
            self.reply(401, json.dumps({"message": "Invalid JWT"}))
            return False
        return True

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.end_headers()

    def do_GET(self):
        if not self.path.startswith("/rest/v1"):
            return self.reply(404, "{}")
        if not self.auth_ok():
            return
        self.reply(200, json.dumps({"swagger": "2.0"}))

    def do_POST(self):
        if not self.auth_ok():
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8")
        path = self.path.split("?")[0]
        if path == "/rest/v1/events":
            ok, out = run_sql(SQL["events"], body)
            return self.reply(201, "") if ok else self.reply(400, json.dumps({"message": out[-300:]}))
        if path.startswith("/rest/v1/rpc/"):
            fn = path.rsplit("/", 1)[1]
            if fn not in ("family_sync", "send_cheer", "car_cheers"):
                return self.reply(404, json.dumps({"message": f"function {fn} not found"}))
            ok, out = run_sql(SQL[fn], body)
            return self.reply(200, out or "null") if ok else self.reply(400, json.dumps({"message": out[-300:]}))
        self.reply(404, "{}")


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=54321)
    ap.add_argument("--key", default="sb_publishable_test")
    ap.add_argument("--psql", default="psql -h /tmp -p 55432 -U postgres -d ansim")
    ARGS = ap.parse_args()
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
