import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 서버 기본값: 프로젝트 맨 위 local.properties(깃에 안 올라감)에 적어 두면 설정 탭에서 따로 안 넣어도 된다.
//   ansim.serverUrl=https://abcd.supabase.co
//   ansim.apiKey=sb_publishable_...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String): String = (localProps.getProperty(name) ?: "").trim().replace("\"", "")

android {
    namespace = "kr.ansimgwiga.car"
    compileSdk = 35

    // 속도·기어·주행 제한 상태: 안드로이드 표준 차량 API(CarPropertyManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.ansimgwiga.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.0"

        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
        buildConfigField("String", "NAVI_HELPER_VERSION", "\"${libs.versions.naviHelper.get()}\"")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${localProp("ansim.serverUrl")}\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"${localProp("ansim.apiKey")}\"")
    }

    // release 서명: local.properties에 키 정보가 있으면 그 키로, 없으면 디버그 키로 서명해 바로 설치할 수 있게 한다.
    //   ansim.storeFile=drivebokbok-release.jks   (프로젝트 맨 위 기준 경로, 깃에 올리지 않음)
    //   ansim.storePassword=...
    //   ansim.keyAlias=drivebokbok
    //   ansim.keyPassword=...
    val hasReleaseKey = localProp("ansim.storeFile").isNotEmpty()
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(localProp("ansim.storeFile"))
                storePassword = localProp("ansim.storePassword")
                keyAlias = localProp("ansim.keyAlias")
                keyPassword = localProp("ansim.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper)
    implementation(libs.kotlin.reflect)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    // core 패키지(판단 로직) 단위 테스트: 안드로이드 없이 돈다.
    testImplementation(libs.junit)
}
