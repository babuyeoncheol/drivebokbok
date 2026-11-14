package kr.ansimgwiga.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.ansimgwiga.car.diag.DiagEntry
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus

class DiagActions(
    val refreshVehicle: () -> Unit,
    val requestNavi: () -> Unit,
    val requestSpeedPermission: () -> Unit,
    val testServer: () -> Unit,
    val testVoice: () -> Unit
)

/** 진단 탭: 에뮬레이터에서 확인할 다섯 가지. 화면을 찍어 보내면 값 이름·단위를 맞춰 고친다. */
@Composable
fun DiagTab(entries: Map<String, DiagEntry>, actions: DiagActions) {
    val grouped = entries.values.groupBy { it.group }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("진단: 에뮬레이터에서 확인할 다섯 가지", style = MaterialTheme.typography.headlineSmall)
        Text(
            "초록 = 값이 왔음, 노랑 = 확인 필요, 빨강 = 실패. 각 칸 오른쪽 숫자는 값을 받은 횟수입니다. " +
                "값이 오지 않으면 아래 SDK 메서드 목록에서 실제 이름을 확인할 수 있습니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DiagGroup.entries.forEach { group ->
            SectionCard(group.title, group.question) {
                when (group) {
                    DiagGroup.VEHICLE -> Button(onClick = actions.refreshVehicle) { Text("다시 읽기") }
                    DiagGroup.NAVI -> Button(onClick = actions.requestNavi) { Text("위치·목적지 지금 요청") }
                    DiagGroup.CAR_API -> Button(onClick = actions.requestSpeedPermission) { Text("속도 권한 요청") }
                    DiagGroup.SERVER -> Button(onClick = actions.testServer) { Text("연결 테스트") }
                    DiagGroup.VOICE -> Button(onClick = actions.testVoice) { Text("안내음 테스트") }
                    DiagGroup.SDK_METHODS -> Unit
                }
                val list = grouped[group].orEmpty().sortedWith(compareBy<DiagEntry>({ it.status == DiagStatus.FAIL }, { it.key }))
                if (list.isEmpty()) {
                    Text("아직 값 없음", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                list.forEach { DiagRow(it, small = group == DiagGroup.SDK_METHODS) }
                if (group == DiagGroup.VEHICLE) {
                    Text(
                        "값 바꿔 보기: 에뮬레이터 옆 도구 막대의 '…'(Extended controls)에서 차량 값(Car data 또는 VHAL)을 " +
                            "바꿀 수 있는지 봐. 문·벨트·기어를 바꿨을 때 위 칸 숫자가 바로 바뀌면 성공이야.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagRow(e: DiagEntry, small: Boolean) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.padding(top = 8.dp)) { Dot(statusColor(e.status)) }
        Spacer(Modifier.width(10.dp))
        Text(e.label, modifier = Modifier.weight(0.38f), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(e.value, modifier = Modifier.weight(0.62f), style = style)
        Spacer(Modifier.width(10.dp))
        Text("×${e.updates}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
