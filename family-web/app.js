// 운전복복 · 가족 앱
// 서버 함수 family_sync를 5초마다 불러 새 알림·위치를 받고, send_cheer로 응원 메시지를 보낸다.
(function () {
  'use strict';

  // ---------------------------------------------------------------- 설정 · 저장
  var CFG = Object.assign({ supabaseUrl: '', supabaseKey: '' }, window.ANSIM_CONFIG || {});
  var KEY = { code: 'ansim.code', url: 'ansim.server', key: 'ansim.key' };
  function cacheKey(code) { return 'ansim.cache.' + code; }

  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) { /* 개인 브라우징 등 */ } },
    del: function (k) { try { localStorage.removeItem(k); } catch (e) { /* */ } }
  };

  var CODE_RE = /^[A-HJ-NP-Z2-9]{6}$/;
  var PRESETS = [
    { id: 'SAFE', label: '조심히 와요' },
    { id: 'SLOW', label: '천천히 와도 괜찮아요' },
    { id: 'CALL', label: '도착하면 연락 줘요' },
    { id: 'LOVE', label: '사랑해요, 기다리고 있어요' }
  ];
  var KIND_TAG = {
    DEPARTED: '출발', ARRIVED: '도착', REST_SUGGESTED: '휴식', MAINTENANCE_NEEDED: '정비',
    BELT_UNBUCKLED_DRIVING: '벨트', HARD_BRAKE: '급제동', REAR_SEAT_UNCHECKED: '뒷좌석',
    EMERGENCY_AIRBAG: '긴급', CHEER_PLAYED: '응원'
  };
  var POLL_MS = 5000, POLL_HIDDEN_MS = 20000, STALE_MS = 3 * 60 * 1000, ALERT_WINDOW_MS = 3 * 60 * 1000;
  var CHEER_COOLDOWN_MS = 10000, CHEER_GIVEUP_MS = 2 * 60 * 1000;

  function $(id) { return document.getElementById(id); }

  // ---------------------------------------------------------------- 서버
  function normalizeBase(u) {
    var s = String(u || '').trim();
    if (!s) return '';
    if (!/^https?:\/\//.test(s)) s = 'https://' + s;
    var i = s.indexOf('/rest/v1');
    if (i >= 0) s = s.slice(0, i);
    return s.replace(/\/+$/, '');
  }
  function serverConf() {
    return {
      url: normalizeBase(store.get(KEY.url) || CFG.supabaseUrl),
      key: String(store.get(KEY.key) || CFG.supabaseKey || '').trim()
    };
  }
  // 새 키(sb_publishable_…)는 apikey 헤더만, 예전 anon 키(JWT)는 Authorization도.
  function authHeaders(key) {
    if (!key) return {};
    return key.indexOf('sb_') === 0 ? { apikey: key } : { apikey: key, Authorization: 'Bearer ' + key };
  }
  function liveBackend(code) {
    var conf = serverConf();
    function rpc(fn, args) {
      var headers = Object.assign({ 'Content-Type': 'application/json' }, authHeaders(conf.key));
      return fetch(conf.url + '/rest/v1/rpc/' + fn, {
        method: 'POST', headers: headers, body: JSON.stringify(args), cache: 'no-store'
      }).then(function (res) {
        if (!res.ok) {
          return res.text().then(function (t) {
            var err = new Error('HTTP ' + res.status);
            err.status = res.status;
            err.body = t.slice(0, 160);
            throw err;
          });
        }
        return res.json();
      });
    }
    return {
      demo: false,
      sync: function (afterId) { return rpc('family_sync', { p_code: code, p_after_id: afterId }); },
      sendCheer: function (preset) { return rpc('send_cheer', { p_code: code, p_preset: preset }); }
    };
  }

  // ---------------------------------------------------------------- 상태
  var state = null;
  var backend = null;
  var timer = null;
  var map = null, trail = null, marker = null, userMovedMap = false;
  var cheer = null; // { id, label, sentAt, status: 'sending'|'sent'|'played'|'failed', note }
  var cheerCooldownUntil = 0;
  var audioCtx = null;

  function emptyState(code, demo) {
    return {
      code: code, demo: demo, lastId: 0, notices: [], locations: [], known: null,
      alertedUpTo: 0, dismissedEmergency: 0, lastOkAt: 0, failures: 0, error: null, primed: false
    };
  }
  function loadState(code) {
    var s = emptyState(code, false);
    try {
      var raw = store.get(cacheKey(code));
      if (raw) Object.assign(s, JSON.parse(raw), { code: code, demo: false, error: null, failures: 0, primed: false });
    } catch (e) { /* 망가진 캐시는 버린다 */ }
    return s;
  }
  function saveState() {
    if (!state || state.demo) return;
    store.set(cacheKey(state.code), JSON.stringify({
      lastId: state.lastId, notices: state.notices.slice(-100), locations: state.locations.slice(-400),
      known: state.known, alertedUpTo: state.alertedUpTo, dismissedEmergency: state.dismissedEmergency
    }));
  }

  // ---------------------------------------------------------------- 시간 · 글자
  function receivedMs(n) {
    if (n && n.received_at) {
      var t = Date.parse(String(n.received_at).replace(/(\.\d{3})\d+/, '$1'));
      if (isFinite(t)) return t;
    }
    return n ? n.at : 0;
  }
  var hm = new Intl.DateTimeFormat('ko-KR', { hour: 'numeric', minute: '2-digit' });
  var md = new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' });
  function clock(t) {
    var d = new Date(t), now = new Date();
    var sameDay = d.toDateString() === now.toDateString();
    return (sameDay ? '' : md.format(d) + ' ') + hm.format(d);
  }
  function ago(t) {
    var s = Math.max(0, Math.round((Date.now() - t) / 1000));
    if (s < 45) return '방금';
    if (s < 3600) return Math.max(1, Math.round(s / 60)) + '분 전';
    if (s < 86400) return Math.round(s / 3600) + '시간 전';
    return Math.round(s / 86400) + '일 전';
  }
  function duration(ms) {
    var m = Math.floor(ms / 60000);
    if (m < 1) return '1분 미만';
    var h = Math.floor(m / 60);
    m = m % 60;
    if (!h) return m + '분';
    return m ? h + '시간 ' + m + '분' : h + '시간';
  }
  // '집으로', '할머니 댁으로', '학교로'
  function toward(word) {
    var c = word.charCodeAt(word.length - 1);
    if (c >= 0xac00 && c <= 0xd7a3) {
      var jong = (c - 0xac00) % 28;
      return word + (jong === 0 || jong === 8 ? '로' : '으로');
    }
    return word + '(으)로';
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  // ---------------------------------------------------------------- 운행 상태 읽기
  function lastNotice(kind) {
    for (var i = state.notices.length - 1; i >= 0; i--) if (state.notices[i].kind === kind) return state.notices[i];
    return null;
  }
  function lastLocation() { return state.locations.length ? state.locations[state.locations.length - 1] : null; }
  function trip() {
    var dep = lastNotice('DEPARTED'), arr = lastNotice('ARRIVED');
    if (dep && (!arr || arr.id < dep.id)) return { mode: 'driving', dep: dep };
    if (arr) return { mode: 'arrived', arr: arr, dep: dep && dep.id < arr.id ? dep : null };
    return { mode: 'idle' };
  }
  function destination(t) {
    var loc = lastLocation();
    if (loc && loc.destination && (!t.dep || loc.id > t.dep.id)) return loc.destination;
    var src = t.dep && t.dep.detail;
    var m = src && /목적지: ([^·]+)/.exec(src);
    return m ? m[1].trim() : null;
  }

  // ---------------------------------------------------------------- 받아 오기
  function schedule(ms) {
    clearTimeout(timer);
    var base = document.hidden ? POLL_HIDDEN_MS : POLL_MS;
    var wait = ms != null ? ms : Math.min(60000, base * Math.pow(2, Math.min(state.failures, 4)));
    timer = setTimeout(tick, wait);
  }

  function tick() {
    if (!state || !backend) return;
    var mine = backend;
    mine.sync(state.lastId).then(function (r) {
      if (mine !== backend) return;
      apply(r);
      state.failures = 0;
      state.error = null;
      state.lastOkAt = Date.now();
    }).catch(function (e) {
      if (mine !== backend) return;
      state.failures += 1;
      state.error = e && e.status === 401 ? '서버 키가 맞지 않아요(설정 확인)'
        : e && e.status === 404 ? '서버에 family_sync 함수가 없어요(schema.sql 실행 확인)'
        : '연결이 불안정해요. 다시 시도하는 중';
    }).then(function () {
      if (mine !== backend) return;
      render();
      schedule();
    });
  }

  function apply(r) {
    var byId = {};
    state.notices.forEach(function (n) { byId[n.id] = true; });
    var fresh = (r.notices || []).filter(function (n) { return !byId[n.id]; });
    state.notices = state.notices.concat(fresh).sort(function (a, b) { return a.id - b.id; }).slice(-100);

    var locIds = {};
    state.locations.forEach(function (l) { locIds[l.id] = true; });
    var freshLoc = (r.locations || []).filter(function (l) { return !locIds[l.id]; });
    state.locations = state.locations.concat(freshLoc).sort(function (a, b) { return a.id - b.id; }).slice(-400);

    state.lastId = Math.max(state.lastId, Number(r.last_id) || 0);
    if (typeof r.known === 'boolean') state.known = r.known;

    fresh.forEach(function (n) {
      if (n.kind === 'CHEER_PLAYED' && cheer && receivedMs(n) >= cheer.sentAt - 5000) {
        cheer.status = 'played';
        cheer.note = n.detail;
      }
      var recent = Date.now() - receivedMs(n) < ALERT_WINDOW_MS;
      if (n.id > state.alertedUpTo && recent) alertFor(n);
      state.alertedUpTo = Math.max(state.alertedUpTo, n.id);
    });
    state.primed = true;
    saveState();
  }

  // ---------------------------------------------------------------- 알림
  function alertFor(n) {
    if (document.hidden) {
      if (n.kind !== 'CHEER_PLAYED') systemNotify(n);
    } else {
      toast(n.title + (n.level === 'INFO' ? '' : ' · ' + (n.detail || '')), n.level);
    }
    if (n.level !== 'INFO') {
      beep(n.level === 'EMERGENCY');
      if (navigator.vibrate) navigator.vibrate(n.level === 'EMERGENCY' ? [400, 150, 400, 150, 400] : [250, 120, 250]);
    }
  }

  function systemNotify(n) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    var opts = {
      body: n.detail || '', tag: 'ansim-' + n.id, icon: 'icons/icon-192.png', badge: 'icons/icon-192.png',
      requireInteraction: n.level === 'EMERGENCY', data: { url: location.href }
    };
    var show = function (reg) {
      if (reg && reg.showNotification) reg.showNotification(n.title, opts);
      else { try { new Notification(n.title, opts); } catch (e) { /* 모바일 일부는 SW로만 가능 */ } }
    };
    if (navigator.serviceWorker && navigator.serviceWorker.getRegistration) {
      navigator.serviceWorker.getRegistration().then(show, function () { show(null); });
    } else {
      show(null);
    }
  }

  var toastTimer = null;
  function toast(text, level) {
    var t = $('toast');
    t.textContent = text;
    t.dataset.level = level || 'INFO';
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, level === 'EMERGENCY' ? 10000 : 5000);
  }

  function beep(urgent) {
    try {
      var Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      audioCtx = audioCtx || new Ctx();
      var times = urgent ? [0, 0.35, 0.7] : [0, 0.25];
      times.forEach(function (start) {
        var o = audioCtx.createOscillator(), g = audioCtx.createGain();
        o.frequency.value = urgent ? 880 : 660;
        g.gain.setValueAtTime(0.0001, audioCtx.currentTime + start);
        g.gain.exponentialRampToValueAtTime(0.3, audioCtx.currentTime + start + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + start + 0.25);
        o.connect(g).connect(audioCtx.destination);
        o.start(audioCtx.currentTime + start);
        o.stop(audioCtx.currentTime + start + 0.3);
      });
    } catch (e) { /* 소리 못 내도 화면 알림은 나간다 */ }
  }
  function unlockAudio() {
    try {
      var Ctx = window.AudioContext || window.webkitAudioContext;
      if (Ctx && !audioCtx) audioCtx = new Ctx();
      if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { /* */ }
  }

  // ---------------------------------------------------------------- 그리기
  function render() {
    if (!state) return;
    renderStatus();
    renderEmergency();
    renderMap();
    renderCheer();
    renderTimeline();
    renderFoot();
    $('notifyCard').hidden = !('Notification' in window) || Notification.permission !== 'default' || state.demo;
  }

  function fact(label, value) {
    var d = el('div');
    d.appendChild(el('dt', null, label));
    d.appendChild(el('dd', null, value));
    return d;
  }

  function renderStatus() {
    var t = trip();
    var card = $('statusCard'), facts = $('facts');
    card.dataset.mode = t.mode;
    facts.textContent = '';
    var loc = lastLocation();
    var stale = $('stale');
    stale.hidden = true;

    if (t.mode === 'driving') {
      var dest = destination(t);
      $('stateText').textContent = '운행 중';
      $('stateSub').textContent = dest ? toward(dest) + ' 가는 중이에요' : '목적지를 정하지 않고 달리는 중이에요';
      var depAt = receivedMs(t.dep);
      facts.appendChild(fact('출발', clock(depAt)));
      facts.appendChild(fact('운행 시간', duration(Date.now() - depAt)));
      if (loc && loc.id > t.dep.id) {
        var locAt = receivedMs(loc);
        facts.appendChild(fact('위치 갱신', ago(locAt)));
        if (Date.now() - locAt > STALE_MS) {
          stale.hidden = false;
          stale.textContent = '위치가 ' + duration(Date.now() - locAt) + '째 갱신되지 않았어요. 터널이거나 차 연결이 끊겼을 수 있어요.';
        }
      }
    } else if (t.mode === 'arrived') {
      $('stateText').textContent = '도착했어요';
      $('stateSub').textContent = t.arr.title + ' · ' + (t.arr.detail || '');
      facts.appendChild(fact('도착', clock(receivedMs(t.arr)) + ' (' + ago(receivedMs(t.arr)) + ')'));
      if (t.dep) facts.appendChild(fact('출발', clock(receivedMs(t.dep))));
    } else if (state.known === false) {
      $('stateText').textContent = '아직 기록이 없어요';
      $('stateSub').textContent = '차에서 운전복복 앱이 켜져 있으면 출발할 때 알려 드려요. 차 코드가 맞는지도 확인해 주세요.';
    } else {
      $('stateText').textContent = state.lastOkAt ? '쉬는 중' : '불러오는 중';
      $('stateSub').textContent = state.lastOkAt ? '다음 출발을 기다리고 있어요.' : '';
    }
  }

  function renderEmergency() {
    var box = $('emergency');
    var em = lastNotice('EMERGENCY_AIRBAG');
    var fresh = em && Date.now() - receivedMs(em) < 24 * 3600 * 1000 && em.id > state.dismissedEmergency;
    box.hidden = !fresh;
    if (!fresh) return;
    $('emTitle').textContent = em.title;
    $('emTime').textContent = clock(receivedMs(em)) + ' · ' + ago(receivedMs(em));
    $('emDetail').textContent = em.detail || '';
    var lat = em.lat, lng = em.lng;
    if (lat == null || lng == null) {
      var l = lastLocation();
      if (l) { lat = l.lat; lng = l.lng; }
    }
    var has = lat != null && lng != null;
    $('emKakao').hidden = !has;
    $('emGoogle').hidden = !has;
    if (has) {
      $('emKakao').href = kakaoLink(lat, lng);
      $('emGoogle').href = googleLink(lat, lng);
    }
  }

  function kakaoLink(lat, lng) { return 'https://map.kakao.com/link/map/' + encodeURIComponent('차 위치') + ',' + lat + ',' + lng; }
  function googleLink(lat, lng) { return 'https://www.google.com/maps/search/?api=1&query=' + lat + ',' + lng; }

  function trailPoints() {
    var t = trip();
    var from = t.dep ? t.dep.id : 0;
    var pts = state.locations.filter(function (l) { return l.id > from; });
    return pts.length ? pts : state.locations.slice(-1);
  }

  function renderMap() {
    var pts = trailPoints();
    var box = $('map');
    var last = pts[pts.length - 1];
    $('mapLinks').hidden = !last;
    $('mapMeta').textContent = last ? ago(receivedMs(last)) + ' 갱신' : '';
    if (!last) {
      if (!box.querySelector('.placeholder')) {
        box.textContent = '';
        box.appendChild(el('div', 'placeholder', '차가 출발하면 여기에 위치가 보여요.'));
      }
      return;
    }
    $('openKakao').href = kakaoLink(last.lat, last.lng);
    $('openGoogle').href = googleLink(last.lat, last.lng);
    var latlngs = pts.map(function (p) { return [p.lat, p.lng]; });

    if (window.L && L.map) {
      if (!map) {
        box.textContent = '';
        map = L.map(box, { zoomControl: false, attributionControl: true });
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19, attribution: '&copy; OpenStreetMap'
        }).addTo(map);
        var accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#e07b24';
        trail = L.polyline([], { color: accent, weight: 5, opacity: 0.85 }).addTo(map);
        marker = L.circleMarker(latlngs[latlngs.length - 1], {
          radius: 9, color: '#ffffff', weight: 3, fillColor: accent, fillOpacity: 1
        }).addTo(map);
        map.on('dragstart', function () { userMovedMap = true; });
        map.setView(latlngs[latlngs.length - 1], 14);
      }
      $('mapNote').hidden = true;
      trail.setLatLngs(latlngs);
      marker.setLatLng(latlngs[latlngs.length - 1]);
      if (!userMovedMap) {
        if (latlngs.length > 1) map.fitBounds(trail.getBounds(), { padding: [28, 28], maxZoom: 16 });
        else map.setView(latlngs[0], 15);
      }
      return;
    }
    drawSketch(box, latlngs);
  }

  // 지도 라이브러리를 못 불러왔을 때: 지나온 길만 간단히 그린다.
  function drawSketch(box, latlngs) {
    var W = 340, H = 240, pad = 24;
    var lats = latlngs.map(function (p) { return p[0]; }), lngs = latlngs.map(function (p) { return p[1]; });
    var minLat = Math.min.apply(null, lats), maxLat = Math.max.apply(null, lats);
    var minLng = Math.min.apply(null, lngs), maxLng = Math.max.apply(null, lngs);
    var span = Math.max(maxLat - minLat, maxLng - minLng, 0.002);
    function x(lng) { return pad + (lng - minLng) / span * (W - 2 * pad) + ((W - 2 * pad) - (maxLng - minLng) / span * (W - 2 * pad)) / 2; }
    function y(lat) { return H - pad - (lat - minLat) / span * (H - 2 * pad) - ((H - 2 * pad) - (maxLat - minLat) / span * (H - 2 * pad)) / 2; }
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('class', 'sketch');
    svg.setAttribute('aria-hidden', 'true');
    var line = document.createElementNS(ns, 'polyline');
    line.setAttribute('points', latlngs.map(function (p) { return x(p[1]).toFixed(1) + ',' + y(p[0]).toFixed(1); }).join(' '));
    line.setAttribute('fill', 'none');
    line.setAttribute('stroke', 'var(--accent)');
    line.setAttribute('stroke-width', '5');
    line.setAttribute('stroke-linecap', 'round');
    line.setAttribute('stroke-linejoin', 'round');
    svg.appendChild(line);
    var lastP = latlngs[latlngs.length - 1];
    var dot = document.createElementNS(ns, 'circle');
    dot.setAttribute('cx', x(lastP[1]).toFixed(1));
    dot.setAttribute('cy', y(lastP[0]).toFixed(1));
    dot.setAttribute('r', '9');
    dot.setAttribute('fill', 'var(--accent)');
    dot.setAttribute('stroke', 'var(--card)');
    dot.setAttribute('stroke-width', '3');
    svg.appendChild(dot);
    box.textContent = '';
    box.appendChild(svg);
    $('mapNote').hidden = false;
  }

  function renderCheer() {
    var now = Date.now();
    var cooling = now < cheerCooldownUntil;
    Array.prototype.forEach.call($('cheerGrid').children, function (b) {
      b.disabled = cooling;
      b.classList.toggle('sent', !!cheer && cheer.id === b.dataset.id && cheer.status !== 'failed');
    });
    var s = $('cheerStatus');
    s.className = 'cheer-status';
    if (!cheer) { s.textContent = ''; return; }
    if (cheer.status === 'sending') s.textContent = '‘' + cheer.label + '’ 보내는 중…';
    else if (cheer.status === 'sent') {
      s.textContent = now - cheer.sentAt > CHEER_GIVEUP_MS
        ? '아직 차에 전해지지 않았어요. 차가 꺼져 있거나 인터넷이 안 될 수 있어요.'
        : '보냈어요. 차에서 들려주면 알려 드릴게요.';
    } else if (cheer.status === 'played') {
      s.className = 'cheer-status ok';
      s.textContent = '차에서 ‘' + cheer.label + '’를 들려줬어요 ✓' + (cheer.note && cheer.note.indexOf('정차') >= 0 ? ' (정차 중)' : '');
    } else {
      s.textContent = cheer.note || '보내지 못했어요.';
    }
  }

  function renderTimeline() {
    var list = $('timeline');
    var items = state.notices.slice(-50).reverse();
    $('timelineEmpty').hidden = items.length > 0;
    $('timelineCount').textContent = items.length ? items.length + '개' : '';
    list.textContent = '';
    items.forEach(function (n) {
      var li = el('li', 'item');
      li.dataset.kind = n.kind;
      li.dataset.level = n.level;
      li.appendChild(el('div', 'badge', KIND_TAG[n.kind] || '알림'));
      var body = el('div');
      var row = el('div', 'row1');
      row.appendChild(el('span', 'title', n.title));
      row.appendChild(el('span', 'time', clock(receivedMs(n))));
      body.appendChild(row);
      if (n.detail) body.appendChild(el('p', 'detail', n.detail));
      if (n.context && n.context.length) {
        var tags = el('div', 'tags');
        n.context.forEach(function (c) { tags.appendChild(el('span', 'tag', c)); });
        body.appendChild(tags);
      }
      li.appendChild(body);
      list.appendChild(li);
    });
  }

  function renderFoot() {
    var f = $('foot');
    f.className = state.error ? 'foot err' : 'foot';
    if (state.error) f.textContent = state.error;
    else if (state.lastOkAt) f.textContent = '마지막 확인 ' + new Date(state.lastOkAt).toLocaleTimeString('ko-KR') + ' · ' + (POLL_MS / 1000) + '초마다 확인해요';
    else f.textContent = '';
  }

  // ---------------------------------------------------------------- 화면 전환
  function showConnect(prefill, message) {
    clearTimeout(timer);
    state = null;
    backend = null;
    $('main').hidden = true;
    $('connect').hidden = false;
    $('codeInput').value = prefill || '';
    $('connectError').textContent = message || '';
    $('serverNote').textContent = serverConf().url ? '' : '아직 서버가 설정되지 않아 체험 모드만 쓸 수 있어요. (설정은 config.js 또는 연결하기 → 서버 설정)';
  }

  function start(code, demo) {
    clearTimeout(timer);
    userMovedMap = false;
    cheer = null;
    if (map) { map.remove(); map = null; trail = null; marker = null; }
    state = demo ? emptyState('체험', true) : loadState(code);
    backend = demo ? window.AnsimDemo.create() : liveBackend(code);
    if (!demo) store.set(KEY.code, code);
    $('connect').hidden = true;
    $('main').hidden = false;
    $('demoBanner').hidden = !demo;
    $('codeChip').textContent = demo ? '체험' : code;
    render();
    tick();
  }

  function cleanCode(raw) { return String(raw || '').toUpperCase().replace(/[\s-]/g, ''); }
  function codeProblem(code) {
    if (!code) return '차 코드를 넣어 주세요.';
    if (/[IO01]/.test(code)) return '차 코드에는 I, O, 0, 1이 없어요. 다시 확인해 주세요.';
    if (!CODE_RE.test(code)) return '영문 대문자와 숫자 6자리예요.';
    return null;
  }

  // ---------------------------------------------------------------- 응원 보내기
  function sendCheer(preset) {
    if (!backend || Date.now() < cheerCooldownUntil) return;
    unlockAudio();
    cheer = { id: preset.id, label: preset.label, sentAt: Date.now(), status: 'sending' };
    cheerCooldownUntil = Date.now() + CHEER_COOLDOWN_MS;
    renderCheer();
    var mine = cheer;
    backend.sendCheer(preset.id, preset.label).then(function (r) {
      if (cheer !== mine) return;
      if (r && r.ok) mine.status = 'sent';
      else {
        mine.status = 'failed';
        mine.note = r && r.reason === 'too_soon' ? '방금 보냈어요. 10초 뒤에 다시 보내 주세요.' : '보내지 못했어요. 차 코드를 확인해 주세요.';
      }
    }).catch(function () {
      if (cheer !== mine) return;
      mine.status = 'failed';
      mine.note = '보내지 못했어요. 인터넷 연결을 확인해 주세요.';
    }).then(function () {
      renderCheer();
      schedule(1500);
    });
    setTimeout(renderCheer, CHEER_COOLDOWN_MS + 50);
  }

  // ---------------------------------------------------------------- 설정 창
  function openSettings(openServer) {
    $('setCode').value = state && !state.demo ? state.code : cleanCode($('codeInput').value);
    var conf = serverConf();
    $('setUrl').value = conf.url;
    $('setKey').value = conf.key;
    $('settingsError').textContent = '';
    $('forget').hidden = !state || state.demo;
    $('settingsForm').querySelector('details').open = !!openServer || !conf.url;
    var d = $('settings');
    if (d.showModal) d.showModal(); else d.setAttribute('open', '');
  }
  function closeSettings() {
    var d = $('settings');
    if (d.close) d.close(); else d.removeAttribute('open');
  }
  function saveSettings() {
    var code = cleanCode($('setCode').value);
    var problem = codeProblem(code);
    if (problem) { $('settingsError').textContent = problem; return; }
    var url = normalizeBase($('setUrl').value), key = $('setKey').value.trim();
    if (url && url !== normalizeBase(CFG.supabaseUrl)) store.set(KEY.url, url); else store.del(KEY.url);
    if (key && key !== String(CFG.supabaseKey || '').trim()) store.set(KEY.key, key); else store.del(KEY.key);
    if (!serverConf().url) { $('settingsError').textContent = '서버 주소가 필요해요.'; return; }
    closeSettings();
    start(code, false);
  }

  // ---------------------------------------------------------------- 시작
  function wire() {
    PRESETS.forEach(function (p) {
      var b = el('button', 'cheer-btn', p.label);
      b.type = 'button';
      b.dataset.id = p.id;
      b.addEventListener('click', function () { sendCheer(p); });
      $('cheerGrid').appendChild(b);
    });

    $('connectForm').addEventListener('submit', function (e) {
      e.preventDefault();
      unlockAudio();
      var code = cleanCode($('codeInput').value);
      var problem = codeProblem(code);
      if (problem) { $('connectError').textContent = problem; return; }
      if (!serverConf().url) { openSettings(true); return; }
      start(code, false);
    });
    $('codeInput').addEventListener('input', function (e) {
      var v = cleanCode(e.target.value).slice(0, 6);
      if (e.target.value !== v) e.target.value = v;
      $('connectError').textContent = '';
    });
    $('startDemo').addEventListener('click', function () { unlockAudio(); start(null, true); });
    $('exitDemo').addEventListener('click', function () { showConnect(store.get(KEY.code) || ''); });
    $('demoRestart').addEventListener('click', function () { start(null, true); });
    $('demoEmergency').addEventListener('click', function () {
      if (backend && backend.emergency) { backend.emergency(); schedule(50); }
    });
    $('emDismiss').addEventListener('click', function () {
      var em = lastNotice('EMERGENCY_AIRBAG');
      if (em) state.dismissedEmergency = em.id;
      saveState();
      renderEmergency();
    });
    $('openSettings').addEventListener('click', function () { openSettings(false); });
    $('codeChip').addEventListener('click', function () { openSettings(false); });
    $('closeSettings').addEventListener('click', closeSettings);
    $('saveSettings').addEventListener('click', saveSettings);
    $('forget').addEventListener('click', function () {
      if (state && !state.demo) store.del(cacheKey(state.code));
      store.del(KEY.code);
      closeSettings();
      showConnect('');
    });
    $('enableNotify').addEventListener('click', function () {
      unlockAudio();
      Notification.requestPermission().then(function () { render(); });
    });
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && state) tick();
    });
    // 지도·시간 글자를 20초마다 새로(서버 확인과 별개)
    setInterval(function () { if (state) { renderStatus(); renderCheer(); renderFoot(); } }, 20000);

    if ('serviceWorker' in navigator && location.protocol !== 'file:') {
      navigator.serviceWorker.register('sw.js').catch(function () { /* 알림·오프라인만 빠진다 */ });
    }
  }

  function boot() {
    wire();
    var params = new URLSearchParams(location.search);
    if (params.get('demo') === '1') return start(null, true);
    var code = cleanCode(params.get('code') || store.get(KEY.code) || '');
    if (!codeProblem(code) && serverConf().url) start(code, false);
    else showConnect(codeProblem(code) ? '' : code);
  }

  // 테스트용으로 몇 가지를 밖에 보여 준다(동작에는 영향 없음).
  window.AnsimFamily = { normalizeBase: normalizeBase, authHeaders: authHeaders, toward: toward, duration: duration, codeProblem: codeProblem };

  boot();
})();
