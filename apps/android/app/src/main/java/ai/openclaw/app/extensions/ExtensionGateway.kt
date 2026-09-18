package ai.openclaw.app.extensions

/** Immutable identity of the Gateway session used by extension-management domains. */
internal data class ExtensionGatewayConnection(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
  val methods: Set<String>,
  val adminScope: Boolean,
)

/**
 * Narrow transport shared by Plugin, Skill, and Connection controllers.
 *
 * The transport owns socket/epoch validation. Each feature controller owns its own state,
 * requests, and lifecycle; this interface intentionally contains no domain state.
 */
internal interface ExtensionGatewayTransport {
  fun capture(): ExtensionGatewayConnection?

  fun publish(
    connection: ExtensionGatewayConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: ExtensionGatewayConnection,
    method: String,
    params: String,
    timeoutMs: Long = 15_000,
  ): String
}
