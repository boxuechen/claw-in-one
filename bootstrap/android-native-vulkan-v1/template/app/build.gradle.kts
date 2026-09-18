plugins {
  id("com.android.application")
}

android {
  namespace = "__PACKAGE_NAME__"
  compileSdk = 37
  buildToolsVersion = "37.0.0"
  ndkVersion = "29.0.14206865"

  defaultConfig {
    applicationId = "__PACKAGE_NAME__"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake { arguments += "-DANDROID_STL=c++_static" }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
}
