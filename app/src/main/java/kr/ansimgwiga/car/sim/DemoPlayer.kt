package kr.ansimgwiga.car.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.ansimgwiga.car.core.DemoScript
import kr.ansimgwiga.car.core.TripSignal

/** [DemoScript]를 실제 시간으로 재생한다. 1초마다 그 초에 해당하는 단계를 넣는다. */
class DemoPlayer(
    private val scope: CoroutineScope,
    private val feed: (TripSignal) -> Unit,
    private val warp: (Long) -> Unit,
    private val onProgress: (sec: Int, narration: String?, running: Boolean) -> Unit
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun play() {
        stop()
        job = scope.launch {
            val steps = DemoScript.steps()
            val length = DemoScript.lengthSec
            var i = 0
            for (sec in 0..length) {
                if (!isActive) return@launch
                var narration: String? = null
                while (i < steps.size && steps[i].atSec == sec) {
                    val step = steps[i++]
                    if (step.warpMs > 0) warp(step.warpMs)
                    step.signals.forEach(feed)
                    step.narration?.let { narration = it }
                }
                onProgress(sec, narration, true)
                delay(1_000)
            }
            onProgress(length, "시연 끝. 운행 탭에서 알림 흐름을 다시 볼 수 있어요", false)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
