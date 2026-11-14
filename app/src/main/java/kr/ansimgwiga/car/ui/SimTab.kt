@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package kr.ansimgwiga.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.ansimgwiga.car.AppController
import kr.ansimgwiga.car.InputMode
import kr.ansimgwiga.car.UiState
import kr.ansimgwiga.car.core.CheerPreset
import kr.ansimgwiga.car.core.DoorPos
import kr.ansimgwiga.car.core.EngineConfig.Companion.MIN
import kr.ansimgwiga.car.core.Maintenance

/** 시뮬레이터 탭: 2차 PT 시연용 자동 재생과, 값을 하나씩 바꿔 보는 스위치. */
@Composable
fun SimTab(ui: UiState, vm: AppController) {
    val sim = ui.sim
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("시뮬레이터", style = MaterialTheme.typography.headlineSmall)
        if (ui.mode != InputMode.SIMULATOR) {
            Text(
                "지금은 실차 신호 모드야. 아래 버튼을 누르면 시뮬레이터 모드로 바뀌고 운행이 새로 시작돼.",
                style = MaterialTheme.typography.bodyLarge,
                color = WarnColor
            )
        }

        SectionCard("시연 자동 재생", "아이를 태우고 귀가 → 휴식 안내 → 급제동 → 비 → 도착 → 뒷좌석 미확인 (약 75초)") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::playDemo, enabled = !ui.demoRunning) { Text("▶ 시연 재생") }
                OutlinedButton(onClick = vm::stopDemo, enabled = ui.demoRunning) { Text("멈춤") }
            }
            LinearProgressIndicator(
                progress = { if (ui.demoLength == 0) 0f else ui.demoSec / ui.demoLength.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                ui.demoNarration ?: "재생하면 여기에 지금 장면 설명이 나와.",
                style = MaterialTheme.typography.titleMedium,
                color = if (ui.demoNarration == null) MaterialTheme.colorScheme.onSurfaceVariant else Accent
            )
        }

        SectionCard("주행") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip("기어 ${if (sim.gearParked) "P" else "D"}", !sim.gearParked, vm::simGear)
                listOf(0f, 30f, 60f).forEach { kmh ->
                    ToggleChip("${kmh.toInt()} km/h", sim.speedKmh == kmh, { vm.simSpeed(kmh) })
                }
                ToggleChip("급제동 한 번", sim.brake, vm::simHardBrake, WarnColor)
                ToggleChip("목적지: 집", sim.destination != null, vm::simDestination)
                ToggleChip("내비 도착", false, vm::simNaviArrived)
                ToggleChip("위치 이동 (${(sim.pathT * 100).toInt()}%)", false, vm::simMove)
            }
        }

        SectionCard("문 · 벨트 · 뒷좌석") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DoorPos.entries.forEach { pos ->
                    ToggleChip("${pos.label} 열림", sim.doorOpen(pos), { vm.simDoor(pos) })
                }
                ToggleChip("운전석 벨트 경고", sim.driverBeltWarning, vm::simDriverBelt, WarnColor)
                ToggleChip("뒷좌석 벨트 체결", sim.rearBuckled == true, vm::simRearBelt)
            }
            Text("아이 동승", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip("자동(문·벨트로 추정)", ui.childOverride == null, { vm.setChildOverride(null) })
                ToggleChip("있음", ui.childOverride == true, { vm.setChildOverride(true) })
                ToggleChip("없음", ui.childOverride == false, { vm.setChildOverride(false) })
            }
        }

        SectionCard("안전 · 정비 · 환경") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip("에어백 전개", sim.airbag, vm::simAirbag, FailColor)
                listOf(Maintenance.ENGINE_OIL, Maintenance.BRAKE_FLUID, Maintenance.WASHER_FLUID).forEach { kind ->
                    ToggleChip("${kind.label} 경고", kind in sim.maintenance, { vm.simMaintenance(kind) }, WarnColor)
                }
                ToggleChip("연료·배터리 부족", sim.energyLow, vm::simEnergy, WarnColor)
                ToggleChip("전조등", sim.headlights, vm::simHeadlights)
                ToggleChip("와이퍼", sim.wiper, vm::simWiper)
            }
        }

        SectionCard("가족 응원 메시지 (시험)", "가족 앱에서 보내면 10초 안에 차에서 울려. 여기서는 서버 없이 소리만 들어 봐") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CheerPreset.entries.forEach { preset ->
                    OutlinedButton(onClick = { vm.simCheer(preset) }) { Text("‘${preset.label}’") }
                }
            }
        }

        SectionCard("시간 빨리 감기", "휴식 안내(기준 시간), 뒷좌석 확인 대기, 도착 판단을 바로 보려면") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("+1분" to 1 * MIN, "+5분" to 5 * MIN, "+30분" to 30 * MIN, "+2시간" to 120 * MIN).forEach { (label, ms) ->
                    OutlinedButton(onClick = { vm.warp(ms) }) { Text(label) }
                }
            }
        }
    }
}
