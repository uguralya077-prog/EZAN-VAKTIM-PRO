plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ezanvaktim.pro"
    compileSdk = 35
    defaultConfig { applicationId = "com.ezanvaktim.pro"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
