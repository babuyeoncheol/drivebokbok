package kr.ansimgwiga.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.ansimgwiga.car.AppController

private enum class Tab(val label: String, val icon: ImageVector) {
    TRIP("운행", Icons.Filled.Home),
    DIAG("진단", Icons.Filled.Build),
    SIM("시뮬레이터", Icons.Filled.PlayArrow),
    SETTINGS("설정", Icons.Filled.Settings)
}

@Composable
fun AppScreen(vm: AppController, onRequestSpeedPermission: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val diagEntries by vm.diag.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TRIP) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            Spacer(Modifier.height(16.dp))
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tab.TRIP -> TripTab(ui, upload, onMode = vm::setMode, onReset = vm::resetTrip)
                Tab.DIAG -> DiagTab(
                    diagEntries,
                    DiagActions(
                        refreshVehicle = vm::refreshVehicle,
                        requestNavi = vm::requestNavi,
                        requestSpeedPermission = onRequestSpeedPermission,
                        testServer = vm::testServer,
                        testVoice = vm::testVoice
                    )
                )
                Tab.SIM -> SimTab(ui, vm)
                Tab.SETTINGS -> SettingsTab(ui, upload, vm)
            }
            ui.cueBanner?.let { text ->
                CueBanner(text, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
            }
        }
    }
}

/** 안내음과 같은 문장을 화면 위에 크게. TTS가 없을 때도 운전자가 알 수 있게. */
@Composable
private fun CueBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Accent, RoundedCornerShape(20.dp))
            .padding(horizontal = 32.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔔", fontSize = 30.sp)
            Spacer(Modifier.width(14.dp))
            Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1405))
        }
    }
}
