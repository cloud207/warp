plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.leon.warpsimple"
 compileSdk = 35
 defaultConfig { applicationId = "com.leon.warpsimple"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.wireguard.android:tunnel:1.0.20230706")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
