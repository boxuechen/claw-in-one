use std::fs;
use std::fs::File;
use std::io::Read;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};

use crate::config::Config;
use crate::install::{DownloadProgress, InstallError, download, run_checked, verify_sha256};
use crate::protocol::Stage;
use crate::setup::{
    ANDROID_KOTLIN_PROFILE_GENERATION, ANDROID_KOTLIN_PROFILE_ID,
    ANDROID_NATIVE_PROFILE_GENERATION, ANDROID_NATIVE_PROFILE_ID, FLUTTER_PROFILE_GENERATION,
    FLUTTER_PROFILE_ID, GODOT_ANDROID_PROFILE_GENERATION, GODOT_ANDROID_PROFILE_ID,
    REACT_NATIVE_ANDROID_PROFILE_GENERATION, REACT_NATIVE_ANDROID_PROFILE_ID,
    REACT_NATIVE_DISTRIBUTION_GENERATION, WEB_DEVELOPMENT_PROFILE_GENERATION,
    WEB_DEVELOPMENT_PROFILE_ID, WEB_DISTRIBUTION_GENERATION,
};
use crate::toolchains::ToolchainStore;

const JAVA_HOME: &str = "/usr/lib/jvm/java-21-openjdk-arm64";

const EXECUTION_FOUNDATION_PACKAGES: &[&str] = &[
    "build-essential",
    "ca-certificates",
    "cmake",
    "curl",
    "file",
    "git",
    "jq",
    "ninja-build",
    "openssh-client",
    "pkg-config",
    "python3",
    "python3-pip",
    "python3-venv",
    "ripgrep",
    "rsync",
    "tar",
    "unzip",
    "wget",
    "xz-utils",
    "zip",
];

const CHROMIUM_PACKAGES: &[&str] = &[
    "chromium",
    "fonts-noto-cjk",
    "fonts-noto-color-emoji",
    "fonts-noto-core",
];

const ANDROID_BUILD_FOUNDATION_PACKAGES: &[&str] = &[
    "binfmt-support",
    "libc6:amd64",
    "libstdc++6:amd64",
    "openjdk-21-jdk-headless",
    "qemu-user-static",
    "zlib1g:amd64",
];

pub fn install_openclaw_execution_foundation(
    _config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if openclaw_execution_foundation_is_ready() {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    apt_update()?;
    apt_install(EXECUTION_FOUNDATION_PACKAGES)?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    openclaw_execution_foundation_is_ready()
        .then_some(())
        .ok_or_else(|| failure(70, "OpenClaw execution foundation health check failed"))
}

pub fn openclaw_execution_foundation_is_ready() -> bool {
    std::env::consts::ARCH == "aarch64"
        && fs::read_to_string("/etc/debian_version").is_ok_and(|value| value.starts_with("13."))
        && EXECUTION_FOUNDATION_PACKAGES
            .iter()
            .all(|package| debian_package_is_installed(package))
        && command_succeeds(Command::new("/usr/bin/git").arg("--version"))
        && command_succeeds(Command::new("/usr/bin/python3").arg("--version"))
        && command_succeeds(Command::new("/usr/bin/rg").arg("--version"))
}

pub fn install_chromium_runtime(
    _config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if chromium_runtime_is_ready() {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    apt_update()?;
    apt_install(CHROMIUM_PACKAGES)?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    chromium_runtime_is_ready()
        .then_some(())
        .ok_or_else(|| failure(71, "Chromium runtime health check failed"))
}

pub fn chromium_runtime_is_ready() -> bool {
    CHROMIUM_PACKAGES
        .iter()
        .all(|package| debian_package_is_installed(package))
        && chromium_cdp_probe()
}

fn chromium_cdp_probe() -> bool {
    let profile =
        std::env::temp_dir().join(format!("claw-in-one-chromium-probe-{}", std::process::id()));
    if remove_directory(&profile).is_err() || fs::create_dir_all(&profile).is_err() {
        return false;
    }
    let mut child = match Command::new("/usr/bin/chromium")
        .arg("--headless")
        .arg("--no-sandbox")
        .arg("--disable-dev-shm-usage")
        .arg("--disable-gpu")
        .arg("--remote-debugging-port=0")
        .arg(format!("--user-data-dir={}", profile.display()))
        .arg("about:blank")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(_) => {
            let _ = remove_directory(&profile);
            return false;
        }
    };
    let port_file = profile.join("DevToolsActivePort");
    let deadline = Instant::now() + Duration::from_secs(5);
    let ready = loop {
        if let Ok(value) = fs::read_to_string(&port_file) {
            let mut lines = value.lines();
            let valid_port = lines
                .next()
                .and_then(|line| line.parse::<u16>().ok())
                .is_some_and(|port| port > 0);
            let valid_browser = lines
                .next()
                .is_some_and(|line| line.starts_with("/devtools/browser/"));
            if valid_port && valid_browser {
                break true;
            }
        }
        if child.try_wait().ok().flatten().is_some() || Instant::now() >= deadline {
            break false;
        }
        thread::sleep(Duration::from_millis(50));
    };
    let _ = child.kill();
    let _ = child.wait();
    let _ = remove_directory(&profile);
    ready
}

pub fn install_adb_runtime(
    _config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if adb_runtime_is_ready() {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    apt_update()?;
    apt_install(&["adb"])?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    adb_runtime_is_ready()
        .then_some(())
        .ok_or_else(|| failure(72, "ADB runtime health check failed"))
}

pub fn adb_runtime_is_ready() -> bool {
    debian_package_is_installed("adb")
        && command_succeeds(Command::new("/usr/bin/adb").arg("version"))
        && command_output(
            Command::new("/usr/bin/file").arg("-L").arg("/usr/bin/adb"),
            72,
            "",
        )
        .is_ok_and(|output| output.contains("ARM aarch64") || output.contains("ARM64"))
}

pub fn install_android_build_foundation(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if android_build_foundation_is_ready() {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    enable_amd64_packages()?;
    apt_update()?;
    apt_install(ANDROID_BUILD_FOUNDATION_PACKAGES)?;
    enable_x86_64_binfmt()?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if android_build_foundation_is_ready() {
        write_capability_environment(config, &android_sdk_root(config))?;
        Ok(())
    } else {
        Err(failure(73, "Android build foundation health check failed"))
    }
}

pub fn android_build_foundation_is_ready() -> bool {
    ANDROID_BUILD_FOUNDATION_PACKAGES
        .iter()
        .all(|package| debian_package_is_installed(package))
        && command_succeeds(
            Command::new("/usr/bin/java")
                .arg("-version")
                .env("JAVA_HOME", JAVA_HOME),
        )
        && command_succeeds(Command::new("/usr/bin/qemu-x86_64-static").arg("--version"))
}

pub fn install_android_sdk_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if android_sdk_is_ready(config) {
        return Ok(());
    }
    let cache = ToolchainStore::new(&config.install_root).cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(33, format!("cannot create toolchain cache: {error}")))?;
    let archive = cache.join(format!(
        "android-command-tools-{}.zip",
        config.manifest.android_command_tools_version
    ));
    if !verify_sha256(&archive, &config.manifest.android_command_tools_sha256) {
        download(
            &config.manifest.android_command_tools_url,
            &archive,
            config.manifest.android_command_tools_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, &config.manifest.android_command_tools_sha256) {
        return Err(failure(34, "Android command tools digest mismatch"));
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let sdk = android_sdk_root(config);
    install_command_tools(config, &archive, &sdk)?;
    install_android_packages(config, &sdk)?;
    write_capability_environment(config, &sdk)?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    android_sdk_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(42, "Android SDK health check failed"))
}

pub fn android_sdk_is_ready(config: &Config) -> bool {
    let sdk = android_sdk_root(config);
    sdk.join("cmdline-tools/latest/bin/sdkmanager").is_file()
        && sdk
            .join(format!(
                "platforms/android-{}/android.jar",
                config.manifest.android_platform
            ))
            .is_file()
        && command_succeeds(
            Command::new(sdk.join(format!(
                "build-tools/{}/aapt2",
                config.manifest.android_build_tools
            )))
            .arg("version"),
        )
}

pub fn install_gradle_component(
    config: &Config,
    version: &str,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if gradle_version_is_ready(config, version) {
        return Ok(());
    }
    let (url, size_bytes, sha256) = gradle_release(config, version)?;
    let cache = ToolchainStore::new(&config.install_root).cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(43, format!("cannot create toolchain cache: {error}")))?;
    let archive = cache.join(format!("gradle-{}-bin.zip", version));
    if !verify_sha256(&archive, sha256) {
        download(
            url,
            &archive,
            size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, sha256) {
        return Err(failure(44, "Gradle distribution digest mismatch"));
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_gradle(config, &archive, version)?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    gradle_version_is_ready(config, version)
        .then_some(())
        .ok_or_else(|| failure(45, "Gradle health check failed"))
}

pub fn install_android_kotlin_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_android_kotlin_profile(config, &android_sdk_root(config))?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    android_kotlin_profile_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(46, "Android Kotlin profile health check failed"))
}

pub fn android_kotlin_profile_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "android-kotlin").join("active");
    let release = android_kotlin_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && profile_is_ready(config, &active)
}

pub fn install_android_package_component(
    config: &Config,
    package: &str,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if android_package_is_ready(config, package) {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let sdk = android_sdk_root(config);
    let manager = sdk.join("cmdline-tools/latest/bin/sdkmanager");
    let sdk_root = format!("--sdk_root={}", sdk.display());
    run_with_acceptance(
        Command::new(&manager)
            .arg(&sdk_root)
            .arg(format!("--channel={}", config.manifest.android_sdk_channel))
            .arg(package)
            .env("JAVA_HOME", JAVA_HOME),
        60,
        "versioned Android package installation failed",
    )?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    android_package_is_ready(config, package)
        .then_some(())
        .ok_or_else(|| failure(60, "versioned Android package health check failed"))
}

pub fn android_package_is_ready(config: &Config, package: &str) -> bool {
    let store = ToolchainStore::new(&config.install_root);
    if let Some(version) = package.strip_prefix("ndk;") {
        let root = store.android_ndk_root(version);
        return root.join("source.properties").is_file()
            && command_succeeds(
                Command::new(root.join("toolchains/llvm/prebuilt/linux-x86_64/bin/clang"))
                    .arg("--version"),
            );
    }
    if let Some(version) = package.strip_prefix("cmake;") {
        let root = store.android_cmake_root(version);
        return command_succeeds(Command::new(root.join("bin/cmake")).arg("--version"))
            && root.join("bin/ninja").is_file();
    }
    if let Some(version) = package.strip_prefix("platforms;android-") {
        return store
            .android_sdk_root()
            .join("platforms")
            .join(format!("android-{version}"))
            .join("android.jar")
            .is_file();
    }
    false
}

pub fn install_android_native_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_android_native_profile(config, &android_sdk_root(config))?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    android_native_profile_component_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(61, "Android Native profile health check failed"))
}

pub fn android_native_profile_component_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "android-native").join("active");
    let release = android_native_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && native_profile_is_ready(config, &active)
}

pub fn install_general_node_runtime(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if general_node_runtime_is_ready(config) {
        return Ok(());
    }
    let store = ToolchainStore::new(&config.install_root);
    let cache = store.cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(65, format!("cannot create general Node cache: {error}")))?;
    let archive = cache.join(format!(
        "node-v{}-linux-arm64.tar.xz",
        config.manifest.general_node_version
    ));
    if !verify_sha256(&archive, &config.manifest.general_node_sha256) {
        download(
            &config.manifest.general_node_url,
            &archive,
            config.manifest.general_node_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, &config.manifest.general_node_sha256) {
        return Err(failure(65, "general Node digest mismatch"));
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let destination = general_node_root(config);
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging)
        .map_err(|error| failure(65, format!("cannot create general Node staging: {error}")))?;
    run_checked(
        Command::new("/usr/bin/tar")
            .arg("-xJf")
            .arg(&archive)
            .arg("--strip-components=1")
            .arg("-C")
            .arg(&staging),
        65,
        "general Node extraction failed",
    )?;
    remove_directory(&destination)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(65, format!("cannot activate general Node: {error}")))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    general_node_runtime_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(65, "general Node health check failed"))
}

pub fn general_node_runtime_is_ready(config: &Config) -> bool {
    let store = ToolchainStore::new(&config.install_root);
    let archive = store.cache_root().join(format!(
        "node-v{}-linux-arm64.tar.xz",
        config.manifest.general_node_version
    ));
    let root = general_node_root(config);
    verify_sha256(&archive, &config.manifest.general_node_sha256)
        && command_output(Command::new(root.join("bin/node")).arg("--version"), 0, "")
            .is_ok_and(|value| value.trim() == format!("v{}", config.manifest.general_node_version))
        && command_output(
            general_node_command(config, &root.join("bin/npm")).arg("--version"),
            0,
            "",
        )
        .is_ok_and(|value| value.trim() == config.manifest.general_node_npm_version)
        && command_output(
            Command::new("/usr/bin/file").arg(root.join("bin/node")),
            0,
            "",
        )
        .is_ok_and(|value| value.contains("ARM aarch64") || value.contains("ARM64"))
}

pub fn install_vscreen_runtime_assets(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if vscreen_runtime_assets_are_ready(config) {
        return Ok(());
    }
    let store = ToolchainStore::new(&config.install_root);
    let cache = store.cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(74, format!("cannot create VScreen asset cache: {error}")))?;
    let cached = cache.join(format!(
        "scrcpy-server-v{}",
        config.manifest.scrcpy_server_version
    ));
    if !verify_sha256(&cached, &config.manifest.scrcpy_server_sha256) {
        download(
            &config.manifest.scrcpy_server_url,
            &cached,
            config.manifest.scrcpy_server_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&cached, &config.manifest.scrcpy_server_sha256) {
        return Err(failure(74, "scrcpy server digest mismatch"));
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let destination = store.scrcpy_server_release(&config.manifest.scrcpy_server_version);
    let release = destination
        .parent()
        .ok_or_else(|| failure(74, "invalid VScreen asset destination"))?;
    let releases = release
        .parent()
        .ok_or_else(|| failure(74, "invalid VScreen release root"))?;
    let staging = releases.join(format!(
        "{}.staging.{}",
        config.manifest.scrcpy_server_version,
        std::process::id()
    ));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging)
        .map_err(|error| failure(74, format!("cannot stage VScreen runtime asset: {error}")))?;
    fs::copy(&cached, staging.join("scrcpy-server"))
        .map_err(|error| failure(74, format!("cannot stage scrcpy server: {error}")))?;
    if !verify_sha256(
        &staging.join("scrcpy-server"),
        &config.manifest.scrcpy_server_sha256,
    ) {
        return Err(failure(74, "staged scrcpy server digest mismatch"));
    }
    remove_directory(release)?;
    fs::create_dir_all(releases)
        .map_err(|error| failure(74, format!("cannot create VScreen release root: {error}")))?;
    fs::rename(&staging, release).map_err(|error| {
        failure(
            74,
            format!("cannot activate VScreen asset release: {error}"),
        )
    })?;
    let current = store.scrcpy_server_current();
    let next = current.with_extension(format!("next.{}", std::process::id()));
    remove_path(&next)?;
    symlink(release, &next)
        .map_err(|error| failure(74, format!("cannot stage VScreen asset pointer: {error}")))?;
    fs::rename(&next, &current).map_err(|error| {
        failure(
            74,
            format!("cannot activate VScreen asset pointer: {error}"),
        )
    })?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    vscreen_runtime_assets_are_ready(config)
        .then_some(())
        .ok_or_else(|| failure(74, "VScreen runtime asset health check failed"))
}

pub fn vscreen_runtime_assets_are_ready(config: &Config) -> bool {
    let store = ToolchainStore::new(&config.install_root);
    let release = store.scrcpy_server_release(&config.manifest.scrcpy_server_version);
    let current = store.scrcpy_server_current();
    fs::canonicalize(&current).ok()
        == release
            .parent()
            .and_then(|path| fs::canonicalize(path).ok())
        && fs::metadata(&release)
            .is_ok_and(|metadata| metadata.len() == config.manifest.scrcpy_server_size_bytes)
        && verify_sha256(&release, &config.manifest.scrcpy_server_sha256)
}

pub fn install_react_native_distribution_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if react_native_distribution_is_ready(config) {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let source = config
        .support_script(REACT_NATIVE_ANDROID_PROFILE_ID)
        .join("template");
    let source_lock = source.join("package-lock.json");
    if !verify_sha256(
        &source_lock,
        &config.manifest.react_native_package_lock_sha256,
    ) {
        return Err(failure(
            66,
            "pinned React Native package lock digest mismatch",
        ));
    }
    let destination = react_native_distribution_root(config);
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging).map_err(|error| {
        failure(
            66,
            format!("cannot create React Native distribution staging: {error}"),
        )
    })?;
    for name in ["package.json", "package-lock.json"] {
        fs::copy(source.join(name), staging.join(name)).map_err(|error| {
            failure(
                66,
                format!("cannot stage React Native dependency manifest: {error}"),
            )
        })?;
    }
    run_react_native_npm(
        config,
        &staging,
        false,
        &["ci", "--ignore-scripts", "--no-audit", "--no-fund"],
    )?;
    verify_react_native_distribution(config, &staging)?;
    fs::write(
        staging.join("qualified"),
        format!("{}\n", config.manifest.react_native_package_lock_sha256),
    )
    .map_err(|error| {
        failure(
            66,
            format!("cannot publish React Native distribution: {error}"),
        )
    })?;
    remove_directory(&destination)?;
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            failure(
                66,
                format!("cannot create React Native distribution root: {error}"),
            )
        })?;
    }
    fs::rename(&staging, &destination).map_err(|error| {
        failure(
            66,
            format!("cannot activate React Native distribution: {error}"),
        )
    })?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    react_native_distribution_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(66, "React Native distribution health check failed"))
}

pub fn react_native_distribution_is_ready(config: &Config) -> bool {
    let root = react_native_distribution_root(config);
    fs::read_to_string(root.join("qualified"))
        .is_ok_and(|value| value.trim() == config.manifest.react_native_package_lock_sha256)
        && verify_sha256(
            &root.join("package-lock.json"),
            &config.manifest.react_native_package_lock_sha256,
        )
        && verify_react_native_distribution(config, &root).is_ok()
}

pub fn install_react_native_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_react_native_profile(config)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    react_native_profile_component_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(67, "React Native Android profile health check failed"))
}

pub fn react_native_profile_component_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "react-native").join("active");
    let release = react_native_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && react_native_profile_is_ready(config, &active)
}

pub fn install_web_distribution_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if web_distribution_is_ready(config) {
        return Ok(());
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    let source = config
        .support_script(WEB_DEVELOPMENT_PROFILE_ID)
        .join("template");
    if !verify_sha256(
        &source.join("package-lock.json"),
        &config.manifest.web_package_lock_sha256,
    ) {
        return Err(failure(68, "pinned Web package lock digest mismatch"));
    }
    let destination = web_distribution_root(config);
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging).map_err(|error| {
        failure(
            68,
            format!("cannot create Web distribution staging: {error}"),
        )
    })?;
    for name in ["package.json", "package-lock.json"] {
        fs::copy(source.join(name), staging.join(name)).map_err(|error| {
            failure(68, format!("cannot stage Web dependency manifest: {error}"))
        })?;
    }
    run_web_npm(
        config,
        &staging,
        false,
        &["ci", "--ignore-scripts", "--no-audit", "--no-fund"],
    )?;
    verify_web_distribution(config, &staging)?;
    fs::write(
        staging.join("qualified"),
        format!("{}\n", config.manifest.web_package_lock_sha256),
    )
    .map_err(|error| failure(68, format!("cannot publish Web distribution: {error}")))?;
    remove_directory(&destination)?;
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            failure(68, format!("cannot create Web distribution root: {error}"))
        })?;
    }
    fs::rename(&staging, &destination)
        .map_err(|error| failure(68, format!("cannot activate Web distribution: {error}")))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    web_distribution_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(68, "Web distribution health check failed"))
}

pub fn web_distribution_is_ready(config: &Config) -> bool {
    let root = web_distribution_root(config);
    fs::read_to_string(root.join("qualified"))
        .is_ok_and(|value| value.trim() == config.manifest.web_package_lock_sha256)
        && verify_sha256(
            &root.join("package-lock.json"),
            &config.manifest.web_package_lock_sha256,
        )
        && verify_web_distribution(config, &root).is_ok()
}

pub fn install_web_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_web_profile(config)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    web_profile_component_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(69, "Web Development profile health check failed"))
}

pub fn web_profile_component_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "web-development").join("active");
    let release = web_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && web_profile_is_ready(config, &active)
}

pub fn install_godot_engine_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if godot_engine_is_ready(config) {
        return Ok(());
    }
    let cache = ToolchainStore::new(&config.install_root).cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(62, format!("cannot create Godot cache: {error}")))?;
    let archive = cache.join(format!(
        "godot-{}-linux-arm64.zip",
        config.manifest.godot_version
    ));
    if !verify_sha256(&archive, &config.manifest.godot_engine_sha256) {
        download(
            &config.manifest.godot_engine_url,
            &archive,
            config.manifest.godot_engine_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, &config.manifest.godot_engine_sha256) {
        return Err(failure(62, "Godot engine digest mismatch"));
    }
    validate_zip_archive(&archive, 62, "Godot engine")?;
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_godot_engine(config, &archive)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    godot_engine_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(62, "Godot engine health check failed"))
}

pub fn install_godot_export_templates_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if godot_export_templates_are_ready(config) {
        return Ok(());
    }
    let cache = ToolchainStore::new(&config.install_root).cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(63, format!("cannot create Godot cache: {error}")))?;
    let archive = cache.join(format!(
        "godot-{}-export-templates.tpz",
        config.manifest.godot_version
    ));
    if !verify_sha256(&archive, &config.manifest.godot_export_templates_sha256) {
        download(
            &config.manifest.godot_export_templates_url,
            &archive,
            config.manifest.godot_export_templates_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, &config.manifest.godot_export_templates_sha256) {
        return Err(failure(63, "Godot export templates digest mismatch"));
    }
    validate_zip_archive(&archive, 63, "Godot export templates")?;
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_godot_export_templates(config, &archive)?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    godot_export_templates_are_ready(config)
        .then_some(())
        .ok_or_else(|| failure(63, "Godot export templates health check failed"))
}

pub fn install_godot_android_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_godot_android_profile(config)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    godot_android_profile_component_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(64, "Godot Android profile health check failed"))
}

pub fn godot_engine_is_ready(config: &Config) -> bool {
    let binary = godot_root(config).join("godot");
    let expected = format!(
        "{}.{}",
        config.manifest.godot_version, config.manifest.godot_build
    );
    godot_binary_is_ready(&binary, &expected)
}

fn godot_binary_is_ready(binary: &Path, expected: &str) -> bool {
    command_output(
        Command::new(binary).arg("--headless").arg("--version"),
        0,
        "",
    )
    .is_ok_and(|output| output.trim() == expected)
        && command_output(Command::new("/usr/bin/file").arg(binary), 0, "")
            .is_ok_and(|output| output.contains("ARM aarch64") || output.contains("ARM64"))
}

pub fn godot_export_templates_are_ready(config: &Config) -> bool {
    godot_android_template_is_ready(config, &godot_android_debug_template(config))
}

fn godot_android_template_is_ready(config: &Config, template: &Path) -> bool {
    verify_sha256(
        template,
        &config.manifest.godot_android_debug_template_sha256,
    ) && command_succeeds(Command::new("/usr/bin/unzip").arg("-tq").arg(template))
        && command_output(
            Command::new("/usr/bin/unzip").arg("-Z1").arg(template),
            0,
            "",
        )
        .is_ok_and(|entries| {
            entries
                .lines()
                .any(|entry| entry == "lib/arm64-v8a/libgodot_android.so")
        })
}

pub fn godot_android_profile_component_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "godot-android").join("active");
    let release = godot_android_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && godot_profile_is_ready(config, &active)
}

pub fn install_flutter_sdk_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    if flutter_sdk_is_ready(config) {
        return Ok(());
    }
    let cache = ToolchainStore::new(&config.install_root).cache_root();
    fs::create_dir_all(&cache)
        .map_err(|error| failure(51, format!("cannot create toolchain cache: {error}")))?;
    let archive = cache.join(format!(
        "flutter-linux-{}.tar.xz",
        config.manifest.flutter_version
    ));
    if !verify_sha256(&archive, &config.manifest.flutter_sha256) {
        download(
            &config.manifest.flutter_url,
            &archive,
            config.manifest.flutter_size_bytes,
            Stage::DownloadingComponent,
            &mut report,
        )?;
    }
    report_stage(&mut report, Stage::VerifyingComponent)?;
    if !verify_sha256(&archive, &config.manifest.flutter_sha256) {
        return Err(failure(52, "Flutter archive digest mismatch"));
    }
    report_stage(&mut report, Stage::InstallingComponent)?;
    install_flutter_sdk(config, &archive)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    flutter_sdk_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(53, "Flutter SDK health check failed"))
}

pub fn install_flutter_profile_component(
    config: &Config,
    mut report: impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
) -> Result<(), InstallError> {
    report_stage(&mut report, Stage::InstallingComponent)?;
    bootstrap_flutter_host(config)?;
    warm_flutter_android(config)?;
    install_flutter_profile(config)?;
    write_capability_environment(config, &android_sdk_root(config))?;
    report_stage(&mut report, Stage::VerifyingComponent)?;
    flutter_profile_component_is_ready(config)
        .then_some(())
        .ok_or_else(|| failure(57, "Flutter profile health check failed"))
}

pub fn flutter_profile_component_is_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "flutter").join("active");
    let release = flutter_profile_root(config);
    fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && flutter_profile_is_ready(config, &active)
}

fn flutter_artifacts_are_ready(config: &Config) -> bool {
    let active = profile_family_root(config, "flutter").join("active");
    let release = flutter_profile_root(config);
    flutter_sdk_is_ready(config)
        && fs::canonicalize(&active).ok() == fs::canonicalize(&release).ok()
        && fs::canonicalize(&active).is_ok()
        && flutter_profile_is_ready(config, &active)
}

fn install_gradle(config: &Config, archive: &Path, version: &str) -> Result<(), InstallError> {
    let destination = gradle_root_for(config, version);
    if gradle_version_is_ready(config, version) {
        return Ok(());
    }
    remove_directory(&destination)?;
    let staging_root = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging_root)?;
    fs::create_dir_all(&staging_root)
        .map_err(|error| failure(45, format!("cannot create Gradle staging: {error}")))?;
    run_checked(
        Command::new("/usr/bin/unzip")
            .arg("-q")
            .arg(archive)
            .arg("-d")
            .arg(&staging_root),
        45,
        "Gradle distribution extraction failed",
    )?;
    let extracted = staging_root.join(format!("gradle-{version}"));
    if !extracted.join("bin/gradle").is_file() {
        return Err(failure(45, "Gradle distribution is malformed"));
    }
    fs::rename(&extracted, &destination)
        .map_err(|error| failure(45, format!("cannot activate Gradle distribution: {error}")))?;
    remove_directory(&staging_root)?;
    if gradle_version_is_ready(config, version) {
        Ok(())
    } else {
        Err(failure(45, "Gradle distribution health check failed"))
    }
}

fn stage_gradle_wrapper(config: &Config, template: &Path, code: u8) -> Result<(), InstallError> {
    stage_gradle_wrapper_for(
        config,
        template,
        &config.manifest.android_gradle_version,
        &config.manifest.android_gradle_sha256,
        code,
    )
}

fn stage_gradle_wrapper_for(
    config: &Config,
    template: &Path,
    version: &str,
    sha256: &str,
    code: u8,
) -> Result<(), InstallError> {
    let parent = template
        .parent()
        .ok_or_else(|| failure(code, "invalid profile template path"))?;
    let wrapper_project = parent.join(format!("wrapper.staging.{}", std::process::id()));
    remove_directory(&wrapper_project)?;
    fs::create_dir_all(&wrapper_project)
        .map_err(|error| failure(code, format!("cannot create wrapper staging: {error}")))?;
    fs::write(
        wrapper_project.join("settings.gradle.kts"),
        "rootProject.name = \"wrapper\"\n",
    )
    .map_err(|error| failure(code, format!("cannot create wrapper build: {error}")))?;
    run_checked(
        Command::new(gradle_root_for(config, version).join("bin/gradle"))
            .arg("--no-daemon")
            .arg("--project-dir")
            .arg(&wrapper_project)
            .arg("wrapper")
            .arg("--gradle-version")
            .arg(version)
            .arg("--distribution-type")
            .arg("bin")
            .env("JAVA_HOME", JAVA_HOME)
            .env("GRADLE_USER_HOME", gradle_user_home(config)),
        code,
        "Gradle wrapper generation failed",
    )?;
    for relative in [
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
    ] {
        let from = wrapper_project.join(relative);
        let to = template.join(relative);
        if let Some(destination_parent) = to.parent() {
            fs::create_dir_all(destination_parent).map_err(|error| {
                failure(code, format!("cannot create wrapper destination: {error}"))
            })?;
        }
        fs::copy(&from, &to)
            .map_err(|error| failure(code, format!("cannot stage Gradle wrapper: {error}")))?;
    }
    set_executable(&template.join("gradlew"))?;
    let wrapper_properties = template.join("gradle/wrapper/gradle-wrapper.properties");
    let properties = fs::read_to_string(&wrapper_properties).map_err(|error| {
        failure(
            code,
            format!("cannot read Gradle wrapper properties: {error}"),
        )
    })?;
    let mut properties = properties
        .lines()
        .filter(|line| {
            !line.starts_with("distributionUrl=") && !line.starts_with("distributionSha256Sum=")
        })
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    properties.push(format!("distributionSha256Sum={}", sha256));
    properties.push(format!(
        "distributionUrl={}",
        gradle_archive_url_for(config, version)
    ));
    fs::write(&wrapper_properties, format!("{}\n", properties.join("\n")))
        .map_err(|error| failure(code, format!("cannot pin Gradle wrapper digest: {error}")))?;
    remove_directory(&wrapper_project)
}

fn install_android_kotlin_profile(config: &Config, sdk: &Path) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "android-kotlin");
    let releases = profiles.join("releases");
    let destination = android_kotlin_profile_root(config);
    if profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }

    let source = config.support_script(ANDROID_KOTLIN_PROFILE_ID);
    if !source.join("release.json").is_file() || !source.join("new-project.mjs").is_file() {
        return Err(failure(
            46,
            "pinned Android Kotlin profile source is missing",
        ));
    }
    fs::create_dir_all(&releases).map_err(|error| {
        failure(
            46,
            format!("cannot create development profiles root: {error}"),
        )
    })?;
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    set_executable(&staging.join("new-project.mjs"))?;

    stage_gradle_wrapper(config, &staging.join("template"), 46)?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    copy_directory(&staging.join("template"), &qualification)?;
    replace_profile_placeholders(
        &qualification,
        "ClawInOne Kotlin Qualification",
        "io.github.boxuechen.clawinone.qualification",
    )?;
    let artifact = qualification.join("app/build/outputs/apk/debug/app-debug.apk");
    run_profile_build(
        config,
        sdk,
        &qualification,
        false,
        47,
        "Android Kotlin profile qualification failed",
    )?;
    run_profile_build(
        config,
        sdk,
        &qualification,
        true,
        47,
        "Android Kotlin profile qualification failed",
    )?;
    if !artifact.is_file() {
        return Err(failure(
            46,
            "qualified Kotlin profile did not produce its APK",
        ));
    }
    let signer = apk_signer_digest(config, sdk, &artifact)?;
    // Publish paths for the activated generation, never for the disposable
    // staging directory that is renamed immediately below.
    let profile_json = render_profile_json(config, sdk, &destination, &signer);
    fs::write(staging.join("profile.json"), profile_json)
        .map_err(|error| failure(46, format!("cannot publish Android profile: {error}")))?;
    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(46, format!("cannot inspect Android profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions).map_err(|error| {
        failure(
            46,
            format!("cannot make Android profile read-only: {error}"),
        )
    })?;
    fs::write(staging.join("qualified"), format!("{signer}\n"))
        .map_err(|error| failure(46, format!("cannot publish Android qualification: {error}")))?;
    remove_directory(&qualification)?;

    fs::rename(&staging, &destination).map_err(|error| {
        failure(
            46,
            format!("cannot activate Android Kotlin profile: {error}"),
        )
    })?;
    activate_profile(&profiles, &destination)
}

fn install_android_native_profile(config: &Config, sdk: &Path) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "android-native");
    let releases = profiles.join("releases");
    let destination = android_native_profile_root(config);
    if native_profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }

    let source = config.support_script(ANDROID_NATIVE_PROFILE_ID);
    if !source.join("release.json").is_file() || !source.join("new-project.mjs").is_file() {
        return Err(failure(
            61,
            "pinned Android Native profile source is missing",
        ));
    }
    fs::create_dir_all(&releases)
        .map_err(|error| failure(61, format!("cannot create Native profile root: {error}")))?;
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    set_executable(&staging.join("new-project.mjs"))?;
    stage_gradle_wrapper(config, &staging.join("template"), 61)?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    copy_directory(&staging.join("template"), &qualification)?;
    replace_text_files(
        &qualification,
        "ClawInOne Vulkan Qualification",
        "io.github.boxuechen.clawinone.vulkanqualification",
    )?;
    let artifact = qualification.join("app/build/outputs/apk/debug/app-debug.apk");
    run_profile_build(
        config,
        sdk,
        &qualification,
        false,
        61,
        "Android Native profile qualification failed",
    )?;
    run_profile_build(
        config,
        sdk,
        &qualification,
        true,
        61,
        "Android Native profile qualification failed",
    )?;
    if !artifact.is_file() {
        return Err(failure(
            61,
            "qualified Android Native profile did not produce its APK",
        ));
    }
    let signer = apk_signer_digest(config, sdk, &artifact)?;
    fs::write(
        staging.join("profile.json"),
        render_native_profile_json(config, sdk, &destination, &signer),
    )
    .map_err(|error| failure(61, format!("cannot publish Native profile: {error}")))?;
    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(61, format!("cannot inspect Native profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions)
        .map_err(|error| failure(61, format!("cannot make Native profile read-only: {error}")))?;
    fs::write(staging.join("qualified"), format!("{signer}\n"))
        .map_err(|error| failure(61, format!("cannot publish Native qualification: {error}")))?;
    remove_directory(&qualification)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(61, format!("cannot activate Native profile: {error}")))?;
    activate_profile(&profiles, &destination)
}

fn install_flutter_sdk(config: &Config, archive: &Path) -> Result<(), InstallError> {
    let destination = flutter_root(config);
    if flutter_sdk_is_ready(config) {
        return Ok(());
    }
    remove_directory(&destination)?;
    let staging_root = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging_root)?;
    fs::create_dir_all(&staging_root)
        .map_err(|error| failure(53, format!("cannot create Flutter staging: {error}")))?;
    run_checked(
        Command::new("/usr/bin/tar")
            .arg("-xJf")
            .arg(archive)
            .arg("-C")
            .arg(&staging_root),
        53,
        "Flutter distribution extraction failed",
    )?;
    let extracted = staging_root.join("flutter");
    if !extracted.join("bin/flutter").is_file() || !extracted.join(".git").is_dir() {
        return Err(failure(53, "Flutter distribution is malformed"));
    }
    let revision = command_output(
        Command::new("/usr/bin/git")
            .arg("-C")
            .arg(&extracted)
            .arg("rev-parse")
            .arg("HEAD"),
        53,
        "cannot inspect Flutter framework revision",
    )?;
    if revision.trim() != config.manifest.flutter_framework_revision {
        return Err(failure(53, "Flutter framework revision mismatch"));
    }

    // The official Linux archive may carry a cache produced on another host
    // architecture. The framework sources are immutable; rebuild only the host
    // cache so Flutter downloads its native ARM64 Dart SDK and tool snapshot.
    remove_directory(&extracted.join("bin/cache/dart-sdk"))?;
    for file in [
        "bin/cache/flutter_tools.snapshot",
        "bin/cache/flutter_tools.stamp",
        "bin/cache/engine-dart-sdk.stamp",
        "bin/cache/flutter.version.json",
    ] {
        remove_path(&extracted.join(file))?;
    }
    fs::rename(&extracted, &destination)
        .map_err(|error| failure(53, format!("cannot activate Flutter SDK: {error}")))?;
    remove_directory(&staging_root)?;
    bootstrap_flutter_host(config)?;
    if flutter_sdk_is_ready(config) {
        Ok(())
    } else {
        Err(failure(53, "staged Flutter SDK failed verification"))
    }
}

fn bootstrap_flutter_host(config: &Config) -> Result<(), InstallError> {
    let sdk = android_sdk_root(config);
    let flutter = flutter_root(config).join("bin/flutter");
    run_checked(
        flutter_command(config, &flutter).arg("--version"),
        54,
        "Flutter ARM64 host bootstrap failed",
    )?;
    let dart_description = command_output(
        Command::new("/usr/bin/file").arg(flutter_root(config).join("bin/cache/dart-sdk/bin/dart")),
        54,
        "cannot inspect Flutter Dart host runtime",
    )?;
    if !dart_description.contains("ARM aarch64") && !dart_description.contains("ARM64") {
        return Err(failure(54, "Flutter Dart host runtime is not ARM64"));
    }
    run_checked(
        flutter_command(config, &flutter)
            .arg("config")
            .arg("--no-analytics")
            .arg("--enable-android")
            .arg("--no-enable-web")
            .arg("--no-enable-linux-desktop")
            .arg("--no-enable-macos-desktop")
            .arg("--no-enable-windows-desktop")
            .arg("--no-enable-ios")
            .arg("--jdk-dir")
            .arg(JAVA_HOME)
            .env("ANDROID_HOME", &sdk)
            .env("ANDROID_SDK_ROOT", &sdk),
        54,
        "Flutter Android configuration failed",
    )
}

fn warm_flutter_android(config: &Config) -> Result<(), InstallError> {
    let flutter = flutter_root(config).join("bin/flutter");
    run_checked(
        flutter_command(config, &flutter)
            .arg("precache")
            .arg("--android"),
        55,
        "Flutter Android artifacts could not be warmed",
    )
}

fn install_flutter_profile(config: &Config) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "flutter");
    let releases = profiles.join("releases");
    let destination = flutter_profile_root(config);
    if flutter_profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }
    let source = config.support_script(FLUTTER_PROFILE_ID);
    if !source.join("release.json").is_file() || !source.join("new-project.mjs").is_file() {
        return Err(failure(56, "pinned Flutter profile source is missing"));
    }
    fs::create_dir_all(&releases)
        .map_err(|error| failure(56, format!("cannot create Flutter profile root: {error}")))?;
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    set_executable(&staging.join("new-project.mjs"))?;

    let profile_json = render_flutter_profile_json(config, &destination);
    fs::write(staging.join("profile.json"), profile_json)
        .map_err(|error| failure(56, format!("cannot publish Flutter profile: {error}")))?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    run_flutter_create(
        config,
        &qualification,
        "ClawInOne Flutter Qualification",
        "io.github.boxuechen.clawinone.flutterqualification",
    )?;
    let artifact = qualification.join("build/app/outputs/flutter-apk/app-debug.apk");
    run_flutter_build(config, &qualification, false)?;
    run_flutter_build(config, &qualification, true)?;
    if !artifact.is_file() {
        return Err(failure(
            56,
            "qualified Flutter profile did not produce its APK",
        ));
    }
    let digest = sha256_file(&artifact)?;
    fs::write(staging.join("qualified"), format!("{digest}\n"))
        .map_err(|error| failure(56, format!("cannot publish Flutter qualification: {error}")))?;
    remove_directory(&qualification)?;

    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(56, format!("cannot inspect Flutter profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions).map_err(|error| {
        failure(
            56,
            format!("cannot make Flutter profile read-only: {error}"),
        )
    })?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(56, format!("cannot activate Flutter profile: {error}")))?;
    activate_profile(&profiles, &destination)
}

fn install_godot_engine(config: &Config, archive: &Path) -> Result<(), InstallError> {
    let destination = godot_root(config);
    if godot_engine_is_ready(config) {
        return Ok(());
    }
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging)
        .map_err(|error| failure(62, format!("cannot create Godot staging: {error}")))?;
    let entry = format!(
        "Godot_v{}-stable_linux.arm64",
        config.manifest.godot_version
    );
    extract_zip_entry(archive, &entry, &staging.join("godot"), 62)?;
    set_executable(&staging.join("godot"))?;
    let expected = format!(
        "{}.{}",
        config.manifest.godot_version, config.manifest.godot_build
    );
    if !godot_binary_is_ready(&staging.join("godot"), &expected) {
        return Err(failure(62, "staged Godot engine failed verification"));
    }
    remove_directory(&destination)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(62, format!("cannot activate Godot engine: {error}")))?;
    if godot_engine_is_ready(config) {
        Ok(())
    } else {
        Err(failure(62, "staged Godot engine failed verification"))
    }
}

fn install_godot_export_templates(config: &Config, archive: &Path) -> Result<(), InstallError> {
    let destination = godot_export_templates_root(config);
    if godot_export_templates_are_ready(config) {
        return Ok(());
    }
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    fs::create_dir_all(&staging).map_err(|error| {
        failure(
            63,
            format!("cannot create Godot export templates staging: {error}"),
        )
    })?;
    extract_zip_entry(
        archive,
        "templates/android_debug.apk",
        &staging.join("android_debug.apk"),
        63,
    )?;
    if !godot_android_template_is_ready(config, &staging.join("android_debug.apk")) {
        return Err(failure(
            63,
            "staged Godot Android debug template failed verification",
        ));
    }
    remove_directory(&destination)?;
    fs::rename(&staging, &destination).map_err(|error| {
        failure(
            63,
            format!("cannot activate Godot export templates: {error}"),
        )
    })?;
    if godot_export_templates_are_ready(config) {
        Ok(())
    } else {
        Err(failure(
            63,
            "staged Godot export templates failed verification",
        ))
    }
}

fn install_godot_android_profile(config: &Config) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "godot-android");
    let releases = profiles.join("releases");
    let destination = godot_android_profile_root(config);
    if godot_profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }
    let source = config.support_script(GODOT_ANDROID_PROFILE_ID);
    if !source.join("release.json").is_file() || !source.join("new-project.mjs").is_file() {
        return Err(failure(
            64,
            "pinned Godot Android profile source is missing",
        ));
    }
    fs::create_dir_all(&releases)
        .map_err(|error| failure(64, format!("cannot create Godot profile root: {error}")))?;
    let state =
        ToolchainStore::new(&config.install_root).godot_state_root(&config.manifest.godot_version);
    for directory in [
        state.join("config"),
        state.join("data"),
        state.join("cache"),
    ] {
        fs::create_dir_all(&directory)
            .map_err(|error| failure(64, format!("cannot create Godot state: {error}")))?;
    }
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    set_executable(&staging.join("new-project.mjs"))?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    copy_directory(&staging.join("template"), &qualification)?;
    replace_godot_placeholders(
        &qualification,
        "ClawInOne Godot Qualification",
        "io.github.boxuechen.clawinone.godotqualification",
        &godot_android_debug_template(config),
    )?;
    fs::create_dir_all(qualification.join("build")).map_err(|error| {
        failure(
            64,
            format!("cannot create Godot qualification build directory: {error}"),
        )
    })?;
    run_godot_import(config, &qualification)?;
    let artifact = qualification.join("build/app-debug.apk");
    run_godot_export(config, &qualification, &artifact)?;
    if !artifact.is_file() {
        return Err(failure(
            64,
            "qualified Godot profile did not produce its APK",
        ));
    }
    let signer = apk_signer_digest(config, &android_sdk_root(config), &artifact)?;
    fs::write(
        staging.join("profile.json"),
        render_godot_profile_json(config, &destination, &signer),
    )
    .map_err(|error| failure(64, format!("cannot publish Godot profile: {error}")))?;
    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(64, format!("cannot inspect Godot profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions)
        .map_err(|error| failure(64, format!("cannot make Godot profile read-only: {error}")))?;
    fs::write(staging.join("qualified"), format!("{signer}\n"))
        .map_err(|error| failure(64, format!("cannot publish Godot qualification: {error}")))?;
    remove_directory(&qualification)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(64, format!("cannot activate Godot profile: {error}")))?;
    activate_profile(&profiles, &destination)
}

fn install_react_native_profile(config: &Config) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "react-native");
    let releases = profiles.join("releases");
    let destination = react_native_profile_root(config);
    if react_native_profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }
    let source = config.support_script(REACT_NATIVE_ANDROID_PROFILE_ID);
    if !source.join("release.json").is_file()
        || !source.join("new-project.mjs").is_file()
        || !source.join("build-project.mjs").is_file()
        || !verify_sha256(
            &source.join("template/package-lock.json"),
            &config.manifest.react_native_package_lock_sha256,
        )
    {
        return Err(failure(
            67,
            "pinned React Native Android profile source is invalid",
        ));
    }
    fs::create_dir_all(&releases).map_err(|error| {
        failure(
            67,
            format!("cannot create React Native profile root: {error}"),
        )
    })?;
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    set_executable(&staging.join("new-project.mjs"))?;
    set_executable(&staging.join("build-project.mjs"))?;
    stage_gradle_wrapper_for(
        config,
        &staging.join("template/android"),
        &config.manifest.react_native_gradle_version,
        &config.manifest.react_native_gradle_sha256,
        67,
    )?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    copy_directory(&staging.join("template"), &qualification)?;
    replace_react_native_placeholders(
        config,
        &qualification,
        "ClawInOne React Native Qualification",
        "io.github.boxuechen.clawinone.reactnativequalification",
    )?;
    run_react_native_npm(
        config,
        &qualification,
        true,
        &[
            "ci",
            "--offline",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
    )?;
    run_react_native_npm(config, &qualification, true, &["run", "typecheck"])?;
    run_react_native_npm(config, &qualification, true, &["test", "--", "--runInBand"])?;
    run_react_native_build(config, &qualification, false)?;
    run_react_native_build(config, &qualification, true)?;
    let artifact = qualification.join("android/app/build/outputs/apk/debug/app-debug.apk");
    verify_react_native_apk(&artifact)?;
    let signer = apk_signer_digest(config, &android_sdk_root(config), &artifact)?;
    fs::write(
        staging.join("profile.json"),
        render_react_native_profile_json(config, &destination, &signer),
    )
    .map_err(|error| failure(67, format!("cannot publish React Native profile: {error}")))?;
    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(67, format!("cannot inspect React Native profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions).map_err(|error| {
        failure(
            67,
            format!("cannot make React Native profile read-only: {error}"),
        )
    })?;
    fs::write(staging.join("qualified"), format!("{signer}\n")).map_err(|error| {
        failure(
            67,
            format!("cannot publish React Native qualification: {error}"),
        )
    })?;
    remove_directory(&qualification)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(67, format!("cannot activate React Native profile: {error}")))?;
    activate_profile(&profiles, &destination)
}

fn install_web_profile(config: &Config) -> Result<(), InstallError> {
    let profiles = profile_family_root(config, "web-development");
    let releases = profiles.join("releases");
    let destination = web_profile_root(config);
    if web_profile_is_ready(config, &destination) {
        return activate_profile(&profiles, &destination);
    }
    let source = config.support_script(WEB_DEVELOPMENT_PROFILE_ID);
    if !source.join("release.json").is_file()
        || !source.join("new-project.mjs").is_file()
        || !source.join("build-project.mjs").is_file()
        || !source.join("serve-project.mjs").is_file()
        || !verify_sha256(
            &source.join("template/package-lock.json"),
            &config.manifest.web_package_lock_sha256,
        )
    {
        return Err(failure(
            69,
            "pinned Web Development profile source is invalid",
        ));
    }
    fs::create_dir_all(&releases)
        .map_err(|error| failure(69, format!("cannot create Web profile root: {error}")))?;
    remove_directory(&destination)?;
    let staging = destination.with_extension(format!("staging.{}", std::process::id()));
    remove_directory(&staging)?;
    copy_directory(&source, &staging)?;
    for script in ["new-project.mjs", "build-project.mjs", "serve-project.mjs"] {
        set_executable(&staging.join(script))?;
    }
    fs::write(
        staging.join("profile.json"),
        render_web_profile_json(config, &destination),
    )
    .map_err(|error| failure(69, format!("cannot publish Web profile: {error}")))?;
    let mut permissions = fs::metadata(staging.join("profile.json"))
        .map_err(|error| failure(69, format!("cannot inspect Web profile: {error}")))?
        .permissions();
    permissions.set_mode(0o444);
    fs::set_permissions(staging.join("profile.json"), permissions)
        .map_err(|error| failure(69, format!("cannot make Web profile read-only: {error}")))?;

    let qualification = profiles.join(format!("qualification.staging.{}", std::process::id()));
    remove_directory(&qualification)?;
    copy_directory(&staging.join("template"), &qualification)?;
    for (file, from, to) in [
        (
            "index.html",
            "__APP_NAME_HTML__",
            "ClawInOne Web Qualification",
        ),
        (
            "src/main.tsx",
            "__APP_NAME_JSON__",
            "\"ClawInOne Web Qualification\"",
        ),
    ] {
        let path = qualification.join(file);
        let content = fs::read_to_string(&path).map_err(|error| {
            failure(69, format!("cannot read Web qualification source: {error}"))
        })?;
        fs::write(path, content.replace(from, to)).map_err(|error| {
            failure(
                69,
                format!("cannot write Web qualification source: {error}"),
            )
        })?;
    }
    run_web_npm(
        config,
        &qualification,
        true,
        &[
            "ci",
            "--offline",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
    )?;
    run_web_npm(config, &qualification, true, &["run", "build"])?;
    if !qualification.join("dist/index.html").is_file() {
        return Err(failure(
            69,
            "qualified Web profile did not produce its build",
        ));
    }
    fs::write(
        staging.join("qualified"),
        format!("{}\n", config.manifest.web_package_lock_sha256),
    )
    .map_err(|error| failure(69, format!("cannot publish Web qualification: {error}")))?;
    remove_directory(&qualification)?;
    fs::rename(&staging, &destination)
        .map_err(|error| failure(69, format!("cannot activate Web profile: {error}")))?;
    activate_profile(&profiles, &destination)
}

fn verify_web_distribution(config: &Config, root: &Path) -> Result<(), InstallError> {
    for (package, version) in [
        ("react", &config.manifest.web_react_version),
        ("react-dom", &config.manifest.web_react_dom_version),
        ("vite", &config.manifest.web_vite_version),
        ("typescript", &config.manifest.web_typescript_version),
    ] {
        let manifest =
            fs::read_to_string(root.join("node_modules").join(package).join("package.json"))
                .map_err(|error| {
                    failure(
                        68,
                        format!("cannot inspect Web dependency {package}: {error}"),
                    )
                })?;
        if !manifest.contains(&format!("\"version\": \"{version}\"")) {
            return Err(failure(
                68,
                format!("Web dependency {package} version mismatch"),
            ));
        }
    }
    Ok(())
}

fn run_web_npm(
    config: &Config,
    directory: &Path,
    offline: bool,
    arguments: &[&str],
) -> Result<(), InstallError> {
    let root = general_node_root(config);
    let mut command = Command::new(root.join("bin/npm"));
    command
        .current_dir(directory)
        .args(arguments)
        .env("PATH", developer_path(config))
        .env(
            "npm_config_cache",
            ToolchainStore::new(&config.install_root).web_npm_cache(),
        )
        .env("npm_config_audit", "false")
        .env("npm_config_fund", "false")
        .env("CI", "true");
    if offline {
        command.env("npm_config_offline", "true");
    }
    run_checked(&mut command, 68, "Web npm operation failed")
}

fn render_web_profile_json(config: &Config, profile: &Path) -> String {
    let store = ToolchainStore::new(&config.install_root);
    let active = profile_family_root(config, "web-development").join("active");
    let builder = active.join("build-project.mjs");
    let server = active.join("serve-project.mjs");
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"{}\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"nodeVersion\": \"{}\",\n",
            "  \"npmVersion\": \"{}\",\n",
            "  \"reactVersion\": \"{}\",\n",
            "  \"reactDomVersion\": \"{}\",\n",
            "  \"viteVersion\": \"{}\",\n",
            "  \"typescriptVersion\": \"{}\",\n",
            "  \"nodeBinary\": \"{}\",\n",
            "  \"npmBinary\": \"{}\",\n",
            "  \"npmCache\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"builder\": \"{}\",\n",
            "  \"server\": \"{}\",\n",
            "  \"buildCommand\": \"{} --project-dir .\",\n",
            "  \"artifact\": \"dist/index.html\",\n",
            "  \"serverHost\": \"127.0.0.1\",\n",
            "  \"productionBuild\": true,\n",
            "  \"cdpRequired\": false\n",
            "}}\n"
        ),
        WEB_DEVELOPMENT_PROFILE_ID,
        WEB_DEVELOPMENT_PROFILE_GENERATION,
        config.manifest.general_node_version,
        config.manifest.general_node_npm_version,
        config.manifest.web_react_version,
        config.manifest.web_react_dom_version,
        config.manifest.web_vite_version,
        config.manifest.web_typescript_version,
        json_path(&general_node_root(config).join("bin/node")),
        json_path(&general_node_root(config).join("bin/npm")),
        json_path(&store.web_npm_cache()),
        json_path(&profile.join("new-project.mjs")),
        json_path(&builder),
        json_path(&server),
        json_path(&builder),
    )
}

fn verify_react_native_distribution(config: &Config, root: &Path) -> Result<(), InstallError> {
    let package = fs::read_to_string(root.join("node_modules/react-native/package.json"))
        .map_err(|error| failure(66, format!("cannot inspect React Native package: {error}")))?;
    let react = fs::read_to_string(root.join("node_modules/react/package.json"))
        .map_err(|error| failure(66, format!("cannot inspect React package: {error}")))?;
    let cli =
        fs::read_to_string(root.join("node_modules/@react-native-community/cli/package.json"))
            .map_err(|error| failure(66, format!("cannot inspect React Native CLI: {error}")))?;
    for (content, version) in [
        (&package, &config.manifest.react_native_version),
        (&react, &config.manifest.react_native_react_version),
        (&cli, &config.manifest.react_native_community_cli_version),
    ] {
        if !content.contains(&format!("\"version\": \"{version}\"")) {
            return Err(failure(66, "React Native dependency version mismatch"));
        }
    }
    let hermes = root.join("node_modules/hermes-compiler/hermesc/linux64-bin/hermesc");
    if !verify_sha256(
        &hermes,
        &config.manifest.react_native_hermes_compiler_sha256,
    ) {
        return Err(failure(66, "Hermes compiler digest mismatch"));
    }
    let description = command_output(
        Command::new("/usr/bin/file").arg(&hermes),
        66,
        "cannot inspect Hermes compiler",
    )?;
    if !description.contains("x86-64") || !command_succeeds(Command::new(&hermes).arg("-version")) {
        return Err(failure(
            66,
            "Hermes compiler is not executable through x86_64 binfmt",
        ));
    }
    Ok(())
}

fn run_react_native_npm(
    config: &Config,
    directory: &Path,
    offline: bool,
    arguments: &[&str],
) -> Result<(), InstallError> {
    let root = general_node_root(config);
    let mut command = general_node_command(config, &root.join("bin/npm"));
    command.current_dir(directory).args(arguments);
    if offline {
        command.env("npm_config_offline", "true");
    }
    run_checked(&mut command, 66, "React Native npm operation failed")
}

fn run_react_native_build(
    config: &Config,
    project: &Path,
    offline: bool,
) -> Result<(), InstallError> {
    let sdk = android_sdk_root(config);
    let node = general_node_root(config).join("bin/node");
    let mut command = Command::new(project.join("android/gradlew"));
    command
        .current_dir(project.join("android"))
        .arg("--no-daemon")
        .arg("--max-workers=1");
    if offline {
        command.arg("--offline");
    }
    command
        .arg(":app:assembleDebug")
        .arg("-PreactNativeArchitectures=arm64-v8a")
        .env("JAVA_HOME", JAVA_HOME)
        .env("ANDROID_HOME", &sdk)
        .env("ANDROID_SDK_ROOT", &sdk)
        .env("GRADLE_USER_HOME", gradle_user_home(config))
        .env("PATH", developer_path(config))
        .env("NODE_BINARY", node);
    run_checked(
        &mut command,
        67,
        "React Native Android profile qualification failed",
    )
}

fn verify_react_native_apk(apk: &Path) -> Result<(), InstallError> {
    if !apk.is_file() {
        return Err(failure(
            67,
            "qualified React Native profile did not produce its APK",
        ));
    }
    let entries = command_output(
        Command::new("/usr/bin/unzip").arg("-Z1").arg(apk),
        67,
        "cannot inspect qualified React Native APK",
    )?;
    for entry in [
        "assets/index.android.bundle",
        "lib/arm64-v8a/libreactnative.so",
        "lib/arm64-v8a/libhermesvm.so",
    ] {
        if !entries.lines().any(|value| value == entry) {
            return Err(failure(
                67,
                format!("qualified React Native APK is missing {entry}"),
            ));
        }
    }
    if entries
        .lines()
        .any(|entry| entry.starts_with("lib/") && !entry.starts_with("lib/arm64-v8a/"))
    {
        return Err(failure(
            67,
            "qualified React Native APK contains an unexpected ABI",
        ));
    }
    Ok(())
}

fn replace_react_native_placeholders(
    config: &Config,
    root: &Path,
    app_name: &str,
    package_name: &str,
) -> Result<(), InstallError> {
    let source = root.join("android/app/src/main/java/starter");
    let target = root
        .join("android/app/src/main/java")
        .join(package_name.replace('.', "/"));
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            failure(
                67,
                format!("cannot create React Native source root: {error}"),
            )
        })?;
    }
    fs::rename(source, target).map_err(|error| {
        failure(
            67,
            format!("cannot materialize React Native source: {error}"),
        )
    })?;
    replace_react_native_text_files(
        root,
        app_name,
        package_name,
        &general_node_root(config).join("bin/node"),
    )
}

fn replace_react_native_text_files(
    root: &Path,
    app_name: &str,
    package_name: &str,
    node: &Path,
) -> Result<(), InstallError> {
    for entry in fs::read_dir(root).map_err(|error| {
        failure(
            67,
            format!("cannot read React Native profile tree: {error}"),
        )
    })? {
        let entry = entry
            .map_err(|error| failure(67, format!("cannot read React Native entry: {error}")))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| failure(67, format!("cannot inspect React Native entry: {error}")))?;
        if metadata.file_type().is_symlink() {
            return Err(failure(
                67,
                "React Native profile must not contain symbolic links",
            ));
        }
        if metadata.is_dir() {
            replace_react_native_text_files(&path, app_name, package_name, node)?;
        } else if metadata.is_file()
            && !matches!(
                path.file_name().and_then(|value| value.to_str()),
                Some("package-lock.json")
            )
        {
            let Ok(content) = fs::read_to_string(&path) else {
                continue;
            };
            let replaced = content
                .replace("__PACKAGE_NAME__", package_name)
                .replace("__DEVELOPER_NODE__", &node.display().to_string())
                .replace("__APP_NAME_JSON__", &json_string(app_name))
                .replace("__APP_NAME_XML__", &xml_text(app_name));
            fs::write(&path, replaced).map_err(|error| {
                failure(
                    67,
                    format!("cannot write React Native profile source: {error}"),
                )
            })?;
        }
    }
    Ok(())
}

fn render_react_native_profile_json(config: &Config, profile: &Path, signer: &str) -> String {
    let store = ToolchainStore::new(&config.install_root);
    let builder = profile_family_root(config, "react-native")
        .join("active")
        .join("build-project.mjs");
    let build_command = format!("{} --project-dir .", json_path(&builder));
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"{}\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"targetPlatform\": \"android-arm64\",\n",
            "  \"reactNativeVersion\": \"{}\",\n",
            "  \"reactVersion\": \"{}\",\n",
            "  \"nodeVersion\": \"{}\",\n",
            "  \"javaHome\": \"{}\",\n",
            "  \"androidSdkRoot\": \"{}\",\n",
            "  \"nodeBinary\": \"{}\",\n",
            "  \"npmBinary\": \"{}\",\n",
            "  \"npmCache\": \"{}\",\n",
            "  \"gradleUserHome\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"builder\": \"{}\",\n",
            "  \"buildCommand\": \"{}\",\n",
            "  \"artifact\": \"android/app/build/outputs/apk/debug/app-debug.apk\",\n",
            "  \"debugSignerSha256\": \"{}\",\n",
            "  \"newArchitecture\": true,\n",
            "  \"hermes\": true,\n",
            "  \"metroRequired\": false\n",
            "}}\n"
        ),
        REACT_NATIVE_ANDROID_PROFILE_ID,
        REACT_NATIVE_ANDROID_PROFILE_GENERATION,
        config.manifest.react_native_version,
        config.manifest.react_native_react_version,
        config.manifest.general_node_version,
        json_path(Path::new(JAVA_HOME)),
        json_path(&android_sdk_root(config)),
        json_path(&general_node_root(config).join("bin/node")),
        json_path(&general_node_root(config).join("bin/npm")),
        json_path(&store.react_native_npm_cache()),
        json_path(&gradle_user_home(config)),
        json_path(&profile.join("new-project.mjs")),
        json_path(&builder),
        build_command,
        signer,
    )
}

fn run_godot_import(config: &Config, project: &Path) -> Result<(), InstallError> {
    run_godot_checked(
        godot_command(config)
            .arg("--headless")
            .arg("--import")
            .arg("--path")
            .arg(project),
        64,
        "Godot Android profile import failed",
    )
}

fn run_godot_export(config: &Config, project: &Path, artifact: &Path) -> Result<(), InstallError> {
    run_godot_checked(
        godot_command(config)
            .arg("--headless")
            .arg("--path")
            .arg(project)
            .arg("--export-debug")
            .arg("Android")
            .arg(artifact),
        64,
        "Godot Android profile export failed",
    )
}

fn run_godot_checked(command: &mut Command, code: u8, context: &str) -> Result<(), InstallError> {
    let output = command
        .output()
        .map_err(|error| failure(code, format!("{context}: {error}")))?;
    let diagnostics = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let reported_error = godot_reported_error(&diagnostics);
    if output.status.success() && reported_error.is_none() {
        Ok(())
    } else {
        let detail = reported_error.unwrap_or_else(|| "no Godot error detail".to_string());
        Err(failure(
            code,
            format!("{context}: {}; {detail}", output.status),
        ))
    }
}

fn godot_reported_error(diagnostics: &str) -> Option<String> {
    diagnostics
        .lines()
        .map(|line| line.trim_start_matches(|character: char| character.is_ascii_whitespace()))
        .find(|line| line.contains("SCRIPT ERROR") || line.starts_with("ERROR:"))
        .map(|line| {
            line.chars()
                .filter(|character| !character.is_control())
                .take(512)
                .collect()
        })
}

fn godot_command(config: &Config) -> Command {
    let state =
        ToolchainStore::new(&config.install_root).godot_state_root(&config.manifest.godot_version);
    let sdk = android_sdk_root(config);
    let mut command = Command::new(godot_root(config).join("godot"));
    command
        .env("HOME", "/home/droid")
        .env("XDG_CONFIG_HOME", state.join("config"))
        .env("XDG_DATA_HOME", state.join("data"))
        .env("XDG_CACHE_HOME", state.join("cache"))
        .env("JAVA_HOME", JAVA_HOME)
        .env("ANDROID_HOME", &sdk)
        .env("ANDROID_SDK_ROOT", &sdk);
    command
}

fn run_flutter_create(
    config: &Config,
    project: &Path,
    app_name: &str,
    package_name: &str,
) -> Result<(), InstallError> {
    let project_name = package_name.rsplit('.').next().unwrap_or("flutterapp");
    let parent = project
        .parent()
        .ok_or_else(|| failure(56, "invalid Flutter project path"))?;
    fs::create_dir_all(parent)
        .map_err(|error| failure(56, format!("cannot create Flutter project root: {error}")))?;
    run_checked(
        flutter_command(config, &flutter_root(config).join("bin/flutter"))
            .arg("create")
            .arg("--empty")
            .arg("--platforms=android")
            .arg("--android-language=kotlin")
            .arg("--org")
            .arg(
                package_name
                    .rsplit_once('.')
                    .map_or(package_name, |value| value.0),
            )
            .arg("--project-name")
            .arg(project_name)
            .arg("--no-pub")
            .arg(project),
        56,
        "Flutter profile project creation failed",
    )?;
    fs::write(
        project.join("lib/main.dart"),
        format!(
            "import 'package:flutter/material.dart';\n\nvoid main() => runApp(const MaterialApp(home: Scaffold(body: Center(child: Text({:?})))));\n",
            app_name
        ),
    )
    .map_err(|error| failure(56, format!("cannot write Flutter qualification: {error}")))?;
    fs::write(
        project.join("android/gradle.properties"),
        "org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8\norg.gradle.workers.max=1\norg.gradle.parallel=false\norg.gradle.daemon=false\nkotlin.compiler.execution.strategy=in-process\nandroid.useAndroidX=true\n",
    )
    .map_err(|error| failure(56, format!("cannot configure Flutter qualification: {error}")))?;
    run_checked(
        flutter_command(config, &flutter_root(config).join("bin/flutter"))
            .current_dir(project)
            .arg("pub")
            .arg("get"),
        56,
        "Flutter profile dependencies could not be warmed",
    )
}

fn run_flutter_build(config: &Config, project: &Path, warmed: bool) -> Result<(), InstallError> {
    let mut command = flutter_command(config, &flutter_root(config).join("bin/flutter"));
    command
        .current_dir(project)
        .arg("build")
        .arg("apk")
        .arg("--debug")
        .arg("--target-platform")
        .arg("android-arm64")
        .arg("--no-pub");
    if warmed {
        command.env("PUB_CACHE", flutter_pub_cache(config));
    }
    run_checked(&mut command, 56, "Flutter profile qualification failed")
}

fn run_profile_build(
    config: &Config,
    sdk: &Path,
    project: &Path,
    offline: bool,
    failure_code: u8,
    failure_context: &str,
) -> Result<(), InstallError> {
    let mut command = Command::new(project.join("gradlew"));
    command
        .current_dir(project)
        .arg("--no-daemon")
        .arg("--max-workers=2");
    if offline {
        command.arg("--offline");
    }
    command
        .arg(":app:assembleDebug")
        .env("JAVA_HOME", JAVA_HOME)
        .env("ANDROID_HOME", sdk)
        .env("ANDROID_SDK_ROOT", sdk)
        .env("GRADLE_USER_HOME", gradle_user_home(config));
    run_checked(&mut command, failure_code, failure_context)
}

fn apk_signer_digest(config: &Config, sdk: &Path, apk: &Path) -> Result<String, InstallError> {
    let output = Command::new(sdk.join(format!(
        "build-tools/{}/apksigner",
        config.manifest.android_build_tools
    )))
    .arg("verify")
    .arg("--print-certs")
    .arg(apk)
    .env("JAVA_HOME", JAVA_HOME)
    .output()
    .map_err(|error| failure(47, format!("cannot inspect qualified APK signer: {error}")))?;
    if !output.status.success() {
        return Err(failure(47, "qualified APK signer verification failed"));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .lines()
        .find_map(|line| {
            line.split_once("certificate SHA-256 digest:")
                .map(|(_, value)| value.trim())
        })
        .filter(|value| value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .map(str::to_ascii_lowercase)
        .ok_or_else(|| failure(47, "qualified APK signer digest is missing"))
}

fn replace_profile_placeholders(
    root: &Path,
    app_name: &str,
    package_name: &str,
) -> Result<(), InstallError> {
    let placeholder = root.join("app/src/main/java/starter");
    let package_path = package_name.replace('.', "/");
    let target = root.join("app/src/main/java").join(&package_path);
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            failure(
                46,
                format!("cannot create qualification source root: {error}"),
            )
        })?;
    }
    fs::rename(&placeholder, &target).map_err(|error| {
        failure(
            46,
            format!("cannot materialize qualification source: {error}"),
        )
    })?;
    replace_text_files(root, app_name, package_name)
}

fn replace_text_files(root: &Path, app_name: &str, package_name: &str) -> Result<(), InstallError> {
    for entry in fs::read_dir(root)
        .map_err(|error| failure(46, format!("cannot read profile tree: {error}")))?
    {
        let entry =
            entry.map_err(|error| failure(46, format!("cannot read profile entry: {error}")))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| failure(46, format!("cannot inspect profile entry: {error}")))?;
        if metadata.file_type().is_symlink() {
            return Err(failure(
                46,
                "profile source must not contain symbolic links",
            ));
        }
        if metadata.is_dir() {
            replace_text_files(&path, app_name, package_name)?;
        } else if matches!(
            path.extension().and_then(|value| value.to_str()),
            Some("kt" | "kts" | "xml" | "properties")
        ) {
            let content = fs::read_to_string(&path)
                .map_err(|error| failure(46, format!("cannot read profile source: {error}")))?;
            let replaced = content
                .replace("__PACKAGE_NAME__", package_name)
                .replace("__PROJECT_NAME__", app_name)
                .replace("__APP_LABEL__", app_name);
            fs::write(&path, replaced)
                .map_err(|error| failure(46, format!("cannot write profile source: {error}")))?;
        }
    }
    Ok(())
}

fn replace_godot_placeholders(
    root: &Path,
    app_name: &str,
    package_name: &str,
    android_debug_template: &Path,
) -> Result<(), InstallError> {
    for entry in fs::read_dir(root)
        .map_err(|error| failure(64, format!("cannot read Godot profile tree: {error}")))?
    {
        let entry = entry
            .map_err(|error| failure(64, format!("cannot read Godot profile entry: {error}")))?;
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| failure(64, format!("cannot inspect Godot profile entry: {error}")))?;
        if metadata.file_type().is_symlink() {
            return Err(failure(
                64,
                "Godot profile source must not contain symbolic links",
            ));
        }
        if metadata.is_dir() {
            replace_godot_placeholders(&path, app_name, package_name, android_debug_template)?;
        } else if metadata.is_file() {
            let content = fs::read_to_string(&path).map_err(|error| {
                failure(64, format!("cannot read Godot profile source: {error}"))
            })?;
            let replaced = content
                .replace("__PROJECT_NAME__", app_name)
                .replace("__APP_NAME__", app_name)
                .replace("__PACKAGE_NAME__", package_name)
                .replace(
                    "__ANDROID_DEBUG_TEMPLATE__",
                    &android_debug_template.display().to_string(),
                );
            fs::write(&path, replaced).map_err(|error| {
                failure(64, format!("cannot write Godot profile source: {error}"))
            })?;
        }
    }
    Ok(())
}

fn validate_zip_archive(path: &Path, code: u8, label: &str) -> Result<(), InstallError> {
    let entries = command_output(
        Command::new("/usr/bin/unzip").arg("-Z1").arg(path),
        code,
        &format!("cannot inspect {label} archive"),
    )?;
    if entries.lines().next().is_none()
        || entries.lines().any(|entry| {
            entry.starts_with('/')
                || entry.contains('\\')
                || Path::new(entry)
                    .components()
                    .any(|component| component == std::path::Component::ParentDir)
        })
    {
        return Err(failure(
            code,
            format!("{label} archive contains unsafe paths"),
        ));
    }
    Ok(())
}

fn extract_zip_entry(
    archive: &Path,
    entry: &str,
    destination: &Path,
    code: u8,
) -> Result<(), InstallError> {
    let parent = destination
        .parent()
        .ok_or_else(|| failure(code, "invalid archive destination"))?;
    fs::create_dir_all(parent)
        .map_err(|error| failure(code, format!("cannot create archive destination: {error}")))?;
    let output = File::create(destination)
        .map_err(|error| failure(code, format!("cannot create archive output: {error}")))?;
    let status = Command::new("/usr/bin/unzip")
        .arg("-p")
        .arg(archive)
        .arg(entry)
        .stdout(Stdio::from(output))
        .stderr(Stdio::inherit())
        .status()
        .map_err(|error| failure(code, format!("cannot extract archive entry: {error}")))?;
    if status.success() {
        Ok(())
    } else {
        Err(failure(
            code,
            format!("archive entry extraction failed: {status}"),
        ))
    }
}

fn copy_directory(source: &Path, destination: &Path) -> Result<(), InstallError> {
    fs::create_dir_all(destination)
        .map_err(|error| failure(46, format!("cannot create profile directory: {error}")))?;
    for entry in fs::read_dir(source)
        .map_err(|error| failure(46, format!("cannot read profile source: {error}")))?
    {
        let entry =
            entry.map_err(|error| failure(46, format!("cannot read profile entry: {error}")))?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        let metadata = fs::symlink_metadata(&source_path)
            .map_err(|error| failure(46, format!("cannot inspect profile source: {error}")))?;
        if metadata.file_type().is_symlink() {
            return Err(failure(
                46,
                "profile source must not contain symbolic links",
            ));
        }
        if metadata.is_dir() {
            copy_directory(&source_path, &destination_path)?;
        } else if metadata.is_file() {
            fs::copy(&source_path, &destination_path)
                .map_err(|error| failure(46, format!("cannot copy profile source: {error}")))?;
        } else {
            return Err(failure(46, "profile source contains an unsupported entry"));
        }
    }
    Ok(())
}

fn set_executable(path: &Path) -> Result<(), InstallError> {
    let mut permissions = fs::metadata(path)
        .map_err(|error| failure(46, format!("cannot inspect executable: {error}")))?
        .permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)
        .map_err(|error| failure(46, format!("cannot mark executable: {error}")))
}

fn activate_profile(profiles: &Path, destination: &Path) -> Result<(), InstallError> {
    let active = profiles.join("active");
    let next = profiles.join(format!("active.next.{}", std::process::id()));
    remove_path(&next)?;
    symlink(destination, &next)
        .map_err(|error| failure(46, format!("cannot stage active profile: {error}")))?;
    fs::rename(&next, &active)
        .map_err(|error| failure(46, format!("cannot activate profile: {error}")))
}

fn render_profile_json(config: &Config, sdk: &Path, profile: &Path, signer: &str) -> String {
    let gradle = gradle_root(config).join("bin/gradle");
    let node = config.install_root.join(format!(
        "runtimes/node-v{}-linux-arm64/bin/node",
        config.manifest.node_version
    ));
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"{}\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"javaHome\": \"{}\",\n",
            "  \"androidSdkRoot\": \"{}\",\n",
            "  \"gradle\": \"{}\",\n",
            "  \"gradleUserHome\": \"{}\",\n",
            "  \"node\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"buildCommand\": \"./gradlew --offline --max-workers=2 :app:assembleDebug\",\n",
            "  \"artifact\": \"app/build/outputs/apk/debug/app-debug.apk\",\n",
            "  \"debugSignerSha256\": \"{}\",\n",
            "  \"warmInputs\": [\"gradle-wrapper\", \"agp\", \"kotlin\", \"compose\"]\n",
            "}}\n"
        ),
        ANDROID_KOTLIN_PROFILE_ID,
        ANDROID_KOTLIN_PROFILE_GENERATION,
        json_path(Path::new(JAVA_HOME)),
        json_path(sdk),
        json_path(&gradle),
        json_path(&gradle_user_home(config)),
        json_path(&node),
        json_path(&profile.join("new-project.mjs")),
        signer,
    )
}

fn render_native_profile_json(config: &Config, sdk: &Path, profile: &Path, signer: &str) -> String {
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"{}\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"javaHome\": \"{}\",\n",
            "  \"androidSdkRoot\": \"{}\",\n",
            "  \"androidNdk\": \"{}\",\n",
            "  \"cmake\": \"{}\",\n",
            "  \"abi\": \"arm64-v8a\",\n",
            "  \"gradleUserHome\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"buildCommand\": \"./gradlew --offline --max-workers=2 :app:assembleDebug\",\n",
            "  \"artifact\": \"app/build/outputs/apk/debug/app-debug.apk\",\n",
            "  \"debugSignerSha256\": \"{}\"\n",
            "}}\n"
        ),
        ANDROID_NATIVE_PROFILE_ID,
        ANDROID_NATIVE_PROFILE_GENERATION,
        json_path(Path::new(JAVA_HOME)),
        json_path(sdk),
        config.manifest.android_native_ndk,
        config.manifest.android_native_cmake,
        json_path(&gradle_user_home(config)),
        json_path(&profile.join("new-project.mjs")),
        signer,
    )
}

fn json_path(path: &Path) -> String {
    path.display()
        .to_string()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
}

fn json_string(value: &str) -> String {
    format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "\\n")
            .replace('\r', "\\r")
    )
}

fn xml_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let wrapper_properties =
        fs::read_to_string(profile.join("template/gradle/wrapper/gradle-wrapper.properties"))
            .unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains(&format!("\"profileId\": \"{ANDROID_KOTLIN_PROFILE_ID}\""))
        && profile_json.contains(&format!(
            "\"generation\": {ANDROID_KOTLIN_PROFILE_GENERATION}"
        ))
        && profile_json.contains("\"status\": \"ready\"")
        && release_json.contains(&format!(
            "\"gradle\": \"{}\"",
            config.manifest.android_gradle_version
        ))
        && wrapper_properties.contains(&format!(
            "distributionSha256Sum={}",
            config.manifest.android_gradle_sha256
        ))
        && wrapper_properties.contains(&format!("distributionUrl={}", gradle_archive_url(config)))
        && profile_read_only
        && profile_sources_match(&config.support_script(ANDROID_KOTLIN_PROFILE_ID), profile)
        && profile.join("qualified").is_file()
        && profile.join("new-project.mjs").is_file()
        && profile.join("template/gradlew").is_file()
        && profile
            .join("template/gradle/wrapper/gradle-wrapper.jar")
            .is_file()
}

fn native_profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let wrapper_properties =
        fs::read_to_string(profile.join("template/gradle/wrapper/gradle-wrapper.properties"))
            .unwrap_or_default();
    let qualified = fs::read_to_string(profile.join("qualified")).unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains(&format!("\"profileId\": \"{ANDROID_NATIVE_PROFILE_ID}\""))
        && profile_json.contains(&format!(
            "\"generation\": {ANDROID_NATIVE_PROFILE_GENERATION}"
        ))
        && profile_json.contains("\"status\": \"ready\"")
        && profile_json.contains(&format!(
            "\"androidNdk\": \"{}\"",
            config.manifest.android_native_ndk
        ))
        && profile_json.contains(&format!(
            "\"cmake\": \"{}\"",
            config.manifest.android_native_cmake
        ))
        && profile_json.contains("\"abi\": \"arm64-v8a\"")
        && release_json.contains(&format!(
            "\"androidNdk\": \"{}\"",
            config.manifest.android_native_ndk
        ))
        && release_json.contains(&format!(
            "\"cmake\": \"{}\"",
            config.manifest.android_native_cmake
        ))
        && wrapper_properties.contains(&format!(
            "distributionSha256Sum={}",
            config.manifest.android_gradle_sha256
        ))
        && qualified.trim().len() == 64
        && qualified
            .trim()
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        && profile_read_only
        && profile_sources_match(&config.support_script(ANDROID_NATIVE_PROFILE_ID), profile)
        && profile.join("new-project.mjs").is_file()
        && profile.join("template/gradlew").is_file()
        && profile
            .join("template/gradle/wrapper/gradle-wrapper.jar")
            .is_file()
}

fn react_native_profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let wrapper_properties = fs::read_to_string(
        profile.join("template/android/gradle/wrapper/gradle-wrapper.properties"),
    )
    .unwrap_or_default();
    let qualified = fs::read_to_string(profile.join("qualified")).unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains(&format!(
        "\"profileId\": \"{REACT_NATIVE_ANDROID_PROFILE_ID}\""
    )) && profile_json.contains(&format!(
        "\"generation\": {REACT_NATIVE_ANDROID_PROFILE_GENERATION}"
    )) && profile_json.contains("\"status\": \"ready\"")
        && profile_json.contains("\"targetPlatform\": \"android-arm64\"")
        && profile_json.contains(&format!(
            "\"reactNativeVersion\": \"{}\"",
            config.manifest.react_native_version
        ))
        && profile_json.contains(&format!(
            "\"nodeVersion\": \"{}\"",
            config.manifest.general_node_version
        ))
        && profile_json.contains("\"newArchitecture\": true")
        && profile_json.contains("\"hermes\": true")
        && profile_json.contains("\"metroRequired\": false")
        && release_json.contains(&format!(
            "\"packageLockSha256\": \"{}\"",
            config.manifest.react_native_package_lock_sha256
        ))
        && wrapper_properties.contains(&format!(
            "distributionSha256Sum={}",
            config.manifest.react_native_gradle_sha256
        ))
        && wrapper_properties.contains(&format!(
            "distributionUrl={}",
            gradle_archive_url_for(config, &config.manifest.react_native_gradle_version)
        ))
        && qualified.trim().len() == 64
        && qualified
            .trim()
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        && profile_read_only
        && profile_sources_match(
            &config.support_script(REACT_NATIVE_ANDROID_PROFILE_ID),
            profile,
        )
        && profile.join("new-project.mjs").is_file()
        && profile.join("build-project.mjs").is_file()
        && profile.join("template/android/gradlew").is_file()
        && profile
            .join("template/android/gradle/wrapper/gradle-wrapper.jar")
            .is_file()
        && general_node_runtime_is_ready(config)
        && react_native_distribution_is_ready(config)
}

fn web_profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let qualified = fs::read_to_string(profile.join("qualified")).unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains(&format!("\"profileId\": \"{WEB_DEVELOPMENT_PROFILE_ID}\""))
        && profile_json.contains(&format!(
            "\"generation\": {WEB_DEVELOPMENT_PROFILE_GENERATION}"
        ))
        && profile_json.contains("\"status\": \"ready\"")
        && profile_json.contains(&format!(
            "\"nodeVersion\": \"{}\"",
            config.manifest.general_node_version
        ))
        && profile_json.contains(&format!(
            "\"reactVersion\": \"{}\"",
            config.manifest.web_react_version
        ))
        && profile_json.contains(&format!(
            "\"viteVersion\": \"{}\"",
            config.manifest.web_vite_version
        ))
        && profile_json.contains("\"serverHost\": \"127.0.0.1\"")
        && profile_json.contains("\"productionBuild\": true")
        && profile_json.contains("\"cdpRequired\": false")
        && release_json.contains(&format!(
            "\"packageLockSha256\": \"{}\"",
            config.manifest.web_package_lock_sha256
        ))
        && qualified.trim() == config.manifest.web_package_lock_sha256
        && profile_read_only
        && profile_sources_match(&config.support_script(WEB_DEVELOPMENT_PROFILE_ID), profile)
        && profile.join("new-project.mjs").is_file()
        && profile.join("build-project.mjs").is_file()
        && profile.join("serve-project.mjs").is_file()
        && general_node_runtime_is_ready(config)
        && web_distribution_is_ready(config)
}

fn profile_sources_match(source: &Path, installed: &Path) -> bool {
    let entries = match fs::read_dir(source) {
        Ok(entries) => entries,
        Err(_) => return false,
    };
    for entry in entries {
        let Ok(entry) = entry else { return false };
        let source_path = entry.path();
        let installed_path = installed.join(entry.file_name());
        let Ok(metadata) = fs::symlink_metadata(&source_path) else {
            return false;
        };
        if metadata.file_type().is_symlink() {
            return false;
        }
        if metadata.is_dir() {
            if !profile_sources_match(&source_path, &installed_path) {
                return false;
            }
        } else if !metadata.is_file()
            || fs::read(&source_path).ok() != fs::read(&installed_path).ok()
        {
            return false;
        }
    }
    true
}

pub fn gradle_version_is_ready(config: &Config, version: &str) -> bool {
    let Ok((_, _, sha256)) = gradle_release(config, version) else {
        return false;
    };
    let archive = ToolchainStore::new(&config.install_root)
        .cache_root()
        .join(format!("gradle-{version}-bin.zip"));
    if !verify_sha256(&archive, sha256) {
        return false;
    }
    let output = Command::new(gradle_root_for(config, version).join("bin/gradle"))
        .arg("--version")
        .env("JAVA_HOME", JAVA_HOME)
        .env("GRADLE_USER_HOME", gradle_user_home(config))
        .output();
    output.is_ok_and(|value| {
        value.status.success()
            && String::from_utf8_lossy(&value.stdout).contains(&format!("Gradle {}", version))
    })
}

fn gradle_release<'a>(
    config: &'a Config,
    version: &str,
) -> Result<(&'a str, u64, &'a str), InstallError> {
    if version == config.manifest.android_gradle_version {
        Ok((
            &config.manifest.android_gradle_url,
            config.manifest.android_gradle_size_bytes,
            &config.manifest.android_gradle_sha256,
        ))
    } else if version == config.manifest.react_native_gradle_version {
        Ok((
            &config.manifest.react_native_gradle_url,
            config.manifest.react_native_gradle_size_bytes,
            &config.manifest.react_native_gradle_sha256,
        ))
    } else {
        Err(failure(43, "unknown Gradle release"))
    }
}

fn apt_update() -> Result<(), InstallError> {
    run_checked(
        Command::new("/usr/bin/sudo")
            .arg("-n")
            .arg("/usr/bin/env")
            .arg("DEBIAN_FRONTEND=noninteractive")
            .arg("/usr/bin/apt-get")
            .arg("update"),
        36,
        "Debian package metadata update failed",
    )
}

fn apt_install(packages: &[&str]) -> Result<(), InstallError> {
    let mut command = Command::new("/usr/bin/sudo");
    command
        .arg("-n")
        .arg("/usr/bin/env")
        .arg("DEBIAN_FRONTEND=noninteractive")
        .arg("/usr/bin/apt-get")
        .arg("install")
        .arg("-y")
        .arg("--no-install-recommends")
        .args(packages);
    run_checked(&mut command, 37, "Debian package installation failed")
}

fn debian_package_is_installed(package: &str) -> bool {
    command_output(
        Command::new("/usr/bin/dpkg-query")
            .arg("-W")
            .arg("-f=${Status}")
            .arg(package),
        0,
        "",
    )
    .is_ok_and(|output| output.trim() == "install ok installed")
}

fn enable_amd64_packages() -> Result<(), InstallError> {
    run_checked(
        Command::new("/usr/bin/sudo")
            .arg("-n")
            .arg("/usr/bin/dpkg")
            .arg("--add-architecture")
            .arg("amd64"),
        38,
        "Debian could not enable amd64 packages",
    )
}

fn enable_x86_64_binfmt() -> Result<(), InstallError> {
    run_checked(
        Command::new("/usr/bin/sudo")
            .arg("-n")
            .arg("/usr/sbin/update-binfmts")
            .arg("--enable")
            .arg("qemu-x86_64"),
        39,
        "Debian could not enable x86_64 execution",
    )
}

fn install_command_tools(config: &Config, archive: &Path, sdk: &Path) -> Result<(), InstallError> {
    let command_tools = sdk.join("cmdline-tools");
    let versioned = command_tools.join(&config.manifest.android_command_tools_version);
    let manager = versioned.join("bin/sdkmanager");
    if !manager.is_file() {
        fs::create_dir_all(&command_tools)
            .map_err(|error| failure(33, format!("cannot create Android SDK root: {error}")))?;
        let staging = command_tools.join(format!("staging.{}", std::process::id()));
        remove_directory(&staging)?;
        fs::create_dir_all(&staging).map_err(|error| {
            failure(33, format!("cannot create command tools staging: {error}"))
        })?;
        run_checked(
            Command::new("/usr/bin/unzip")
                .arg("-q")
                .arg(archive)
                .arg("-d")
                .arg(&staging),
            40,
            "Android command tools extraction failed",
        )?;
        let extracted = staging.join("cmdline-tools");
        if !extracted.join("bin/sdkmanager").is_file() {
            return Err(failure(40, "Android command tools archive is malformed"));
        }
        remove_directory(&versioned)?;
        fs::rename(&extracted, &versioned).map_err(|error| {
            failure(
                40,
                format!("cannot activate Android command tools: {error}"),
            )
        })?;
        remove_directory(&staging)?;
    }
    let latest = command_tools.join("latest");
    let next = command_tools.join(format!("latest.next.{}", std::process::id()));
    remove_path(&next)?;
    symlink(&versioned, &next).map_err(|error| {
        failure(
            40,
            format!("cannot stage Android command tools link: {error}"),
        )
    })?;
    remove_path(&latest)?;
    fs::rename(&next, &latest).map_err(|error| {
        failure(
            40,
            format!("cannot activate Android command tools link: {error}"),
        )
    })
}

fn install_android_packages(config: &Config, sdk: &Path) -> Result<(), InstallError> {
    let manager = sdk.join("cmdline-tools/latest/bin/sdkmanager");
    let sdk_root = format!("--sdk_root={}", sdk.display());
    run_with_acceptance(
        Command::new(&manager)
            .arg(&sdk_root)
            .arg("--licenses")
            .env("JAVA_HOME", JAVA_HOME),
        41,
        "Android SDK license acceptance failed",
    )?;
    run_with_acceptance(
        Command::new(&manager)
            .arg(&sdk_root)
            .args(android_package_arguments(
                config.manifest.android_sdk_channel,
                &config.manifest.android_platform,
                &config.manifest.android_build_tools,
            ))
            .env("JAVA_HOME", JAVA_HOME),
        42,
        "Android SDK package installation failed",
    )
}

fn android_package_arguments(channel: u8, platform: &str, build_tools: &str) -> [String; 3] {
    [
        format!("--channel={channel}"),
        format!("platforms;android-{platform}"),
        format!("build-tools;{build_tools}"),
    ]
}

fn run_with_acceptance(command: &mut Command, code: u8, context: &str) -> Result<(), InstallError> {
    let mut child = command
        .stdin(Stdio::piped())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|error| failure(code, format!("{context}: {error}")))?;
    if let Some(mut input) = child.stdin.take() {
        input
            .write_all("y\n".repeat(128).as_bytes())
            .map_err(|error| failure(code, format!("{context}: {error}")))?;
    }
    let status = child
        .wait()
        .map_err(|error| failure(code, format!("{context}: {error}")))?;
    if status.success() {
        Ok(())
    } else {
        Err(failure(code, format!("{context}: {status}")))
    }
}

fn write_capability_environment(config: &Config, sdk: &Path) -> Result<(), InstallError> {
    let android_profile = profile_family_root(config, "android-kotlin").join("active");
    let native_profile = profile_family_root(config, "android-native").join("active");
    let flutter_profile = profile_family_root(config, "flutter").join("active");
    let godot_profile = profile_family_root(config, "godot-android").join("active");
    let react_native_profile = profile_family_root(config, "react-native").join("active");
    let web_profile = profile_family_root(config, "web-development").join("active");
    let node_bin = config.install_root.join(format!(
        "runtimes/node-v{}-linux-arm64/bin",
        config.manifest.node_version
    ));
    let mut content = format!(
        concat!(
            "export JAVA_HOME={}\n",
            "export ANDROID_HOME={}\n",
            "export ANDROID_SDK_ROOT={}\n",
            "export GRADLE_USER_HOME={}\n",
            "export PATH={}:{}:{}:{}"
        ),
        shell_quote(Path::new(JAVA_HOME)),
        shell_quote(sdk),
        shell_quote(sdk),
        shell_quote(&gradle_user_home(config)),
        shell_quote(&node_bin),
        shell_quote(&gradle_root(config).join("bin")),
        shell_quote(&sdk.join("cmdline-tools/latest/bin")),
        shell_quote(&sdk.join(format!(
            "build-tools/{}",
            config.manifest.android_build_tools
        )),),
    );
    if android_kotlin_profile_is_ready(config) {
        content.push_str(&format!(
            ":\"$PATH\"\nexport CLAW_IN_ONE_ANDROID_PROFILE={}\nexport CLAW_IN_ONE_ANDROID_NEW_PROJECT={}\n",
            shell_quote(&android_profile.join("profile.json")),
            shell_quote(&android_profile.join("new-project.mjs")),
        ));
    } else {
        content.push_str(":\"$PATH\"\n");
    }
    if android_native_profile_component_is_ready(config) {
        content.push_str(&format!(
            "export CLAW_IN_ONE_ANDROID_NATIVE_PROFILE={}\nexport CLAW_IN_ONE_ANDROID_NATIVE_NEW_PROJECT={}\n",
            shell_quote(&native_profile.join("profile.json")),
            shell_quote(&native_profile.join("new-project.mjs")),
        ));
    }
    if flutter_artifacts_are_ready(config) {
        content.push_str(&format!(
            "export PATH={}:\"$PATH\"\nexport CLAW_IN_ONE_FLUTTER_PROFILE={}\nexport CLAW_IN_ONE_FLUTTER_NEW_PROJECT={}\n",
            shell_quote(&flutter_root(config).join("bin")),
            shell_quote(&flutter_profile.join("profile.json")),
            shell_quote(&flutter_profile.join("new-project.mjs")),
        ));
    }
    if godot_android_profile_component_is_ready(config) {
        content.push_str(&format!(
            "export CLAW_IN_ONE_GODOT_ANDROID_PROFILE={}\nexport CLAW_IN_ONE_GODOT_ANDROID_NEW_PROJECT={}\n",
            shell_quote(&godot_profile.join("profile.json")),
            shell_quote(&godot_profile.join("new-project.mjs")),
        ));
    }
    if react_native_profile_component_is_ready(config) {
        content.push_str(&format!(
            "export CLAW_IN_ONE_REACT_NATIVE_PROFILE={}\nexport CLAW_IN_ONE_REACT_NATIVE_NEW_PROJECT={}\n",
            shell_quote(&react_native_profile.join("profile.json")),
            shell_quote(&react_native_profile.join("new-project.mjs")),
        ));
    }
    if web_profile_component_is_ready(config) {
        content.push_str(&format!(
            concat!(
                "export CLAW_IN_ONE_WEB_PROFILE={}\n",
                "export CLAW_IN_ONE_WEB_NEW_PROJECT={}\n",
                "export CLAW_IN_ONE_WEB_BUILD_PROJECT={}\n",
                "export CLAW_IN_ONE_WEB_SERVE_PROJECT={}\n"
            ),
            shell_quote(&web_profile.join("profile.json")),
            shell_quote(&web_profile.join("new-project.mjs")),
            shell_quote(&web_profile.join("build-project.mjs")),
            shell_quote(&web_profile.join("serve-project.mjs")),
        ));
    }
    let destination = config.install_root.join("capabilities.env");
    let temporary = destination.with_extension(format!("next.{}", std::process::id()));
    fs::write(&temporary, content)
        .and_then(|()| fs::rename(&temporary, &destination))
        .map_err(|error| {
            failure(
                43,
                format!("cannot publish capability environment: {error}"),
            )
        })
}

fn android_sdk_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).android_sdk_root()
}

fn general_node_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root)
        .general_node_root(&config.manifest.general_node_version)
}

fn react_native_distribution_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).react_native_distribution_root(
        &config.manifest.react_native_version,
        REACT_NATIVE_DISTRIBUTION_GENERATION,
    )
}

fn web_distribution_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).web_distribution_root(
        &config.manifest.web_vite_version,
        WEB_DISTRIBUTION_GENERATION,
    )
}

fn developer_path(config: &Config) -> String {
    let inherited = std::env::var("PATH").unwrap_or_else(|_| "/usr/bin:/bin".to_owned());
    format!(
        "{}:{inherited}",
        general_node_root(config).join("bin").display()
    )
}

fn general_node_command(config: &Config, executable: &Path) -> Command {
    let mut command = Command::new(executable);
    command
        .env("PATH", developer_path(config))
        .env(
            "npm_config_cache",
            ToolchainStore::new(&config.install_root).react_native_npm_cache(),
        )
        .env("npm_config_audit", "false")
        .env("npm_config_fund", "false")
        .env("CI", "true");
    command
}

fn gradle_root(config: &Config) -> PathBuf {
    gradle_root_for(config, &config.manifest.android_gradle_version)
}

fn gradle_root_for(config: &Config, version: &str) -> PathBuf {
    ToolchainStore::new(&config.install_root).gradle_root(version)
}

fn gradle_user_home(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).gradle_user_home()
}

fn gradle_archive_url(config: &Config) -> String {
    gradle_archive_url_for(config, &config.manifest.android_gradle_version)
}

fn gradle_archive_url_for(config: &Config, version: &str) -> String {
    let archive = ToolchainStore::new(&config.install_root)
        .cache_root()
        .join(format!("gradle-{}-bin.zip", version));
    let encoded = archive
        .to_string_lossy()
        .as_bytes()
        .iter()
        .map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'/' | b'-' | b'.' | b'_' | b'~' => {
                char::from(*byte).to_string()
            }
            _ => format!("%{byte:02X}"),
        })
        .collect::<String>();
    format!("file\\://{encoded}")
}

fn android_kotlin_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "android-kotlin")
        .join("releases")
        .join(format!(
            "{ANDROID_KOTLIN_PROFILE_ID}-generation-{ANDROID_KOTLIN_PROFILE_GENERATION}"
        ))
}

fn android_native_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "android-native")
        .join("releases")
        .join(format!(
            "{ANDROID_NATIVE_PROFILE_ID}-generation-{ANDROID_NATIVE_PROFILE_GENERATION}"
        ))
}

fn flutter_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "flutter")
        .join("releases")
        .join(format!(
            "{FLUTTER_PROFILE_ID}-generation-{FLUTTER_PROFILE_GENERATION}"
        ))
}

fn godot_android_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "godot-android")
        .join("releases")
        .join(format!(
            "{GODOT_ANDROID_PROFILE_ID}-generation-{GODOT_ANDROID_PROFILE_GENERATION}"
        ))
}

fn react_native_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "react-native")
        .join("releases")
        .join(format!(
            "{REACT_NATIVE_ANDROID_PROFILE_ID}-generation-{REACT_NATIVE_ANDROID_PROFILE_GENERATION}"
        ))
}

fn web_profile_root(config: &Config) -> PathBuf {
    profile_family_root(config, "web-development")
        .join("releases")
        .join(format!(
            "{WEB_DEVELOPMENT_PROFILE_ID}-generation-{WEB_DEVELOPMENT_PROFILE_GENERATION}"
        ))
}

fn profile_family_root(config: &Config, family: &str) -> PathBuf {
    config
        .install_root
        .join("development-profiles")
        .join(family)
}

fn flutter_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).flutter_root(&config.manifest.flutter_version)
}

fn flutter_pub_cache(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).flutter_pub_cache()
}

fn godot_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root).godot_root(&config.manifest.godot_version)
}

fn godot_export_templates_root(config: &Config) -> PathBuf {
    ToolchainStore::new(&config.install_root)
        .godot_export_templates_root(&config.manifest.godot_version)
}

fn godot_android_debug_template(config: &Config) -> PathBuf {
    godot_export_templates_root(config).join("android_debug.apk")
}

fn flutter_command(config: &Config, executable: &Path) -> Command {
    let sdk = android_sdk_root(config);
    let mut command = Command::new(executable);
    command
        .env("CI", "true")
        .env("JAVA_HOME", JAVA_HOME)
        .env("ANDROID_HOME", &sdk)
        .env("ANDROID_SDK_ROOT", &sdk)
        .env("PUB_CACHE", flutter_pub_cache(config))
        .env("GRADLE_USER_HOME", gradle_user_home(config));
    command
}

pub fn flutter_sdk_is_ready(config: &Config) -> bool {
    let root = flutter_root(config);
    let flutter = root.join("bin/flutter");
    let dart = root.join("bin/cache/dart-sdk/bin/dart");
    let revision = command_output(
        Command::new("/usr/bin/git")
            .arg("-C")
            .arg(&root)
            .arg("rev-parse")
            .arg("HEAD"),
        0,
        "",
    )
    .unwrap_or_default();
    let engine = fs::read_to_string(root.join("bin/internal/engine.version")).unwrap_or_default();
    let version = flutter_command(config, &flutter)
        .arg("--version")
        .arg("--machine")
        .output();
    let dart_arch = Command::new("/usr/bin/file").arg(&dart).output();
    revision.trim() == config.manifest.flutter_framework_revision
        && engine.trim() == config.manifest.flutter_engine_revision
        && version.is_ok_and(|output| {
            output.status.success()
                && flutter_machine_version_matches(
                    &output.stdout,
                    &config.manifest.flutter_version,
                    &config.manifest.flutter_dart_version,
                )
        })
        && dart_arch.is_ok_and(|output| {
            output.status.success() && {
                let value = String::from_utf8_lossy(&output.stdout);
                value.contains("ARM aarch64") || value.contains("ARM64")
            }
        })
}

fn flutter_machine_version_matches(output: &[u8], flutter: &str, dart: &str) -> bool {
    let compact = String::from_utf8_lossy(output)
        .chars()
        .filter(|character| !character.is_ascii_whitespace())
        .collect::<String>();
    compact.contains(&format!("\"frameworkVersion\":\"{flutter}\""))
        && compact.contains(&format!("\"dartSdkVersion\":\"{dart}\""))
}

fn render_flutter_profile_json(config: &Config, profile: &Path) -> String {
    let build_command = format!(
        "{} build apk --debug --target-platform android-arm64 --no-pub",
        json_path(&flutter_root(config).join("bin/flutter"))
    );
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"flutter-android-v1\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"flutterVersion\": \"{}\",\n",
            "  \"dartVersion\": \"{}\",\n",
            "  \"frameworkRevision\": \"{}\",\n",
            "  \"engineRevision\": \"{}\",\n",
            "  \"flutterRoot\": \"{}\",\n",
            "  \"javaHome\": \"{}\",\n",
            "  \"androidSdkRoot\": \"{}\",\n",
            "  \"androidPlatform\": \"{}\",\n",
            "  \"androidNdk\": \"{}\",\n",
            "  \"pubCache\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"buildCommand\": \"{}\",\n",
            "  \"artifact\": \"build/app/outputs/flutter-apk/app-debug.apk\",\n",
            "  \"targetPlatform\": \"android-arm64\",\n",
            "  \"maxGradleWorkers\": 1\n",
            "}}\n"
        ),
        FLUTTER_PROFILE_GENERATION,
        config.manifest.flutter_version,
        config.manifest.flutter_dart_version,
        config.manifest.flutter_framework_revision,
        config.manifest.flutter_engine_revision,
        json_path(&flutter_root(config)),
        json_path(Path::new(JAVA_HOME)),
        json_path(&android_sdk_root(config)),
        config.manifest.flutter_android_platform,
        config.manifest.flutter_android_ndk,
        json_path(&flutter_pub_cache(config)),
        json_path(&profile.join("new-project.mjs")),
        build_command,
    )
}

fn render_godot_profile_json(config: &Config, profile: &Path, signer: &str) -> String {
    let store = ToolchainStore::new(&config.install_root);
    let state = store.godot_state_root(&config.manifest.godot_version);
    let sdk = android_sdk_root(config);
    let godot = godot_root(config).join("godot");
    let environment = format!(
        "HOME={} XDG_CONFIG_HOME={} XDG_DATA_HOME={} XDG_CACHE_HOME={} JAVA_HOME={} ANDROID_HOME={} ANDROID_SDK_ROOT={}",
        shell_quote(Path::new("/home/droid")),
        shell_quote(&state.join("config")),
        shell_quote(&state.join("data")),
        shell_quote(&state.join("cache")),
        shell_quote(Path::new(JAVA_HOME)),
        shell_quote(&sdk),
        shell_quote(&sdk),
    );
    let build_command = format!(
        "{environment} {} --headless --import --path . && {environment} {} --headless --path . --export-debug Android build/app-debug.apk",
        shell_quote(&godot),
        shell_quote(&godot),
    );
    format!(
        concat!(
            "{{\n",
            "  \"schemaVersion\": 1,\n",
            "  \"profileId\": \"{}\",\n",
            "  \"generation\": {},\n",
            "  \"status\": \"ready\",\n",
            "  \"godotVersion\": \"{}\",\n",
            "  \"godotBuild\": \"{}\",\n",
            "  \"targetPlatform\": \"android-arm64\",\n",
            "  \"renderer\": \"mobile\",\n",
            "  \"javaHome\": \"{}\",\n",
            "  \"androidSdkRoot\": \"{}\",\n",
            "  \"godotBinary\": \"{}\",\n",
            "  \"androidDebugTemplate\": \"{}\",\n",
            "  \"configHome\": \"{}\",\n",
            "  \"dataHome\": \"{}\",\n",
            "  \"cacheHome\": \"{}\",\n",
            "  \"materializer\": \"{}\",\n",
            "  \"buildCommand\": \"{}\",\n",
            "  \"artifact\": \"build/app-debug.apk\",\n",
            "  \"debugSignerSha256\": \"{}\"\n",
            "}}\n"
        ),
        GODOT_ANDROID_PROFILE_ID,
        GODOT_ANDROID_PROFILE_GENERATION,
        config.manifest.godot_version,
        config.manifest.godot_build,
        json_path(Path::new(JAVA_HOME)),
        json_path(&sdk),
        json_path(&godot),
        json_path(&godot_android_debug_template(config)),
        json_path(&state.join("config")),
        json_path(&state.join("data")),
        json_path(&state.join("cache")),
        json_path(&profile.join("new-project.mjs")),
        build_command.replace('\\', "\\\\").replace('"', "\\\""),
        signer,
    )
}

fn flutter_profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let qualified = fs::read_to_string(profile.join("qualified")).unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains("\"profileId\": \"flutter-android-v1\"")
        && profile_json.contains(&format!("\"generation\": {FLUTTER_PROFILE_GENERATION}"))
        && profile_json.contains("\"status\": \"ready\"")
        && profile_json.contains(&format!(
            "\"flutterVersion\": \"{}\"",
            config.manifest.flutter_version
        ))
        && profile_json.contains(&format!(
            "\"frameworkRevision\": \"{}\"",
            config.manifest.flutter_framework_revision
        ))
        && profile_json.contains(&format!(
            "\"androidNdk\": \"{}\"",
            config.manifest.flutter_android_ndk
        ))
        && release_json.contains(&format!(
            "\"flutterVersion\": \"{}\"",
            config.manifest.flutter_version
        ))
        && qualified.trim().len() == 64
        && qualified
            .trim()
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        && profile_read_only
        && profile_sources_match(&config.support_script(FLUTTER_PROFILE_ID), profile)
        && profile.join("new-project.mjs").is_file()
}

fn godot_profile_is_ready(config: &Config, profile: &Path) -> bool {
    let profile_json = fs::read_to_string(profile.join("profile.json")).unwrap_or_default();
    let release_json = fs::read_to_string(profile.join("release.json")).unwrap_or_default();
    let qualified = fs::read_to_string(profile.join("qualified")).unwrap_or_default();
    let profile_read_only = fs::metadata(profile.join("profile.json"))
        .is_ok_and(|metadata| metadata.permissions().mode() & 0o222 == 0);
    profile_json.contains(&format!("\"profileId\": \"{GODOT_ANDROID_PROFILE_ID}\""))
        && profile_json.contains(&format!(
            "\"generation\": {GODOT_ANDROID_PROFILE_GENERATION}"
        ))
        && profile_json.contains("\"status\": \"ready\"")
        && profile_json.contains(&format!(
            "\"godotVersion\": \"{}\"",
            config.manifest.godot_version
        ))
        && profile_json.contains(&format!(
            "\"godotBuild\": \"{}\"",
            config.manifest.godot_build
        ))
        && profile_json.contains("\"targetPlatform\": \"android-arm64\"")
        && profile_json.contains("\"renderer\": \"mobile\"")
        && profile_json.contains(&format!(
            "\"androidDebugTemplate\": \"{}\"",
            json_path(&godot_android_debug_template(config))
        ))
        && release_json.contains(&format!(
            "\"godotVersion\": \"{}\"",
            config.manifest.godot_version
        ))
        && release_json.contains(&format!(
            "\"godotBuild\": \"{}\"",
            config.manifest.godot_build
        ))
        && release_json.contains("\"customBuild\": false")
        && qualified.trim().len() == 64
        && qualified
            .trim()
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        && profile_read_only
        && profile_sources_match(&config.support_script(GODOT_ANDROID_PROFILE_ID), profile)
        && profile.join("new-project.mjs").is_file()
        && godot_engine_is_ready(config)
        && godot_export_templates_are_ready(config)
}

fn command_output(command: &mut Command, code: u8, context: &str) -> Result<String, InstallError> {
    let output = command
        .output()
        .map_err(|error| failure(code, format!("{context}: {error}")))?;
    if !output.status.success() {
        return Err(failure(code, context));
    }
    String::from_utf8(output.stdout).map_err(|error| failure(code, format!("{context}: {error}")))
}

fn sha256_file(path: &Path) -> Result<String, InstallError> {
    let mut file = File::open(path)
        .map_err(|error| failure(56, format!("cannot read file for hashing: {error}")))?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|error| failure(56, format!("cannot hash file: {error}")))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn command_succeeds(command: &mut Command) -> bool {
    command
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

fn report_stage(
    report: &mut impl FnMut(Stage, Option<DownloadProgress>) -> Result<(), String>,
    stage: Stage,
) -> Result<(), InstallError> {
    report(stage, None).map_err(|error| failure(44, error))
}

fn remove_directory(path: &Path) -> Result<(), InstallError> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(failure(
            40,
            format!("cannot remove {}: {error}", path.display()),
        )),
    }
}

fn remove_path(path: &Path) -> Result<(), InstallError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {
            remove_directory(path)
        }
        Ok(_) => fs::remove_file(path)
            .map_err(|error| failure(40, format!("cannot remove {}: {error}", path.display()))),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(failure(
            40,
            format!("cannot inspect {}: {error}", path.display()),
        )),
    }
}

fn shell_quote(path: &Path) -> String {
    format!("'{}'", path.display().to_string().replace('\'', "'\"'\"'"))
}

fn failure(code: u8, message: impl Into<String>) -> InstallError {
    InstallError {
        code,
        message: message.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        activate_profile, android_package_arguments, flutter_machine_version_matches,
        godot_reported_error, shell_quote,
    };
    use std::fs;
    use std::os::unix::fs::symlink;
    use std::path::Path;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn capability_environment_paths_are_shell_literals() {
        assert_eq!(shell_quote(Path::new("/tmp/a b")), "'/tmp/a b'");
        assert_eq!(shell_quote(Path::new("/tmp/a'b")), "'/tmp/a'\"'\"'b'");
    }

    #[test]
    fn android_packages_pin_channel_platform_and_build_tools() {
        assert_eq!(
            android_package_arguments(3, "37.0", "37.0.0"),
            [
                "--channel=3",
                "platforms;android-37.0",
                "build-tools;37.0.0",
            ],
        );
    }

    #[test]
    fn flutter_machine_version_accepts_pretty_json_but_rejects_another_release() {
        let output = br#"{
          "frameworkVersion": "3.47.4",
          "dartSdkVersion": "3.13.3"
        }"#;

        assert!(flutter_machine_version_matches(output, "3.47.4", "3.13.3"));
        assert!(!flutter_machine_version_matches(output, "3.47.3", "3.13.3"));
    }

    #[test]
    fn godot_error_detail_preserves_the_first_actionable_diagnostic() {
        let output =
            "Godot Engine v4.7.2\nSCRIPT ERROR: Parse Error: bad starter\nERROR: load failed\n";

        assert_eq!(
            godot_reported_error(output).as_deref(),
            Some("SCRIPT ERROR: Parse Error: bad starter"),
        );
        assert_eq!(godot_reported_error("Godot Engine v4.7.2\n"), None);
    }

    #[test]
    fn profile_activation_atomically_replaces_the_previous_symlink() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "claw-profile-activation-{}-{nonce}",
            std::process::id()
        ));
        let previous = root.join("previous");
        let next = root.join("next");
        fs::create_dir_all(&previous).expect("previous profile");
        fs::create_dir_all(&next).expect("next profile");
        symlink(&previous, root.join("active")).expect("active profile");

        activate_profile(&root, &next).expect("activate next profile");

        assert_eq!(
            fs::canonicalize(root.join("active")).expect("resolved active profile"),
            fs::canonicalize(&next).expect("resolved next profile")
        );
        assert!(previous.is_dir());
        fs::remove_dir_all(root).expect("cleanup");
    }
}
