import com.android.build.api.variant.impl.VariantOutputImpl
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.inject.Inject

abstract class GenerateClawInOneBootstrapAssets
  @Inject
  constructor(
    private val fileSystemOperations: FileSystemOperations,
  ) : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    abstract val supervisorBinary: RegularFileProperty

    @get:InputDirectory
    abstract val androidUsePluginDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val vscreenFoundationPluginDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val androidDeveloperBridgePluginDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val webDevelopmentPluginDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val projectWorkspacesPluginDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val productSkillsDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
      fileSystemOperations.sync {
        from(sourceDirectory) {
          into("claw-in-one")
        }
        from(supervisorBinary) {
          into("claw-in-one")
        }
        from(androidUsePluginDirectory) {
          into("claw-in-one/android-use")
        }
        from(vscreenFoundationPluginDirectory) {
          into("claw-in-one/vscreen-foundation")
        }
        from(androidDeveloperBridgePluginDirectory) {
          into("claw-in-one/android-developer-bridge")
        }
        from(webDevelopmentPluginDirectory) {
          into("claw-in-one/web-development")
        }
        from(projectWorkspacesPluginDirectory) {
          into("claw-in-one/project-workspaces")
        }
        from(productSkillsDirectory) {
          into("claw-in-one/product-skills")
        }
        into(outputDirectory)
      }
    }
  }

val dnsjavaInetAddressResolverService = "META-INF/services/java.net.spi.InetAddressResolverProvider"
val androidSourceNamespace = "ai.openclaw.app"
val clawInOneApplicationId = "io.github.boxuechen.clawinone"
val productVersionFile = rootProject.file("Config/ProductVersion.properties")
val thirdPartyLicensesDir = rootProject.file("THIRD_PARTY_LICENSES")
val clawInOneBootstrapSourceDir =
  rootProject.file("../../bootstrap").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne bootstrap sources: $directory" }
  }
val clawInOneSupervisorSourceDir =
  rootProject.file("../../supervisor").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne Supervisor sources: $directory" }
  }
val clawInOneAndroidUsePluginDir =
  rootProject.file("../../product-plugins/android-use").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne Android Use Plugin: $directory" }
  }
val clawInOneVScreenFoundationPluginDir =
  rootProject.file("../../product-plugins/vscreen-foundation").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne VScreen Foundation Plugin: $directory" }
  }
val clawInOneAndroidDeveloperBridgePluginDir =
  rootProject.file("../../product-plugins/android-developer-bridge").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne Android Developer Bridge Plugin: $directory" }
  }
val clawInOneWebDevelopmentPluginDir =
  rootProject.file("../../product-plugins/web-development").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne Web Development Plugin: $directory" }
  }
val clawInOneProjectWorkspacesPluginDir =
  rootProject.file("../../product-plugins/project-workspaces").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne Project Workspaces Plugin: $directory" }
  }
val clawInOneProductSkillsDir =
  rootProject.file("../../product-skills").also { directory ->
    require(directory.isDirectory) { "Missing ClawInOne product Skills: $directory" }
  }
val clawInOneSupervisorBinary =
  clawInOneSupervisorSourceDir.resolve("target/aarch64-unknown-linux-musl/release/claw-in-one-supervisor")
val generatedBootstrapAssetsDir = layout.buildDirectory.dir("generated/clawInOneBootstrapAssets")
val productVersionProperties =
  Properties().apply {
    if (!productVersionFile.isFile) {
      error("Missing ClawInOne version properties at Config/ProductVersion.properties.")
    }
    productVersionFile.inputStream().use(::load)
  }

fun requireProductVersionProperty(name: String): String =
  productVersionProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("Missing $name in Config/ProductVersion.properties.")

val productVersionName = requireProductVersionProperty("CLAW_IN_ONE_VERSION_NAME")
val productVersionCode =
  requireProductVersionProperty("CLAW_IN_ONE_VERSION_CODE").toIntOrNull()
    ?: error("CLAW_IN_ONE_VERSION_CODE must be an integer in Config/ProductVersion.properties.")

fun optionalProductBuildProperty(name: String): String? =
  providers
    .gradleProperty(name)
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val fullGitCommitPattern = Regex("^[a-f0-9]{40}$")
val buildTimestampFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
val explicitProductBuildCommit =
  optionalProductBuildProperty("clawInOneBuildCommit")
    ?.lowercase()
    ?.also { commit ->
      if (!fullGitCommitPattern.matches(commit)) {
        error("clawInOneBuildCommit must be a full 40-character hexadecimal Git commit.")
      }
    }

val explicitProductBuildTimestamp =
  optionalProductBuildProperty("clawInOneBuildTimestamp")
    ?.let { timestamp ->
      if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z$").matches(timestamp)) {
        error("clawInOneBuildTimestamp must be an ISO-8601 UTC timestamp.")
      }
      val instant =
        runCatching { Instant.parse(timestamp) }
          .getOrElse { error("clawInOneBuildTimestamp must be an ISO-8601 UTC timestamp.") }
      buildTimestampFormatter.format(instant)
    }

val repositoryBuildCommit =
  if (explicitProductBuildCommit == null) {
    runCatching {
      providers
        .exec {
          workingDir(rootProject.projectDir)
          commandLine("git", "rev-parse", "HEAD")
        }.standardOutput
        .asText
        .get()
        .trim()
        .lowercase()
        .takeIf(fullGitCommitPattern::matches)
    }.getOrNull()
  } else {
    null
  }

val productBuildCommit = explicitProductBuildCommit ?: repositoryBuildCommit ?: "unknown"
// Keep every variant generated by one Gradle invocation on the same build instant.
val invocationBuildTimestamp =
  providers.provider { buildTimestampFormatter.format(Instant.now()) }.get()
val productBuildTimestamp = explicitProductBuildTimestamp ?: invocationBuildTimestamp

val androidStoreFile = providers.gradleProperty("CLAW_IN_ONE_ANDROID_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val androidStorePassword = providers.gradleProperty("CLAW_IN_ONE_ANDROID_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val androidKeyAlias = providers.gradleProperty("CLAW_IN_ONE_ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val androidKeyPassword = providers.gradleProperty("CLAW_IN_ONE_ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val resolvedAndroidStoreFile =
  androidStoreFile?.let { storeFilePath ->
    if (storeFilePath.startsWith("~/")) {
      "${System.getProperty("user.home")}/${storeFilePath.removePrefix("~/")}"
    } else {
      storeFilePath
    }
  }

val hasAndroidReleaseSigning =
  listOf(resolvedAndroidStoreFile, androidStorePassword, androidKeyAlias, androidKeyPassword).all { it != null }

val wantsAndroidReleaseBuild =
  gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true) ||
      Regex("""(^|:)(bundle|assemble)$""").containsMatchIn(taskName)
  }
val missingAndroidBuildMetadata =
  explicitProductBuildCommit == null || explicitProductBuildTimestamp == null

if (wantsAndroidReleaseBuild && !hasAndroidReleaseSigning) {
  error(
    "Missing Android release signing properties. Set CLAW_IN_ONE_ANDROID_STORE_FILE, " +
      "CLAW_IN_ONE_ANDROID_STORE_PASSWORD, CLAW_IN_ONE_ANDROID_KEY_ALIAS, and " +
      "CLAW_IN_ONE_ANDROID_KEY_PASSWORD in ~/.gradle/gradle.properties.",
  )
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val buildClawInOneSupervisor =
  tasks.register<Exec>("buildClawInOneSupervisor") {
    description = "Builds the static ARM64 ClawInOne Supervisor."
    workingDir(clawInOneSupervisorSourceDir)
    commandLine("cargo", "build", "--locked", "--release", "--target", "aarch64-unknown-linux-musl")
    inputs.files(
      fileTree(clawInOneSupervisorSourceDir) {
        include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/**", "src/**")
      },
    )
    outputs.file(clawInOneSupervisorBinary)
  }

val generateClawInOneBootstrapAssets =
  tasks.register<GenerateClawInOneBootstrapAssets>("generateClawInOneBootstrapAssets") {
    description = "Stages canonical Bootstrap sources and the ARM64 Supervisor for APK packaging."
    dependsOn(buildClawInOneSupervisor)
    sourceDirectory.set(clawInOneBootstrapSourceDir)
    supervisorBinary.set(clawInOneSupervisorBinary)
    androidUsePluginDirectory.set(clawInOneAndroidUsePluginDir)
    vscreenFoundationPluginDirectory.set(clawInOneVScreenFoundationPluginDir)
    androidDeveloperBridgePluginDirectory.set(clawInOneAndroidDeveloperBridgePluginDir)
    webDevelopmentPluginDirectory.set(clawInOneWebDevelopmentPluginDir)
    projectWorkspacesPluginDirectory.set(clawInOneProjectWorkspacesPluginDir)
    productSkillsDirectory.set(clawInOneProductSkillsDir)
    outputDirectory.set(generatedBootstrapAssetsDir)
  }

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

android {
  namespace = androidSourceNamespace
  // AndroidX Core 1.19 and Lifecycle 2.11 require API 37 compilation.
  // targetSdk stays separate so runtime behavior changes remain an explicit migration.
  compileSdk = 37

  // Release signing is local-only; keep the keystore path and passwords out of the repo.
  signingConfigs {
    if (hasAndroidReleaseSigning) {
      create("release") {
        storeFile = project.file(checkNotNull(resolvedAndroidStoreFile))
        storePassword = checkNotNull(androidStorePassword)
        keyAlias = checkNotNull(androidKeyAlias)
        keyPassword = checkNotNull(androidKeyPassword)
      }
    }
  }

  sourceSets {
    getByName("main") {
      assets.directories.add("../../shared/OpenClawKit/Sources/OpenClawKit/Resources")
      assets.directories.add(thirdPartyLicensesDir.path)
    }
  }

  defaultConfig {
    applicationId = clawInOneApplicationId
    resValue("string", "application_id", clawInOneApplicationId)
    minSdk = 31
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    versionCode = productVersionCode
    versionName = productVersionName
    buildConfigField("String", "GIT_COMMIT", "\"$productBuildCommit\"")
    buildConfigField("String", "BUILD_TIMESTAMP", "\"$productBuildTimestamp\"")
    ndk {
      // Support all major ABIs — native libs are tiny (~47 KB per ABI)
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
  }

  flavorDimensions += "store"

  productFlavors {
    create("play") { dimension = "store" }
    create("thirdParty") { dimension = "store" }
  }

  buildTypes {
    release {
      if (hasAndroidReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      ndk {
        debugSymbolLevel = "SYMBOL_TABLE"
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
      resValue("string", "application_id", "$clawInOneApplicationId.debug")
      isMinifyEnabled = false
    }
  }

  bundle {
    language {
      // The in-app picker can select a locale outside the device language list.
      // Without a Play Core download path, every translated resource must stay installed.
      enableSplit = false
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true
  }

  androidResources {
    generateLocaleConfig = true
    localeFilters +=
      listOf(
        "en",
        "zh-rCN",
      )
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes +=
        setOf(
          "/META-INF/{AL2.0,LGPL2.1}",
          "/META-INF/*.version",
          "/META-INF/LICENSE*.txt",
          "DebugProbesKt.bin",
          "kotlin-tooling-metadata.json",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
          "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
          "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
        )
    }
  }

  lint {
    lintConfig = file("lint.xml")
    warningsAsErrors = true
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

androidComponents {
  val adbExecutable = sdkComponents.adb
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
      generateClawInOneBootstrapAssets,
      GenerateClawInOneBootstrapAssets::outputDirectory,
    )
    variant.outputs
      .filterIsInstance<VariantOutputImpl>()
      .forEach { output ->
        val versionName = output.versionName.orNull ?: "0"
        val buildType = variant.buildType
        val flavorName = variant.flavorName?.takeIf { it.isNotBlank() }
        val outputFileName =
          if (flavorName == null) {
            "claw-in-one-$versionName-$buildType.apk"
          } else {
            "claw-in-one-$versionName-$flavorName-$buildType.apk"
          }
        output.outputFileName = outputFileName
      }

    if (variant.buildType == "debug") {
      val variantNameCapitalized = variant.name.replaceFirstChar(Char::titlecase)
      tasks.register<Exec>("run$variantNameCapitalized") {
        group = "install"
        description = "Installs and launches the ${variant.name} app."
        dependsOn("install$variantNameCapitalized")
        commandLine(
          adbExecutable.get().asFile.absolutePath,
          "shell",
          "am",
          "start",
          "-W",
          "-n",
          "${variant.applicationId.get()}/$androidSourceNamespace.MainActivity",
        )
      }
    }
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
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  // AppCompat owns per-app locale persistence and Activity recreation on API 31-32.
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.webkit)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // material-icons-extended pulled in full icon set (~20 MB DEX). Only ~18 icons used.
  // R8 will tree-shake unused icons when minify is enabled on release builds.
  implementation(libs.androidx.compose.material.icons.extended)

  debugImplementation(libs.androidx.compose.ui.tooling)

  // Material Components (XML theme + resources)
  implementation(libs.material)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.androidx.security.crypto)
  // Room owns separate disposable gateway cache and durable client-state databases.
  implementation(libs.androidx.room.runtime)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.exifinterface)
  implementation(libs.okhttp)
  implementation(libs.bcprov)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  implementation(libs.commonmark)
  implementation(libs.commonmark.ext.autolink)
  implementation(libs.commonmark.ext.gfm.strikethrough)
  implementation(libs.commonmark.ext.gfm.tables)
  implementation(libs.commonmark.ext.task.list.items)

  // Unicast DNS-SD (Wide-Area Bonjour) for tailnet discovery domains.
  implementation(libs.dnsjava)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testRuntimeOnly(libs.junit.vintage.engine)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.uiautomator)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

val validateClawInOneReleaseBuildMetadata =
  tasks.register("validateClawInOneReleaseBuildMetadata") {
    doLast {
      if (missingAndroidBuildMetadata) {
        error(
          "Android release builds require -PclawInOneBuildCommit and -PclawInOneBuildTimestamp.",
        )
      }
    }
  }

val validateThirdPartyLicenseAssets =
  tasks.register("validateThirdPartyLicenseAssets") {
    inputs.dir(thirdPartyLicensesDir)
    doLast {
      if (!thirdPartyLicensesDir.isDirectory) {
        error("Missing Android third-party license directory: ${thirdPartyLicensesDir.relativeTo(rootProject.projectDir)}")
      }
      val invalidFiles =
        thirdPartyLicensesDir
          .walkTopDown()
          .filter { file -> file.isFile && file.extension.lowercase() != "txt" }
          .map { file -> file.relativeTo(thirdPartyLicensesDir).path }
          .toList()

      if (invalidFiles.isNotEmpty()) {
        error(
          "Android third-party license assets must be .txt files:\n" +
            invalidFiles.joinToString(separator = "\n") { path -> "- $path" },
        )
      }
    }
  }

tasks.matching { task -> task.name == "preBuild" }.configureEach {
  dependsOn(validateThirdPartyLicenseAssets)
}

androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    val variantName = variant.name
    val variantNameCapitalized = variantName.replaceFirstChar(Char::titlecase)
    val preBuildTaskName = "pre${variantNameCapitalized}Build"
    val stripTaskName = "strip${variantNameCapitalized}DnsjavaServiceDescriptor"
    val mergeTaskName = "merge${variantNameCapitalized}JavaResource"
    val minifyTaskName = "minify${variantNameCapitalized}WithR8"
    val mergedJar =
      layout.buildDirectory.file(
        "intermediates/merged_java_res/$variantName/$mergeTaskName/base.jar",
      )

    tasks.matching { task -> task.name == preBuildTaskName }.configureEach {
      dependsOn(validateClawInOneReleaseBuildMetadata)
    }

    val stripTask =
      tasks.register(stripTaskName) {
        inputs.file(mergedJar)
        outputs.file(mergedJar)

        doLast {
          val jarFile = mergedJar.get().asFile
          if (!jarFile.exists()) {
            return@doLast
          }

          val unpackDir = temporaryDir.resolve("merged-java-res")
          delete(unpackDir)
          copy {
            from(zipTree(jarFile))
            into(unpackDir)
            exclude(dnsjavaInetAddressResolverService)
          }
          delete(jarFile)
          ant.invokeMethod(
            "zip",
            mapOf(
              "destfile" to jarFile.absolutePath,
              "basedir" to unpackDir.absolutePath,
            ),
          )
        }
      }

    tasks.matching { it.name == mergeTaskName }.configureEach {
      finalizedBy(stripTask)
    }
    tasks.matching { it.name == minifyTaskName }.configureEach {
      dependsOn(stripTask)
    }
  }
}
