package kr.ansimgwiga.car

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.ansimgwiga.car.core.CheerPreset
import kr.ansimgwiga.car.core.Cue
import kr.ansimgwiga.car.core.DemoScript
import kr.ansimgwiga.car.core.DoorPos
import kr.ansimgwiga.car.core.EngineConfig
import kr.ansimgwiga.car.core.EngineSnapshot
import kr.ansimgwiga.car.core.EventJson
import kr.ansimgwiga.car.core.Level
import kr.ansimgwiga.car.core.Maintenance
import kr.ansimgwiga.car.core.NoticeKind
import kr.ansimgwiga.car.core.SeatPos
import kr.ansimgwiga.car.core.TripEngine
import kr.ansimgwiga.car.core.TripEvent
import kr.ansimgwiga.car.core.TripSignal
import kr.ansimgwiga.car.data.CarPropertySource
import kr.ansimgwiga.car.data.NaviSource
import kr.ansimgwiga.car.data.PleosVehicleSource
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus
import kr.ansimgwiga.car.diag.DiagStore
import kr.ansimgwiga.car.net.CheerPoller
import kr.ansimgwiga.car.net.EventUploader
import kr.ansimgwiga.car.net.UploadStatus
import kr.ansimgwiga.car.sim.DemoPlayer
import kr.ansimgwiga.car.sim.SimState
import kr.ansimgwiga.car.voice.VoiceGuide
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 엔진에 신호를 넣는 곳. 실차 신호와 시뮬레이터 중 하나만 엔진에 들어간다(진단 값은 늘 받는다). */
enum class InputMode(val label: String) { LIVE("실차 신호"), SIMULATOR("시뮬레이터") }

enum class FeedKind(val label: String) { FAMILY("가족 알림"), CUE("차 안 안내"), STATE("상태") }

data class FeedItem(
    val id: Long,
    val atMs: Long,
    val kind: FeedKind,
    val title: String,
    val detail: String,
    val level: Level?
)

data class UiState(
    val mode: InputMode = InputMode.LIVE,
    val snapshot: EngineSnapshot? = null,
    val feed: List<FeedItem> = emptyList(),
    val cueBanner: String? = null,
    val warpMs: Long = 0L,
    val demoRunning: Boolean = false,
    val demoSec: Int = 0,
    val demoLength: Int = DemoScript.lengthSec,
    val demoNarration: String? = null,
    val sim: SimState = SimState(),
    val carCode: String = "",
    val serverUrl: String = "",
    val apiKey: String = "",
    val demoTiming: Boolean = true,
    val childMode: Boolean = true,
    val hardBrake: Boolean = true,
    val childOverride: Boolean? = null,
    val locationsSent: Int = 0
)

/**
 * 앱의 두뇌(신호 → 판단 → 안내음·가족 알림). 화면이 아니라 프로세스에 하나만 있다.
 *
 * 실제 차에서 운전자는 내비 화면을 보고, 우리 앱은 뒤에서 돌아야 한다. 그래서 화면(Activity)의
 * ViewModel이 아니라 [TripService](포그라운드 서비스)가 살려 두는 싱글턴으로 둔다.
 * 화면은 [get]으로 같은 인스턴스를 받아 상태를 보여 주기만 한다.
 */
class AppController private constructor(app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings = AppSettings(app)
    val diag = DiagStore()
    private val warpMs = AtomicLong(0L)
    private val feedIds = AtomicLong(0L)
    private val lastLive = ConcurrentHashMap<String, TripSignal>()
    @Volatile private var cueShownAt = 0L

    @Volatile private var engine: TripEngine = newEngine()

    private val _ui = MutableStateFlow(
        UiState(
            carCode = settings.carCode,
            serverUrl = settings.serverUrl,
            apiKey = settings.apiKey,
            demoTiming = settings.demoTiming,
            childMode = settings.childMode,
            hardBrake = settings.hardBrake
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val voice = VoiceGuide(app, diag)
    private val uploader = EventUploader(scope, { settings.serverUrl }, { settings.apiKey })
    val upload: StateFlow<UploadStatus> = uploader.status

    private val vehicleSource = PleosVehicleSource(app, ::onLive, diag)
    private val naviSource = NaviSource(app, ::onLive, diag)
    private val carSource = CarPropertySource(app, ::onLive, diag)

    private val cheers = CheerPoller(
        scope = scope,
        serverUrl = { settings.serverUrl },
        apiKey = { settings.apiKey },
        carCode = { settings.carCode },
        lastId = { settings.lastCheerId },
        saveLastId = { settings.lastCheerId = it },
        onCheer = { preset -> onCheer(preset, fromServer = true) },
        onStatus = { ok, text ->
            diag.put(DiagGroup.SERVER, "cheer", "응원 메시지 받기", text, if (ok) DiagStatus.OK else DiagStatus.WARN)
        }
    )

    private val player = DemoPlayer(
        scope = scope,
        feed = { s -> applySim(s) },
        warp = { ms -> warpMs.addAndGet(ms) },
        onProgress = { sec, narration, running ->
            _ui.update { it.copy(demoSec = sec, demoNarration = narration ?: it.demoNarration, demoRunning = running) }
        }
    )

    init {
        voice.start()
        safely("Vehicle SDK", DiagGroup.VEHICLE) { vehicleSource.start() }
        safely("NaviHelper", DiagGroup.NAVI) { naviSource.start() }
        safely("Car API", DiagGroup.CAR_API) { carSource.start() }
        uploader.kick()
        cheers.start()
        scope.launch {
            while (isActive) {
                handle(engine.tick())
                refresh()
                delay(1_000)
            }
        }
    }

    // ------------------------------------------------------------------
    // 신호 → 엔진 → 이벤트
    // ------------------------------------------------------------------

    private fun onLive(signal: TripSignal) {
        liveKey(signal)?.let { lastLive[it] = signal }
        if (_ui.value.mode == InputMode.LIVE) feed(signal)
    }

    private fun feed(signal: TripSignal) = handle(engine.onSignal(signal))

    private fun handle(events: List<TripEvent>) {
        if (events.isEmpty()) return
        val code = settings.carCode
        for (e in events) {
            when (e) {
                is TripEvent.Notice -> {
                    EventJson.encode(code, e)?.let(uploader::enqueue)
                    val ctx = if (e.context.isEmpty()) "" else " · ${e.context.joinToString(", ")}"
                    addFeed(e.atMs, FeedKind.FAMILY, e.title, e.detail + ctx, e.kind.level)
                }
                is TripEvent.LocationUpdate -> {
                    EventJson.encode(code, e)?.let(uploader::enqueue)
                    _ui.update { it.copy(locationsSent = it.locationsSent + 1) }
                }
                is TripEvent.DriverCue -> {
                    voice.play(e.cue)
                    cueShownAt = System.currentTimeMillis()
                    _ui.update { it.copy(cueBanner = e.cue.text) }
                    addFeed(e.atMs, FeedKind.CUE, "안내음", e.cue.text, null)
                }
                is TripEvent.DrivingChanged ->
                    addFeed(e.atMs, FeedKind.STATE, if (e.driving) "주행 시작" else "정차", "", null)
            }
        }
        refresh()
    }

    private fun addFeed(atMs: Long, kind: FeedKind, title: String, detail: String, level: Level?) {
        val item = FeedItem(feedIds.incrementAndGet(), atMs, kind, title, detail, level)
        _ui.update { it.copy(feed = (listOf(item) + it.feed).take(MAX_FEED)) }
    }

    private fun refresh() {
        val bannerExpired = System.currentTimeMillis() - cueShownAt > CUE_BANNER_MS
        _ui.update {
            it.copy(
                snapshot = engine.snapshot(),
                warpMs = warpMs.get(),
                cueBanner = if (bannerExpired) null else it.cueBanner
            )
        }
    }

    private fun config(): EngineConfig =
        (if (settings.demoTiming) EngineConfig.demo() else EngineConfig())
            .copy(childMode = settings.childMode, hardBrakeEnabled = settings.hardBrake)

    private fun newEngine() = TripEngine(config()) { System.currentTimeMillis() + warpMs.get() }

    /** 엔진을 새로 만들고, 지금 모드의 마지막 상태를 다시 넣는다. */
    fun resetTrip() {
        warpMs.set(0L)
        engine = newEngine().also { it.setChildOnBoard(_ui.value.childOverride) }
        _ui.update { it.copy(feed = emptyList(), cueBanner = null, locationsSent = 0, warpMs = 0L) }
        when (_ui.value.mode) {
            InputMode.LIVE -> lastLive.values
                .sortedBy { if (it is TripSignal.Gear || it is TripSignal.Speed || it is TripSignal.UxRestricted) 1 else 0 }
                .forEach { feed(it) }
            InputMode.SIMULATOR -> {
                val fresh = SimState()
                _ui.update { it.copy(sim = fresh) }
                fresh.baseline().forEach { feed(it) }
            }
        }
        refresh()
    }

    fun setMode(mode: InputMode) {
        if (_ui.value.mode == mode) return
        player.stop()
        _ui.update { it.copy(mode = mode, demoRunning = false, demoNarration = null) }
        resetTrip()
    }

    // ------------------------------------------------------------------
    // 시뮬레이터
    // ------------------------------------------------------------------

    private fun applySim(signal: TripSignal) {
        _ui.update { it.copy(sim = it.sim.apply(signal)) }
        feed(signal)
    }

    fun sim(signal: TripSignal) {
        if (_ui.value.mode != InputMode.SIMULATOR) setMode(InputMode.SIMULATOR)
        applySim(signal)
    }

    private val simState get() = _ui.value.sim

    fun simGear() = sim(TripSignal.Gear(!simState.gearParked))
    fun simSpeed(kmh: Float) = sim(TripSignal.Speed(kmh))
    fun simDoor(pos: DoorPos) = sim(TripSignal.Door(pos, !simState.doorOpen(pos)))
    fun simDriverBelt() = sim(TripSignal.BeltWarning(SeatPos.DRIVER, !simState.driverBeltWarning))
    fun simRearBelt() = sim(TripSignal.SeatBuckled(SeatPos.REAR_LEFT, simState.rearBuckled != true))
    fun simAirbag() = sim(TripSignal.Airbag(!simState.airbag))
    fun simMaintenance(kind: Maintenance) = sim(TripSignal.MaintenanceWarning(kind, kind !in simState.maintenance))
    fun simEnergy() = sim(TripSignal.EnergyLow(!simState.energyLow))
    fun simHeadlights() = sim(TripSignal.Lights(headlights = !simState.headlights))
    fun simWiper() = sim(TripSignal.Wiper(!simState.wiper))
    fun simDestination() = sim(TripSignal.Destination(if (simState.destination == null) DemoScript.DESTINATION else null))
    fun simNaviArrived() = sim(TripSignal.NaviArrived)

    /** 서버 없이 응원 메시지 재생을 시험한다(시뮬레이터 탭). */
    fun simCheer(preset: CheerPreset) = onCheer(preset, fromServer = false)

    // ------------------------------------------------------------------
    // 응원 메시지(가족 → 차)
    // ------------------------------------------------------------------

    /**
     * 가족이 보낸 메시지를 안내음으로 틀고, 가족 앱에 '들려줬어요'를 돌려보낸다.
     * 화면에는 큰 글자로 잠깐만 띄운다(운전 중이라 읽을 필요 없게 소리가 먼저).
     */
    private fun onCheer(preset: CheerPreset, fromServer: Boolean) {
        val now = System.currentTimeMillis() + warpMs.get()
        voice.play(preset.cue)
        cueShownAt = System.currentTimeMillis()
        _ui.update { it.copy(cueBanner = preset.cue.text) }
        addFeed(now, FeedKind.CUE, "가족 응원", "‘${preset.label}’" + if (fromServer) "" else " (시험)", null)
        if (!fromServer) return
        val snapshot = engine.snapshot()
        val played = TripEvent.Notice(
            kind = NoticeKind.CHEER_PLAYED,
            atMs = now,
            title = "응원 메시지를 들려줬어요",
            detail = "‘${preset.label}’ · " + if (snapshot.driving) "주행 중" else "정차 중",
            context = emptyList(),
            location = snapshot.location
        )
        EventJson.encode(settings.carCode, played)?.let(uploader::enqueue)
    }

    fun simMove() {
        val t = (simState.pathT + 0.1).coerceAtMost(1.0)
        _ui.update { it.copy(sim = it.sim.copy(pathT = t)) }
        val p = DemoScript.pointAt(t)
        sim(TripSignal.Location(p.lat, p.lng))
    }

    /** 1초 간격 급제동 한 번: 60 → 35 km/h + 브레이크. */
    fun simHardBrake() {
        scope.launch {
            sim(TripSignal.Speed(60f))
            delay(1_000)
            sim(TripSignal.Brake(true))
            sim(TripSignal.Speed(35f))
            delay(1_000)
            sim(TripSignal.Brake(false))
            sim(TripSignal.Speed(50f))
        }
    }

    /** 시계를 앞으로 돌린다(2시간 운전, 3분 기다림 등을 건너뛰기). */
    fun warp(ms: Long) {
        warpMs.addAndGet(ms)
        handle(engine.tick())
        refresh()
    }

    fun playDemo() {
        if (_ui.value.mode != InputMode.SIMULATOR) setMode(InputMode.SIMULATOR) else resetTrip()
        player.play()
        _ui.update { it.copy(demoRunning = true, demoSec = 0, demoNarration = null) }
    }

    fun stopDemo() {
        player.stop()
        _ui.update { it.copy(demoRunning = false) }
    }

    // ------------------------------------------------------------------
    // 설정
    // ------------------------------------------------------------------

    fun saveServer(url: String, key: String) {
        settings.serverUrl = url
        settings.apiKey = key
        _ui.update { it.copy(serverUrl = settings.serverUrl, apiKey = settings.apiKey) }
        uploader.kick()
        pollCheersNow()
    }

    fun regenerateCarCode() {
        val code = settings.regenerateCarCode()
        _ui.update { it.copy(carCode = code) }
    }

    fun setDemoTiming(on: Boolean) {
        settings.demoTiming = on
        _ui.update { it.copy(demoTiming = on) }
        resetTrip()
    }

    fun setChildMode(on: Boolean) {
        settings.childMode = on
        _ui.update { it.copy(childMode = on) }
        resetTrip()
    }

    fun setHardBrake(on: Boolean) {
        settings.hardBrake = on
        _ui.update { it.copy(hardBrake = on) }
        resetTrip()
    }

    /** 아이 동승: null = 문·벨트로 추정, true/false = 직접 지정. */
    fun setChildOverride(value: Boolean?) {
        _ui.update { it.copy(childOverride = value) }
        engine.setChildOnBoard(value)
        refresh()
    }

    // ------------------------------------------------------------------
    // 진단
    // ------------------------------------------------------------------

    fun refreshVehicle() = safely("Vehicle SDK", DiagGroup.VEHICLE) { vehicleSource.refresh() }

    fun requestNavi() = safely("NaviHelper", DiagGroup.NAVI) { naviSource.requestAll() }

    fun onSpeedPermission(granted: Boolean) {
        diag.put(
            DiagGroup.CAR_API, "perm_result", "권한 요청 결과",
            if (granted) "허용" else "거부", if (granted) DiagStatus.OK else DiagStatus.WARN
        )
        safely("Car API", DiagGroup.CAR_API) { carSource.subscribeSpeed() }
    }

    fun testServer() {
        scope.launch {
            diag.put(DiagGroup.SERVER, "internet", "인터넷", "확인 중…")
            val internet = EventUploader.check("", "")
            diag.put(DiagGroup.SERVER, "internet", "인터넷", internet.text, if (internet.ok) DiagStatus.OK else DiagStatus.FAIL)
            val url = settings.serverUrl
            if (url.isBlank()) {
                diag.put(DiagGroup.SERVER, "server", "우리 서버", "주소가 없어요(설정 탭)", DiagStatus.WARN)
            } else {
                diag.put(DiagGroup.SERVER, "server", "우리 서버", "확인 중…")
                val r = EventUploader.check(url, settings.apiKey)
                diag.put(DiagGroup.SERVER, "server", "우리 서버", r.text, if (r.ok) DiagStatus.OK else DiagStatus.FAIL)
                uploader.kick()
                cheers.pollOnce()
            }
        }
    }

    fun testVoice() = voice.play(Cue.REAR_SEAT_CHECK)

    fun pollCheersNow() {
        scope.launch { cheers.pollOnce() }
    }

    private fun safely(name: String, group: DiagGroup, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            diag.fail(group, "crash/$name", "$name 시작 실패", t)
        }
    }

    /** 서비스가 완전히 끝날 때만 부른다(화면을 닫을 때는 부르지 않는다). */
    fun close() {
        player.stop()
        cheers.stop()
        runCatching { vehicleSource.release() }
        runCatching { naviSource.release() }
        runCatching { carSource.release() }
        voice.release()
        scope.cancel()
        synchronized(AppController) { if (instance === this) instance = null }
    }

    companion object {
        @Volatile private var instance: AppController? = null

        /** 프로세스에 하나. 서비스와 화면이 같은 것을 쓴다. */
        fun get(app: Application): AppController =
            instance ?: synchronized(this) { instance ?: AppController(app).also { instance = it } }

        private const val MAX_FEED = 60
        private const val CUE_BANNER_MS = 8_000L

        /** 실차 모드로 돌아올 때 다시 넣을 '마지막 값'의 열쇠. 한 번만 일어나는 일(도착)은 빼고. */
        private fun liveKey(s: TripSignal): String? = when (s) {
            is TripSignal.Door -> "door/${s.pos}"
            is TripSignal.BeltWarning -> "belt/${s.seat}"
            is TripSignal.SeatBuckled -> "buckled/${s.seat}"
            is TripSignal.SeatOccupied -> "occupied/${s.seat}"
            is TripSignal.MaintenanceWarning -> "maintenance/${s.kind}"
            is TripSignal.Lights -> if (s.headlights != null) "lights/head" else "lights/fog"
            TripSignal.NaviArrived -> null
            else -> s::class.simpleName
        }
    }
}
