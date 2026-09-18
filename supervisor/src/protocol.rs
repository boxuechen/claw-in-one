use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::config::PROTOCOL_VERSION;
use crate::setup::{
    CapabilityPlan, CapabilityPlanRequest, ComponentIdentity, ComponentKey, DeveloperCapability,
    component_list, optional_capability_list, valid_plan_id,
};

const MAX_SNAPSHOT_BYTES: u64 = 4 * 1024;
const MAX_AGE_SECONDS: u64 = 12 * 60 * 60;
const MAX_FUTURE_SKEW_SECONDS: u64 = 5 * 60;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Operation {
    Probe,
    ApplyCapabilities(CapabilityPlanRequest),
    RetryCapabilities(String),
    SkipCapability {
        plan_id: String,
        capability: DeveloperCapability,
    },
    EnsureGateway,
    RequestGatewayPairing,
}

impl Operation {
    fn parse(name: &str, argument: &str) -> Option<Self> {
        match name {
            "probe" if argument == "-" => Some(Self::Probe),
            "apply_capabilities" => {
                CapabilityPlanRequest::parse(argument).map(Self::ApplyCapabilities)
            }
            "retry_capabilities" if valid_plan_id(argument) => {
                Some(Self::RetryCapabilities(argument.to_owned()))
            }
            "skip_capability" => {
                let (plan_id, capability) = argument.split_once(':')?;
                let capability = DeveloperCapability::parse(capability)?;
                if valid_plan_id(plan_id) {
                    Some(Self::SkipCapability {
                        plan_id: plan_id.to_owned(),
                        capability,
                    })
                } else {
                    None
                }
            }
            "ensure_gateway" if argument == "-" => Some(Self::EnsureGateway),
            "request_gateway_pairing" if argument == "-" => Some(Self::RequestGatewayPairing),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Stage {
    SupervisorReady,
    CapabilitiesRequired,
    CapabilityPlanAccepted,
    DownloadingNode,
    VerifyingNode,
    InstallingNode,
    DownloadingOpenClaw,
    VerifyingOpenClaw,
    InstallingOpenClaw,
    VerifyingOpenClawInstallation,
    OpenClawReady,
    DownloadingComponent,
    VerifyingComponent,
    InstallingComponent,
    ComponentReady,
    CapabilitiesReady,
    CapabilityFailed,
    CapabilityPlanRejected,
    GatewayNotStarted,
    GatewayConfiguring,
    GatewayStarting,
    GatewayHealthy,
    GatewayReady,
    GatewayPairingReady,
    GatewayPairingFailed,
    GatewayFailed,
}

impl Stage {
    pub fn wire_name(self) -> &'static str {
        match self {
            Self::SupervisorReady => "supervisor_ready",
            Self::CapabilitiesRequired => "capabilities_required",
            Self::CapabilityPlanAccepted => "capability_plan_accepted",
            Self::DownloadingNode => "downloading_node",
            Self::VerifyingNode => "verifying_node",
            Self::InstallingNode => "installing_node",
            Self::DownloadingOpenClaw => "downloading_openclaw",
            Self::VerifyingOpenClaw => "verifying_openclaw",
            Self::InstallingOpenClaw => "installing_openclaw",
            Self::VerifyingOpenClawInstallation => "verifying_openclaw_installation",
            Self::OpenClawReady => "openclaw_ready",
            Self::DownloadingComponent => "downloading_component",
            Self::VerifyingComponent => "verifying_component",
            Self::InstallingComponent => "installing_component",
            Self::ComponentReady => "component_ready",
            Self::CapabilitiesReady => "capabilities_ready",
            Self::CapabilityFailed => "capability_failed",
            Self::CapabilityPlanRejected => "capability_plan_rejected",
            Self::GatewayNotStarted => "gateway_not_started",
            Self::GatewayConfiguring => "gateway_configuring",
            Self::GatewayStarting => "gateway_starting",
            Self::GatewayHealthy => "gateway_healthy",
            Self::GatewayReady => "gateway_ready",
            Self::GatewayPairingReady => "gateway_pairing_ready",
            Self::GatewayPairingFailed => "gateway_pairing_failed",
            Self::GatewayFailed => "gateway_failed",
        }
    }

    pub fn from_script(value: &str) -> Option<Self> {
        match value {
            "gateway_configuring" => Some(Self::GatewayConfiguring),
            "gateway_starting" => Some(Self::GatewayStarting),
            "gateway_healthy" => Some(Self::GatewayHealthy),
            "gateway_ready" => Some(Self::GatewayReady),
            _ => None,
        }
    }

    fn is_failure(self) -> bool {
        matches!(
            self,
            Self::CapabilityFailed
                | Self::CapabilityPlanRejected
                | Self::GatewayPairingFailed
                | Self::GatewayFailed
        )
    }

    fn is_download(self) -> bool {
        matches!(
            self,
            Self::DownloadingNode | Self::DownloadingOpenClaw | Self::DownloadingComponent
        )
    }

    fn expected_component_key(self) -> Option<ComponentKey> {
        match self {
            Self::DownloadingNode
            | Self::VerifyingNode
            | Self::InstallingNode
            | Self::DownloadingOpenClaw
            | Self::VerifyingOpenClaw
            | Self::InstallingOpenClaw
            | Self::VerifyingOpenClawInstallation
            | Self::OpenClawReady => Some(ComponentKey::OpenClawRuntime),
            _ => None,
        }
    }

    fn requires_component(self) -> bool {
        self.expected_component_key().is_some()
            || matches!(
                self,
                Self::DownloadingComponent
                    | Self::VerifyingComponent
                    | Self::InstallingComponent
                    | Self::ComponentReady
                    | Self::CapabilityFailed
            )
    }

    fn requires_plan(self) -> bool {
        !matches!(self, Self::SupervisorReady | Self::CapabilitiesRequired)
    }

    pub(crate) fn is_gateway(self) -> bool {
        matches!(
            self,
            Self::GatewayConfiguring
                | Self::GatewayStarting
                | Self::GatewayHealthy
                | Self::GatewayReady
                | Self::GatewayPairingReady
                | Self::GatewayPairingFailed
                | Self::GatewayFailed
        )
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Command {
    pub sequence: u64,
    pub operation: Operation,
}

#[derive(Clone, Copy, Debug)]
pub struct StatusContext<'a> {
    pub supervisor_boot_id: &'a str,
    pub gateway_generation: Option<&'a str>,
    pub ensure_attempt_id: Option<u64>,
    pub plan: Option<&'a CapabilityPlan>,
    pub ready_capabilities: &'a [DeveloperCapability],
    pub current_component: Option<&'a ComponentIdentity>,
    pub completed_bytes: Option<u64>,
    pub total_bytes: Option<u64>,
    pub setup_code: Option<&'a str>,
}

impl StatusContext<'_> {
    pub fn unscoped<'a>(
        supervisor_boot_id: &'a str,
        ready_capabilities: &'a [DeveloperCapability],
    ) -> StatusContext<'a> {
        StatusContext {
            supervisor_boot_id,
            gateway_generation: None,
            ensure_attempt_id: None,
            plan: None,
            ready_capabilities,
            current_component: None,
            completed_bytes: None,
            total_bytes: None,
            setup_code: None,
        }
    }
}

pub struct Protocol {
    supervisor_id: String,
    secret: Vec<u8>,
}

impl Protocol {
    pub fn new(supervisor_id: String, secret: Vec<u8>) -> Self {
        Self {
            supervisor_id,
            secret,
        }
    }

    pub fn read_command(&self, path: &Path, last_sequence: u64) -> Option<Command> {
        let metadata = fs::metadata(path).ok()?;
        if metadata.len() == 0 || metadata.len() > MAX_SNAPSHOT_BYTES {
            return None;
        }
        let bytes = fs::read(path).ok()?;
        if bytes.last() != Some(&b'\n') || bytes[..bytes.len() - 1].contains(&b'\n') {
            return None;
        }
        let line = std::str::from_utf8(&bytes[..bytes.len() - 1]).ok()?;
        let fields: Vec<&str> = line.split('|').collect();
        if fields.len() != 7 || fields.iter().any(|field| field.is_empty()) {
            return None;
        }
        if fields[0] != PROTOCOL_VERSION || fields[1] != self.supervisor_id {
            return None;
        }
        let sequence = parse_decimal(fields[2])?;
        let timestamp = parse_decimal(fields[3])?;
        let operation = Operation::parse(fields[4], fields[5])?;
        if sequence <= last_sequence || !valid_timestamp(timestamp, now()) {
            return None;
        }
        let canonical = fields[..6].join("\n");
        if !self.verify(&canonical, fields[6]) {
            return None;
        }
        Some(Command {
            sequence,
            operation,
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn write_status(
        &self,
        path: &Path,
        event_sequence: u64,
        command_sequence: u64,
        stage: Stage,
        exit_code: u8,
        context: StatusContext<'_>,
    ) -> Result<(), String> {
        if stage.is_failure() != (exit_code != 0) {
            return Err("stage and exit code disagree".into());
        }
        if !is_lower_hex(context.supervisor_boot_id, 32) {
            return Err("invalid Supervisor boot ID".into());
        }
        if stage.is_gateway()
            != (context.gateway_generation.is_some() && context.ensure_attempt_id.is_some())
        {
            return Err("stage and Gateway runtime identity disagree".into());
        }
        if context
            .gateway_generation
            .is_some_and(|value| !is_lower_hex(value, 32))
            || context.ensure_attempt_id == Some(0)
        {
            return Err("invalid Gateway runtime identity".into());
        }
        if stage.requires_plan() != context.plan.is_some() {
            return Err("stage and capability plan disagree".into());
        }
        if !valid_ready_capabilities(context.ready_capabilities) {
            return Err("ready capabilities are not canonical".into());
        }
        if context.completed_bytes.is_some() != context.total_bytes.is_some() {
            return Err("incomplete progress".into());
        }
        if let (Some(completed), Some(total)) = (context.completed_bytes, context.total_bytes)
            && (!stage.is_download() || total == 0 || completed > total)
        {
            return Err("invalid progress".into());
        }
        if context.completed_bytes.is_none() && stage.is_download() {
            return Err("download stage requires progress".into());
        }
        if let Some(fixed) = stage.expected_component_key()
            && context.current_component.map(|component| component.key) != Some(fixed)
        {
            return Err("stage and component disagree".into());
        }
        if stage.requires_component() && context.current_component.is_none() {
            return Err("capability failure requires a component".into());
        }
        if !stage.requires_component() && context.current_component.is_some() {
            return Err("stage cannot carry a component".into());
        }
        if let (Some(plan), Some(component)) = (context.plan, context.current_component)
            && !plan.resolved.contains(component)
        {
            return Err("component is outside capability plan".into());
        }
        if context.setup_code.is_some() != (stage == Stage::GatewayPairingReady) {
            return Err("invalid setup code stage".into());
        }
        if let Some(code) = context.setup_code
            && (!(16..=3072).contains(&code.len())
                || !code
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-'))
        {
            return Err("invalid setup code".into());
        }
        let plan_id = context.plan.map_or("-", |plan| plan.id.as_str());
        let selected = context.plan.map_or_else(
            || "-".to_owned(),
            |plan| optional_capability_list(&plan.selected),
        );
        let resolved = context
            .plan
            .map_or_else(|| "-".to_owned(), |plan| component_list(&plan.resolved));
        let ready = optional_capability_list(context.ready_capabilities);
        let current = context
            .current_component
            .map_or_else(|| "-".to_owned(), ComponentIdentity::wire_name);
        let fields = [
            PROTOCOL_VERSION.to_owned(),
            self.supervisor_id.clone(),
            context.supervisor_boot_id.to_owned(),
            event_sequence.to_string(),
            command_sequence.to_string(),
            now().to_string(),
            stage.wire_name().to_owned(),
            context.gateway_generation.unwrap_or("-").to_owned(),
            context
                .ensure_attempt_id
                .map_or_else(|| "-".into(), |value| value.to_string()),
            exit_code.to_string(),
            plan_id.to_owned(),
            selected,
            resolved,
            ready,
            current,
            context
                .completed_bytes
                .map_or_else(|| "-".into(), |value| value.to_string()),
            context
                .total_bytes
                .map_or_else(|| "-".into(), |value| value.to_string()),
            context.setup_code.unwrap_or("-").to_owned(),
        ];
        let canonical = fields.join("\n");
        let line = format!("{}|{}\n", fields.join("|"), self.sign(&canonical));
        let mut file = fs::OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(path)
            .map_err(|error| format!("cannot open status snapshot: {error}"))?;
        file.write_all(line.as_bytes())
            .and_then(|()| file.sync_data())
            .map_err(|error| format!("cannot write status snapshot: {error}"))
    }

    fn sign(&self, canonical: &str) -> String {
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.secret).expect("HMAC accepts any key");
        mac.update(canonical.as_bytes());
        hex::encode(mac.finalize().into_bytes())
    }

    fn verify(&self, canonical: &str, actual_hex: &str) -> bool {
        let Ok(actual) = hex::decode(actual_hex) else {
            return false;
        };
        let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(&self.secret) else {
            return false;
        };
        mac.update(canonical.as_bytes());
        mac.verify_slice(&actual).is_ok()
    }
}

fn is_lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn valid_ready_capabilities(values: &[DeveloperCapability]) -> bool {
    values
        == DeveloperCapability::ORDERED
            .into_iter()
            .filter(|capability| values.contains(capability))
            .collect::<Vec<_>>()
}

fn valid_timestamp(timestamp: u64, current: u64) -> bool {
    timestamp > 0
        && timestamp >= current.saturating_sub(MAX_AGE_SECONDS)
        && timestamp <= current.saturating_add(MAX_FUTURE_SKEW_SECONDS)
}

fn parse_decimal(value: &str) -> Option<u64> {
    if value.is_empty() || value.len() > 18 || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    value.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::{Operation, Protocol, Stage, StatusContext, now};
    use crate::config::{InstallManifest, PROTOCOL_VERSION};
    use crate::setup::{
        CapabilityPlan, CapabilityPlanRequest, ComponentKey, DeveloperCapability, ReleaseGraph,
        component_list,
    };
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    use std::fs;

    #[test]
    fn accepts_only_authenticated_v10_capability_commands() {
        let root = std::env::temp_dir().join(format!("claw-protocol-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let path = root.join("command");
        let id = "a".repeat(32);
        let secret = vec![0xbb; 32];
        let timestamp = now();
        let plan = format!("{}:android_kotlin,android_native,flutter", "c".repeat(32));
        let canonical =
            format!("{PROTOCOL_VERSION}\n{id}\n1\n{timestamp}\napply_capabilities\n{plan}");
        let mut mac = Hmac::<Sha256>::new_from_slice(&secret).unwrap();
        mac.update(canonical.as_bytes());
        fs::write(
            &path,
            format!(
                "{PROTOCOL_VERSION}|{id}|1|{timestamp}|apply_capabilities|{plan}|{}\n",
                hex::encode(mac.finalize().into_bytes())
            ),
        )
        .unwrap();
        let protocol = Protocol::new(id.clone(), secret.clone());
        let command = protocol.read_command(&path, 0).unwrap();
        assert_eq!(command.sequence, 1);
        assert!(matches!(command.operation, Operation::ApplyCapabilities(_)));
        assert!(protocol.read_command(&path, 1).is_none());

        let legacy = format!(
            "6|{id}|2|{timestamp}|apply_capabilities|{plan}|{}\n",
            "0".repeat(64)
        );
        fs::write(&path, legacy).unwrap();
        assert!(protocol.read_command(&path, 1).is_none());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn status_separates_selected_resolved_and_ready_identities() {
        let root = std::env::temp_dir().join(format!("claw-status-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let path = root.join("status");
        fs::write(&path, "").unwrap();
        let protocol = Protocol::new("a".repeat(32), vec![0xbb; 32]);
        let graph = ReleaseGraph::for_manifest(&InstallManifest::test_fixture()).unwrap();
        let plan = CapabilityPlan::create(
            CapabilityPlanRequest {
                id: "c".repeat(32),
                selected: vec![
                    DeveloperCapability::AndroidKotlin,
                    DeveloperCapability::Flutter,
                ],
            },
            &graph,
        )
        .unwrap();
        let openclaw = plan
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::OpenClawRuntime)
            .unwrap();
        let flutter = plan
            .resolved
            .iter()
            .find(|component| component.key == ComponentKey::FlutterProfile)
            .unwrap();
        protocol
            .write_status(
                &path,
                1,
                1,
                Stage::DownloadingNode,
                0,
                StatusContext {
                    supervisor_boot_id: "b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6",
                    gateway_generation: None,
                    ensure_attempt_id: None,
                    plan: Some(&plan),
                    ready_capabilities: &[],
                    current_component: Some(openclaw),
                    completed_bytes: Some(2),
                    total_bytes: Some(10),
                    setup_code: None,
                },
            )
            .unwrap();
        let line = fs::read_to_string(&path).unwrap();
        assert_eq!(line.split('|').count(), 19);
        assert!(line.contains(&format!(
            "|downloading_node|-|-|0|{}|android_kotlin,flutter|{}|-|{}|2|10|-|",
            plan.id,
            component_list(&plan.resolved),
            openclaw.wire_name()
        )));
        assert!(
            protocol
                .write_status(
                    &path,
                    2,
                    1,
                    Stage::InstallingOpenClaw,
                    0,
                    StatusContext {
                        supervisor_boot_id: "b1c2d3e4f5a6b7c8d9e0a1b2c3d4e5f6",
                        gateway_generation: None,
                        ensure_attempt_id: None,
                        plan: Some(&plan),
                        ready_capabilities: &[DeveloperCapability::AndroidKotlin],
                        current_component: Some(flutter),
                        completed_bytes: None,
                        total_bytes: None,
                        setup_code: None,
                    },
                )
                .is_err()
        );
        let _ = fs::remove_dir_all(root);
    }
}
