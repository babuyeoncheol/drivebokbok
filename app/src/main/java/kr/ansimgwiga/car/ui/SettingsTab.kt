package kr.ansimgwiga.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.ansimgwiga.car.AppController
import kr.ansimgwiga.car.BuildConfig
import kr.ansimgwiga.car.UiState
import kr.ansimgwiga.car.net.UploadStatus

@Composable
fun SettingsTab(ui: UiState, upload: UploadStatus, vm: AppController) {
    var url by remember(ui.serverUrl) { mutableStateOf(ui.serverUrl) }
    var key by remember(ui.apiKey) { mutableStateOf(ui.apiKey) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        SectionCard("서버 (Supabase)", "Supabase 대시보드의 Connect 창이나 Settings → API Keys에 있는 주소와 공개 키. 비워 두면 알림은 차 안에 쌓아 두기만 해") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("프로젝트 주소 (예: https://abcd.supabase.co)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("공개 키 (sb_publishable_… 또는 anon 키)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.saveServer(url, key) }) { Text("저장하고 보내기") }
                Text(
                    "보냄 ${upload.sent} · 대기 ${upload.pending}" + (upload.lastError?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (upload.lastError != null) WarnColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard("차 코드", "가족 앱에 이 코드를 넣으면 이 차의 알림을 받아. 실명·전화번호 대신 쓰는 값이야") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.carCode, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = Accent)
                OutlinedButton(onClick = vm::regenerateCarCode) { Text("새 코드 만들기") }
            }
        }

        SectionCard("판단 기준") {
            SettingSwitch(
                "시연용 짧은 시간",
                "휴식 안내 2분, 뒷좌석 확인 20초, 도착 판단 1분. 실제 기준(2시간, 3분, 5분)으로 보려면 끄기",
                ui.demoTiming,
                vm::setDemoTiming
            )
            SettingSwitch(
                "아이 동승 모드",
                "뒷문이 열렸거나 뒷좌석 벨트가 채워진 운행에서, 내릴 때 뒷좌석 확인",
                ui.childMode,
                vm::setChildMode
            )
            SettingSwitch(
                "급제동 케어 (2차 기능)",
                "10분 동안 급제동 3번이면 안내음 + 가족에게 주의",
                ui.hardBrake,
                vm::setHardBrake
            )
        }

        SectionCard("정보") {
            InfoRow("앱", BuildConfig.VERSION_NAME)
            InfoRow("Vehicle SDK", BuildConfig.VEHICLE_SDK_VERSION)
            InfoRow("NaviHelper", BuildConfig.NAVI_HELPER_VERSION)
        }
    }
}
