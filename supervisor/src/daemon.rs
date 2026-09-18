use signal_hook::consts::{SIGINT, SIGTERM};
use signal_hook::flag;
use std::fs;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant};

use crate::capabilities::{
    adb_runtime_is_ready, android_build_foundation_is_ready, android_kotlin_profile_is_ready,
    android_native_profile_component_is_ready, android_package_is_ready, android_sdk_is_ready,
    chromium_runtime_is_ready, flutter_profile_component_is_ready, flutter_sdk_is_ready,
    general_node_runtime_is_ready, godot_android_profile_component_is_ready, godot_engine_is_ready,
    godot_export_templates_are_ready, install_adb_runtime, install_android_build_foundation,
    install_android_kotlin_profile_component, install_android_native_profile_component,
    install_android_package_component, install_android_sdk_component, install_chromium_runtime,
    install_flutter_profile_component, install_flutter_sdk_component, install_general_node_runtime,
    install_godot_android_profile_component, install_godot_engine_component,
    install_godot_export_templates_component, install_gradle_component,
    install_openclaw_execution_foundation, install_react_native_distribution_component,
    install_react_native_profile_component, install_vscreen_runtime_assets,
    install_web_distribution_component, install_web_profile_component,
    openclaw_execution_foundation_is_ready, react_native_distribution_is_ready,
    react_native_profile_component_is_ready, vscreen_runtime_assets_are_ready,
    web_distribution_is_ready, web_profile_component_is_ready,
};
use crate::config::{Config, PROTOCOL_VERSION};
use crate::install::{DownloadProgress, InstallError, install, verify_clawinone_release};
use crate::protocol::{Operation, Protocol, Stage, StatusContext};
use crate::setup::{
    CapabilityPlan, CapabilityPlanRequest, CapabilityPlanStore, ComponentIdentity, ComponentKey,
    DeveloperCapability, ReleaseGraph,
};

pub struct Daemon {
    config: Config,
    graph: ReleaseGraph,
    protocol: Protocol,
    event_sequence: u64,
    command_sequence: u64,
    supervisor_boot_id: String,
    gateway_worker: Option<Child>,
    gateway_events: Option<Receiver<Stage>>,
    gateway_generation: Option<String>,
    gateway_attempt_id: u64,
    gateway_start_count: u64,
    gateway_stage: Option<Stage>,
    gateway_failure_code: Option<u8>,
    last_gateway_status_at: Instant,
    pairing_result_pending: bool,
    ready_capabilities: Vec<DeveloperCapability>,
    plan_store: CapabilityPlanStore,
    plan: Option<CapabilityPlan>,
    shutdown: Arc<AtomicBool>,
}

impl Daemon {
    pub fn new(config: Config) -> Result<Self, String> {
        fs::create_dir_all(&config.state_dir)
            .map_err(|error| format!("cannot create Supervisor state: {error}"))?;
        let event_sequence = read_sequence(&config.event_sequence_file());
        let command_sequence = read_sequence(&config.command_sequence_file());
        let graph = ReleaseGraph::for_manifest(&config.manifest)?;
        let plan_store = CapabilityPlanStore::new(&config.state_dir);
        let plan = plan_store.load(&graph)?;
        Ok(Self {
            protocol: Protocol::new(config.supervisor_id.clone(), config.secret.clone()),
            config,
            graph,
            event_sequence,
            command_sequence,
            supervisor_boot_id: random_runtime_id()?,
            gateway_worker: None,
            gateway_events: None,
            gateway_generation: None,
            gateway_attempt_id: 0,
            gateway_start_count: 0,
            gateway_stage: None,
            gateway_failure_code: None,
            last_gateway_status_at: Instant::now(),
            pairing_result_pending: false,
            ready_capabilities: Vec::new(),
            plan_store,
            plan,
            shutdown: Arc::new(AtomicBool::new(false)),
        })
    }

    pub fn run(mut self) -> Result<(), String> {
        flag::register(SIGINT, Arc::clone(&self.shutdown))
            .and_then(|_| flag::register(SIGTERM, Arc::clone(&self.shutdown)))
            .map_err(|error| format!("cannot register shutdown signals: {error}"))?;
        self.write_process_state("running")?;
        self.emit_unscoped(Stage::SupervisorReady, 0)?;
        self.initialize()?;
        while !self.shutdown.load(Ordering::Relaxed) {
            self.consume_gateway_events()?;
            if let Some(command) = self
                .protocol
                .read_command(&self.config.command_file, self.command_sequence)
            {
                self.pairing_result_pending = false;
                self.command_sequence = command.sequence;
                write_sequence(&self.config.command_sequence_file(), self.command_sequence)?;
                match command.operation {
                    Operation::Probe => self.probe()?,
                    Operation::ApplyCapabilities(request) => self.apply_capabilities(request)?,
                    Operation::RetryCapabilities(plan_id) => self.retry_capabilities(&plan_id)?,
                    Operation::SkipCapability {
                        plan_id,
                        capability,
                    } => self.skip_capability(&plan_id, capability)?,
                    Operation::EnsureGateway => self.ensure_gateway()?,
                    Operation::RequestGatewayPairing => self.pair_gateway()?,
                }
            }
            if let Some(worker) = self.gateway_worker.as_mut()
                && let Some(status) = worker
                    .try_wait()
                    .map_err(|error| format!("cannot inspect Gateway worker: {error}"))?
            {
                self.gateway_worker = None;
                self.gateway_events = None;
                if !self.shutdown.load(Ordering::Relaxed) {
                    let code = bounded_exit_code(status.code().unwrap_or(40));
                    self.gateway_stage = Some(Stage::GatewayFailed);
                    self.gateway_failure_code = Some(code);
                    if !self.pairing_result_pending {
                        self.emit(Stage::GatewayFailed, code, None, None)?;
                    }
                }
            }
            if let Some(stage) = self.gateway_stage
                && !self.pairing_result_pending
                && self.plan.as_ref().is_none_or(|plan| plan.failed.is_none())
                && self.last_gateway_status_at.elapsed() >= Duration::from_secs(5)
            {
                self.emit(stage, self.gateway_failure_code.unwrap_or(0), None, None)?;
            }
            thread::sleep(Duration::from_millis(250));
        }
        self.stop_gateway();
        self.write_process_state("stopped")
    }

    fn initialize(&mut self) -> Result<(), String> {
        self.adopt_verified_capabilities()?;
        self.refresh_ready_capabilities();
        let Some(plan) = self.plan.as_ref() else {
            return self.emit_unscoped(Stage::CapabilitiesRequired, 0);
        };
        if let Some(component) = plan.failed.clone() {
            return self.emit(
                Stage::CapabilityFailed,
                plan.failure_code.unwrap_or(1),
                Some(&component),
                None,
            );
        }
        if plan.is_settled(&self.graph) {
            self.probe()
        } else {
            // An accepted, durable installation plan survives Supervisor restart.
            self.run_capabilities()
        }
    }

    /** Read-only snapshot. Probe never installs components or starts processes. */
    fn probe(&mut self) -> Result<(), String> {
        let Some(plan) = self.plan.as_ref() else {
            return self.emit_unscoped(Stage::CapabilitiesRequired, 0);
        };
        if let Some(component) = plan.failed.clone() {
            return self.emit(
                Stage::CapabilityFailed,
                plan.failure_code.unwrap_or(1),
                Some(&component),
                None,
            );
        }
        if let Some(stage) = self.gateway_stage {
            return self.emit(stage, self.gateway_failure_code.unwrap_or(0), None, None);
        }
        self.emit(Stage::GatewayNotStarted, 0, None, None)
    }

    fn apply_capabilities(&mut self, request: CapabilityPlanRequest) -> Result<(), String> {
        let restart_gateway_for_repair = self.plan.as_ref().is_some_and(|plan| {
            settled_development_reapply_requires_gateway_restart(
                &plan.selected,
                &request.selected,
                plan.is_settled(&self.graph),
            )
        });
        match self.plan.as_ref() {
            Some(plan) if plan.accepts(&request) => {}
            Some(plan) if !plan.is_settled(&self.graph) => {
                return self.emit(Stage::CapabilityPlanRejected, 30, None, None);
            }
            Some(_) | None => {
                let plan = CapabilityPlan::create(request, &self.graph)?;
                self.plan_store.save(&plan)?;
                self.plan = Some(plan);
            }
        }
        if restart_gateway_for_repair {
            self.stop_gateway();
        }
        self.emit(Stage::CapabilityPlanAccepted, 0, None, None)?;
        self.run_capabilities()
    }

    fn retry_capabilities(&mut self, plan_id: &str) -> Result<(), String> {
        let Some(plan) = self.plan.as_mut() else {
            return self.emit_unscoped(Stage::CapabilitiesRequired, 0);
        };
        if plan.id != plan_id || plan.failed.is_none() {
            return self.emit(Stage::CapabilityPlanRejected, 30, None, None);
        }
        plan.failed = None;
        plan.failure_code = None;
        self.plan_store.save(plan)?;
        self.run_capabilities()
    }

    fn skip_capability(
        &mut self,
        plan_id: &str,
        capability: DeveloperCapability,
    ) -> Result<(), String> {
        let Some(plan) = self.plan.as_mut() else {
            return self.emit_unscoped(Stage::CapabilitiesRequired, 0);
        };
        if plan.id != plan_id || !plan.skip_failed(capability, &self.graph) {
            return self.emit(Stage::CapabilityPlanRejected, 30, None, None);
        }
        self.plan_store.save(plan)?;
        self.run_capabilities()
    }

    fn run_capabilities(&mut self) -> Result<(), String> {
        let resolved = self
            .plan
            .as_ref()
            .map(|plan| plan.resolved.clone())
            .ok_or("capability plan is missing")?;
        for component in resolved {
            if self
                .plan
                .as_ref()
                .is_some_and(|plan| plan.is_component_complete(&component, &self.graph))
            {
                let skipped = self
                    .plan
                    .as_ref()
                    .is_some_and(|plan| plan.is_component_skipped(&component, &self.graph));
                if skipped || self.component_is_ready(&component) {
                    continue;
                }
                let plan = self.plan.as_mut().ok_or("capability plan is missing")?;
                plan.invalidate_completed(&component);
                self.plan_store.save(plan)?;
            }
            self.install_component(&component)?;
            if self.plan.as_ref().is_some_and(|plan| plan.failed.is_some()) {
                return Ok(());
            }
        }
        self.refresh_ready_capabilities();
        self.emit(Stage::CapabilitiesReady, 0, None, None)?;
        Ok(())
    }

    fn component_is_ready(&self, component: &ComponentIdentity) -> bool {
        match component.key {
            ComponentKey::OpenClawExecutionFoundation => openclaw_execution_foundation_is_ready(),
            ComponentKey::GeneralNodeRuntime => general_node_runtime_is_ready(&self.config),
            ComponentKey::ChromiumRuntime => chromium_runtime_is_ready(),
            ComponentKey::AdbRuntime => adb_runtime_is_ready(),
            ComponentKey::VScreenRuntimeAssets => vscreen_runtime_assets_are_ready(&self.config),
            ComponentKey::OpenClawRuntime => self.openclaw_is_ready(),
            ComponentKey::AndroidBuildFoundation => android_build_foundation_is_ready(),
            ComponentKey::AndroidSdk => android_sdk_is_ready(&self.config),
            ComponentKey::AndroidPlatform => android_package_is_ready(
                &self.config,
                &format!("platforms;android-{}", component.version),
            ),
            ComponentKey::AndroidGradle => {
                crate::capabilities::gradle_version_is_ready(&self.config, &component.version)
            }
            ComponentKey::AndroidNdk => {
                android_package_is_ready(&self.config, &format!("ndk;{}", component.version))
            }
            ComponentKey::AndroidCmake => {
                android_package_is_ready(&self.config, &format!("cmake;{}", component.version))
            }
            ComponentKey::AndroidKotlinProfile => android_kotlin_profile_is_ready(&self.config),
            ComponentKey::AndroidNativeProfile => {
                android_native_profile_component_is_ready(&self.config)
            }
            ComponentKey::FlutterSdk => flutter_sdk_is_ready(&self.config),
            ComponentKey::FlutterProfile => flutter_profile_component_is_ready(&self.config),
            ComponentKey::GodotEngine => godot_engine_is_ready(&self.config),
            ComponentKey::GodotExportTemplates => godot_export_templates_are_ready(&self.config),
            ComponentKey::GodotAndroidProfile => {
                godot_android_profile_component_is_ready(&self.config)
            }
            ComponentKey::ReactNativeDistribution => {
                react_native_distribution_is_ready(&self.config)
            }
            ComponentKey::ReactNativeAndroidProfile => {
                react_native_profile_component_is_ready(&self.config)
            }
            ComponentKey::WebDistribution => web_distribution_is_ready(&self.config),
            ComponentKey::WebProfile => web_profile_component_is_ready(&self.config),
        }
    }

    fn install_component(&mut self, component: &ComponentIdentity) -> Result<(), String> {
        if self.component_is_ready(component) {
            self.mark_completed(component)?;
            return self.emit(ready_stage(component), 0, Some(component), None);
        }
        let config = self.config.clone();
        let result = if component.key == ComponentKey::OpenClawRuntime {
            install(&config, |stage, progress| {
                self.emit_progress(stage, component, progress)
            })
        } else {
            self.install_toolchain_component(&config, component)
        };
        if result.is_ok() && self.component_is_ready(component) {
            if component_requires_gateway_restart(component.key) {
                self.stop_gateway();
            }
            self.mark_completed(component)?;
            self.emit(ready_stage(component), 0, Some(component), None)
        } else {
            let error = result.err().unwrap_or_else(|| InstallError {
                code: 1,
                message: format!("{} verification failed", component.wire_name()),
            });
            eprintln!("component installation stopped: {}", error.message);
            self.mark_failed(component, error.code)?;
            self.emit(Stage::CapabilityFailed, error.code, Some(component), None)
        }
    }

    fn install_toolchain_component(
        &mut self,
        config: &Config,
        component: &ComponentIdentity,
    ) -> Result<(), InstallError> {
        let mut report = |stage, progress| self.emit_progress(stage, component, progress);
        match component.key {
            ComponentKey::OpenClawExecutionFoundation => {
                install_openclaw_execution_foundation(config, &mut report)
            }
            ComponentKey::GeneralNodeRuntime => install_general_node_runtime(config, &mut report),
            ComponentKey::ChromiumRuntime => install_chromium_runtime(config, &mut report),
            ComponentKey::AdbRuntime => install_adb_runtime(config, &mut report),
            ComponentKey::VScreenRuntimeAssets => {
                install_vscreen_runtime_assets(config, &mut report)
            }
            ComponentKey::AndroidBuildFoundation => {
                install_android_build_foundation(config, &mut report)
            }
            ComponentKey::AndroidSdk => install_android_sdk_component(config, &mut report),
            ComponentKey::AndroidPlatform => install_android_package_component(
                config,
                &format!("platforms;android-{}", component.version),
                &mut report,
            ),
            ComponentKey::AndroidGradle => {
                install_gradle_component(config, &component.version, &mut report)
            }
            ComponentKey::AndroidNdk => install_android_package_component(
                config,
                &format!("ndk;{}", component.version),
                &mut report,
            ),
            ComponentKey::AndroidCmake => install_android_package_component(
                config,
                &format!("cmake;{}", component.version),
                &mut report,
            ),
            ComponentKey::AndroidKotlinProfile => {
                install_android_kotlin_profile_component(config, &mut report)
            }
            ComponentKey::AndroidNativeProfile => {
                install_android_native_profile_component(config, &mut report)
            }
            ComponentKey::FlutterSdk => install_flutter_sdk_component(config, &mut report),
            ComponentKey::FlutterProfile => install_flutter_profile_component(config, &mut report),
            ComponentKey::GodotEngine => install_godot_engine_component(config, &mut report),
            ComponentKey::GodotExportTemplates => {
                install_godot_export_templates_component(config, &mut report)
            }
            ComponentKey::GodotAndroidProfile => {
                install_godot_android_profile_component(config, &mut report)
            }
            ComponentKey::ReactNativeDistribution => {
                install_react_native_distribution_component(config, &mut report)
            }
            ComponentKey::ReactNativeAndroidProfile => {
                install_react_native_profile_component(config, &mut report)
            }
            ComponentKey::WebDistribution => {
                install_web_distribution_component(config, &mut report)
            }
            ComponentKey::WebProfile => install_web_profile_component(config, &mut report),
            ComponentKey::OpenClawRuntime => unreachable!("OpenClaw has a signed installer"),
        }
    }

    fn mark_completed(&mut self, component: &ComponentIdentity) -> Result<(), String> {
        let plan = self.plan.as_mut().ok_or("capability plan is missing")?;
        plan.mark_completed(component);
        self.plan_store.save(plan)
    }

    fn mark_failed(&mut self, component: &ComponentIdentity, code: u8) -> Result<(), String> {
        let plan = self.plan.as_mut().ok_or("capability plan is missing")?;
        plan.mark_failed(component, code);
        self.plan_store.save(plan)
    }

    fn gateway_environment_ready(&mut self) -> Result<bool, String> {
        let Some(plan) = self.plan.as_ref() else {
            self.emit_unscoped(Stage::CapabilitiesRequired, 0)?;
            return Ok(false);
        };
        if !plan.required_environment_ready(&self.graph) {
            self.emit(Stage::CapabilityPlanRejected, 30, None, None)?;
            return Ok(false);
        }
        Ok(true)
    }

    fn ensure_gateway(&mut self) -> Result<(), String> {
        if !self.gateway_environment_ready()? {
            return Ok(());
        }
        self.start_gateway()
    }

    fn start_gateway(&mut self) -> Result<(), String> {
        if let Some(worker) = self.gateway_worker.as_mut()
            && worker.try_wait().ok().flatten().is_none()
        {
            return self.emit(
                self.gateway_stage.unwrap_or(Stage::GatewayConfiguring),
                self.gateway_failure_code.unwrap_or(0),
                None,
                None,
            );
        }
        self.gateway_attempt_id = self
            .gateway_attempt_id
            .checked_add(1)
            .ok_or("Gateway attempt sequence exhausted")?;
        self.gateway_generation = Some(random_runtime_id()?);
        self.gateway_start_count = 0;
        self.gateway_stage = Some(Stage::GatewayConfiguring);
        self.gateway_failure_code = None;
        let script = self.config.support_script("start-gateway.sh");
        let mut child = Command::new("/usr/bin/bash")
            .arg("-c")
            .arg(GATEWAY_WORKER_RUNNER)
            .arg("claw-in-one-gateway")
            .arg(&script)
            .env("CLAW_IN_ONE_SHARED_DIR", &self.config.shared_dir)
            .env("CLAW_IN_ONE_INSTALL_ROOT", &self.config.install_root)
            .env("CLAW_IN_ONE_PAIRING_REQUIRED", "false")
            .env(
                "CLAW_IN_ONE_NODE_VERSION",
                &self.config.manifest.node_version,
            )
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|error| format!("cannot start Gateway worker: {error}"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or("Gateway worker stdout is unavailable")?;
        let (sender, receiver) = mpsc::channel();
        thread::spawn(move || {
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                if let Some((stage, _)) = parse_script_status(&line) {
                    let _ = sender.send(stage);
                }
            }
        });
        self.gateway_worker = Some(child);
        self.gateway_events = Some(receiver);
        self.emit(Stage::GatewayConfiguring, 0, None, None)
    }

    fn consume_gateway_events(&mut self) -> Result<(), String> {
        let stages = self
            .gateway_events
            .as_ref()
            .map(|events| events.try_iter().collect::<Vec<_>>())
            .unwrap_or_default();
        for stage in stages {
            if stage == Stage::GatewayStarting {
                if self.gateway_start_count > 0 {
                    self.gateway_attempt_id = self
                        .gateway_attempt_id
                        .checked_add(1)
                        .ok_or("Gateway attempt sequence exhausted")?;
                    self.gateway_generation = Some(random_runtime_id()?);
                }
                self.gateway_start_count = self
                    .gateway_start_count
                    .checked_add(1)
                    .ok_or("Gateway start sequence exhausted")?;
            }
            self.gateway_stage = Some(stage);
            self.gateway_failure_code = None;
            if !self.pairing_result_pending {
                self.emit(stage, 0, None, None)?;
            }
        }
        Ok(())
    }

    fn pair_gateway(&mut self) -> Result<(), String> {
        if !self.gateway_environment_ready()? {
            return Ok(());
        }
        self.start_gateway()?;
        if self.gateway_stage != Some(Stage::GatewayReady) && !self.wait_for_gateway_ready()? {
            return Ok(());
        }
        let script = self.config.support_script("start-gateway.sh");
        let mut child = Command::new("/usr/bin/bash")
            .arg("-c")
            .arg(SCRIPT_RUNNER)
            .arg("claw-in-one-pairing")
            .arg(&script)
            .env("CLAW_IN_ONE_SHARED_DIR", &self.config.shared_dir)
            .env("CLAW_IN_ONE_INSTALL_ROOT", &self.config.install_root)
            .env("CLAW_IN_ONE_PAIRING_REQUIRED", "true")
            .env(
                "CLAW_IN_ONE_NODE_VERSION",
                &self.config.manifest.node_version,
            )
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|error| format!("cannot start Gateway pairing: {error}"))?;
        let stdout = child.stdout.take().ok_or("pairing stdout is unavailable")?;
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some((stage, setup_code)) = parse_script_status(&line) {
                let reported = if setup_code.is_some() {
                    Stage::GatewayPairingReady
                } else {
                    stage
                };
                self.emit(reported, 0, None, setup_code.as_deref())?;
                if reported == Stage::GatewayPairingReady {
                    self.pairing_result_pending = true;
                }
            }
        }
        let status = child
            .wait()
            .map_err(|error| format!("cannot wait for Gateway pairing: {error}"))?;
        if status.success() {
            Ok(())
        } else {
            self.pairing_result_pending = true;
            self.emit(
                Stage::GatewayPairingFailed,
                bounded_exit_code(status.code().unwrap_or(1)),
                None,
                None,
            )
        }
    }

    fn wait_for_gateway_ready(&mut self) -> Result<bool, String> {
        for _ in 0..(12 * 60 * 4) {
            self.consume_gateway_events()?;
            if self.gateway_stage == Some(Stage::GatewayReady) {
                return Ok(true);
            }
            let exited = if let Some(worker) = self.gateway_worker.as_mut() {
                worker
                    .try_wait()
                    .map_err(|error| format!("cannot inspect Gateway worker: {error}"))?
            } else {
                None
            };
            if let Some(status) = exited {
                self.gateway_worker = None;
                self.gateway_events = None;
                let code = bounded_exit_code(status.code().unwrap_or(40));
                self.gateway_stage = Some(Stage::GatewayFailed);
                self.gateway_failure_code = Some(code);
                self.pairing_result_pending = true;
                self.emit(Stage::GatewayPairingFailed, code, None, None)?;
                return Ok(false);
            }
            thread::sleep(Duration::from_millis(250));
        }
        self.pairing_result_pending = true;
        self.emit(Stage::GatewayPairingFailed, 1, None, None)?;
        Ok(false)
    }

    fn openclaw_is_ready(&self) -> bool {
        let release = self.config.install_root.join("current");
        let runtime = self.config.install_root.join(format!(
            "runtimes/node-v{}-linux-arm64",
            self.config.manifest.node_version
        ));
        verify_clawinone_release(
            &release,
            &runtime,
            &self.config.manifest.openclaw_version,
            &self.config.shared_dir,
        )
    }

    fn emit(
        &mut self,
        stage: Stage,
        exit_code: u8,
        current_component: Option<&ComponentIdentity>,
        setup_code: Option<&str>,
    ) -> Result<(), String> {
        self.event_sequence = self
            .event_sequence
            .checked_add(1)
            .ok_or("event sequence exhausted")?;
        write_sequence(&self.config.event_sequence_file(), self.event_sequence)?;
        let gateway_identity = stage.is_gateway();
        self.protocol.write_status(
            &self.config.status_file,
            self.event_sequence,
            self.command_sequence,
            stage,
            exit_code,
            StatusContext {
                supervisor_boot_id: &self.supervisor_boot_id,
                gateway_generation: self
                    .gateway_generation
                    .as_deref()
                    .filter(|_| gateway_identity),
                ensure_attempt_id: gateway_identity.then_some(self.gateway_attempt_id),
                plan: self.plan.as_ref(),
                ready_capabilities: &self.ready_capabilities,
                current_component,
                completed_bytes: None,
                total_bytes: None,
                setup_code,
            },
        )?;
        if gateway_identity {
            self.last_gateway_status_at = Instant::now();
        }
        Ok(())
    }

    fn emit_unscoped(&mut self, stage: Stage, exit_code: u8) -> Result<(), String> {
        self.event_sequence = self
            .event_sequence
            .checked_add(1)
            .ok_or("event sequence exhausted")?;
        write_sequence(&self.config.event_sequence_file(), self.event_sequence)?;
        self.protocol.write_status(
            &self.config.status_file,
            self.event_sequence,
            self.command_sequence,
            stage,
            exit_code,
            StatusContext::unscoped(&self.supervisor_boot_id, &self.ready_capabilities),
        )
    }

    fn emit_progress(
        &mut self,
        stage: Stage,
        component: &ComponentIdentity,
        progress: Option<DownloadProgress>,
    ) -> Result<(), String> {
        self.event_sequence = self
            .event_sequence
            .checked_add(1)
            .ok_or("event sequence exhausted")?;
        write_sequence(&self.config.event_sequence_file(), self.event_sequence)?;
        self.protocol.write_status(
            &self.config.status_file,
            self.event_sequence,
            self.command_sequence,
            stage,
            0,
            StatusContext {
                supervisor_boot_id: &self.supervisor_boot_id,
                gateway_generation: None,
                ensure_attempt_id: None,
                plan: self.plan.as_ref(),
                ready_capabilities: &self.ready_capabilities,
                current_component: Some(component),
                completed_bytes: progress.map(|value| value.completed_bytes),
                total_bytes: progress.map(|value| value.total_bytes),
                setup_code: None,
            },
        )
    }

    fn stop_gateway(&mut self) {
        if let Some(mut child) = self.gateway_worker.take() {
            let _ = Command::new("kill")
                .arg("-TERM")
                .arg(child.id().to_string())
                .status();
            let _ = child.wait();
        }
        self.gateway_events = None;
        self.gateway_generation = None;
        self.gateway_stage = None;
        self.gateway_failure_code = None;
        self.gateway_start_count = 0;
        self.pairing_result_pending = false;
    }

    fn write_process_state(&self, status: &str) -> Result<(), String> {
        let value = format!(
            "status={status}\nprotocol_version={PROTOCOL_VERSION}\npid={}\nupdated_at={}\n",
            std::process::id(),
            crate::protocol::now()
        );
        atomic_write(
            &self.config.state_dir.join("supervisor.state"),
            value.as_bytes(),
        )
    }

    fn refresh_ready_capabilities(&mut self) {
        self.ready_capabilities = self.detect_ready_capabilities();
    }

    fn adopt_verified_capabilities(&mut self) -> Result<(), String> {
        let Some(plan) = self
            .plan
            .as_ref()
            .filter(|plan| plan.is_settled(&self.graph))
            .cloned()
        else {
            return Ok(());
        };
        let projected = plan.ready_capabilities(&self.graph);
        let verified = DeveloperCapability::ORDERED
            .into_iter()
            .filter(|capability| !projected.contains(capability))
            .filter(|capability| {
                self.graph
                    .resolve(std::slice::from_ref(capability))
                    .is_ok_and(|components| {
                        components
                            .iter()
                            .all(|component| self.component_is_ready(component))
                    })
            })
            .collect::<Vec<_>>();
        let Some(adopted) =
            plan.adopt_verified_capabilities(random_runtime_id()?, &verified, &self.graph)?
        else {
            return Ok(());
        };
        self.plan_store.save(&adopted)?;
        self.plan = Some(adopted);
        Ok(())
    }

    fn detect_ready_capabilities(&self) -> Vec<DeveloperCapability> {
        self.plan
            .as_ref()
            .map(|plan| plan.ready_capabilities(&self.graph))
            .unwrap_or_default()
    }
}

fn component_requires_gateway_restart(key: ComponentKey) -> bool {
    matches!(
        key,
        ComponentKey::AndroidKotlinProfile
            | ComponentKey::AndroidNativeProfile
            | ComponentKey::FlutterProfile
            | ComponentKey::GodotAndroidProfile
            | ComponentKey::ReactNativeAndroidProfile
            | ComponentKey::WebProfile
    )
}

fn settled_development_reapply_requires_gateway_restart(
    current: &[DeveloperCapability],
    requested: &[DeveloperCapability],
    settled: bool,
) -> bool {
    settled && !requested.is_empty() && current == requested
}

fn ready_stage(component: &ComponentIdentity) -> Stage {
    if component.key == ComponentKey::OpenClawRuntime {
        Stage::OpenClawReady
    } else {
        Stage::ComponentReady
    }
}

const SCRIPT_RUNNER: &str = r#"
set -Eeuo pipefail
report_status() { printf 'CLAW_STATUS\t%s\t%s\n' "$1" "${2:--}"; }
source "$1"
"#;

const GATEWAY_WORKER_RUNNER: &str = r#"
set -Eeuo pipefail
shutdown() {
  if declare -F claw_in_one_stop_gateway >/dev/null; then claw_in_one_stop_gateway; fi
  exit 0
}
trap '' HUP
trap shutdown INT TERM
report_status() { printf 'CLAW_STATUS\t%s\t%s\n' "$1" "${2:--}"; }
source "$1"
claw_in_one_wait_gateway
"#;

fn parse_script_status(line: &str) -> Option<(Stage, Option<String>)> {
    let mut fields = line.split('\t');
    if fields.next()? != "CLAW_STATUS" {
        return None;
    }
    let stage = Stage::from_script(fields.next()?)?;
    let setup_code = fields
        .next()
        .filter(|value| *value != "-")
        .map(str::to_owned);
    if fields.next().is_some() {
        return None;
    }
    Some((stage, setup_code))
}

fn bounded_exit_code(value: i32) -> u8 {
    if (1..=255).contains(&value) {
        value as u8
    } else {
        1
    }
}

fn random_runtime_id() -> Result<String, String> {
    let mut value = [0_u8; 16];
    getrandom::getrandom(&mut value)
        .map_err(|error| format!("cannot create runtime identity: {error}"))?;
    Ok(hex::encode(value))
}

fn read_sequence(path: &std::path::Path) -> u64 {
    fs::read_to_string(path)
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0)
}

fn write_sequence(path: &std::path::Path, value: u64) -> Result<(), String> {
    atomic_write(path, format!("{value}\n").as_bytes())
}

fn atomic_write(path: &std::path::Path, value: &[u8]) -> Result<(), String> {
    let temporary = path.with_extension(format!("next.{}", std::process::id()));
    fs::write(&temporary, value)
        .and_then(|()| fs::rename(&temporary, path))
        .map_err(|error| format!("cannot persist {}: {error}", path.display()))
}

#[cfg(test)]
mod tests {
    use super::{
        component_requires_gateway_restart, parse_script_status,
        settled_development_reapply_requires_gateway_restart,
    };
    use crate::protocol::Stage;
    use crate::setup::{ComponentKey, DeveloperCapability};

    #[test]
    fn parses_only_typed_script_status() {
        assert_eq!(
            parse_script_status("CLAW_STATUS\tgateway_healthy\t-"),
            Some((Stage::GatewayHealthy, None))
        );
        assert!(parse_script_status("hello").is_none());
        assert!(parse_script_status("CLAW_STATUS\tunknown\t-").is_none());
    }

    #[test]
    fn every_development_profile_restarts_gateway_to_refresh_skill_eligibility() {
        for key in [
            ComponentKey::AndroidKotlinProfile,
            ComponentKey::AndroidNativeProfile,
            ComponentKey::FlutterProfile,
            ComponentKey::GodotAndroidProfile,
            ComponentKey::ReactNativeAndroidProfile,
            ComponentKey::WebProfile,
        ] {
            assert!(component_requires_gateway_restart(key), "{key:?}");
        }
        assert!(!component_requires_gateway_restart(
            ComponentKey::AndroidSdk
        ));
    }

    #[test]
    fn explicit_repair_of_a_settled_development_plan_restarts_gateway() {
        let selected = [DeveloperCapability::AndroidKotlin];
        assert!(settled_development_reapply_requires_gateway_restart(
            &selected, &selected, true
        ));
        assert!(!settled_development_reapply_requires_gateway_restart(
            &selected,
            &[DeveloperCapability::Flutter],
            true
        ));
        assert!(!settled_development_reapply_requires_gateway_restart(
            &[],
            &[],
            true
        ));
        assert!(!settled_development_reapply_requires_gateway_restart(
            &selected, &selected, false
        ));
    }
}
