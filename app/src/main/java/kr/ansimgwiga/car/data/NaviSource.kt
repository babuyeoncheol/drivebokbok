package kr.ansimgwiga.car.data

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kr.ansimgwiga.car.BuildConfig
import kr.ansimgwiga.car.core.SdkValues
import kr.ansimgwiga.car.core.TripSignal
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus
import kr.ansimgwiga.car.diag.DiagStore
import java.lang.reflect.Proxy

/**
 * NaviHelper → 위치·목적지·도착 신호.
 *
 * 리스너는 프록시로 만든다. 콜백이 20개가 넘고 버전마다 늘어나서, 필요한 것만 이름으로 골라 쓰고
 * 나머지는 진단 화면에 '받은 횟수'만 남긴다. 위치는 요청해야 오는 방식이라 2초마다 요청한다.
 */
class NaviSource(
    private val context: Context,
    private val emit: (TripSignal) -> Unit,
    private val diag: DiagStore
) {
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private val main = Handler(Looper.getMainLooper())
    private var polls = 0

    private val poll = object : Runnable {
        override fun run() {
            val h = helper ?: return
            runCatching { h.getCurrentLocationInfo() }.onFailure { fail("poll/location", "위치 요청", it) }
            if (polls % 5 == 0) runCatching { h.getRouteStateInfo() }.onFailure { fail("poll/route", "경로 상태 요청", it) }
            if (polls % 15 == 0) runCatching { h.getDestinationInfo() }.onFailure { fail("poll/dest", "목적지 요청", it) }
            polls++
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (helper != null) return
        try {
            val h = NaviHelper(context.applicationContext)
            h.initialize()
            val l = Proxy.newProxyInstance(
                NaviHelperEventListener::class.java.classLoader,
                arrayOf(NaviHelperEventListener::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AnsimNaviListener"
                    else -> {
                        onEvent(method.name, args?.firstOrNull())
                        if (method.returnType == Void.TYPE) null else Unit
                    }
                }
            } as NaviHelperEventListener
            h.addListener(l)
            helper = h
            listener = l
            diag.put(DiagGroup.NAVI, "init", "NaviHelper 초기화", "성공 · NaviHelper ${BuildConfig.NAVI_HELPER_VERSION}", DiagStatus.OK)
            main.post(poll)
        } catch (t: Throwable) {
            fail("init", "NaviHelper 초기화", t)
        }
    }

    /** 진단 화면 '지금 요청' 버튼. */
    fun requestAll() {
        val h = helper ?: return start()
        runCatching { h.getCurrentLocationInfo() }.onFailure { fail("req/location", "위치 요청", it) }
        runCatching { h.getDestinationInfo() }.onFailure { fail("req/dest", "목적지 요청", it) }
        runCatching { h.getRouteStateInfo() }.onFailure { fail("req/route", "경로 상태 요청", it) }
    }

    fun release() {
        main.removeCallbacks(poll)
        val h = helper ?: return
        listener?.let { l -> runCatching { h.removeListener(l) } }
        runCatching { h.release() }
        helper = null
        listener = null
    }

    // ------------------------------------------------------------------

    private fun onEvent(name: String, arg: Any?) {
        when (name) {
            "onCurrentLocationInfo" -> {
                val p = ObjectFields.latLng(arg)
                show("location", "현재 위치", arg, if (p != null) DiagStatus.OK else DiagStatus.WARN)
                p?.let { emit(TripSignal.Location(it.lat, it.lng)) }
            }
            "onDestinationInfo" -> {
                val title = ObjectFields.placeName(arg)
                show("destination", "목적지", arg)
                if (arg != null) emit(TripSignal.Destination(title ?: ObjectFields.latLng(arg)?.let { "지정한 목적지" }))
            }
            "onRouteStarted", "onDestinationChanged" -> {
                show("route_started", "경로 시작·목적지 변경", arg)
                helper?.let { h -> runCatching { h.getDestinationInfo() } }
            }
            "onDestinationArrived" -> {
                show("arrived", "목적지 도착", arg, DiagStatus.OK)
                emit(TripSignal.NaviArrived)
            }
            "onRouteCancelled", "onRouteEnded" -> {
                show("route_end", "경로 종료·취소", name)
                emit(TripSignal.Destination(null))
            }
            // 남은 시간·거리가 어디에 들어 있는지 확인하려고 원래 모양 그대로 보여 준다.
            "onRouteStateInfo" -> show("route_state", "경로 상태(남은 시간?)", arg)
            "onTripInfo" -> show("trip", "트립 정보", arg)
            "onDrivingInfo" -> show("driving", "주행 정보", arg)
            "onNavigationStatus" -> show("status", "내비 상태", arg)
            "onCurrentRoadSpeedLimit" -> show("speed_limit", "도로 제한 속도", arg)
            "onError" -> show("error", "내비 오류", arg, DiagStatus.FAIL)
            else -> show("other/$name", name, "받음")
        }
    }

    private fun show(key: String, label: String, value: Any?, status: DiagStatus = DiagStatus.INFO) =
        diag.put(DiagGroup.NAVI, key, label, SdkValues.describe(value), status)

    private fun fail(key: String, label: String, t: Throwable) = diag.fail(DiagGroup.NAVI, key, label, t)

    private companion object {
        const val POLL_MS = 2_000L
    }
}
