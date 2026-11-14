@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package kr.ansimgwiga.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.ansimgwiga.car.FeedItem
import kr.ansimgwiga.car.FeedKind
import kr.ansimgwiga.car.InputMode
import kr.ansimgwiga.car.UiState
import kr.ansimgwiga.car.core.formatDuration
import kr.ansimgwiga.car.core.formatKm
import kr.ansimgwiga.car.net.UploadStatus

/** 운행 탭: 지금 상태(왼쪽)와 알림 흐름(오른쪽). 운전 중에 볼 화면이라 조작은 거의 없다. */
@Composable
fun TripTab(
    ui: UiState,
    upload: UploadStatus,
    onMode: (InputMode) -> Unit,
    onReset: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("운전복복", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text("신호 출처", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            InputMode.entries.forEach { m ->
                FilterChip(selected = ui.mode == m, onClick = { onMode(m) }, label = { Text(m.label) })
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onReset) { Text("새 운행") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(ui, upload)
            }
            SectionCard(
                title = "알림 흐름",
                subtitle = "가족 알림은 서버로, 차 안 안내는 소리로",
                modifier = Modifier.weight(1.2f).fillMaxHeight()
            ) {
                if (ui.feed.isEmpty()) {
                    Text(
                        "아직 없어요. 시뮬레이터 탭에서 '시연 재생'을 눌러 보세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.feed, key = { it.id }) { item -> FeedRow(item) }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: UiState, upload: UploadStatus) {
    val s = ui.snapshot
    if (s == null) {
        SectionCard(title = "지금 상태") {
            Text("신호를 기다리는 중", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    SectionCard(title = "지금 상태") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (s.tripActive) OkColor else InfoColor, 16)
            Spacer(Modifier.width(10.dp))
            Text(
                if (s.tripActive) "가족에게 공유 중" else "대기 중",
                style = MaterialTheme.typography.headlineSmall,
                color = if (s.tripActive) OkColor else MaterialTheme.colorScheme.onSurface
            )
        }
        InfoRow("주행", (if (s.driving) "주행 중" else "정차") + " · 판단: ${s.driveBasis.label}")
        if (s.tripActive) {
            InfoRow("운행 시간", formatDuration(s.tripMs))
            InfoRow("연속 운전", formatDuration(s.continuousDrivingMs) + " · 휴식 안내 ${s.restCount}회")
            InfoRow("거리", s.distanceKm?.let { "${formatKm(it)} km" } ?: "-")
        }
        InfoRow("목적지", (s.destination ?: "없음") + if (s.naviArrived) " · 내비 도착" else "")
        InfoRow(
            "뒷좌석",
            when {
                s.rearCheckPending -> "확인 기다리는 중"
                s.childInRear -> "동승 있음"
                else -> "동승 없음"
            },
            if (s.rearCheckPending) WarnColor else MaterialTheme.colorScheme.onSurface
        )
        InfoRow(
            "벨트 경고",
            if (s.beltWarnings.isEmpty()) "없음" else s.beltWarnings.joinToString { it.label },
            if (s.beltWarnings.isEmpty()) MaterialTheme.colorScheme.onSurface else WarnColor
        )
        InfoRow(
            "정비 경고",
            if (s.maintenance.isEmpty()) "없음" else s.maintenance.joinToString { it.label },
            if (s.maintenance.isEmpty()) MaterialTheme.colorScheme.onSurface else WarnColor
        )
        if (s.airbagDeployed) InfoRow("에어백", "전개됨", FailColor)
        if (s.context.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.context.forEach { Pill(it, InfoColor) }
            }
        }
        HorizontalDivider()
        InfoRow(
            "서버",
            when {
                !upload.configured && upload.pending > 0 -> "주소 없음 · 대기 ${upload.pending}"
                upload.lastError != null -> "오류 · 대기 ${upload.pending} · ${upload.lastError}"
                else -> "보냄 ${upload.sent} · 대기 ${upload.pending}"
            },
            if (upload.lastError != null) WarnColor else MaterialTheme.colorScheme.onSurface
        )
        InfoRow("위치 갱신", "${ui.locationsSent}회")
        InfoRow("차 코드", ui.carCode)
        if (ui.warpMs > 0) InfoRow("시계", "+${formatDuration(ui.warpMs)} 앞으로 돌림", Accent)
    }
}

@Composable
private fun FeedRow(item: FeedItem) {
    val color = when (item.kind) {
        FeedKind.FAMILY -> levelColor(item.level)
        FeedKind.CUE -> Accent
        FeedKind.STATE -> InfoColor
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(item.atMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Pill(item.kind.label, color)
            Spacer(Modifier.width(10.dp))
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (item.detail.isNotBlank()) {
            Text(item.detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
