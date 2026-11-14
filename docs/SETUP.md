# 소스에서 빌드하고 서버까지 연결하기

심사용으로 앱만 확인할 때는 [README](../README.md)의 실행 방법으로 충분합니다. 이 문서는 소스를 직접 빌드하거나 차량 앱과 가족 앱을 실제 서버로 연결할 때의 순서입니다.

## 1. 차량 앱 빌드

1. Android Studio → **Open** → 저장소 폴더를 엽니다.
2. Gradle JDK 오류가 나면 **Settings → Build Tools → Gradle → Gradle JDK = 21**로 바꿉니다.
3. Pleos SDK는 `settings.gradle.kts`에 적힌 Pleos Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받습니다.
4. **Pleos Connect v2.1.0** 에뮬레이터를 고르고 ▶ Run 합니다.

### 안내 음성 파일

안내 음성은 `app/src/main/res/raw/cue_*.wav` 10개입니다. Windows에서는 아래 명령으로 Windows 한국어 음성을 써서 한 번에 만들 수 있습니다. 문장은 `core/Model.kt`의 `Cue`와 같습니다.

```
powershell -ExecutionPolicy Bypass -File tools\make_voice.ps1
```

| 파일 | 문장 |
|---|---|
| `cue_belt` | 안전벨트를 매 주세요. |
| `cue_maintenance` | 차량 점검 경고가 있어요. 운행 후 확인해 주세요. |
| `cue_energy` | 연료나 배터리가 부족해요. |
| `cue_rest` | 오래 운전하셨어요. 잠시 쉬어 가세요. |
| `cue_rear_seat` | 뒷좌석을 확인해 주세요. |
| `cue_slow_down` | 급제동이 잦아요. 천천히 가세요. |
| `cue_cheer_safe` | 가족 메시지예요. 조심히 와요. |
| `cue_cheer_slow` | 가족 메시지예요. 천천히 와도 괜찮아요. |
| `cue_cheer_call` | 가족 메시지예요. 도착하면 연락 줘요. |
| `cue_cheer_love` | 가족 메시지예요. 사랑해요, 기다리고 있어요. |

### release APK 만들기

`local.properties`에 서명 키 정보가 없으면 release 빌드도 Android 기본 디버그 키로 서명되어 바로 설치할 수 있습니다. 별도 키를 쓰려면 실명이 들어가지 않게 만든 뒤 `local.properties`에 적습니다.

```
keytool -genkeypair -v -keystore drivebokbok-release.jks -alias drivebokbok -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DriveBokbok, O=DriveBokbok, C=KR"
```

```
ansim.storeFile=drivebokbok-release.jks
ansim.storePassword=...
ansim.keyAlias=drivebokbok
ansim.keyPassword=...
```

키 파일(`*.jks`)과 `local.properties`는 저장소에 올리지 않습니다. 빌드는 Android Studio의 **Build → Generate App Bundles or APKs → Generate APKs**(release 변형) 또는 `./gradlew assembleRelease`로 하고, 결과 `app/build/outputs/apk/release/app-release.apk`를 `apk/` 폴더에 공모전 규칙의 이름으로 복사합니다.

## 2. 서버 (Supabase)

1. supabase.com에서 새 프로젝트를 만듭니다. 지역은 서울(Northeast Asia, Seoul)을 권장합니다.
2. **SQL Editor**에 `server/schema.sql` 내용을 붙여 넣고 **Run** 합니다. 여러 번 실행해도 안전합니다.
3. **Settings → API Keys**에서 프로젝트 주소(`https://<프로젝트>.supabase.co`)와 공개 키(`sb_publishable_…`, 예전 프로젝트는 `anon` 키)를 복사합니다.

공개 키는 앱에 들어가도 되는 키입니다. 이 키로는 표를 직접 읽을 수 없고, 차 코드를 아는 함수로만 그 차의 기록을 읽을 수 있습니다. 비밀 키(`sb_secret_…`, `service_role`)는 앱이나 저장소에 넣지 않습니다.

### 차량 앱에 연결

앱의 **설정 탭**에 프로젝트 주소와 공개 키를 넣습니다. 빌드할 때 기본값으로 넣으려면 프로젝트 맨 위 `local.properties`에 적습니다.

```
ansim.serverUrl=https://<프로젝트>.supabase.co
ansim.apiKey=sb_publishable_...
```

### 가족 앱에 연결

1. `family-web/config.js`에 같은 주소와 공개 키를 넣습니다.
2. `family-web` 폴더를 정적 호스팅(Netlify, Vercel, GitHub Pages 등)에 올립니다.
3. 폰에서 주소를 열고 차량 앱 설정 탭의 6자리 차 코드를 넣습니다. `주소?code=K7M2QX`처럼 코드를 붙인 링크를 보내도 됩니다.
4. **알림 켜기**를 누르고 홈 화면에 추가하면 앱처럼 열립니다.

## 3. 서버가 주고받는 것

| 누가 | 호출 | 내용 |
|---|---|---|
| 차 | `POST /rest/v1/events` | 알림·위치 행을 20개씩. 끊기면 차 안에 쌓아 뒀다가 다시 보냄 |
| 가족 앱 | `POST /rest/v1/rpc/family_sync` | `{p_code, p_after_id}` → 새 알림(최대 50)·위치(최대 300) |
| 가족 앱 | `POST /rest/v1/rpc/send_cheer` | `{p_code, p_preset: SAFE·SLOW·CALL·LOVE}` (같은 차에 10초에 한 번) |
| 차 | `POST /rest/v1/rpc/car_cheers` | `{p_code, p_after_id}` → 2분 안의 새 응원 메시지 |

이벤트 한 행의 모양:

```json
{"car_code":"K7M2QX","type":"notice","kind":"REAR_SEAT_UNCHECKED","level":"CAUTION",
 "title":"뒷좌석 확인이 안 됐어요","detail":"차를 세운 뒤 3분 동안 …","context":["전조등 켬"],
 "lat":37.4020000,"lng":127.1090000,"destination":null,"occurred_at_ms":1730000000000}
```

`car_code`는 차량 앱이 만든 무작위 6자리(헷갈리는 I, O, 0, 1 제외)입니다.

## 4. 로컬에서 서버 검증

`server/test/`에는 Supabase 없이 권한과 전체 흐름을 확인하는 도구가 있습니다.

- `supabase_roles.sql`: 로컬 PostgreSQL에 Supabase의 역할(anon 등)을 흉내 냅니다.
- `check.sql`: 공개 키 역할로 추가는 되고, 표 직접 읽기·수정·삭제는 막히고, 잘못된 값은 거절되는지 확인합니다.
- `mock_supabase.py`: 로컬 PostgreSQL 앞에서 Supabase REST처럼 응답하는 테스트 서버입니다.
