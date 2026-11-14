package kr.ansimgwiga.car

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kr.ansimgwiga.car.data.CarPropertySource
import kr.ansimgwiga.car.ui.AnsimTheme
import kr.ansimgwiga.car.ui.AppScreen

class MainActivity : ComponentActivity() {

    // 두뇌는 서비스가 살려 두는 싱글턴. 화면은 같은 것을 받아 보여 주기만 한다.
    private val vm: AppController by lazy { AppController.get(application) }

    private val askSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onSpeedPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TripService.start(this)
        setContent {
            AnsimTheme {
                AppScreen(vm, onRequestSpeedPermission = ::requestSpeedPermission)
            }
        }
        if (checkSelfPermission(CarPropertySource.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestSpeedPermission()
        }
    }

    private fun requestSpeedPermission() = askSpeed.launch(CarPropertySource.PERMISSION_SPEED)
}
