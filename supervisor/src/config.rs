use std::collections::HashMap;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

pub const PROTOCOL_VERSION: &str = "10";

#[derive(Clone, Debug)]
pub struct InstallManifest {
    pub openclaw_version: String,
    pub node_version: String,
    pub node_url: String,
    pub node_size_bytes: u64,
    pub node_sha256: String,
    pub openclaw_url: String,
    pub openclaw_size_bytes: u64,
    pub openclaw_integrity: String,
    pub openclaw_signature: String,
    pub npm_key_id: String,
    pub npm_public_key: String,
    pub debian_series: String,
    pub package_set_generation: u32,
    pub general_node_version: String,
    pub general_node_npm_version: String,
    pub general_node_url: String,
    pub general_node_size_bytes: u64,
    pub general_node_sha256: String,
    pub scrcpy_server_version: String,
    pub scrcpy_server_url: String,
    pub scrcpy_server_size_bytes: u64,
    pub scrcpy_server_sha256: String,
    pub android_command_tools_version: String,
    pub android_command_tools_url: String,
    pub android_command_tools_size_bytes: u64,
    pub android_command_tools_sha256: String,
    pub android_sdk_channel: u8,
    pub android_platform: String,
    pub android_build_tools: String,
    pub android_gradle_version: String,
    pub android_gradle_url: String,
    pub android_gradle_size_bytes: u64,
    pub android_gradle_sha256: String,
    pub android_native_ndk: String,
    pub android_native_cmake: String,
    pub flutter_version: String,
    pub flutter_dart_version: String,
    pub flutter_framework_revision: String,
    pub flutter_engine_revision: String,
    pub flutter_url: String,
    pub flutter_size_bytes: u64,
    pub flutter_sha256: String,
    pub flutter_android_platform: String,
    pub flutter_android_ndk: String,
    pub godot_version: String,
    pub godot_build: String,
    pub godot_engine_url: String,
    pub godot_engine_size_bytes: u64,
    pub godot_engine_sha256: String,
    pub godot_export_templates_url: String,
    pub godot_export_templates_size_bytes: u64,
    pub godot_export_templates_sha256: String,
    pub godot_android_debug_template_sha256: String,
    pub react_native_version: String,
    pub react_native_react_version: String,
    pub react_native_community_cli_version: String,
    pub react_native_gradle_version: String,
    pub react_native_gradle_url: String,
    pub react_native_gradle_size_bytes: u64,
    pub react_native_gradle_sha256: String,
    pub react_native_android_gradle_plugin: String,
    pub react_native_android_platform: String,
    pub react_native_android_build_tools: String,
    pub react_native_android_ndk: String,
    pub react_native_android_cmake: String,
    pub react_native_hermes_compiler_version: String,
    pub react_native_hermes_compiler_sha256: String,
    pub react_native_package_lock_sha256: String,
    pub web_react_version: String,
    pub web_react_dom_version: String,
    pub web_vite_version: String,
    pub web_typescript_version: String,
    pub web_package_lock_sha256: String,
}

#[derive(Clone, Debug)]
pub struct Config {
    pub supervisor_id: String,
    pub secret: Vec<u8>,
    pub command_file: PathBuf,
    pub status_file: PathBuf,
    pub shared_dir: PathBuf,
    pub install_root: PathBuf,
    pub state_dir: PathBuf,
    pub manifest: InstallManifest,
}

impl Config {
    pub fn load(path: &Path) -> Result<Self, String> {
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| format!("cannot inspect Supervisor config: {error}"))?;
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            return Err("Supervisor config must be a regular file".into());
        }
        let encoded = fs::read_to_string(path)
            .map_err(|error| format!("cannot read Supervisor config: {error}"))?;
        let values = parse_assignments(&encoded)?;
        if require(&values, "CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION")? != PROTOCOL_VERSION {
            return Err("unsupported Supervisor protocol".into());
        }
        let supervisor_id = require(&values, "CLAW_IN_ONE_SUPERVISOR_ID")?.to_owned();
        if !is_lower_hex(&supervisor_id, 32) {
            return Err("invalid Supervisor ID".into());
        }
        let secret_hex = require(&values, "CLAW_IN_ONE_SUPERVISOR_SECRET")?;
        if !is_lower_hex(secret_hex, 64) {
            return Err("invalid Supervisor secret".into());
        }
        let secret = hex::decode(secret_hex).map_err(|_| "invalid Supervisor secret")?;
        let command_file = absolute_path(require(&values, "CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE")?)?;
        let status_file = absolute_path(require(&values, "CLAW_IN_ONE_SUPERVISOR_STATUS_FILE")?)?;
        validate_control_paths(&command_file, &status_file, &supervisor_id)?;
        validate_control_file(&command_file, false)?;
        validate_control_file(&status_file, true)?;

        let shared_dir = absolute_path(require(&values, "CLAW_IN_ONE_SHARED_DIR")?)?;
        let home = env::var_os("HOME")
            .map(PathBuf::from)
            .filter(|value| value.is_absolute())
            .ok_or_else(|| "HOME must be an absolute path".to_owned())?;
        let install_root = env::var_os("CLAW_IN_ONE_INSTALL_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".local/share/claw-in-one"));
        if !install_root.is_absolute() {
            return Err("install root must be absolute".into());
        }
        let state_home = env::var_os("XDG_STATE_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".local/state"));
        if !state_home.is_absolute() {
            return Err("state root must be absolute".into());
        }

        let manifest = InstallManifest {
            openclaw_version: require(&values, "CLAW_IN_ONE_OPENCLAW_VERSION")?.to_owned(),
            node_version: require(&values, "CLAW_IN_ONE_NODE_VERSION")?.to_owned(),
            node_url: require(&values, "CLAW_IN_ONE_NODE_URL")?.to_owned(),
            node_size_bytes: positive_u64(require(&values, "CLAW_IN_ONE_NODE_SIZE_BYTES")?)?,
            node_sha256: require(&values, "CLAW_IN_ONE_NODE_SHA256")?.to_owned(),
            openclaw_url: require(&values, "CLAW_IN_ONE_OPENCLAW_URL")?.to_owned(),
            openclaw_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_OPENCLAW_SIZE_BYTES",
            )?)?,
            openclaw_integrity: require(&values, "CLAW_IN_ONE_OPENCLAW_INTEGRITY")?.to_owned(),
            openclaw_signature: require(&values, "CLAW_IN_ONE_OPENCLAW_SIGNATURE")?.to_owned(),
            npm_key_id: require(&values, "CLAW_IN_ONE_NPM_KEY_ID")?.to_owned(),
            npm_public_key: require(&values, "CLAW_IN_ONE_NPM_PUBLIC_KEY")?.to_owned(),
            debian_series: require(&values, "CLAW_IN_ONE_DEBIAN_SERIES")?.to_owned(),
            package_set_generation: positive_u32(require(
                &values,
                "CLAW_IN_ONE_PACKAGE_SET_GENERATION",
            )?)?,
            general_node_version: require(&values, "CLAW_IN_ONE_GENERAL_NODE_VERSION")?.to_owned(),
            general_node_npm_version: require(&values, "CLAW_IN_ONE_GENERAL_NODE_NPM_VERSION")?
                .to_owned(),
            general_node_url: require(&values, "CLAW_IN_ONE_GENERAL_NODE_URL")?.to_owned(),
            general_node_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_GENERAL_NODE_SIZE_BYTES",
            )?)?,
            general_node_sha256: require(&values, "CLAW_IN_ONE_GENERAL_NODE_SHA256")?.to_owned(),
            scrcpy_server_version: require(&values, "CLAW_IN_ONE_SCRCPY_SERVER_VERSION")?
                .to_owned(),
            scrcpy_server_url: require(&values, "CLAW_IN_ONE_SCRCPY_SERVER_URL")?.to_owned(),
            scrcpy_server_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_SCRCPY_SERVER_SIZE_BYTES",
            )?)?,
            scrcpy_server_sha256: require(&values, "CLAW_IN_ONE_SCRCPY_SERVER_SHA256")?.to_owned(),
            android_command_tools_version: require(
                &values,
                "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_VERSION",
            )?
            .to_owned(),
            android_command_tools_url: require(&values, "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_URL")?
                .to_owned(),
            android_command_tools_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SIZE_BYTES",
            )?)?,
            android_command_tools_sha256: require(
                &values,
                "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SHA256",
            )?
            .to_owned(),
            android_sdk_channel: sdk_channel(require(&values, "CLAW_IN_ONE_ANDROID_SDK_CHANNEL")?)?,
            android_platform: require(&values, "CLAW_IN_ONE_ANDROID_PLATFORM")?.to_owned(),
            android_build_tools: require(&values, "CLAW_IN_ONE_ANDROID_BUILD_TOOLS")?.to_owned(),
            android_gradle_version: require(&values, "CLAW_IN_ONE_ANDROID_GRADLE_VERSION")?
                .to_owned(),
            android_gradle_url: require(&values, "CLAW_IN_ONE_ANDROID_GRADLE_URL")?.to_owned(),
            android_gradle_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_ANDROID_GRADLE_SIZE_BYTES",
            )?)?,
            android_gradle_sha256: require(&values, "CLAW_IN_ONE_ANDROID_GRADLE_SHA256")?
                .to_owned(),
            android_native_ndk: require(&values, "CLAW_IN_ONE_ANDROID_NATIVE_NDK")?.to_owned(),
            android_native_cmake: require(&values, "CLAW_IN_ONE_ANDROID_NATIVE_CMAKE")?.to_owned(),
            flutter_version: require(&values, "CLAW_IN_ONE_FLUTTER_VERSION")?.to_owned(),
            flutter_dart_version: require(&values, "CLAW_IN_ONE_FLUTTER_DART_VERSION")?.to_owned(),
            flutter_framework_revision: require(&values, "CLAW_IN_ONE_FLUTTER_FRAMEWORK_REVISION")?
                .to_owned(),
            flutter_engine_revision: require(&values, "CLAW_IN_ONE_FLUTTER_ENGINE_REVISION")?
                .to_owned(),
            flutter_url: require(&values, "CLAW_IN_ONE_FLUTTER_URL")?.to_owned(),
            flutter_size_bytes: positive_u64(require(&values, "CLAW_IN_ONE_FLUTTER_SIZE_BYTES")?)?,
            flutter_sha256: require(&values, "CLAW_IN_ONE_FLUTTER_SHA256")?.to_owned(),
            flutter_android_platform: require(&values, "CLAW_IN_ONE_FLUTTER_ANDROID_PLATFORM")?
                .to_owned(),
            flutter_android_ndk: require(&values, "CLAW_IN_ONE_FLUTTER_ANDROID_NDK")?.to_owned(),
            godot_version: require(&values, "CLAW_IN_ONE_GODOT_VERSION")?.to_owned(),
            godot_build: require(&values, "CLAW_IN_ONE_GODOT_BUILD")?.to_owned(),
            godot_engine_url: require(&values, "CLAW_IN_ONE_GODOT_ENGINE_URL")?.to_owned(),
            godot_engine_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_GODOT_ENGINE_SIZE_BYTES",
            )?)?,
            godot_engine_sha256: require(&values, "CLAW_IN_ONE_GODOT_ENGINE_SHA256")?.to_owned(),
            godot_export_templates_url: require(&values, "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_URL")?
                .to_owned(),
            godot_export_templates_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SIZE_BYTES",
            )?)?,
            godot_export_templates_sha256: require(
                &values,
                "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SHA256",
            )?
            .to_owned(),
            godot_android_debug_template_sha256: require(
                &values,
                "CLAW_IN_ONE_GODOT_ANDROID_DEBUG_TEMPLATE_SHA256",
            )?
            .to_owned(),
            react_native_version: require(&values, "CLAW_IN_ONE_REACT_NATIVE_VERSION")?.to_owned(),
            react_native_react_version: require(&values, "CLAW_IN_ONE_REACT_NATIVE_REACT_VERSION")?
                .to_owned(),
            react_native_community_cli_version: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_COMMUNITY_CLI_VERSION",
            )?
            .to_owned(),
            react_native_gradle_version: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_GRADLE_VERSION",
            )?
            .to_owned(),
            react_native_gradle_url: require(&values, "CLAW_IN_ONE_REACT_NATIVE_GRADLE_URL")?
                .to_owned(),
            react_native_gradle_size_bytes: positive_u64(require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_GRADLE_SIZE_BYTES",
            )?)?,
            react_native_gradle_sha256: require(&values, "CLAW_IN_ONE_REACT_NATIVE_GRADLE_SHA256")?
                .to_owned(),
            react_native_android_gradle_plugin: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_ANDROID_GRADLE_PLUGIN",
            )?
            .to_owned(),
            react_native_android_platform: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_ANDROID_PLATFORM",
            )?
            .to_owned(),
            react_native_android_build_tools: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_ANDROID_BUILD_TOOLS",
            )?
            .to_owned(),
            react_native_android_ndk: require(&values, "CLAW_IN_ONE_REACT_NATIVE_ANDROID_NDK")?
                .to_owned(),
            react_native_android_cmake: require(&values, "CLAW_IN_ONE_REACT_NATIVE_ANDROID_CMAKE")?
                .to_owned(),
            react_native_hermes_compiler_version: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_VERSION",
            )?
            .to_owned(),
            react_native_hermes_compiler_sha256: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_SHA256",
            )?
            .to_owned(),
            react_native_package_lock_sha256: require(
                &values,
                "CLAW_IN_ONE_REACT_NATIVE_PACKAGE_LOCK_SHA256",
            )?
            .to_owned(),
            web_react_version: require(&values, "CLAW_IN_ONE_WEB_REACT_VERSION")?.to_owned(),
            web_react_dom_version: require(&values, "CLAW_IN_ONE_WEB_REACT_DOM_VERSION")?
                .to_owned(),
            web_vite_version: require(&values, "CLAW_IN_ONE_WEB_VITE_VERSION")?.to_owned(),
            web_typescript_version: require(&values, "CLAW_IN_ONE_WEB_TYPESCRIPT_VERSION")?
                .to_owned(),
            web_package_lock_sha256: require(&values, "CLAW_IN_ONE_WEB_PACKAGE_LOCK_SHA256")?
                .to_owned(),
        };
        manifest.validate()?;

        Ok(Self {
            supervisor_id,
            secret,
            command_file,
            status_file,
            shared_dir,
            install_root,
            state_dir: state_home.join("claw-in-one"),
            manifest,
        })
    }

    pub fn support_script(&self, name: &str) -> PathBuf {
        self.shared_dir.join(name)
    }

    pub fn event_sequence_file(&self) -> PathBuf {
        self.state_dir
            .join(format!("control-{}-event.sequence", self.supervisor_id))
    }

    pub fn command_sequence_file(&self) -> PathBuf {
        self.state_dir
            .join(format!("control-{}-command.sequence", self.supervisor_id))
    }
}

impl InstallManifest {
    fn validate(&self) -> Result<(), String> {
        if !self.node_url.starts_with("https://")
            || !self.openclaw_url.starts_with("https://")
            || !self
                .android_command_tools_url
                .starts_with("https://dl.google.com/android/repository/")
            || !self
                .android_gradle_url
                .starts_with("https://services.gradle.org/distributions/")
            || !self
                .flutter_url
                .starts_with("https://storage.googleapis.com/flutter_infra_release/releases/")
            || !self
                .godot_engine_url
                .starts_with("https://github.com/godotengine/godot-builds/releases/download/")
            || !self
                .godot_export_templates_url
                .starts_with("https://github.com/godotengine/godot-builds/releases/download/")
            || !self
                .general_node_url
                .starts_with("https://nodejs.org/dist/")
            || !self
                .scrcpy_server_url
                .starts_with("https://github.com/Genymobile/scrcpy/releases/download/")
            || !self
                .react_native_gradle_url
                .starts_with("https://services.gradle.org/distributions/")
        {
            return Err("artifact URLs must use HTTPS".into());
        }
        if !is_lower_hex(&self.node_sha256, 64) {
            return Err("invalid Node digest".into());
        }
        if !is_lower_hex(&self.android_command_tools_sha256, 64) {
            return Err("invalid Android command tools digest".into());
        }
        if !is_lower_hex(&self.android_gradle_sha256, 64) {
            return Err("invalid Gradle digest".into());
        }
        if !is_lower_hex(&self.flutter_sha256, 64) {
            return Err("invalid Flutter digest".into());
        }
        if !is_lower_hex(&self.godot_engine_sha256, 64)
            || !is_lower_hex(&self.godot_export_templates_sha256, 64)
            || !is_lower_hex(&self.godot_android_debug_template_sha256, 64)
        {
            return Err("invalid Godot digest".into());
        }
        if !is_lower_hex(&self.general_node_sha256, 64) {
            return Err("invalid general Node digest".into());
        }
        if !is_lower_hex(&self.scrcpy_server_sha256, 64) {
            return Err("invalid scrcpy server digest".into());
        }
        if !is_lower_hex(&self.react_native_gradle_sha256, 64)
            || !is_lower_hex(&self.react_native_hermes_compiler_sha256, 64)
            || !is_lower_hex(&self.react_native_package_lock_sha256, 64)
        {
            return Err("invalid React Native digest".into());
        }
        if !is_lower_hex(&self.web_package_lock_sha256, 64) {
            return Err("invalid Web Development digest".into());
        }
        if !is_lower_hex(&self.flutter_framework_revision, 40)
            || !is_lower_hex(&self.flutter_engine_revision, 40)
        {
            return Err("invalid Flutter revision".into());
        }
        if !self.openclaw_integrity.starts_with("sha512-") {
            return Err("invalid OpenClaw integrity".into());
        }
        if self.openclaw_version.contains('/')
            || self.node_version.contains('/')
            || self.openclaw_version.contains("..")
            || self.node_version.contains("..")
            || self.debian_series != "13"
            || !self
                .android_command_tools_version
                .bytes()
                .all(|byte| byte.is_ascii_digit())
            || !valid_platform_version(&self.android_platform)
            || !valid_dotted_version(&self.android_build_tools)
            || !valid_dotted_version(&self.android_gradle_version)
            || !valid_dotted_version(&self.android_native_ndk)
            || !valid_dotted_version(&self.android_native_cmake)
            || !valid_dotted_version(&self.flutter_version)
            || !valid_dotted_version(&self.flutter_dart_version)
            || !valid_platform_version(&self.flutter_android_platform)
            || !valid_dotted_version(&self.flutter_android_ndk)
            || !valid_dotted_version(&self.godot_version)
            || !valid_dotted_version(&self.general_node_version)
            || !valid_dotted_version(&self.general_node_npm_version)
            || !valid_platform_version(&self.scrcpy_server_version)
            || !valid_dotted_version(&self.react_native_version)
            || !valid_dotted_version(&self.react_native_react_version)
            || !valid_dotted_version(&self.react_native_community_cli_version)
            || !valid_dotted_version(&self.react_native_gradle_version)
            || !valid_dotted_version(&self.react_native_android_gradle_plugin)
            || !valid_platform_version(&self.react_native_android_platform)
            || !valid_dotted_version(&self.react_native_android_build_tools)
            || !valid_dotted_version(&self.react_native_android_ndk)
            || !valid_dotted_version(&self.react_native_android_cmake)
            || !valid_dotted_version(&self.react_native_hermes_compiler_version)
            || !valid_dotted_version(&self.web_react_version)
            || !valid_dotted_version(&self.web_react_dom_version)
            || !valid_dotted_version(&self.web_vite_version)
            || !valid_dotted_version(&self.web_typescript_version)
            || self.godot_build != "stable.official.ed1daf0bf"
            || !self.godot_engine_url.contains(&format!(
                "/{0}-stable/Godot_v{0}-stable_linux.arm64.zip",
                self.godot_version
            ))
            || !self.godot_export_templates_url.contains(&format!(
                "/{0}-stable/Godot_v{0}-stable_export_templates.tpz",
                self.godot_version
            ))
            || !self.general_node_url.contains(&format!(
                "/v{0}/node-v{0}-linux-arm64.tar.xz",
                self.general_node_version
            ))
            || !self.scrcpy_server_url.ends_with(&format!(
                "/v{0}/scrcpy-server-v{0}",
                self.scrcpy_server_version
            ))
            || !self.react_native_gradle_url.contains(&format!(
                "/gradle-{}-bin.zip",
                self.react_native_gradle_version
            ))
        {
            return Err("invalid release version".into());
        }
        if !self.npm_key_id.starts_with("SHA256:") {
            return Err("invalid registry key ID".into());
        }
        if self.react_native_android_platform != self.android_platform
            || self.react_native_android_build_tools != self.android_build_tools
            || self.react_native_android_cmake != self.android_native_cmake
        {
            return Err("React Native must reuse the shared Android SDK and CMake release".into());
        }
        Ok(())
    }
}

#[cfg(test)]
impl InstallManifest {
    pub fn test_fixture() -> Self {
        Self {
            openclaw_version: "2026.9.4".into(),
            node_version: "24.12.0".into(),
            node_url: "https://example.test/node.tar.xz".into(),
            node_size_bytes: 1,
            node_sha256: "a".repeat(64),
            openclaw_url: "https://example.test/openclaw.tgz".into(),
            openclaw_size_bytes: 1,
            openclaw_integrity: "sha512-test".into(),
            openclaw_signature: "test".into(),
            npm_key_id: "SHA256:test".into(),
            npm_public_key: "test".into(),
            debian_series: "13".into(),
            package_set_generation: 1,
            general_node_version: "22.22.0".into(),
            general_node_npm_version: "10.9.4".into(),
            general_node_url:
                "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-arm64.tar.xz".into(),
            general_node_size_bytes: 1,
            general_node_sha256: "4".repeat(64),
            scrcpy_server_version: "4.1".into(),
            scrcpy_server_url:
                "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1"
                    .into(),
            scrcpy_server_size_bytes: 1,
            scrcpy_server_sha256: "6".repeat(64),
            android_command_tools_version: "16111833".into(),
            android_command_tools_url: "https://dl.google.com/android/repository/tools.zip".into(),
            android_command_tools_size_bytes: 1,
            android_command_tools_sha256: "b".repeat(64),
            android_sdk_channel: 3,
            android_platform: "37.0".into(),
            android_build_tools: "37.0.0".into(),
            android_gradle_version: "9.7.1".into(),
            android_gradle_url: "https://services.gradle.org/distributions/gradle.zip".into(),
            android_gradle_size_bytes: 1,
            android_gradle_sha256: "c".repeat(64),
            android_native_ndk: "29.0.14206865".into(),
            android_native_cmake: "3.22.1".into(),
            flutter_version: "3.47.4".into(),
            flutter_dart_version: "3.13.3".into(),
            flutter_framework_revision: "d".repeat(40),
            flutter_engine_revision: "e".repeat(40),
            flutter_url: "https://storage.googleapis.com/flutter_infra_release/releases/test"
                .into(),
            flutter_size_bytes: 1,
            flutter_sha256: "f".repeat(64),
            flutter_android_platform: "36".into(),
            flutter_android_ndk: "28.2.13676358".into(),
            godot_version: "4.7.2".into(),
            godot_build: "stable.official.ed1daf0bf".into(),
            godot_engine_url: "https://github.com/godotengine/godot-builds/releases/download/4.7.2-stable/Godot_v4.7.2-stable_linux.arm64.zip".into(),
            godot_engine_size_bytes: 1,
            godot_engine_sha256: "1".repeat(64),
            godot_export_templates_url: "https://github.com/godotengine/godot-builds/releases/download/4.7.2-stable/Godot_v4.7.2-stable_export_templates.tpz".into(),
            godot_export_templates_size_bytes: 1,
            godot_export_templates_sha256: "2".repeat(64),
            godot_android_debug_template_sha256: "3".repeat(64),
            react_native_version: "0.87.1".into(),
            react_native_react_version: "19.2.3".into(),
            react_native_community_cli_version: "20.2.0".into(),
            react_native_gradle_version: "9.4.1".into(),
            react_native_gradle_url:
                "https://services.gradle.org/distributions/gradle-9.4.1-bin.zip".into(),
            react_native_gradle_size_bytes: 1,
            react_native_gradle_sha256: "5".repeat(64),
            react_native_android_gradle_plugin: "9.2.1".into(),
            react_native_android_platform: "37.0".into(),
            react_native_android_build_tools: "37.0.0".into(),
            react_native_android_ndk: "27.1.12297006".into(),
            react_native_android_cmake: "3.22.1".into(),
            react_native_hermes_compiler_version: "250829098.0.17".into(),
            react_native_hermes_compiler_sha256: "6".repeat(64),
            react_native_package_lock_sha256: "7".repeat(64),
            web_react_version: "19.3.0".into(),
            web_react_dom_version: "19.3.0".into(),
            web_vite_version: "8.3.0".into(),
            web_typescript_version: "7.0.2".into(),
            web_package_lock_sha256: "8".repeat(64),
        }
    }
}

fn valid_dotted_version(value: &str) -> bool {
    !value.is_empty()
        && value.split('.').count() == 3
        && value
            .split('.')
            .all(|field| !field.is_empty() && field.bytes().all(|byte| byte.is_ascii_digit()))
}

fn valid_platform_version(value: &str) -> bool {
    let fields = value.split('.').collect::<Vec<_>>();
    (1..=2).contains(&fields.len())
        && fields
            .iter()
            .all(|field| !field.is_empty() && field.bytes().all(|byte| byte.is_ascii_digit()))
}

fn parse_assignments(encoded: &str) -> Result<HashMap<String, String>, String> {
    let mut values = HashMap::new();
    for (index, line) in encoded.lines().enumerate() {
        if line.is_empty() {
            continue;
        }
        let (name, encoded_value) = line
            .split_once('=')
            .ok_or_else(|| format!("invalid config line {}", index + 1))?;
        if !name
            .bytes()
            .all(|byte| byte == b'_' || byte.is_ascii_uppercase() || byte.is_ascii_digit())
            || name.is_empty()
        {
            return Err(format!("invalid config key on line {}", index + 1));
        }
        let value = decode_shell_single_quoted(encoded_value)
            .ok_or_else(|| format!("invalid config value on line {}", index + 1))?;
        if values.insert(name.to_owned(), value).is_some() {
            return Err(format!("duplicate config key {name}"));
        }
    }
    Ok(values)
}

fn decode_shell_single_quoted(value: &str) -> Option<String> {
    if value.len() < 2 || !value.starts_with('\'') || !value.ends_with('\'') {
        return None;
    }
    let inner = &value[1..value.len() - 1];
    if inner.contains('\'') {
        return None;
    }
    Some(inner.to_owned())
}

fn require<'a>(values: &'a HashMap<String, String>, name: &str) -> Result<&'a str, String> {
    values
        .get(name)
        .map(String::as_str)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("missing {name}"))
}

fn positive_u64(value: &str) -> Result<u64, String> {
    value
        .parse::<u64>()
        .ok()
        .filter(|parsed| *parsed > 0)
        .ok_or_else(|| "artifact size must be a positive integer".to_owned())
}

fn positive_u32(value: &str) -> Result<u32, String> {
    value
        .parse::<u32>()
        .ok()
        .filter(|parsed| *parsed > 0)
        .ok_or_else(|| "generation must be a positive integer".to_owned())
}

fn sdk_channel(value: &str) -> Result<u8, String> {
    value
        .parse::<u8>()
        .ok()
        .filter(|parsed| *parsed <= 3)
        .ok_or_else(|| "Android SDK channel must be between 0 and 3".to_owned())
}

fn absolute_path(value: &str) -> Result<PathBuf, String> {
    let path = PathBuf::from(value);
    if !path.is_absolute() {
        return Err(format!("path must be absolute: {value}"));
    }
    Ok(path)
}

fn validate_control_paths(command: &Path, status: &Path, id: &str) -> Result<(), String> {
    let command_parent = command
        .parent()
        .ok_or_else(|| "command file has no parent".to_owned())?;
    let status_parent = status
        .parent()
        .ok_or_else(|| "status file has no parent".to_owned())?;
    if command_parent != status_parent
        || command_parent.file_name().and_then(|value| value.to_str())
            != Some(format!("supervisor-{id}").as_str())
        || command.file_name().and_then(|value| value.to_str()) != Some("supervisor.command")
        || status.file_name().and_then(|value| value.to_str()) != Some("supervisor.status")
    {
        return Err("invalid Supervisor control paths".into());
    }
    Ok(())
}

fn validate_control_file(path: &Path, writable: bool) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("cannot inspect control file {}: {error}", path.display()))?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err(format!("control file is not regular: {}", path.display()));
    }
    if writable {
        fs::OpenOptions::new()
            .write(true)
            .open(path)
            .map_err(|error| format!("status file is not writable: {error}"))?;
    } else {
        fs::File::open(path).map_err(|error| format!("command file is not readable: {error}"))?;
    }
    Ok(())
}

fn is_lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::{parse_assignments, sdk_channel, valid_platform_version};

    #[test]
    fn parses_only_literal_assignments() {
        let values = parse_assignments("A='one'\nB='two words'\n").unwrap();
        assert_eq!(values["A"], "one");
        assert_eq!(values["B"], "two words");
        assert!(parse_assignments("A=$(id)\n").is_err());
        assert!(parse_assignments("A='one'\nA='two'\n").is_err());
    }

    #[test]
    fn accepts_only_known_android_sdk_channels() {
        assert_eq!(sdk_channel("0"), Ok(0));
        assert_eq!(sdk_channel("3"), Ok(3));
        assert!(sdk_channel("4").is_err());
        assert!(sdk_channel("canary").is_err());
    }

    #[test]
    fn accepts_android_platform_versions_with_an_optional_minor() {
        assert!(valid_platform_version("37"));
        assert!(valid_platform_version("37.0"));
        assert!(!valid_platform_version("37.0.0"));
        assert!(!valid_platform_version("37-preview"));
    }
}
