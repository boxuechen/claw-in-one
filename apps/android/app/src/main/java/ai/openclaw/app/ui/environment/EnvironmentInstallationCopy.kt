package ai.openclaw.app.ui.environment

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.ui.setup.setupComponentTitle

internal fun environmentComponentLabel(component: CapabilityComponent): String = setupComponentTitle(component)

internal fun environmentSetupLabel(stage: SupervisorStatusStage): String =
  when (stage) {
    SupervisorStatusStage.SupervisorReady, SupervisorStatusStage.CapabilitiesRequired -> nativeString("OpenClaw is not installed")
    SupervisorStatusStage.CapabilityPlanAccepted -> nativeString("Preparing your setup")
    SupervisorStatusStage.DownloadingNode -> nativeString("Downloading the runtime")
    SupervisorStatusStage.VerifyingNode, SupervisorStatusStage.InstallingNode -> nativeString("Preparing the runtime")
    SupervisorStatusStage.DownloadingOpenClaw -> nativeString("Downloading OpenClaw")
    SupervisorStatusStage.VerifyingOpenClaw, SupervisorStatusStage.VerifyingOpenClawInstallation -> nativeString("Verifying OpenClaw")
    SupervisorStatusStage.InstallingOpenClaw -> nativeString("Installing OpenClaw")
    SupervisorStatusStage.OpenClawReady -> nativeString("OpenClaw is ready")
    SupervisorStatusStage.DownloadingComponent -> nativeString("Downloading a development component")
    SupervisorStatusStage.VerifyingComponent -> nativeString("Checking a development component")
    SupervisorStatusStage.InstallingComponent -> nativeString("Installing a development component")
    SupervisorStatusStage.ComponentReady -> nativeString("Development component is ready")
    SupervisorStatusStage.CapabilityFailed -> nativeString("Setup stopped")
    SupervisorStatusStage.CapabilityPlanRejected -> nativeString("Setup plan was rejected")
    SupervisorStatusStage.GatewayNotStarted -> nativeString("OpenClaw is not running")
    SupervisorStatusStage.GatewayConfiguring, SupervisorStatusStage.GatewayStarting -> nativeString("Starting OpenClaw")
    SupervisorStatusStage.GatewayHealthy, SupervisorStatusStage.GatewayReady -> nativeString("OpenClaw is ready")
    SupervisorStatusStage.GatewayPairingReady -> nativeString("OpenClaw pairing is ready")
    SupervisorStatusStage.GatewayPairingFailed -> nativeString("OpenClaw pairing failed")
    SupervisorStatusStage.GatewayFailed -> nativeString("OpenClaw could not be started")
    SupervisorStatusStage.CapabilitiesReady -> nativeString("Selected capabilities are ready")
  }
