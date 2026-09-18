plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
}

val fixtureVersion =
  providers
    .gradleProperty("fixtureVersion")
    .orElse("1")
    .get()
    .toInt()
require(fixtureVersion in 1..2) { "fixtureVersion must be 1 or 2" }

android {
  namespace = "ai.clawinone.androiduse.fixture"
  compileSdk = 37

  buildFeatures {
    resValues = true
  }

  defaultConfig {
    applicationId = "io.github.boxuechen.clawinone.fixture"
    minSdk = 31
    targetSdk = 37
    versionCode = fixtureVersion
    versionName = "$fixtureVersion.0"
    resValue("string", "fixture_build_version", "Fixture v$fixtureVersion")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    warningsAsErrors = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

ktlint {
  android.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/build/**")
  }
}

dependencies {
  testImplementation(libs.junit)
}
