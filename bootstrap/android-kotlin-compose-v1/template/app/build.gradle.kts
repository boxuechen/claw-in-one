plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "__PACKAGE_NAME__"
  compileSdk = 37
  buildToolsVersion = "37.0.0"

  defaultConfig {
    applicationId = "__PACKAGE_NAME__"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.08.00"))
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
