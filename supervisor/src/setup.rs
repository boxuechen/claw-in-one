use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};

use crate::config::InstallManifest;

const PLAN_FORMAT_VERSION: &str = "6";

pub const ANDROID_KOTLIN_PROFILE_ID: &str = "android-kotlin-compose-v1";
pub const ANDROID_KOTLIN_PROFILE_GENERATION: u32 = 1;
pub const ANDROID_NATIVE_PROFILE_ID: &str = "android-native-vulkan-v1";
pub const ANDROID_NATIVE_PROFILE_GENERATION: u32 = 1;
pub const FLUTTER_PROFILE_ID: &str = "flutter-android-v1";
pub const FLUTTER_PROFILE_GENERATION: u32 = 2;
pub const GODOT_ANDROID_PROFILE_ID: &str = "godot-android-v1";
pub const GODOT_ANDROID_PROFILE_GENERATION: u32 = 1;
pub const REACT_NATIVE_ANDROID_PROFILE_ID: &str = "react-native-android-v1";
pub const REACT_NATIVE_ANDROID_PROFILE_GENERATION: u32 = 1;
pub const REACT_NATIVE_DISTRIBUTION_GENERATION: u32 = 1;
pub const WEB_DEVELOPMENT_PROFILE_ID: &str = "web-development-v1";
pub const WEB_DEVELOPMENT_PROFILE_GENERATION: u32 = 1;
pub const WEB_DISTRIBUTION_GENERATION: u32 = 1;

/// Stable product choices. These never identify downloaded bytes or a Skill.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum DeveloperCapability {
    AndroidKotlin,
    AndroidNative,
    Flutter,
    GodotAndroid,
    ReactNative,
    WebDevelopment,
}

impl DeveloperCapability {
    pub const ORDERED: [Self; 6] = [
        Self::AndroidKotlin,
        Self::AndroidNative,
        Self::Flutter,
        Self::GodotAndroid,
        Self::ReactNative,
        Self::WebDevelopment,
    ];
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::AndroidKotlin => "android_kotlin",
            Self::AndroidNative => "android_native",
            Self::Flutter => "flutter",
            Self::GodotAndroid => "godot_android",
            Self::ReactNative => "react_native",
            Self::WebDevelopment => "web_development",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        Self::ORDERED
            .into_iter()
            .find(|capability| capability.wire_name() == value)
    }
}

/// Stable physical component kind. Version is carried separately so exact
/// components can be deduplicated while side-by-side NDK/CMake versions remain
/// independently addressable.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum ComponentKey {
    OpenClawExecutionFoundation,
    GeneralNodeRuntime,
    ChromiumRuntime,
    AdbRuntime,
    VScreenRuntimeAssets,
    OpenClawRuntime,
    AndroidBuildFoundation,
    AndroidSdk,
    AndroidPlatform,
    AndroidGradle,
    AndroidNdk,
    AndroidCmake,
    AndroidKotlinProfile,
    AndroidNativeProfile,
    FlutterSdk,
    FlutterProfile,
    GodotEngine,
    GodotExportTemplates,
    GodotAndroidProfile,
    ReactNativeDistribution,
    ReactNativeAndroidProfile,
    WebDistribution,
    WebProfile,
}

impl ComponentKey {
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::OpenClawExecutionFoundation => "openclaw_execution_foundation",
            Self::GeneralNodeRuntime => "general_node_runtime",
            Self::ChromiumRuntime => "chromium_runtime",
            Self::AdbRuntime => "adb_runtime",
            Self::VScreenRuntimeAssets => "vscreen_runtime_assets",
            Self::OpenClawRuntime => "openclaw_runtime",
            Self::AndroidBuildFoundation => "android_build_foundation",
            Self::AndroidSdk => "android_sdk",
            Self::AndroidPlatform => "android_platform",
            Self::AndroidGradle => "android_gradle",
            Self::AndroidNdk => "android_ndk",
            Self::AndroidCmake => "android_cmake",
            Self::AndroidKotlinProfile => "android_kotlin_profile",
            Self::AndroidNativeProfile => "android_native_profile",
            Self::FlutterSdk => "flutter_sdk",
            Self::FlutterProfile => "flutter_profile",
            Self::GodotEngine => "godot_engine",
            Self::GodotExportTemplates => "godot_export_templates",
            Self::GodotAndroidProfile => "godot_android_profile",
            Self::ReactNativeDistribution => "react_native_distribution",
            Self::ReactNativeAndroidProfile => "react_native_android_profile",
            Self::WebDistribution => "web_distribution",
            Self::WebProfile => "web_profile",
        }
    }

    fn parse(value: &str) -> Option<Self> {
        [
            Self::OpenClawExecutionFoundation,
            Self::GeneralNodeRuntime,
            Self::ChromiumRuntime,
            Self::AdbRuntime,
            Self::VScreenRuntimeAssets,
            Self::OpenClawRuntime,
            Self::AndroidBuildFoundation,
            Self::AndroidSdk,
            Self::AndroidPlatform,
            Self::AndroidGradle,
            Self::AndroidNdk,
            Self::AndroidCmake,
            Self::AndroidKotlinProfile,
            Self::AndroidNativeProfile,
            Self::FlutterSdk,
            Self::FlutterProfile,
            Self::GodotEngine,
            Self::GodotExportTemplates,
            Self::GodotAndroidProfile,
            Self::ReactNativeDistribution,
            Self::ReactNativeAndroidProfile,
            Self::WebDistribution,
            Self::WebProfile,
        ]
        .into_iter()
        .find(|key| key.wire_name() == value)
    }

    pub fn allows_side_by_side(self) -> bool {
        matches!(
            self,
            Self::AndroidPlatform
                | Self::AndroidGradle
                | Self::AndroidNdk
                | Self::AndroidCmake
                | Self::GodotEngine
                | Self::GodotExportTemplates
                | Self::ReactNativeDistribution
                | Self::WebDistribution
        )
    }
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct ComponentIdentity {
    pub key: ComponentKey,
    pub version: String,
}

impl ComponentIdentity {
    fn new(key: ComponentKey, version: impl Into<String>) -> Result<Self, String> {
        let version = version.into();
        if version.is_empty()
            || version.len() > 96
            || !version.bytes().all(|byte| {
                byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_' | b'+')
            })
        {
            return Err("invalid component version".into());
        }
        Ok(Self { key, version })
    }

    pub fn wire_name(&self) -> String {
        format!("{}@{}", self.key.wire_name(), self.version)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ComponentNode {
    pub identity: ComponentIdentity,
    pub dependencies: Vec<ComponentIdentity>,
}

#[derive(Clone, Debug)]
pub struct ReleaseGraph {
    nodes: Vec<ComponentNode>,
    required: Vec<ComponentIdentity>,
    roots: BTreeMap<DeveloperCapability, ComponentIdentity>,
}

impl ReleaseGraph {
    pub fn for_manifest(manifest: &InstallManifest) -> Result<Self, String> {
        let package_set_version = format!(
            "debian{}.g{}",
            manifest.debian_series, manifest.package_set_generation
        );
        let foundation = component(
            ComponentKey::OpenClawExecutionFoundation,
            &package_set_version,
        )?;
        let general_node = component(
            ComponentKey::GeneralNodeRuntime,
            &manifest.general_node_version,
        )?;
        let chromium = component(ComponentKey::ChromiumRuntime, &package_set_version)?;
        let adb = component(ComponentKey::AdbRuntime, &package_set_version)?;
        let vscreen = component(
            ComponentKey::VScreenRuntimeAssets,
            &manifest.scrcpy_server_version,
        )?;
        let openclaw = component(ComponentKey::OpenClawRuntime, &manifest.openclaw_version)?;
        let android_build_foundation =
            component(ComponentKey::AndroidBuildFoundation, &package_set_version)?;
        let android_sdk = component(
            ComponentKey::AndroidSdk,
            format!(
                "cmd{}.platform{}.build{}",
                manifest.android_command_tools_version,
                manifest.android_platform,
                manifest.android_build_tools
            ),
        )?;
        let gradle = component(
            ComponentKey::AndroidGradle,
            &manifest.android_gradle_version,
        )?;
        let kotlin_profile = component(
            ComponentKey::AndroidKotlinProfile,
            format!("{ANDROID_KOTLIN_PROFILE_ID}.g{ANDROID_KOTLIN_PROFILE_GENERATION}"),
        )?;
        let native_ndk = component(ComponentKey::AndroidNdk, &manifest.android_native_ndk)?;
        let native_cmake = component(ComponentKey::AndroidCmake, &manifest.android_native_cmake)?;
        let native_profile = component(
            ComponentKey::AndroidNativeProfile,
            format!("{ANDROID_NATIVE_PROFILE_ID}.g{ANDROID_NATIVE_PROFILE_GENERATION}"),
        )?;
        let flutter_sdk = component(ComponentKey::FlutterSdk, &manifest.flutter_version)?;
        let flutter_platform = component(
            ComponentKey::AndroidPlatform,
            &manifest.flutter_android_platform,
        )?;
        let flutter_ndk = component(ComponentKey::AndroidNdk, &manifest.flutter_android_ndk)?;
        let flutter_profile = component(
            ComponentKey::FlutterProfile,
            format!("{FLUTTER_PROFILE_ID}.g{FLUTTER_PROFILE_GENERATION}"),
        )?;
        let godot_engine = component(ComponentKey::GodotEngine, &manifest.godot_version)?;
        let godot_export_templates =
            component(ComponentKey::GodotExportTemplates, &manifest.godot_version)?;
        let godot_profile = component(
            ComponentKey::GodotAndroidProfile,
            format!("{GODOT_ANDROID_PROFILE_ID}.g{GODOT_ANDROID_PROFILE_GENERATION}"),
        )?;
        let react_native_gradle = component(
            ComponentKey::AndroidGradle,
            &manifest.react_native_gradle_version,
        )?;
        let react_native_ndk =
            component(ComponentKey::AndroidNdk, &manifest.react_native_android_ndk)?;
        let react_native_distribution = component(
            ComponentKey::ReactNativeDistribution,
            format!(
                "{}.g{REACT_NATIVE_DISTRIBUTION_GENERATION}",
                manifest.react_native_version
            ),
        )?;
        let react_native_profile = component(
            ComponentKey::ReactNativeAndroidProfile,
            format!("{REACT_NATIVE_ANDROID_PROFILE_ID}.g{REACT_NATIVE_ANDROID_PROFILE_GENERATION}"),
        )?;
        let web_distribution = component(
            ComponentKey::WebDistribution,
            format!(
                "vite{}.g{WEB_DISTRIBUTION_GENERATION}",
                manifest.web_vite_version
            ),
        )?;
        let web_profile = component(
            ComponentKey::WebProfile,
            format!("{WEB_DEVELOPMENT_PROFILE_ID}.g{WEB_DEVELOPMENT_PROFILE_GENERATION}"),
        )?;

        Self::try_new(
            vec![
                node(foundation.clone(), &[]),
                node(general_node.clone(), std::slice::from_ref(&foundation)),
                node(chromium.clone(), std::slice::from_ref(&foundation)),
                node(adb.clone(), std::slice::from_ref(&foundation)),
                node(vscreen.clone(), std::slice::from_ref(&foundation)),
                node(openclaw.clone(), std::slice::from_ref(&foundation)),
                node(
                    android_build_foundation.clone(),
                    std::slice::from_ref(&foundation),
                ),
                node(
                    android_sdk.clone(),
                    std::slice::from_ref(&android_build_foundation),
                ),
                node(gradle.clone(), std::slice::from_ref(&android_sdk)),
                node(kotlin_profile.clone(), std::slice::from_ref(&gradle)),
                node(native_ndk.clone(), std::slice::from_ref(&android_sdk)),
                node(native_cmake.clone(), std::slice::from_ref(&android_sdk)),
                node(
                    native_profile.clone(),
                    &[gradle.clone(), native_ndk, native_cmake.clone()],
                ),
                node(flutter_sdk.clone(), std::slice::from_ref(&foundation)),
                node(flutter_platform.clone(), std::slice::from_ref(&android_sdk)),
                node(flutter_ndk.clone(), std::slice::from_ref(&android_sdk)),
                node(
                    flutter_profile.clone(),
                    &[gradle, flutter_sdk, flutter_platform, flutter_ndk],
                ),
                node(godot_engine.clone(), std::slice::from_ref(&foundation)),
                node(
                    godot_export_templates.clone(),
                    std::slice::from_ref(&foundation),
                ),
                node(
                    godot_profile.clone(),
                    &[android_sdk.clone(), godot_engine, godot_export_templates],
                ),
                node(
                    react_native_gradle.clone(),
                    std::slice::from_ref(&android_sdk),
                ),
                node(react_native_ndk.clone(), std::slice::from_ref(&android_sdk)),
                node(
                    react_native_distribution.clone(),
                    std::slice::from_ref(&general_node),
                ),
                node(
                    react_native_profile.clone(),
                    &[
                        android_sdk,
                        react_native_gradle,
                        react_native_ndk,
                        native_cmake,
                        react_native_distribution,
                    ],
                ),
                node(
                    web_distribution.clone(),
                    std::slice::from_ref(&general_node),
                ),
                node(web_profile.clone(), &[foundation.clone(), web_distribution]),
            ],
            vec![foundation, general_node, chromium, adb, vscreen, openclaw],
            BTreeMap::from([
                (DeveloperCapability::AndroidKotlin, kotlin_profile),
                (DeveloperCapability::AndroidNative, native_profile),
                (DeveloperCapability::Flutter, flutter_profile),
                (DeveloperCapability::GodotAndroid, godot_profile),
                (DeveloperCapability::ReactNative, react_native_profile),
                (DeveloperCapability::WebDevelopment, web_profile),
            ]),
        )
    }

    fn try_new(
        nodes: Vec<ComponentNode>,
        required: Vec<ComponentIdentity>,
        roots: BTreeMap<DeveloperCapability, ComponentIdentity>,
    ) -> Result<Self, String> {
        let identities = nodes
            .iter()
            .map(|node| &node.identity)
            .collect::<BTreeSet<_>>();
        if identities.len() != nodes.len() {
            return Err("duplicate component identity".into());
        }
        for node in &nodes {
            if node
                .dependencies
                .iter()
                .any(|dependency| !identities.contains(dependency))
            {
                return Err("unknown component dependency".into());
            }
        }
        for key in [
            ComponentKey::OpenClawExecutionFoundation,
            ComponentKey::GeneralNodeRuntime,
            ComponentKey::ChromiumRuntime,
            ComponentKey::AdbRuntime,
            ComponentKey::VScreenRuntimeAssets,
            ComponentKey::OpenClawRuntime,
            ComponentKey::AndroidBuildFoundation,
            ComponentKey::AndroidSdk,
            ComponentKey::AndroidGradle,
            ComponentKey::AndroidKotlinProfile,
            ComponentKey::AndroidNativeProfile,
            ComponentKey::FlutterSdk,
            ComponentKey::FlutterProfile,
            ComponentKey::GodotAndroidProfile,
            ComponentKey::ReactNativeAndroidProfile,
            ComponentKey::WebProfile,
        ] {
            let versions = nodes
                .iter()
                .filter(|node| node.identity.key == key)
                .map(|node| node.identity.version.as_str())
                .collect::<BTreeSet<_>>();
            if versions.len() > 1 && !key.allows_side_by_side() {
                return Err("incompatible singleton component versions".into());
            }
        }
        if required.is_empty()
            || required.iter().collect::<BTreeSet<_>>().len() != required.len()
            || required.iter().any(|root| !identities.contains(root))
            || DeveloperCapability::ORDERED
                .into_iter()
                .any(|capability| !roots.contains_key(&capability))
            || roots.values().any(|root| !identities.contains(root))
        {
            return Err("invalid capability root".into());
        }
        let graph = Self {
            nodes,
            required,
            roots,
        };
        graph.resolve(&DeveloperCapability::ORDERED)?;
        Ok(graph)
    }

    pub fn resolve(
        &self,
        selected: &[DeveloperCapability],
    ) -> Result<Vec<ComponentIdentity>, String> {
        let mut visiting = BTreeSet::new();
        let mut resolved = Vec::new();
        for root in &self.required {
            self.visit(root, &mut visiting, &mut resolved)?;
        }
        for capability in DeveloperCapability::ORDERED {
            if selected.contains(&capability) {
                let root = self
                    .roots
                    .get(&capability)
                    .ok_or("unknown capability root")?;
                self.visit(root, &mut visiting, &mut resolved)?;
            }
        }
        Ok(resolved)
    }

    fn visit(
        &self,
        identity: &ComponentIdentity,
        visiting: &mut BTreeSet<ComponentIdentity>,
        resolved: &mut Vec<ComponentIdentity>,
    ) -> Result<(), String> {
        if resolved.contains(identity) {
            return Ok(());
        }
        if !visiting.insert(identity.clone()) {
            return Err("component dependency cycle".into());
        }
        let node = self.node(identity).ok_or("unknown component dependency")?;
        for dependency in &node.dependencies {
            self.visit(dependency, visiting, resolved)?;
        }
        visiting.remove(identity);
        resolved.push(identity.clone());
        Ok(())
    }

    pub fn node(&self, identity: &ComponentIdentity) -> Option<&ComponentNode> {
        self.nodes.iter().find(|node| node.identity == *identity)
    }

    pub fn is_required(&self, identity: &ComponentIdentity) -> bool {
        self.required.iter().any(|root| {
            let mut visiting = BTreeSet::new();
            let mut resolved = Vec::new();
            self.visit(root, &mut visiting, &mut resolved).is_ok() && resolved.contains(identity)
        })
    }

    pub fn consumers(&self, identity: &ComponentIdentity) -> Vec<DeveloperCapability> {
        DeveloperCapability::ORDERED
            .into_iter()
            .filter(|capability| {
                self.resolve(std::slice::from_ref(capability))
                    .is_ok_and(|resolved| resolved.contains(identity))
            })
            .collect()
    }
}

fn component(key: ComponentKey, version: impl Into<String>) -> Result<ComponentIdentity, String> {
    ComponentIdentity::new(key, version)
}

fn node(identity: ComponentIdentity, dependencies: &[ComponentIdentity]) -> ComponentNode {
    ComponentNode {
        identity,
        dependencies: dependencies.to_vec(),
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CapabilityPlanRequest {
    pub id: String,
    pub selected: Vec<DeveloperCapability>,
}

impl CapabilityPlanRequest {
    pub fn parse(value: &str) -> Option<Self> {
        let (id, selected) = value.split_once(':')?;
        if !valid_plan_id(id) {
            return None;
        }
        let selected = parse_optional_capability_wire_list(selected)?;
        Some(Self {
            id: id.to_owned(),
            selected,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CapabilityPlan {
    pub id: String,
    pub selected: Vec<DeveloperCapability>,
    pub resolved: Vec<ComponentIdentity>,
    pub completed: Vec<ComponentIdentity>,
    pub skipped: Vec<DeveloperCapability>,
    pub failed: Option<ComponentIdentity>,
    pub failure_code: Option<u8>,
}

impl CapabilityPlan {
    pub fn create(request: CapabilityPlanRequest, graph: &ReleaseGraph) -> Result<Self, String> {
        Ok(Self {
            id: request.id,
            resolved: graph.resolve(&request.selected)?,
            selected: request.selected,
            completed: Vec::new(),
            skipped: Vec::new(),
            failed: None,
            failure_code: None,
        })
    }

    pub fn accepts(&self, request: &CapabilityPlanRequest) -> bool {
        self.id == request.id && self.selected == request.selected
    }

    pub fn is_component_complete(
        &self,
        component: &ComponentIdentity,
        graph: &ReleaseGraph,
    ) -> bool {
        self.completed.contains(component) || self.is_component_skipped(component, graph)
    }

    pub fn is_component_skipped(
        &self,
        component: &ComponentIdentity,
        graph: &ReleaseGraph,
    ) -> bool {
        let selected_consumers = graph
            .consumers(component)
            .into_iter()
            .filter(|consumer| self.selected.contains(consumer))
            .collect::<Vec<_>>();
        !graph.is_required(component)
            && !selected_consumers.is_empty()
            && selected_consumers
                .iter()
                .all(|consumer| self.skipped.contains(consumer))
    }

    pub fn mark_completed(&mut self, component: &ComponentIdentity) {
        if !self.completed.contains(component) {
            self.completed.push(component.clone());
            self.completed
                .sort_by_key(|value| self.resolved.iter().position(|item| item == value));
        }
        self.failed = None;
        self.failure_code = None;
    }

    pub fn invalidate_completed(&mut self, component: &ComponentIdentity) {
        self.completed.retain(|value| value != component);
    }

    pub fn mark_failed(&mut self, component: &ComponentIdentity, code: u8) {
        self.failed = Some(component.clone());
        self.failure_code = Some(code);
    }

    pub fn skip_failed(&mut self, capability: DeveloperCapability, graph: &ReleaseGraph) -> bool {
        let Some(failed) = self.failed.as_ref() else {
            return false;
        };
        let active_consumers = graph
            .consumers(failed)
            .into_iter()
            .filter(|consumer| self.selected.contains(consumer) && !self.skipped.contains(consumer))
            .collect::<Vec<_>>();
        if active_consumers != [capability] {
            return false;
        }
        if !self.skipped.contains(&capability) {
            self.skipped.push(capability);
            self.skipped.sort();
        }
        self.failed = None;
        self.failure_code = None;
        true
    }

    pub fn is_settled(&self, graph: &ReleaseGraph) -> bool {
        self.failed.is_none()
            && self
                .resolved
                .iter()
                .all(|component| self.is_component_complete(component, graph))
    }

    pub fn required_environment_ready(&self, graph: &ReleaseGraph) -> bool {
        self.failed
            .as_ref()
            .is_none_or(|component| !graph.is_required(component))
            && graph
                .required
                .iter()
                .all(|component| self.is_component_complete(component, graph))
    }

    pub fn ready_capabilities(&self, graph: &ReleaseGraph) -> Vec<DeveloperCapability> {
        DeveloperCapability::ORDERED
            .into_iter()
            .filter(|capability| {
                self.selected.contains(capability)
                    && !self.skipped.contains(capability)
                    && graph
                        .resolve(std::slice::from_ref(capability))
                        .is_ok_and(|resolved| {
                            resolved
                                .iter()
                                .all(|component| self.is_component_complete(component, graph))
                        })
            })
            .collect()
    }

    /// Extends a settled plan with capabilities whose exact current component
    /// closure was independently verified by the Supervisor.
    pub fn adopt_verified_capabilities(
        &self,
        id: String,
        verified: &[DeveloperCapability],
        graph: &ReleaseGraph,
    ) -> Result<Option<Self>, String> {
        if !self.is_settled(graph) {
            return Ok(None);
        }
        let selected = DeveloperCapability::ORDERED
            .into_iter()
            .filter(|capability| {
                self.selected.contains(capability) || verified.contains(capability)
            })
            .collect::<Vec<_>>();
        let resolved = graph.resolve(&selected)?;
        let skipped = self
            .skipped
            .iter()
            .copied()
            .filter(|capability| !verified.contains(capability))
            .collect::<Vec<_>>();
        let verified_components = verified
            .iter()
            .map(|capability| graph.resolve(std::slice::from_ref(capability)))
            .collect::<Result<Vec<_>, _>>()?
            .into_iter()
            .flatten()
            .collect::<BTreeSet<_>>();
        let completed = resolved
            .iter()
            .filter(|component| {
                self.completed.contains(component) || verified_components.contains(component)
            })
            .cloned()
            .collect::<Vec<_>>();
        if selected == self.selected
            && resolved == self.resolved
            && completed == self.completed
            && skipped == self.skipped
        {
            return Ok(None);
        }
        Ok(Some(Self {
            id,
            selected,
            resolved,
            completed,
            skipped,
            failed: None,
            failure_code: None,
        }))
    }
}

pub struct CapabilityPlanStore {
    path: PathBuf,
}

impl CapabilityPlanStore {
    pub fn new(state_dir: &Path) -> Self {
        Self {
            path: state_dir.join("environment-setup-v6.plan"),
        }
    }

    pub fn load(&self, graph: &ReleaseGraph) -> Result<Option<CapabilityPlan>, String> {
        let encoded = match fs::read_to_string(&self.path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(format!("cannot read capability plan: {error}")),
        };
        let stored = parse_stored_plan(&encoded)
            .ok_or_else(|| "stored capability plan is invalid".to_owned())?;
        if plan_matches_graph(&stored, graph) {
            return Ok(Some(stored));
        }

        let resolved = graph.resolve(&stored.selected)?;
        if stored.resolved == resolved {
            return Err("stored capability plan is invalid".to_owned());
        }

        // Product capability choices are stable intent; exact component identities
        // belong to one release graph. A release change therefore starts a fresh,
        // one-way plan and verifies every component again before reuse.
        let plan = CapabilityPlan::create(
            CapabilityPlanRequest {
                id: stored.id,
                selected: stored.selected,
            },
            graph,
        )?;
        self.save(&plan)?;
        Ok(Some(plan))
    }

    pub fn save(&self, plan: &CapabilityPlan) -> Result<(), String> {
        let failed = plan
            .failed
            .as_ref()
            .map_or_else(|| "-".to_owned(), ComponentIdentity::wire_name);
        let failure_code = plan
            .failure_code
            .map_or_else(|| "-".to_owned(), |value| value.to_string());
        let encoded = format!(
            "version={PLAN_FORMAT_VERSION}\nid={}\nselected={}\nresolved={}\ncompleted={}\nskipped={}\nfailed={failed}\nfailure_code={failure_code}\n",
            plan.id,
            optional_capability_list(&plan.selected),
            component_list(&plan.resolved),
            component_list(&plan.completed),
            capability_list(&plan.skipped),
        );
        atomic_write(&self.path, encoded.as_bytes())
    }
}

pub fn capability_list(values: &[DeveloperCapability]) -> String {
    values
        .iter()
        .map(|value| value.wire_name())
        .collect::<Vec<_>>()
        .join(",")
}

pub fn optional_capability_list(values: &[DeveloperCapability]) -> String {
    if values.is_empty() {
        "-".to_owned()
    } else {
        capability_list(values)
    }
}

pub fn component_list(values: &[ComponentIdentity]) -> String {
    values
        .iter()
        .map(ComponentIdentity::wire_name)
        .collect::<Vec<_>>()
        .join(",")
}

pub fn valid_plan_id(value: &str) -> bool {
    value.len() == 32
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn parse_stored_plan(encoded: &str) -> Option<CapabilityPlan> {
    let lines = encoded.lines().collect::<Vec<_>>();
    if lines.len() != 8 || lines[0] != format!("version={PLAN_FORMAT_VERSION}") {
        return None;
    }
    let id = lines[1].strip_prefix("id=")?;
    let selected = parse_optional_capability_wire_list(lines[2].strip_prefix("selected=")?)?;
    let resolved = parse_stored_component_list(lines[3].strip_prefix("resolved=")?, false)?;
    let completed = parse_stored_component_list(lines[4].strip_prefix("completed=")?, true)?;
    let skipped = parse_optional_capability_list(lines[5].strip_prefix("skipped=")?)?;
    let failed_value = lines[6].strip_prefix("failed=")?;
    let failed = if failed_value == "-" {
        None
    } else {
        Some(parse_stored_component(failed_value)?)
    };
    let failure_code_value = lines[7].strip_prefix("failure_code=")?;
    let failure_code = if failure_code_value == "-" {
        None
    } else {
        Some(
            failure_code_value
                .parse::<u8>()
                .ok()
                .filter(|value| *value > 0)?,
        )
    };
    if !valid_plan_id(id)
        || completed
            != resolved
                .iter()
                .filter(|value| completed.contains(value))
                .cloned()
                .collect::<Vec<_>>()
        || skipped.iter().any(|value| !selected.contains(value))
        || failed
            .as_ref()
            .is_some_and(|value| !resolved.contains(value) || completed.contains(value))
        || failed.is_some() != failure_code.is_some()
    {
        return None;
    }
    Some(CapabilityPlan {
        id: id.to_owned(),
        selected,
        resolved,
        completed,
        skipped,
        failed,
        failure_code,
    })
}

fn plan_matches_graph(plan: &CapabilityPlan, graph: &ReleaseGraph) -> bool {
    plan.resolved == graph.resolve(&plan.selected).ok().unwrap_or_default()
        && plan.completed.iter().all(|value| {
            let selected_consumers = graph
                .consumers(value)
                .into_iter()
                .filter(|consumer| plan.selected.contains(consumer))
                .collect::<Vec<_>>();
            graph.is_required(value)
                || (!selected_consumers.is_empty()
                    && !selected_consumers
                        .iter()
                        .all(|consumer| plan.skipped.contains(consumer)))
        })
        && plan.failed.as_ref().is_none_or(|value| {
            let selected_consumers = graph
                .consumers(value)
                .into_iter()
                .filter(|consumer| plan.selected.contains(consumer))
                .collect::<Vec<_>>();
            plan.resolved.contains(value)
                && !plan.completed.contains(value)
                && (graph.is_required(value)
                    || (!selected_consumers.is_empty()
                        && !selected_consumers
                            .iter()
                            .all(|consumer| plan.skipped.contains(consumer))))
        })
}

fn parse_capability_list(value: &str) -> Option<Vec<DeveloperCapability>> {
    parse_ordered_list(value, DeveloperCapability::parse, capability_list)
}

fn parse_optional_capability_list(value: &str) -> Option<Vec<DeveloperCapability>> {
    if value.is_empty() {
        Some(Vec::new())
    } else {
        parse_capability_list(value)
    }
}

fn parse_optional_capability_wire_list(value: &str) -> Option<Vec<DeveloperCapability>> {
    if value == "-" {
        Some(Vec::new())
    } else {
        parse_capability_list(value)
    }
}

fn parse_stored_component(value: &str) -> Option<ComponentIdentity> {
    let (key, version) = value.split_once('@')?;
    ComponentIdentity::new(ComponentKey::parse(key)?, version).ok()
}

fn parse_stored_component_list(value: &str, optional: bool) -> Option<Vec<ComponentIdentity>> {
    if value.is_empty() {
        return optional.then(Vec::new);
    }
    let parsed = value
        .split(',')
        .map(parse_stored_component)
        .collect::<Option<Vec<_>>>()?;
    (component_list(&parsed) == value
        && parsed.iter().collect::<BTreeSet<_>>().len() == parsed.len())
    .then_some(parsed)
}

fn parse_ordered_list<T: Copy + Ord>(
    value: &str,
    parse: impl Fn(&str) -> Option<T>,
    encode: impl Fn(&[T]) -> String,
) -> Option<Vec<T>> {
    if value.is_empty() {
        return None;
    }
    let parsed = value.split(',').map(parse).collect::<Option<Vec<_>>>()?;
    if encode(&parsed) != value || parsed.windows(2).any(|pair| pair[0] >= pair[1]) {
        return None;
    }
    Some(parsed)
}

fn atomic_write(path: &Path, value: &[u8]) -> Result<(), String> {
    let temporary = path.with_extension(format!("next.{}", std::process::id()));
    fs::write(&temporary, value)
        .and_then(|()| fs::rename(&temporary, path))
        .map_err(|error| format!("cannot persist {}: {error}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest() -> InstallManifest {
        InstallManifest::test_fixture()
    }

    #[test]
    fn resolves_shared_core_once_and_keeps_ndk_versions_side_by_side() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let resolved = graph.resolve(&DeveloperCapability::ORDERED).unwrap();
        assert_eq!(
            resolved
                .iter()
                .filter(|component| component.key == ComponentKey::AndroidSdk)
                .count(),
            1
        );
        assert_eq!(
            resolved
                .iter()
                .filter(|component| component.key == ComponentKey::AndroidNdk)
                .map(|component| component.version.as_str())
                .collect::<Vec<_>>(),
            vec!["29.0.14206865", "28.2.13676358", "27.1.12297006"]
        );
    }

    #[test]
    fn empty_extension_selection_resolves_only_required_setup() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request = CapabilityPlanRequest::parse(&format!("{}:-", "a".repeat(32))).unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();

        assert!(plan.selected.is_empty());
        assert_eq!(
            plan.resolved
                .iter()
                .map(|component| component.key)
                .collect::<Vec<_>>(),
            vec![
                ComponentKey::OpenClawExecutionFoundation,
                ComponentKey::GeneralNodeRuntime,
                ComponentKey::ChromiumRuntime,
                ComponentKey::AdbRuntime,
                ComponentKey::VScreenRuntimeAssets,
                ComponentKey::OpenClawRuntime,
            ]
        );
        assert!(!plan.required_environment_ready(&graph));
        for component in plan.resolved.clone() {
            plan.mark_completed(&component);
        }
        assert!(plan.required_environment_ready(&graph));
        assert!(plan.ready_capabilities(&graph).is_empty());
    }

    #[test]
    fn optional_extension_failure_does_not_invalidate_required_environment() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request =
            CapabilityPlanRequest::parse(&format!("{}:android_kotlin", "b".repeat(32))).unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        for component in plan
            .resolved
            .clone()
            .into_iter()
            .filter(|component| graph.is_required(component))
        {
            plan.mark_completed(&component);
        }
        let kotlin = plan
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::AndroidKotlinProfile)
            .unwrap()
            .clone();
        plan.mark_failed(&kotlin, 60);

        assert!(plan.required_environment_ready(&graph));
        assert!(!plan.is_settled(&graph));
    }

    #[test]
    fn react_native_uses_its_own_node_and_gradle_without_framework_profiles() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let resolved = graph.resolve(&[DeveloperCapability::ReactNative]).unwrap();
        assert_eq!(
            resolved
                .iter()
                .map(ComponentIdentity::wire_name)
                .collect::<Vec<_>>(),
            vec![
                "openclaw_execution_foundation@debian13.g1",
                "general_node_runtime@22.22.0",
                "chromium_runtime@debian13.g1",
                "adb_runtime@debian13.g1",
                "vscreen_runtime_assets@4.1",
                "openclaw_runtime@2026.9.4",
                "android_build_foundation@debian13.g1",
                "android_sdk@cmd16111833.platform37.0.build37.0.0",
                "android_gradle@9.4.1",
                "android_ndk@27.1.12297006",
                "android_cmake@3.22.1",
                "react_native_distribution@0.87.1.g1",
                "react_native_android_profile@react-native-android-v1.g1",
            ]
        );
        assert!(!resolved.iter().any(|component| {
            matches!(
                component.key,
                ComponentKey::AndroidKotlinProfile
                    | ComponentKey::AndroidNativeProfile
                    | ComponentKey::FlutterProfile
                    | ComponentKey::GodotAndroidProfile
            )
        }));
    }

    #[test]
    fn web_reuses_required_general_node_without_any_android_component() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let resolved = graph
            .resolve(&[DeveloperCapability::WebDevelopment])
            .unwrap();
        assert_eq!(
            resolved
                .iter()
                .map(ComponentIdentity::wire_name)
                .collect::<Vec<_>>(),
            vec![
                "openclaw_execution_foundation@debian13.g1",
                "general_node_runtime@22.22.0",
                "chromium_runtime@debian13.g1",
                "adb_runtime@debian13.g1",
                "vscreen_runtime_assets@4.1",
                "openclaw_runtime@2026.9.4",
                "web_distribution@vite8.3.0.g1",
                "web_profile@web-development-v1.g1",
            ]
        );
        assert!(!resolved.iter().any(|component| {
            matches!(
                component.key,
                ComponentKey::AndroidSdk
                    | ComponentKey::AndroidPlatform
                    | ComponentKey::AndroidGradle
                    | ComponentKey::AndroidNdk
                    | ComponentKey::AndroidCmake
                    | ComponentKey::ReactNativeDistribution
            )
        }));
    }

    #[test]
    fn shared_cmake_can_only_be_skipped_for_its_last_active_consumer() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,android_native,react_native",
            "e".repeat(32)
        ))
        .unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        let cmake = plan
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::AndroidCmake)
            .unwrap()
            .clone();
        assert_eq!(
            graph.consumers(&cmake),
            vec![
                DeveloperCapability::AndroidNative,
                DeveloperCapability::ReactNative
            ]
        );
        plan.mark_failed(&cmake, 60);
        assert!(!plan.skip_failed(DeveloperCapability::ReactNative, &graph));

        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,react_native",
            "f".repeat(32)
        ))
        .unwrap();
        let mut react_native_only = CapabilityPlan::create(request, &graph).unwrap();
        let cmake = react_native_only
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::AndroidCmake)
            .unwrap()
            .clone();
        react_native_only.mark_failed(&cmake, 60);
        assert!(react_native_only.skip_failed(DeveloperCapability::ReactNative, &graph));
    }

    #[test]
    fn godot_reuses_the_sdk_without_claiming_gradle_or_ndk() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let resolved = graph.resolve(&[DeveloperCapability::GodotAndroid]).unwrap();
        assert_eq!(
            resolved
                .iter()
                .map(|component| component.key)
                .collect::<Vec<_>>(),
            vec![
                ComponentKey::OpenClawExecutionFoundation,
                ComponentKey::GeneralNodeRuntime,
                ComponentKey::ChromiumRuntime,
                ComponentKey::AdbRuntime,
                ComponentKey::VScreenRuntimeAssets,
                ComponentKey::OpenClawRuntime,
                ComponentKey::AndroidBuildFoundation,
                ComponentKey::AndroidSdk,
                ComponentKey::GodotEngine,
                ComponentKey::GodotExportTemplates,
                ComponentKey::GodotAndroidProfile,
            ],
        );
        assert!(!resolved.iter().any(|component| {
            component.key == ComponentKey::AndroidGradle
                || component.key == ComponentKey::AndroidNdk
                || component.key == ComponentKey::AndroidCmake
        }));
    }

    #[test]
    fn rejects_unknown_duplicate_cycle_and_singleton_conflict() {
        let foundation =
            component(ComponentKey::OpenClawExecutionFoundation, "debian13.g1").unwrap();
        let unknown = component(ComponentKey::AndroidSdk, "missing").unwrap();
        let roots = BTreeMap::from([
            (DeveloperCapability::AndroidKotlin, foundation.clone()),
            (DeveloperCapability::AndroidNative, foundation.clone()),
            (DeveloperCapability::Flutter, foundation.clone()),
            (DeveloperCapability::GodotAndroid, foundation.clone()),
            (DeveloperCapability::ReactNative, foundation.clone()),
            (DeveloperCapability::WebDevelopment, foundation.clone()),
        ]);
        assert!(
            ReleaseGraph::try_new(
                vec![node(foundation.clone(), &[unknown])],
                vec![foundation.clone()],
                roots.clone(),
            )
            .is_err()
        );
        assert!(
            ReleaseGraph::try_new(
                vec![node(foundation.clone(), &[]), node(foundation.clone(), &[])],
                vec![foundation.clone()],
                roots.clone(),
            )
            .is_err()
        );
        assert!(
            ReleaseGraph::try_new(
                vec![node(foundation.clone(), std::slice::from_ref(&foundation))],
                vec![foundation.clone()],
                roots.clone(),
            )
            .is_err()
        );
        let other = component(ComponentKey::OpenClawExecutionFoundation, "debian13.g2").unwrap();
        assert!(
            ReleaseGraph::try_new(
                vec![node(foundation.clone(), &[]), node(other, &[])],
                vec![foundation],
                roots,
            )
            .is_err()
        );
    }

    #[test]
    fn persists_only_the_current_versioned_plan_shape() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let root =
            std::env::temp_dir().join(format!("claw-capability-plan-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let store = CapabilityPlanStore::new(&root);
        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,android_native,flutter,godot_android,react_native",
            "b".repeat(32)
        ))
        .unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        let first = plan.resolved[0].clone();
        plan.mark_completed(&first);
        store.save(&plan).unwrap();
        assert_eq!(store.load(&graph).unwrap(), Some(plan));
        fs::write(root.join("environment-setup-v6.plan"), "version=5\n").unwrap();
        assert!(store.load(&graph).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn release_change_rebuilds_exact_plan_from_stable_capability_choices() {
        let current_graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let mut previous_manifest = manifest();
        previous_manifest.openclaw_version = "2026.8.2".into();
        let previous_graph = ReleaseGraph::for_manifest(&previous_manifest).unwrap();
        let root = std::env::temp_dir().join(format!(
            "claw-capability-plan-release-change-{}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();
        let store = CapabilityPlanStore::new(&root);
        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,web_development",
            "c".repeat(32)
        ))
        .unwrap();
        let mut previous_plan = CapabilityPlan::create(request, &previous_graph).unwrap();
        let first = previous_plan.resolved[0].clone();
        previous_plan.mark_completed(&first);
        store.save(&previous_plan).unwrap();

        let migrated = store.load(&current_graph).unwrap().unwrap();
        assert_eq!(migrated.id, previous_plan.id);
        assert_eq!(migrated.selected, previous_plan.selected);
        assert_eq!(
            migrated.resolved,
            current_graph.resolve(&migrated.selected).unwrap()
        );
        assert!(
            migrated
                .resolved
                .iter()
                .any(|component| component.wire_name() == "openclaw_runtime@2026.9.4")
        );
        assert!(migrated.completed.is_empty());
        assert!(migrated.skipped.is_empty());
        assert!(migrated.failed.is_none());
        assert_eq!(store.load(&current_graph).unwrap(), Some(migrated));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn only_the_failed_optional_owner_can_be_skipped() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,android_native,flutter,godot_android,react_native",
            "d".repeat(32)
        ))
        .unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        let native = plan
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::AndroidNativeProfile)
            .unwrap()
            .clone();
        plan.mark_failed(&native, 61);
        assert!(!plan.skip_failed(DeveloperCapability::Flutter, &graph));
        assert!(plan.skip_failed(DeveloperCapability::AndroidNative, &graph));
    }

    #[test]
    fn ready_capabilities_are_a_cheap_projection_of_the_verified_plan() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request = CapabilityPlanRequest::parse(&format!(
            "{}:android_kotlin,web_development",
            "a".repeat(32)
        ))
        .unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        assert!(plan.ready_capabilities(&graph).is_empty());

        for component in graph
            .resolve(&[DeveloperCapability::AndroidKotlin])
            .unwrap()
        {
            plan.mark_completed(&component);
        }
        assert_eq!(
            plan.ready_capabilities(&graph),
            vec![DeveloperCapability::AndroidKotlin]
        );

        for component in graph
            .resolve(&[DeveloperCapability::WebDevelopment])
            .unwrap()
        {
            plan.mark_completed(&component);
        }
        assert_eq!(
            plan.ready_capabilities(&graph),
            vec![
                DeveloperCapability::AndroidKotlin,
                DeveloperCapability::WebDevelopment
            ]
        );
    }

    #[test]
    fn settled_plan_adopts_only_explicitly_verified_current_capabilities() {
        let graph = ReleaseGraph::for_manifest(&manifest()).unwrap();
        let request =
            CapabilityPlanRequest::parse(&format!("{}:android_kotlin", "a".repeat(32))).unwrap();
        let mut plan = CapabilityPlan::create(request, &graph).unwrap();
        for component in plan.resolved.clone() {
            plan.mark_completed(&component);
        }

        let adopted = plan
            .adopt_verified_capabilities(
                "b".repeat(32),
                &[
                    DeveloperCapability::AndroidNative,
                    DeveloperCapability::WebDevelopment,
                ],
                &graph,
            )
            .unwrap()
            .unwrap();

        assert_eq!(
            adopted.selected,
            vec![
                DeveloperCapability::AndroidKotlin,
                DeveloperCapability::AndroidNative,
                DeveloperCapability::WebDevelopment,
            ]
        );
        assert_eq!(adopted.id, "b".repeat(32));
        assert_eq!(adopted.ready_capabilities(&graph), adopted.selected);
        assert!(adopted.is_settled(&graph));
        assert!(
            adopted
                .adopt_verified_capabilities("c".repeat(32), &adopted.selected, &graph)
                .unwrap()
                .is_none()
        );
    }
}
