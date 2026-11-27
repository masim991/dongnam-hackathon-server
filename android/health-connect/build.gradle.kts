plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.dongnam.running.health"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The public permission contract uses Health Connect and AndroidX activity types.
    api("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
