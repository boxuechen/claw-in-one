use std::path::{Path, PathBuf};

/// Physical storage owner for release-pinned developer components. Profiles
/// reference these paths; they never copy SDK/NDK/CMake distributions.
#[derive(Clone, Debug)]
pub struct ToolchainStore {
    root: PathBuf,
}

impl ToolchainStore {
    pub fn new(install_root: &Path) -> Self {
        Self {
            root: install_root.join("toolchains"),
        }
    }

    pub fn cache_root(&self) -> PathBuf {
        self.root.join("cache")
    }

    pub fn android_sdk_root(&self) -> PathBuf {
        self.root.join("android-sdk")
    }

    pub fn android_ndk_root(&self, version: &str) -> PathBuf {
        self.android_sdk_root().join("ndk").join(version)
    }

    pub fn android_cmake_root(&self, version: &str) -> PathBuf {
        self.android_sdk_root().join("cmake").join(version)
    }

    pub fn gradle_root(&self, version: &str) -> PathBuf {
        self.root.join(format!("gradle-{version}"))
    }

    pub fn gradle_user_home(&self) -> PathBuf {
        self.root.join("gradle-user-home")
    }

    pub fn general_node_root(&self, version: &str) -> PathBuf {
        self.root.join(format!("node-v{version}-linux-arm64"))
    }

    pub fn scrcpy_server_release(&self, version: &str) -> PathBuf {
        self.root
            .join("scrcpy")
            .join("releases")
            .join(version)
            .join("scrcpy-server")
    }

    pub fn scrcpy_server_current(&self) -> PathBuf {
        self.root.join("scrcpy").join("current")
    }

    pub fn react_native_distribution_root(&self, version: &str, generation: u32) -> PathBuf {
        self.root
            .join("react-native")
            .join(format!("{version}-generation-{generation}"))
    }

    pub fn react_native_npm_cache(&self) -> PathBuf {
        self.root.join("react-native-npm-cache")
    }

    pub fn web_distribution_root(&self, version: &str, generation: u32) -> PathBuf {
        self.root
            .join("web-development")
            .join(format!("vite-{version}-generation-{generation}"))
    }

    pub fn web_npm_cache(&self) -> PathBuf {
        self.root.join("web-development-npm-cache")
    }

    pub fn flutter_root(&self, version: &str) -> PathBuf {
        self.root.join(format!("flutter-{version}"))
    }

    pub fn flutter_pub_cache(&self) -> PathBuf {
        self.root.join("flutter-pub-cache")
    }

    pub fn godot_root(&self, version: &str) -> PathBuf {
        self.root.join("godot").join(version)
    }

    pub fn godot_export_templates_root(&self, version: &str) -> PathBuf {
        self.root.join("godot-export-templates").join(version)
    }

    pub fn godot_state_root(&self, version: &str) -> PathBuf {
        self.root.join("godot-state").join(version)
    }
}

#[cfg(test)]
mod tests {
    use super::ToolchainStore;
    use std::path::Path;

    #[test]
    fn versions_share_one_sdk_and_use_side_by_side_package_roots() {
        let store = ToolchainStore::new(Path::new("/opt/claw"));
        assert_eq!(
            store.android_sdk_root(),
            Path::new("/opt/claw/toolchains/android-sdk")
        );
        assert_eq!(
            store.android_ndk_root("29.0.14206865"),
            Path::new("/opt/claw/toolchains/android-sdk/ndk/29.0.14206865")
        );
        assert_eq!(
            store.android_ndk_root("28.2.13676358"),
            Path::new("/opt/claw/toolchains/android-sdk/ndk/28.2.13676358")
        );
        assert_eq!(
            store.godot_root("4.7.2"),
            Path::new("/opt/claw/toolchains/godot/4.7.2")
        );
        assert_eq!(
            store.godot_export_templates_root("4.7.2"),
            Path::new("/opt/claw/toolchains/godot-export-templates/4.7.2")
        );
        assert_eq!(
            store.general_node_root("22.22.0"),
            Path::new("/opt/claw/toolchains/node-v22.22.0-linux-arm64")
        );
        assert_eq!(
            store.scrcpy_server_release("4.1"),
            Path::new("/opt/claw/toolchains/scrcpy/releases/4.1/scrcpy-server")
        );
        assert_eq!(
            store.scrcpy_server_current(),
            Path::new("/opt/claw/toolchains/scrcpy/current")
        );
        assert_eq!(
            store.react_native_distribution_root("0.87.1", 1),
            Path::new("/opt/claw/toolchains/react-native/0.87.1-generation-1")
        );
        assert_eq!(
            store.web_distribution_root("8.3.0", 1),
            Path::new("/opt/claw/toolchains/web-development/vite-8.3.0-generation-1")
        );
    }
}
