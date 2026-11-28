import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val lp = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.eudero.gano"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.eudero.gano"
        minSdk = 28
        targetSdk = 34
        versionCode = 27
        versionName = "2.5"
        ndk { abiFilters += listOf("arm64-v8a") }   // 벡터 지도 엔진 용량 줄이기(요즘 폰은 모두 arm64)
        buildConfigField("String", "VERSION_URL", "\"https://yumin-server.duckdns.org/files/version.json\"")
        // 지도 배경: 기본은 벡터 지도(MapLibre). -PmapBackend=kakao 로 빌드하면 카카오맵 시험판
        buildConfigField("int", "MAP_DIM", "${(project.findProperty("mapDim") as String?) ?: "0"}")
        buildConfigField("String", "MAP_BACKEND", "\"${(project.findProperty("mapBackend") as String?) ?: "kakao"}\"")
        buildConfigField("String", "KAKAO_APP_KEY", "\"${lp.getProperty("KAKAO_APP_KEY", "")}\"")
        buildConfigField("String", "BUBBLE_KEY", "\"${lp.getProperty("BUBBLE_KEY", "")}\"")
        buildConfigField("String", "DATA_BASE", "\"https://yumin-server.duckdns.org/files/data/\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // 해커톤용: 디버그 키로 서명 (업데이트 설치 호환 위해 키 고정)
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":map-lib"))
    implementation(project(":health-connect"))
    
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
