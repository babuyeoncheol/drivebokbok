package kr.ansimgwiga.car.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kr.ansimgwiga.car.core.Level
import kr.ansimgwiga.car.diag.DiagStatus

val Accent = Color(0xFFFF9F43)
val OkColor = Color(0xFF4CC38A)
val WarnColor = Color(0xFFF5C451)
val FailColor = Color(0xFFFF6B6B)
val InfoColor = Color(0xFF8AA4C8)

private val CarDark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1F1405),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF0B1220),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE8ECF2),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = Color(0xFF232A35),
    onSurfaceVariant = Color(0xFFB4BECB),
    outline = Color(0xFF3A4351),
    error = FailColor
)

fun levelColor(level: Level?): Color = when (level) {
    Level.EMERGENCY -> FailColor
    Level.CAUTION -> WarnColor
    Level.INFO -> OkColor
    null -> InfoColor
}

fun statusColor(status: DiagStatus): Color = when (status) {
    DiagStatus.OK -> OkColor
    DiagStatus.WARN -> WarnColor
    DiagStatus.FAIL -> FailColor
    DiagStatus.INFO -> InfoColor
}

/** 차 화면은 멀리서 보므로 글자를 크게, 배경은 어둡게. */
@Composable
fun AnsimTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 19.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 17.sp),
        bodySmall = base.bodySmall.copy(fontSize = 15.sp),
        labelLarge = base.labelLarge.copy(fontSize = 17.sp)
    )
    MaterialTheme(colorScheme = CarDark, typography = type, content = content)
}
