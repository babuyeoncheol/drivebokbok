// 체험 모드: 서버 없이 '아이를 태우고 귀가'하는 한 번의 운행을 약 1분 동안 보여 준다(가상 데이터).
// 진짜 서버(family_sync)와 같은 모양으로 돌려줘서 화면 코드는 하나만 쓴다.
window.AnsimDemo = (function () {
  'use strict';

  var START = [37.4380, 127.1050];
  var HOME = [37.4020, 127.1090];

  function lerp(t) {
    return [START[0] + (HOME[0] - START[0]) * t, START[1] + (HOME[1] - START[1]) * t];
  }

  function script() {
    var s = [];
    function notice(t, kind, level, title, detail, context, where) {
      s.push({ t: t, type: 'notice', kind: kind, level: level, title: title, detail: detail, context: context || [], where: where });
    }
    notice(2, 'DEPARTED', 'INFO', '출발했어요',
      '목적지: 집 · 출발 전 점검: 운전석 벨트 미착용, 워셔액 경고 · 뒷좌석 동승 있음', ['전조등 켬'], 0);
    for (var i = 0; i <= 20; i++) s.push({ t: 2 + i * 2, type: 'location', where: i / 20 });
    notice(14, 'REST_SUGGESTED', 'INFO', '휴식 안내',
      '2시간 5분 동안 쉬지 않고 운전했어요. 차에서 쉬어 가자고 안내했어요.', ['전조등 켬'], 0.6);
    notice(24, 'HARD_BRAKE', 'CAUTION', '급제동이 잦아요',
      '10분 동안 급제동 3회. 차에서 천천히 가자고 안내했어요.', ['전조등 켬', '와이퍼 작동'], 1.1);
    notice(44, 'ARRIVED', 'INFO', '집 도착했어요',
      '운행 2시간 5분 · 172.4 km · 휴식 안내 1회 · 급제동 3회', ['전조등 켬', '와이퍼 작동'], 1);
    notice(45, 'MAINTENANCE_NEEDED', 'INFO', '정비가 필요해요', '운행 중 워셔액 경고가 있었어요.', [], 1);
    notice(54, 'REAR_SEAT_UNCHECKED', 'CAUTION', '뒷좌석 확인이 안 됐어요',
      '차를 세운 뒤 3분 동안 뒷문이 열리지 않았어요. 뒷좌석에 아이가 남아 있지 않은지 확인해 주세요.', [], 1);
    s.sort(function (a, b) { return a.t - b.t; });
    return s;
  }

  function create() {
    var items, start, cursor, seq, happened, pending, arrived;

    function reset() {
      items = script();
      start = Date.now();
      cursor = 0;
      seq = 0;
      happened = [];
      pending = [];
      arrived = false;
    }

    function emit(ev, at) {
      seq += 1;
      ev.id = seq;
      ev.at = at;
      ev.received_at = new Date(at).toISOString();
      happened.push(ev);
      if (ev.kind === 'ARRIVED') arrived = true;
    }

    function point(where) {
      if (where == null) return [null, null];
      return lerp(Math.min(where, 1));
    }

    function lastLocation() {
      for (var i = happened.length - 1; i >= 0; i--) if (happened[i].type === 'location') return happened[i];
      return { lat: START[0], lng: START[1] };
    }

    function advance() {
      var now = Date.now();
      while (cursor < items.length && start + items[cursor].t * 1000 <= now) {
        var it = items[cursor++];
        var at = start + it.t * 1000;
        var p = point(it.where);
        if (it.type === 'location') {
          emit({ type: 'location', lat: p[0], lng: p[1], destination: '집' }, at);
        } else {
          emit({ type: 'notice', kind: it.kind, level: it.level, title: it.title, detail: it.detail,
                 context: it.context, lat: p[0], lng: p[1] }, at);
        }
      }
      pending.sort(function (a, b) { return a.due - b.due; });
      while (pending.length && pending[0].due <= now) {
        var q = pending.shift();
        emit(q.ev, q.due);
      }
    }

    reset();

    return {
      demo: true,
      sync: function (afterId) {
        advance();
        var fresh = happened.filter(function (e) { return e.id > afterId; });
        return Promise.resolve({
          notices: fresh.filter(function (e) { return e.type === 'notice'; }),
          locations: fresh.filter(function (e) { return e.type === 'location'; }),
          last_id: seq,
          known: true,
          server_ms: Date.now()
        });
      },
      sendCheer: function (preset, label) {
        var loc = lastLocation();
        pending.push({
          due: Date.now() + 3000,
          ev: { type: 'notice', kind: 'CHEER_PLAYED', level: 'INFO', title: '응원 메시지를 들려줬어요',
                detail: '‘' + label + '’ · ' + (arrived ? '정차 중' : '주행 중'), context: [], lat: loc.lat, lng: loc.lng }
        });
        return Promise.resolve({ ok: true });
      },
      emergency: function () {
        var loc = lastLocation();
        pending.push({
          due: Date.now(),
          ev: { type: 'notice', kind: 'EMERGENCY_AIRBAG', level: 'EMERGENCY', title: '에어백이 터졌어요',
                detail: '사고가 난 것 같아요. 마지막 위치 ' + loc.lat.toFixed(5) + ', ' + loc.lng.toFixed(5) + '. 바로 연락해 보세요.',
                context: [], lat: loc.lat, lng: loc.lng }
        });
      },
      restart: reset
    };
  }

  return { create: create };
})();
