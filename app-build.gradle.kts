plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.emotioncut"
    compileSdk = 34
    defaultConfig { applicationId = "com.emotioncut"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val m = "1.4.1"
    implementation("androidx.media3:media3-transformer:$m")
    implementation("androidx.media3:media3-effect:$m")
    implementation("androidx.media3:media3-common:$m")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.guava:guava:33.0.0-android")
}
