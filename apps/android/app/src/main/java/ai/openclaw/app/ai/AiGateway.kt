package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod

/** Exact Gateway epoch captured by AI-domain operations. */
internal data class AiGatewayConnection(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
  val methods: Set<String>,
  val defaultAgentId: String?,
  val adminScope: Boolean,
)

/** Socket ownership stays below AI domains; controllers own their operation state. */
internal interface AiGatewayTransport {
  fun capture(): AiGatewayConnection?

  fun publish(
    connection: AiGatewayConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: AiGatewayConnection,
    method: String,
    params: String,
    timeoutMs: Long = 15_000,
  ): String
}

internal val requiredAiGatewayMethods: Set<String> =
  setOf(
    GatewayMethod.ModelsList,
    GatewayMethod.OpenclawSetupDetect,
    GatewayMethod.OpenclawSetupActivateStart,
    GatewayMethod.OpenclawSetupAuthStart,
    GatewayMethod.OpenclawSetupVerify,
    GatewayMethod.WizardNext,
    GatewayMethod.WizardCancel,
    GatewayMethod.WizardStatus,
    GatewayMethod.AgentsUpdate,
    GatewayMethod.GatewayRestartPreflight,
    GatewayMethod.GatewayRestartRequest,
  ).mapTo(linkedSetOf(), GatewayMethod::rawValue)

internal fun AiGatewayConnection.missingRequiredAiMethods(): Set<String> = requiredAiGatewayMethods - methods
