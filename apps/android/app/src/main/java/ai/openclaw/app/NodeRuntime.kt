package ai.openclaw.app

import ai.openclaw.app.ai.AiGatewayConnection
import ai.openclaw.app.ai.AiGatewayTransport
import ai.openclaw.app.ai.AiSetupController
import ai.openclaw.app.ai.AndroidAiSetupSessionStore
import ai.openclaw.app.ai.GatewayRestartCoordinator
import ai.openclaw.app.ai.GatewayRuntimeIdentity
import ai.openclaw.app.ai.GatewayRuntimeIdentitySource
import ai.openclaw.app.ai.GatewayWizardController
import ai.openclaw.app.ai.ModelCatalogRepository
import ai.openclaw.app.ai.ModelCatalogSnapshot
import ai.openclaw.app.ai.ModelCatalogStatus
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionController
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiddevice.AndroidDeviceGatewayConnection
import ai.openclaw.app.androiddevice.AndroidDeviceTransport
import ai.openclaw.app.androiddevice.AndroidNsdAdbTlsEndpointDiscovery
import ai.openclaw.app.androiddevice.MediaStoreAndroidDeviceChallengeStore
import ai.openclaw.app.androiduse.AndroidUseChatStopHandoff
import ai.openclaw.app.androiduse.AndroidUseHandler
import ai.openclaw.app.androiduse.AndroidUseLeaseController
import ai.openclaw.app.androiduse.AndroidUseRevocation
import ai.openclaw.app.appdelivery.DeviceHandoffGate
import ai.openclaw.app.appdelivery.parseAndroidAppInstallGatewayEvent
import ai.openclaw.app.approval.ApprovalConnection
import ai.openclaw.app.approval.ApprovalController
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.approval.ApprovalTransport
import ai.openclaw.app.chat.AndroidClientDatabases
import ai.openclaw.app.chat.ChatCacheScope
import ai.openclaw.app.chat.ChatCommandOutbox
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatController
import ai.openclaw.app.chat.ChatConversationManagementFeature
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatDirectoryFeature
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatGatewayFeature
import ai.openclaw.app.chat.ChatGatewayRoutingFeature
import ai.openclaw.app.chat.ChatGatewayScopeFeature
import ai.openclaw.app.chat.ChatGatewayStatusFeature
import ai.openclaw.app.chat.ChatLocalDraftFeature
import ai.openclaw.app.chat.ChatNavigation
import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatSessionDeletion
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.chat.ChatStopFeature
import ai.openclaw.app.chat.ChatTranscriptCache
import ai.openclaw.app.chat.MainSessionBinding
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.chat.SESSION_UNREAD_ACK_CAPABILITY
import ai.openclaw.app.extensions.ExtensionGatewayConnection
import ai.openclaw.app.extensions.ExtensionGatewayTransport
import ai.openclaw.app.gateway.DeviceAuthEntry
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayBinaryWebSocketListener
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayEvent
import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.GatewayUpdateAvailableSummary
import ai.openclaw.app.gateway.formatGatewayAuthority
import ai.openclaw.app.gateway.normalizeGatewayApprovalRequestId
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprintInput
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.mcp.McpConfigController
import ai.openclaw.app.mcp.McpConfigTransport
import ai.openclaw.app.mcp.McpGatewayEpoch
import ai.openclaw.app.node.AndroidUseCapabilityPublication
import ai.openclaw.app.node.ConnectionManager
import ai.openclaw.app.node.InvokeDispatcher
import ai.openclaw.app.node.NodeConnection
import ai.openclaw.app.node.NodeConnectionState
import ai.openclaw.app.node.NodePresence
import ai.openclaw.app.node.NodePresenceAliveBeacon
import ai.openclaw.app.node.asObjectOrNull
import ai.openclaw.app.node.asStringOrNull
import ai.openclaw.app.node.resolveGatewayAccentArgb
import ai.openclaw.app.node.resolveProfileAccentArgb
import ai.openclaw.app.ownership.taskOwnerKey
import ai.openclaw.app.permissions.SessionPermissionConnection
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsController
import ai.openclaw.app.permissions.SessionPermissionsTransport
import ai.openclaw.app.plugin.GatewayPluginIconAccess
import ai.openclaw.app.plugin.PluginController
import ai.openclaw.app.plugin.PluginIconFetcher
import ai.openclaw.app.project.PROJECT_CREATE_METHOD
import ai.openclaw.app.project.ProjectConnection
import ai.openclaw.app.project.ProjectController
import ai.openclaw.app.project.ProjectTransport
import ai.openclaw.app.skill.SkillController
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.terminal.TerminalController
import ai.openclaw.app.terminal.TerminalGatewayConnection
import ai.openclaw.app.terminal.TerminalGatewayTransport
import ai.openclaw.app.vscreen.PreviewSurfaceProbeController
import ai.openclaw.app.vscreen.PreviewSurfaceProbeState
import ai.openclaw.app.vscreen.SystemAndroidVScreenDisplayObserver
import ai.openclaw.app.vscreen.VScreenController
import ai.openclaw.app.vscreen.VScreenFeature
import ai.openclaw.app.vscreen.VScreenGatewayConnection
import ai.openclaw.app.vscreen.VScreenProducerRegistry
import ai.openclaw.app.vscreen.VScreenStream
import ai.openclaw.app.vscreen.VScreenStreamListener
import ai.openclaw.app.vscreen.VScreenTargetRegistry
import ai.openclaw.app.vscreen.VScreenTransport
import ai.openclaw.app.vscreen.producer.ANDROID_VSCREEN_PRODUCER_ID
import ai.openclaw.app.vscreen.producer.AndroidVScreenProducer
import ai.openclaw.app.vscreen.producer.AndroidVScreenWorkloadRegistry
import ai.openclaw.app.webdelivery.WEB_PROJECT_RESULT_METHOD
import ai.openclaw.app.webdelivery.WebProjectResultStorage
import ai.openclaw.app.webdelivery.WebProjectResultsController
import ai.openclaw.app.webdelivery.parseWebProjectGatewayEvent
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val NODE_APPROVAL_COMMAND_FRESH_MS = 30_000L
private const val OperatorAdminScope = "operator.admin"

private fun JsonElement?.asBooleanOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

internal typealias GatewayDataRequestOverride =
  suspend (stableId: String, method: String, paramsJson: String?) -> String

private data class AndroidChatStores(
  val transcriptCache: ChatTranscriptCache,
  val commandOutbox: ChatCommandOutbox,
  val clientDatabases: AndroidClientDatabases,
  val externalTranscriptCache: ChatTranscriptCache? = null,
)

internal enum class NodeRuntimeMode {
  Live,
  ScreenshotFixture,
}

internal class SessionObserverVisibility(
  private val isVisible: () -> Boolean,
  private val captureLease: () -> GatewaySession.RequestLease?,
) {
  private val mutex = Mutex()
  private var appliedLease: GatewaySession.RequestLease? = null
  private var appliedVisibility: Boolean? = null

  suspend fun sync() {
    mutex.withLock {
      val lease = captureLease() ?: return@withLock
      val visible = isVisible()
      // Socket-bound declarations must survive reconnect without duplicate
      // foreground RPCs or leaking a queued update onto the next gateway.
      if (appliedVisibility == visible && appliedLease?.isCurrent() == true) return@withLock
      // A timeout can mean the Gateway applied this change but lost its reply.
      // Invalidate the old confirmation first so the next sync cannot skip recovery.
      appliedLease = null
      appliedVisibility = null
      lease.request(
        GatewayMethod.SessionsObserverVisibility.rawValue,
        """{"visible":$visible}""",
      )
      appliedLease = lease
      appliedVisibility = visible
    }
  }
}

private fun openAndroidChatStores(
  context: Context,
  prefs: SecurePrefs,
): AndroidChatStores {
  val databases =
    AndroidClientDatabases.start(
      context.applicationContext,
      registeredGatewayIds =
        setOfNotNull(prefs.localGatewayPairing.stableId.value),
    )
  return AndroidChatStores(
    transcriptCache = databases.transcriptCache(),
    commandOutbox = databases.commandOutbox(),
    clientDatabases = databases,
  )
}

private fun openAndroidChatStores(
  context: Context,
  prefs: SecurePrefs,
  transcriptCache: ChatTranscriptCache,
): AndroidChatStores {
  val databases =
    AndroidClientDatabases.start(
      context.applicationContext,
      registeredGatewayIds =
        setOfNotNull(prefs.localGatewayPairing.stableId.value),
    )
  return AndroidChatStores(
    transcriptCache = transcriptCache,
    commandOutbox = databases.commandOutbox(),
    clientDatabases = databases,
    externalTranscriptCache = transcriptCache,
  )
}

class NodeRuntime private constructor(
  context: Context,
  val prefs: SecurePrefs,
  chatStores: AndroidChatStores,
  internal val mode: NodeRuntimeMode,
  private val screenshotFixture: AndroidScreenshotRuntimeFixture?,
  initialForeground: Boolean,
  initialReconnectSuppressed: Boolean,
) {
  private val chatTranscriptCache = chatStores.transcriptCache
  private val chatCommandOutbox = chatStores.commandOutbox
  private val clientDatabases = chatStores.clientDatabases
  private val externalTranscriptCache = chatStores.externalTranscriptCache
  private val screenshotRequester by lazy { requireNotNull(screenshotFixture).createRequester() }
  private val gatewayAuthLifecycleLock = Any()
  private var gatewayAuthResetInProgress = false
  private var gatewayConnectOperationsInFlight = 0
  private var gatewayConnectOperationsDrained = CompletableDeferred(Unit)

  private val gatewayDataScopeLock = Any()
  private val localGatewayLifecycleMutex = Mutex()
  private val gatewayLifecycleIntentLock = Any()
  private val gatewayLifecycleIntentSeq = AtomicLong()
  private var gatewayDataGeneration = 0L

  private data class GatewayDataScope(
    val stableId: String,
    val generation: Long,
  )

  private data class LocalGatewayTarget(
    val pairingRevision: Long,
    val endpoint: GatewayEndpoint,
    val auth: GatewayConnectAuth,
    val tls: GatewayTlsParams?,
  )

  constructor(
    context: Context,
    prefs: SecurePrefs = SecurePrefs(context.applicationContext),
  ) : this(
    context = context,
    prefs = prefs,
    chatStores = openAndroidChatStores(context, prefs),
    mode = NodeRuntimeMode.Live,
    screenshotFixture = null,
    initialForeground = true,
    initialReconnectSuppressed = false,
  )

  internal constructor(
    context: Context,
    prefs: SecurePrefs,
    initialForeground: Boolean,
  ) : this(
    context = context,
    prefs = prefs,
    chatStores = openAndroidChatStores(context, prefs),
    mode = NodeRuntimeMode.Live,
    screenshotFixture = null,
    initialForeground = initialForeground,
    initialReconnectSuppressed = false,
  )

  internal constructor(
    context: Context,
    prefs: SecurePrefs,
    chatTranscriptCache: ChatTranscriptCache,
  ) : this(
    context = context,
    prefs = prefs,
    chatStores = openAndroidChatStores(context, prefs, chatTranscriptCache),
    mode = NodeRuntimeMode.Live,
    screenshotFixture = null,
    initialForeground = true,
    initialReconnectSuppressed = false,
  )

  companion object {
    internal fun forGatewayAuthReset(
      context: Context,
      prefs: SecurePrefs,
    ): NodeRuntime =
      NodeRuntime(
        context = context,
        prefs = prefs,
        chatStores = openAndroidChatStores(context, prefs),
        mode = NodeRuntimeMode.Live,
        screenshotFixture = null,
        initialForeground = true,
        initialReconnectSuppressed = true,
      )

    internal fun forScreenshotFixture(
      context: Context,
      prefs: SecurePrefs,
      fixture: AndroidScreenshotRuntimeFixture,
    ): NodeRuntime =
      NodeRuntime(
        context = context,
        prefs = prefs,
        chatStores = openAndroidChatStores(context, prefs),
        mode = NodeRuntimeMode.ScreenshotFixture,
        screenshotFixture = fixture,
        initialForeground = true,
        initialReconnectSuppressed = false,
      )
  }

  /** Authentication material supplied by Supervisor setup before gateway session routing. */
  data class GatewayConnectAuth(
    val token: String?,
    val bootstrapToken: String?,
    val password: String?,
  )

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val deviceAuthStore = DeviceAuthStore(prefs)
  private val json = Json { ignoreUnknownKeys = true }

  private val identityStore = DeviceIdentityStore.withPrefs(appContext, prefs)
  private var activeLocalGatewayTarget: LocalGatewayTarget? = null

  private val deviceHandoffGate = (appContext as? NodeApp)?.deviceHandoffGate ?: DeviceHandoffGate()
  private val androidUseLeaseController =
    (appContext as? NodeApp)?.androidUseLeaseController
      ?: AndroidUseLeaseController(
        scope,
        consentGranted = { prefs.androidUseConsent.value },
        handoffBlocked = deviceHandoffGate::isActive,
      )
  private val vscreenTargets =
    (appContext as? NodeApp)
      ?.vscreenTargets
      ?: VScreenTargetRegistry { retired -> androidUseLeaseController.revokeVScreen(retired) }
  private val androidDeviceVScreenWorkloads = AndroidVScreenWorkloadRegistry()
  private val androidUseHandler =
    AndroidUseHandler(
      androidUseLeaseController,
      vscreenTargets,
      currentOperatorConnection = { operatorSession.captureRequestLease() },
      vscreenTargetPackage = androidDeviceVScreenWorkloads::resolve,
    )
  val androidUseServiceAvailable: StateFlow<Boolean> = androidUseHandler.isConnected
  val androidUseAvailable: StateFlow<Boolean> =
    combine(androidUseServiceAvailable, prefs.androidUseConsent) { connected, consent -> connected && consent }
      .stateIn(scope, SharingStarted.Eagerly, androidUseReady())

  private fun androidUseReady(): Boolean = prefs.androidUseConsent.value && androidUseHandler.isConnected.value

  private val androidUseChatStopHandoff =
    AndroidUseChatStopHandoff(
      currentChatConnection = ::chatCacheScope,
      captureNodeLease = { gatewayId -> nodeConnection.session.captureRequestLease(gatewayId) },
      controls = androidUseLeaseController,
    )

  private val connectionManager: ConnectionManager =
    ConnectionManager(
      prefs = prefs,
      androidUseAvailable = { androidUseServiceAvailable.value },
    )
  private val androidUseCapabilityPublication = AndroidUseCapabilityPublication()

  private val invokeDispatcher: InvokeDispatcher =
    InvokeDispatcher(
      androidUseHandler = androidUseHandler,
      androidUseAvailable = { androidUseServiceAvailable.value },
    )

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
  val nodeConnected: StateFlow<Boolean>
    get() = nodeConnection.connected
  private val _nodeCapabilityApproval = MutableStateFlow<GatewayNodeCapabilityApproval>(GatewayNodeCapabilityApproval.Loading)
  val nodeCapabilityApproval: StateFlow<GatewayNodeCapabilityApproval> = _nodeCapabilityApproval.asStateFlow()

  private val _gatewayConnectionDisplay = MutableStateFlow(GatewayConnectionDisplay(false, GATEWAY_STATUS_OFFLINE, null))
  val gatewayConnectionDisplay: StateFlow<GatewayConnectionDisplay> = _gatewayConnectionDisplay.asStateFlow()
  private val _operatorScopes = MutableStateFlow<List<String>>(emptyList())
  val operatorScopes: StateFlow<List<String>> = _operatorScopes.asStateFlow()
  val operatorAdminScopeAvailable: StateFlow<Boolean> =
    operatorScopes
      .map { scopes -> scopes.any { it == OperatorAdminScope } }
      .stateIn(scope, SharingStarted.Eagerly, false)

  private val connectAttemptSeq = AtomicLong(0)

  /**
   * Builds the node-owned session key from stable device identity plus optional active agent.
   */
  private fun resolveNodeMainSessionKey(agentId: String? = null): String {
    val deviceId = identityStore.loadOrCreate().deviceId
    return buildNodeMainSessionKey(deviceId, agentId)
  }

  private val _mainSessionKey = MutableStateFlow(resolveNodeMainSessionKey())
  val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

  private val _serverName = MutableStateFlow<String?>(null)
  val serverName: StateFlow<String?> = _serverName.asStateFlow()

  private val _remoteAddress = MutableStateFlow<String?>(null)
  val remoteAddress: StateFlow<String?> = _remoteAddress.asStateFlow()

  private val _gatewayVersion = MutableStateFlow<String?>(null)
  val gatewayVersion: StateFlow<String?> = _gatewayVersion.asStateFlow()

  private val _gatewayUpdateAvailable = MutableStateFlow<GatewayUpdateAvailableSummary?>(null)
  val gatewayUpdateAvailable: StateFlow<GatewayUpdateAvailableSummary?> = _gatewayUpdateAvailable.asStateFlow()

  private val _gatewayAccentArgb = MutableStateFlow<Long?>(null)
  val gatewayAccentArgb: StateFlow<Long?> = _gatewayAccentArgb.asStateFlow()
  private val _gatewayDefaultAgentId = MutableStateFlow<String?>(null)
  val gatewayDefaultAgentId: StateFlow<String?> = _gatewayDefaultAgentId.asStateFlow()
  private val gatewayDefaultAgentRevision = AtomicLong(0)
  private var gatewayDefaultAgentStableId: String? = null

  private fun updateGatewayDefaultAgentId(agentId: String?) {
    val normalized = agentId?.trim()?.ifEmpty { null }
    val ownerStableId = normalized?.let { chatCacheGatewayId() }
    if (_gatewayDefaultAgentId.value == normalized && gatewayDefaultAgentStableId == ownerStableId) return
    // Revision first: a send may observe either side of the value write, but never a new
    // owner paired with the previous epoch during an A -> B -> A transition.
    gatewayDefaultAgentRevision.incrementAndGet()
    _gatewayDefaultAgentId.value = normalized
    gatewayDefaultAgentStableId = ownerStableId
    chat.onDefaultAgentChanged(normalized)
  }

  private val pluginIconFetcher = PluginIconFetcher()
  private val mcpConfigController =
    McpConfigController(
      scope = scope,
      transport =
        object : McpConfigTransport {
          override fun captureEpoch(): McpGatewayEpoch? =
            captureGatewayDataScope()?.let {
              McpGatewayEpoch(it.stableId, it.generation, gatewayMethodsEpoch.value)
            }

          override fun isCurrent(epoch: McpGatewayEpoch): Boolean =
            isGatewayDataScopeCurrent(GatewayDataScope(epoch.stableId, epoch.generation)) &&
              epoch.catalogRevision == gatewayMethodsEpoch.value

          override fun isConnected(): Boolean = operatorConnected

          override fun hasAdminScope(): Boolean = operatorAdminScopeAvailable.value

          override fun canReadConfig(): Boolean = gatewayAdvertisesMethod("config.get") == true

          override fun canPatchConfig(): Boolean = gatewayAdvertisesMethod("config.patch") == true

          override suspend fun request(
            epoch: McpGatewayEpoch,
            method: String,
            paramsJson: String,
            timeoutMs: Long,
          ): String =
            requestGatewayData(
              GatewayDataScope(epoch.stableId, epoch.generation),
              method,
              paramsJson,
              timeoutMs,
            )
        },
      json = json,
    )
  internal val connectionFeature = mcpConfigController.feature
  private val nodeApprovalRefreshGuard = LatestGatewayRefreshGuard()
  private val approvalInbox =
    ApprovalController(
      scope,
      object : ApprovalTransport {
        override fun capture(): ApprovalConnection? {
          if (!operatorConnected) return null
          val data = captureGatewayDataScope() ?: return null
          return synchronized(gatewayMethodsLock) {
            ApprovalConnection(data.stableId, data.generation, gatewayMethodsEpoch.value, gatewayAdvertisedMethods.orEmpty())
          }
        }

        override fun publish(
          connection: ApprovalConnection,
          block: () -> Unit,
        ): Boolean {
          var published = false
          publishGatewayData(GatewayDataScope(connection.stableId, connection.generation)) {
            synchronized(gatewayMethodsLock) {
              if (operatorConnected && connection.catalogRevision == gatewayMethodsEpoch.value) {
                block()
                published = true
              }
            }
          }
          return published
        }

        override suspend fun request(
          connection: ApprovalConnection,
          method: String,
          params: String,
        ): String {
          if (capture() != connection) throw GatewayRequestNotEnqueued("approval connection changed before request")
          val result = requestGatewayData(GatewayDataScope(connection.stableId, connection.generation), method, params)
          if (capture() != connection) throw CancellationException("approval connection changed")
          return result
        }
      },
    )
  internal val approvalFeature = approvalInbox.feature(refreshEnabled = mode != NodeRuntimeMode.ScreenshotFixture)

  private val sessionPermissions =
    SessionPermissionsController(
      scope,
      object : SessionPermissionsTransport {
        override fun capture(): SessionPermissionConnection? {
          if (!operatorConnected) return null
          val data = captureGatewayDataScope() ?: return null
          return synchronized(gatewayMethodsLock) {
            SessionPermissionConnection(data.stableId, data.generation, gatewayMethodsEpoch.value, _operatorScopes.value.toSet(), gatewayAdvertisedMethods.orEmpty())
          }
        }

        override fun publish(
          connection: SessionPermissionConnection,
          block: () -> Unit,
        ): Boolean {
          var published = false
          publishGatewayData(GatewayDataScope(connection.gatewayId, connection.generation)) {
            synchronized(gatewayMethodsLock) {
              if (capture() == connection) {
                block()
                published = true
              }
            }
          }
          return published
        }

        override suspend fun request(
          connection: SessionPermissionConnection,
          method: String,
          params: String,
        ): String {
          if (capture() != connection) throw GatewayRequestNotEnqueued("permission connection changed before request")
          return requestGatewayData(GatewayDataScope(connection.gatewayId, connection.generation), method, params)
        }
      },
      onReady = { chat.onSessionPermissionsReady() },
    )

  internal val permissionFeature = sessionPermissions.feature

  // Each hello advances the catalog epoch. It prevents an old socket's
  // response from publishing into a replacement socket on the same stable endpoint.
  private val gatewayMethodsLock = Any()
  private var gatewayAdvertisedMethods: Set<String>? = null
  private var gatewayMethodCatalogPresent = false
  private var gatewayAdvertisedCapabilities: Set<String>? = null
  private val gatewayMethodsEpoch = MutableStateFlow(0L)
  internal val gatewayCatalogRevision: StateFlow<Long> = gatewayMethodsEpoch.asStateFlow()

  private val extensionGatewayTransport =
    object : ExtensionGatewayTransport {
      override fun capture(): ExtensionGatewayConnection? {
        if (!operatorConnected) return null
        val data = captureGatewayDataScope() ?: return null
        return synchronized(gatewayMethodsLock) {
          ExtensionGatewayConnection(
            stableId = data.stableId,
            generation = data.generation,
            catalogRevision = gatewayMethodsEpoch.value,
            methods = gatewayAdvertisedMethods.orEmpty(),
            adminScope = OperatorAdminScope in _operatorScopes.value,
          )
        }
      }

      override fun publish(
        connection: ExtensionGatewayConnection,
        block: () -> Unit,
      ): Boolean {
        var published = false
        publishGatewayData(GatewayDataScope(connection.stableId, connection.generation)) {
          synchronized(gatewayMethodsLock) {
            if (capture() == connection) {
              block()
              published = true
            }
          }
        }
        return published
      }

      override suspend fun request(
        connection: ExtensionGatewayConnection,
        method: String,
        params: String,
        timeoutMs: Long,
      ): String {
        if (capture() != connection) {
          throw GatewayRequestNotEnqueued("extension connection changed before request")
        }
        val response =
          requestGatewayData(
            GatewayDataScope(connection.stableId, connection.generation),
            method,
            params,
            timeoutMs,
          )
        if (capture() != connection) throw CancellationException("extension connection changed")
        return response
      }
    }

  private val aiGatewayTransport =
    object : AiGatewayTransport {
      override fun capture(): AiGatewayConnection? {
        if (!operatorConnected) return null
        val data = captureGatewayDataScope() ?: return null
        return synchronized(gatewayMethodsLock) {
          AiGatewayConnection(
            stableId = data.stableId,
            generation = data.generation,
            catalogRevision = gatewayMethodsEpoch.value,
            methods = gatewayAdvertisedMethods.orEmpty(),
            defaultAgentId = _gatewayDefaultAgentId.value,
            adminScope = OperatorAdminScope in _operatorScopes.value,
          )
        }
      }

      override fun publish(
        connection: AiGatewayConnection,
        block: () -> Unit,
      ): Boolean {
        var published = false
        publishGatewayData(GatewayDataScope(connection.stableId, connection.generation)) {
          synchronized(gatewayMethodsLock) {
            if (capture() == connection) {
              block()
              published = true
            }
          }
        }
        return published
      }

      override suspend fun request(
        connection: AiGatewayConnection,
        method: String,
        params: String,
        timeoutMs: Long,
      ): String {
        if (capture() != connection) {
          throw GatewayRequestNotEnqueued("AI connection changed before request")
        }
        val response =
          requestGatewayData(
            GatewayDataScope(connection.stableId, connection.generation),
            method,
            params,
            timeoutMs,
          )
        if (capture() != connection) throw CancellationException("AI connection changed")
        return response
      }
    }

  private val modelCatalogRepository =
    ModelCatalogRepository(
      scope = scope,
      transport = aiGatewayTransport,
      json = json,
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
    )
  internal val aiModelCatalog = modelCatalogRepository.state

  internal val gatewayWizardController =
    GatewayWizardController(
      scope = scope,
      transport = aiGatewayTransport,
      json = json,
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
    )
  internal val aiWizard = gatewayWizardController.state

  internal val gatewayRestartCoordinator =
    GatewayRestartCoordinator(
      scope = scope,
      transport = aiGatewayTransport,
      identitySource = GatewayRuntimeIdentitySource(::captureGatewayRuntimeIdentity),
      json = json,
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
    )
  internal val aiGatewayRestart = gatewayRestartCoordinator.state

  private val aiSetupController =
    AiSetupController(
      scope = scope,
      transport = aiGatewayTransport,
      wizard = gatewayWizardController,
      restart = gatewayRestartCoordinator,
      json = json,
      sessionStore = AndroidAiSetupSessionStore(appContext),
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
      onReady = { modelCatalogRepository.refresh(force = true) },
    )
  internal val aiSetupFeature = aiSetupController.feature

  private val terminalController =
    TerminalController(
      scope = scope,
      connected = isConnected,
      adminScope = operatorAdminScopeAvailable,
      transport =
        object : TerminalGatewayTransport {
          override fun capture(): TerminalGatewayConnection? =
            when (mode) {
              NodeRuntimeMode.Live -> {
                if (!operatorConnected) return null
                val data = captureGatewayDataScope() ?: return null
                TerminalGatewayConnection(data.stableId, data.generation)
              }
              NodeRuntimeMode.ScreenshotFixture ->
                requireNotNull(screenshotFixture)
                  .takeUnless { it.offline }
                  ?.let { TerminalGatewayConnection(it.gatewayId, 0L) }
            }

          override fun hasAdminScope(): Boolean = operatorAdminScopeAvailable.value

          override suspend fun requestSetupCode(connection: TerminalGatewayConnection): String {
            if (capture() != connection) throw GatewayRequestNotEnqueued("terminal connection changed before request")
            return when (mode) {
              NodeRuntimeMode.Live ->
                requestGatewayData(
                  GatewayDataScope(connection.stableId, connection.generation),
                  GatewayMethod.DevicePairSetupCode.rawValue,
                  "{\"includeQr\":false}",
                )
              NodeRuntimeMode.ScreenshotFixture -> screenshotRequester(GatewayMethod.DevicePairSetupCode.rawValue, "{\"includeQr\":false}")
            }
          }
        },
      reconnect = ::refreshGatewayConnection,
    )
  internal val terminalFeature = terminalController.feature

  private val pluginController =
    PluginController(
      scope = scope,
      transport = extensionGatewayTransport,
      json = json,
      restart = gatewayRestartCoordinator,
      iconLoader = { connection, pluginId ->
        val page = terminalController.page.value ?: return@PluginController null
        pluginIconFetcher.fetch(
          access =
            GatewayPluginIconAccess(
              baseUrl = page.baseUrl,
              authCandidates = listOfNotNull(page.token, page.password),
              tlsFingerprintSha256 = page.tlsFingerprintSha256,
            ),
          pluginId = pluginId,
        )
      },
      incompatibleApiMessage = appContext.getString(R.string.extension_plugin_runtime_update_required),
      genericChangeFailureMessage = appContext.getString(R.string.extension_plugin_change_failed),
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
    )
  internal val pluginFeature = pluginController.feature

  private val skillController =
    SkillController(
      scope = scope,
      transport = extensionGatewayTransport,
      json = json,
      actionsEnabled = mode != NodeRuntimeMode.ScreenshotFixture,
    )
  internal val skillFeature = skillController.feature

  private val androidDeviceConnectionController =
    AndroidDeviceConnectionController(
      scope = scope,
      transport =
        object : AndroidDeviceTransport {
          override fun capture(): AndroidDeviceGatewayConnection? {
            if (!operatorConnected) return null
            val lease = operatorSession.captureRequestLease() ?: return null
            val data = captureGatewayDataScope() ?: return null
            if (data.stableId != lease.endpointStableId) return null
            return synchronized(gatewayMethodsLock) {
              AndroidDeviceGatewayConnection(
                stableId = data.stableId,
                generation = data.generation,
                catalogRevision = gatewayMethodsEpoch.value,
                methods = gatewayAdvertisedMethods.orEmpty(),
                lease = lease,
              )
            }
          }

          override fun publish(
            connection: AndroidDeviceGatewayConnection,
            block: () -> Unit,
          ): Boolean {
            var published = false
            connection.lease.commitIfCurrent {
              publishGatewayData(GatewayDataScope(connection.stableId, connection.generation)) {
                synchronized(gatewayMethodsLock) {
                  if (operatorConnected && connection.catalogRevision == gatewayMethodsEpoch.value) {
                    block()
                    published = true
                  }
                }
              }
            }
            return published
          }

          override suspend fun request(
            connection: AndroidDeviceGatewayConnection,
            method: String,
            params: String,
            timeoutMs: Long,
          ): String {
            if (capture() != connection) {
              throw GatewayRequestNotEnqueued("development connection changed before request")
            }
            val response =
              connection.lease.request(
                method,
                params,
                timeoutMs,
              )
            if (capture() != connection) throw CancellationException("development connection changed")
            return response
          }
        },
      challengeStore = MediaStoreAndroidDeviceChallengeStore(appContext),
      endpointDiscovery = AndroidNsdAdbTlsEndpointDiscovery(appContext),
      json = json,
    )
  internal val androidDeviceConnectionState: StateFlow<AndroidDeviceConnectionState> =
    androidDeviceConnectionController.state

  private val androidAppResultStorage =
    ai.openclaw.app.appdelivery
      .AndroidAppResultStorage(appContext)
  private val androidAppResultsController: ai.openclaw.app.appdelivery.AndroidAppResultsController by lazy {
    ai.openclaw.app.appdelivery.AndroidAppResultsController(
      scope = scope,
      initial = androidAppResultStorage.load(),
      save = androidAppResultStorage::save,
      verifyInstalled = androidAppResultStorage::verifyInstalled,
      handoffAndOpen = { result ->
        if (captureGatewayDataScope()?.stableId != result.gatewayId) {
          false
        } else {
          val handoff = deviceHandoffGate.tryAcquire()
          if (handoff == null) {
            false
          } else {
            try {
              if (!androidUseLeaseController.revokeForHandoff(taskOwnerKey(result.sessionKey), result.sessionId, result.runId)) {
                false
              } else {
                check(androidAppResultStorage.verifyInstalled(result)) { "Installed app changed during handoff" }
                check(captureGatewayDataScope()?.stableId == result.gatewayId)
                androidAppResultStorage.open(result)
                true
              }
            } finally {
              handoff.close()
            }
          }
        }
      },
    )
  }
  internal val androidAppResults get() = androidAppResultsController.feature

  private val webProjectResultStorage = WebProjectResultStorage(appContext)
  private val webProjectResultsController: WebProjectResultsController by lazy {
    WebProjectResultsController(
      scope = scope,
      initial = webProjectResultStorage.load(),
      save = webProjectResultStorage::save,
      resolveAndOpen = open@{ result ->
        val data = captureGatewayDataScope()
        val lease = operatorSession.captureRequestLease() ?: return@open false
        if (data?.stableId != result.gatewayId || lease.endpointStableId != result.gatewayId) {
          false
        } else {
          val params =
            JsonObject(
              mapOf(
                "resultId" to JsonPrimitive(result.resultId),
                "projectId" to JsonPrimitive(result.projectId),
                "generation" to JsonPrimitive(result.generation),
                "targetId" to JsonPrimitive(result.targetId),
                "url" to JsonPrimitive(result.url),
              ),
            ).toString()
          val response = lease.request(WEB_PROJECT_RESULT_METHOD, params, 5_000)
          val current = captureGatewayDataScope()
          val payload = runCatching { json.parseToJsonElement(response) as? JsonObject }.getOrNull()
          if (
            current?.stableId != result.gatewayId ||
            payload?.get("status").asStringOrNull() != "ready" ||
            payload?.get("url").asStringOrNull() != result.url
          ) {
            false
          } else {
            webProjectResultStorage.openInChrome(result.url)
            true
          }
        }
      },
    )
  }
  internal val webProjectResults get() = webProjectResultsController.feature

  private val vscreenTransport =
    object : VScreenTransport {
      override fun capture(): VScreenGatewayConnection? {
        if (!operatorConnected) return null
        val lease = operatorSession.captureRequestLease() ?: return null
        val data = captureGatewayDataScope() ?: return null
        if (data.stableId != lease.endpointStableId) return null
        return synchronized(gatewayMethodsLock) {
          VScreenGatewayConnection(
            stableId = data.stableId,
            generation = data.generation,
            catalogRevision = gatewayMethodsEpoch.value,
            methods = gatewayAdvertisedMethods.orEmpty(),
            lease = lease,
          )
        }
      }

      override fun publish(
        connection: VScreenGatewayConnection,
        block: () -> Unit,
      ): Boolean {
        var published = false
        connection.lease.commitIfCurrent {
          publishGatewayData(GatewayDataScope(connection.stableId, connection.generation)) {
            synchronized(gatewayMethodsLock) {
              if (operatorConnected && connection.catalogRevision == gatewayMethodsEpoch.value) {
                block()
                published = true
              }
            }
          }
        }
        return published
      }

      override suspend fun request(
        connection: VScreenGatewayConnection,
        method: String,
        params: String,
        timeoutMs: Long,
      ): String {
        if (capture() != connection) throw GatewayRequestNotEnqueued("VScreen connection changed before request")
        val response =
          connection.lease.request(
            method,
            params,
            timeoutMs,
          )
        if (capture() != connection) throw CancellationException("VScreen connection changed")
        return response
      }

      override fun openStream(
        connection: VScreenGatewayConnection,
        routePath: String,
        bearerToken: String,
        listener: VScreenStreamListener,
      ): VScreenStream {
        if (capture() != connection) throw GatewayRequestNotEnqueued("VScreen connection changed before stream")
        var stream: VScreenStream? = null
        connection.lease.commitIfCurrent {
          val socket =
            operatorSession.openBinaryWebSocketForEndpoint(
              expectedEndpointStableId = connection.stableId,
              routePath = routePath,
              bearerToken = bearerToken,
              listener =
                object : GatewayBinaryWebSocketListener {
                  override fun onOpen() = listener.onOpen()

                  override fun onBytes(bytes: ByteArray) = listener.onBytes(bytes)

                  override fun onClosed(message: String?) = listener.onClosed(message)
                },
            )
          stream =
            object : VScreenStream {
              override fun send(text: String): Boolean = socket.sendText(text)

              override fun cancel() = socket.cancel()
            }
        }
        return stream ?: throw GatewayRequestNotEnqueued("VScreen connection changed before stream")
      }
    }

  private val previewSurfaceProbeController =
    PreviewSurfaceProbeController(
      scope = scope,
      transport = vscreenTransport,
      producerId = ANDROID_VSCREEN_PRODUCER_ID,
      json = json,
    )
  internal val previewSurfaceProbeState: StateFlow<PreviewSurfaceProbeState> =
    previewSurfaceProbeController.state

  private val androidVScreenController =
    VScreenController(
      scope = scope,
      targets = vscreenTargets,
      producers = VScreenProducerRegistry(listOf(AndroidVScreenProducer)),
      producerId = ANDROID_VSCREEN_PRODUCER_ID,
      onWorkloadRequested = {
        (appContext as? NodeApp)?.vscreenPresentation?.openFloating()
      },
      onWorkloadAccepted = { _, workload ->
        check(androidDeviceVScreenWorkloads.accept(workload))
        chat.clearTaskNotice(workload.run.sessionKey)
      },
      onWorkloadFailed = { workload, message -> chat.reportTaskNotice(workload.run.sessionKey, message) },
      onHumanPointerDown = { target ->
        androidUseLeaseController.revokeForHumanInput(target.attachmentId, target.targetGeneration)
      },
      displayObserver = SystemAndroidVScreenDisplayObserver(appContext),
      transport = vscreenTransport,
      json = json,
    )
  internal val vscreen =
    VScreenFeature(
      state = androidVScreenController.state,
      ensure = androidVScreenController::ensure,
      close = androidVScreenController::close,
      setPresentationVisible = androidVScreenController::setPresentationVisible,
      surfaceOwner = androidVScreenController::surfaceOwner,
      sendPointer = androidVScreenController::sendPointer,
    )

  @Volatile internal var gatewayDataRequestOverrideForTests: GatewayDataRequestOverride? = null

  @Volatile internal var gatewayDataRequestTimeoutObserverForTests: ((method: String, timeoutMs: Long) -> Unit)? = null

  private val _isForeground = MutableStateFlow(initialForeground)
  val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

  private var operatorConnected = false
  private var operatorStatusText: String = "Offline"
  private var operatorConnectionProblem: GatewayConnectionProblem? = null
  private val gatewayStatusLock = Any()

  private val operatorSession: GatewaySession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { hello ->
        _serverName.value = hello.serverName
        _remoteAddress.value = hello.remoteAddress
        _gatewayVersion.value = hello.serverVersion
        _gatewayUpdateAvailable.value = hello.updateAvailable
        replaceGatewayMethods(hello.methods)
        replaceGatewayCapabilities(hello.capabilities)
        val operatorScopes = normalizeOperatorScopes(hello.authScopes)
        _operatorScopes.value = operatorScopes
        _gatewayAccentArgb.value = null
        val mainSessionKey =
          prepareMainSessionKey(resolveAgentIdFromMainSessionKey(hello.mainSessionKey))
        // Create/adopt before history refresh; this keeps the first connected read on the
        // device-owned session without changing the shipped key or its existing transcript.
        chat.onGatewayConnected(mainSessionBinding(mainSessionKey))
        refreshGatewayControlPage()
        updateStatus {
          operatorConnectionProblem = null
          operatorConnected = true
          operatorStatusText = "Connected"
        }
        modelCatalogRepository.onConnectionChanged()
        gatewayWizardController.onConnectionChanged()
        gatewayRestartCoordinator.onRuntimeIdentityChanged()
        aiSetupController.onConnectionChanged()
        pluginController.onConnectionChanged()
        skillController.onConnectionChanged()
        mcpConfigController.onConnectionChanged()
        androidDeviceConnectionController.refresh()
        sessionPermissions.onConnectionChanged()
        projectController.refreshCatalog()
        // Bootstrap can connect the node before operator access is ready.
        refreshNodeCapabilityApproval()
        scope.launch {
          subscribeOperatorSessionEvents()
          refreshBrandingFromGateway()
          approvalInbox.refresh()
        }
      },
      onDisconnected = { message ->
        clearOperatorGatewayState(retirePendingWrites = false)
        chat.applyMainSessionKey(resolveMainSessionKey())
        chat.onDisconnected(message)
        updateStatus {
          operatorConnected = false
          operatorStatusText = message
          operatorConnectionProblem = gatewayProblemAfterDisconnect(operatorConnectionProblem, message)
        }
      },
      onConnectFailure = { error, pauseReconnect ->
        val problem = gatewayConnectionProblem(error, pauseReconnect)
        modelCatalogRepository.clear()
        gatewayWizardController.onConnectionChanged()
        gatewayRestartCoordinator.onRuntimeIdentityChanged()
        aiSetupController.onConnectionChanged()
        pluginController.clear()
        skillController.clear()
        updateStatus {
          operatorConnected = false
          operatorStatusText = problem.message
          operatorConnectionProblem = problem
        }
      },
      onConnectionRetired = ::invalidateDeviceConnection,
      onEvent = { event, payloadJson ->
        handleGatewayEvent(event, payloadJson)
      },
    )

  private val sessionObserverVisibility =
    SessionObserverVisibility(
      isVisible = { _isForeground.value },
      captureLease = { operatorSession.captureRequestLease() },
    )

  private fun clearOperatorGatewayState(retirePendingWrites: Boolean) {
    invalidateNodeCapabilityApprovalState()
    _serverName.value = null
    _remoteAddress.value = null
    _gatewayVersion.value = null
    _gatewayUpdateAvailable.value = null
    replaceGatewayMethods(null, present = false)
    replaceGatewayCapabilities(null)
    _operatorScopes.value = emptyList()
    _gatewayAccentArgb.value = null
    modelCatalogRepository.clear()
    gatewayWizardController.onConnectionChanged()
    gatewayRestartCoordinator.onRuntimeIdentityChanged()
    aiSetupController.onConnectionChanged()
    pluginController.clear()
    skillController.clear()
    mcpConfigController.onDisconnected()
    approvalInbox.clear(retirePendingWrites = retirePendingWrites)
    sessionPermissions.onConnectionChanged()
    projectController.connectionUnavailable()
  }

  private suspend fun subscribeOperatorSessionEvents() {
    try {
      operatorSession.request(GatewayMethod.SessionsSubscribe.rawValue, null)
    } catch (err: Throwable) {
      Log.d("OpenClawRuntime", "sessions.subscribe failed: ${err.message ?: err::class.java.simpleName}")
    }
    syncSessionObserverVisibility()
  }

  private suspend fun syncSessionObserverVisibility() {
    try {
      sessionObserverVisibility.sync()
    } catch (err: Throwable) {
      Log.d(
        "OpenClawRuntime",
        "sessions.observer.visibility failed: ${err.message ?: err::class.java.simpleName}",
      )
    }
  }

  private val nodeConnection: NodeConnection =
    NodeConnection(
      publicationLock = gatewayStatusLock,
      initialPresentation =
        when (mode) {
          NodeRuntimeMode.Live -> NodeConnectionState()
          NodeRuntimeMode.ScreenshotFixture ->
            requireNotNull(screenshotFixture).let { fixture ->
              NodeConnectionState(
                connected = !fixture.offline,
                statusText = if (fixture.offline) "Waiting for Gateway" else "Connected",
              )
            }
        },
      onStateChanged = { updateStatus() },
      onConnected = {
        nodePresence.publish(NodePresenceAliveBeacon.Trigger.Connect)
        refreshNodeCapabilityApproval()
        val target = activeLocalGatewayTarget
        if (!operatorConnected && target != null) {
          maybeStartOperatorSessionAfterNodeConnect(target)
        }
      },
      onDisconnected = {
        invalidateNodeCapabilityApprovalState()
      },
      onApprovalRequired = ::refreshNodeCapabilityApproval,
      createSession = { events ->
        GatewaySession(
          scope = scope,
          identityStore = identityStore,
          deviceAuthStore = deviceAuthStore,
          onConnected = { events.connected() },
          onDisconnected = events::disconnected,
          onConnectFailure = events::connectFailed,
          onConnectionRetired = ::invalidateDeviceConnection,
          onEvent = { _, _ -> },
          onInvoke = { req -> invokeDispatcher.handleInvoke(req.command, req.paramsJson, req.connection) },
        )
      },
    )

  private val nodePresence: NodePresence =
    NodePresence(
      scope = scope,
      captureConnection = { nodeConnection.session.captureRequestLease() },
      clientInfo = { connectionManager.buildClientInfo(clientId = "openclaw-android", clientMode = "node") },
    )

  /** Composition only: each device owner retires work bound to this exact physical connection. */
  private fun invalidateDeviceConnection(connection: GatewaySession.RequestLease) {
    androidUseLeaseController.invalidateConnection(connection)
    androidDeviceConnectionController.invalidateConnection(connection)
    previewSurfaceProbeController.invalidateConnection(connection)
    androidVScreenController.invalidateConnection(connection)
  }

  private val chatSessionDeletionListenerSequence = AtomicLong()
  private val chatSessionDeletionListeners = ConcurrentHashMap<Long, (ChatSessionDeletion) -> Unit>()

  internal fun addChatSessionDeletionListener(listener: (ChatSessionDeletion) -> Unit): () -> Unit {
    val id = chatSessionDeletionListenerSequence.incrementAndGet()
    chatSessionDeletionListeners[id] = listener
    return { chatSessionDeletionListeners.remove(id) }
  }

  private fun publishChatSessionDeletion(deletion: ChatSessionDeletion) {
    chatSessionDeletionListeners.values.forEach { listener -> listener(deletion) }
  }

  private val chat: ChatController =
    when (mode) {
      NodeRuntimeMode.Live ->
        ChatController(
          scope = scope,
          session = operatorSession,
          json = json,
          transcriptCache = chatTranscriptCache,
          cacheScope = ::chatCacheScope,
          currentDefaultAgentId = { gatewayDefaultAgentId.value },
          currentDefaultAgentRevision = gatewayDefaultAgentRevision::get,
          gatewayAdvertisesMethod = ::gatewayAdvertisesMethod,
          gatewayAdvertisesCapability = ::gatewayAdvertisesCapability,
          currentGatewayCatalogRevision = { gatewayMethodsEpoch.value },
          prepareSessionSend = { gatewayId, sessionKey, agentId ->
            val target = gatewayId?.let { runCatching { SessionPermissionTarget(it, sessionKey, agentId) }.getOrNull() }
            target != null &&
              sessionPermissions.prepareSend(target) != null &&
              projectController.admitRunForSession(sessionKey)
          },
          createDraftSession = sessionPermissions::create,
          stopNativeControl = androidUseChatStopHandoff::revoke,
          onSessionStopped = { stopped ->
            ai.openclaw.app.chat
              .reconcileStoppedChatPermissions(stopped, ::chatCacheScope, sessionPermissions)
          },
          commandOutbox = chatCommandOutbox,
          currentModelCatalog = ::currentAiModels,
          recordModelRecent = prefs::recordModelRecent,
          onSessionDeleted = ::publishChatSessionDeletion,
          onOfflineDefaultAgentRestored = ::syncMainSessionKey,
          onAssistantReplyFinalized = { owner, runId, text ->
            if (!_isForeground.value) {
              ConversationReplyNotifier(appContext).show(owner, runId, text)
            }
          },
        )
      NodeRuntimeMode.ScreenshotFixture ->
        ChatController(
          scope = scope,
          json = json,
          requestGateway = screenshotRequester,
          gatewayAdvertisesMethod = { _ -> true },
          gatewayAdvertisesCapability = { _ -> true },
          currentModelCatalog = ::currentAiModels,
        )
    }.also {
      it.applyMainSessionKey(_mainSessionKey.value)
    }

  private fun currentAiModels() =
    (modelCatalogRepository.state.value.status as? ModelCatalogStatus.Ready)
      ?.snapshot
      ?.models
      .orEmpty()

  private val projectController =
    ProjectController(
      scope = scope,
      transport =
        object : ProjectTransport {
          override fun capture(): ProjectConnection? {
            if (!operatorConnected) return null
            val data = captureGatewayDataScope() ?: return null
            return synchronized(gatewayMethodsLock) {
              ProjectConnection(
                gatewayId = data.stableId,
                generation = data.generation,
                methods = gatewayAdvertisedMethods.orEmpty(),
                admin = OperatorAdminScope in _operatorScopes.value,
              )
            }
          }

          override suspend fun request(
            connection: ProjectConnection,
            method: String,
            params: String,
          ): String {
            if (capture() != connection) throw GatewayRequestNotEnqueued("Project connection changed before request")
            return requestGatewayData(
              GatewayDataScope(connection.gatewayId, connection.generation),
              method,
              params,
              timeoutMs = if (method == PROJECT_CREATE_METHOD) 45_000 else 15_000,
            )
          }
        },
      createLocalDraft = chat::startNewProjectDraft,
      bindLocalDraft = chat::bindProjectDraft,
      openSession = chat::switchSession,
    )

  /** Stable pairing scope for offline Chat; null disables cache reads and writes. */
  private fun chatCacheGatewayId(): String? {
    activeLocalGatewayTarget?.endpoint?.stableId?.let { return it }
    return prefs.localGatewayPairing.stableId.value
  }

  private fun chatCacheScope(): ChatCacheScope? =
    chatCacheGatewayId()?.let { gatewayId ->
      ChatCacheScope(gatewayId = gatewayId, connectionGeneration = connectAttemptSeq.get())
    }

  private fun restoreChatConversationSelection(gatewayStableId: String) {
    if (chat.drafts.find(gatewayStableId, chat.sessionKey.value) != null) return
    val selection = prefs.loadChatConversationSelection(gatewayStableId) ?: return
    chat.switchSession(selection.sessionKey, selection.ownerAgentId)
  }

  private fun syncMainSessionKey(agentId: String?) {
    val resolvedKey = resolveNodeMainSessionKey(agentId)
    if (_mainSessionKey.value == resolvedKey) return
    _mainSessionKey.value = resolvedKey
    if (operatorConnected) {
      chat.prepareMainSessionKey(resolvedKey)
      chat.onGatewayConnected(mainSessionBinding(resolvedKey))
    } else {
      chat.applyMainSessionKey(resolvedKey)
    }
  }

  private fun prepareMainSessionKey(agentId: String?): String {
    val resolvedKey = resolveNodeMainSessionKey(agentId)
    if (_mainSessionKey.value != resolvedKey) {
      _mainSessionKey.value = resolvedKey
    }
    chat.prepareMainSessionKey(resolvedKey)
    return resolvedKey
  }

  private fun selectMainSessionKey(agentId: String) {
    val resolvedKey = resolveNodeMainSessionKey(agentId)
    _mainSessionKey.value = resolvedKey
    chat.prepareAndSelectMainSessionKey(resolvedKey)
    chat.onGatewayConnected(mainSessionBinding(resolvedKey))
  }

  private fun mainSessionBinding(sessionKey: String): MainSessionBinding =
    MainSessionBinding(
      key = sessionKey,
      label = buildAndroidAppSessionLabel(prefs.displayName.value, identityStore.loadOrCreate().deviceId),
    )

  private fun updateStatus(update: () -> Unit = {}) {
    synchronized(gatewayStatusLock) {
      update()
      // Select and publish text plus connection state atomically; operator and node callbacks run concurrently.
      val node = nodeConnection.state
      val display =
        gatewayConnectionDisplay(
          operatorConnected = operatorConnected,
          nodeConnected = node.connected,
          operatorStatusText = operatorStatusText,
          nodeStatusText = node.statusText,
          operatorProblem = operatorConnectionProblem,
          nodeProblem = node.problem,
        )
      _gatewayConnectionDisplay.value = display
      _isConnected.value = display.isConnected
    }
  }

  private fun setStandaloneGatewayStatus(statusText: String) {
    synchronized(gatewayStatusLock) {
      val display = GatewayConnectionDisplay(operatorConnected, statusText, null)
      _gatewayConnectionDisplay.value = display
      _isConnected.value = display.isConnected
    }
  }

  private fun resolveMainSessionKey(): String {
    val trimmed = _mainSessionKey.value.trim()
    return if (trimmed.isEmpty()) "main" else trimmed
  }

  private fun launchGatewayRefresh(refresh: suspend () -> Unit) {
    if (mode != NodeRuntimeMode.ScreenshotFixture) scope.launch { refresh() }
  }

  internal fun refreshAndroidDeviceConnection() = androidDeviceConnectionController.refresh()

  internal fun pairAndroidDeviceConnection(
    endpoint: String,
    pairingCode: String,
  ) = androidDeviceConnectionController.pair(endpoint, pairingCode)

  internal fun reconnectAndroidDeviceConnection(endpoint: String?) = androidDeviceConnectionController.connect(endpoint)

  internal fun verifyAndroidDeviceReconnect(endpoint: String?) = androidDeviceConnectionController.verifyReconnect(endpoint)

  internal fun checkPreviewSurface(
    expectedTargetId: String,
    phoneVerificationId: String,
  ) = previewSurfaceProbeController.check(expectedTargetId, phoneVerificationId)

  internal fun forgetAndroidDeviceConnection() = androidDeviceConnectionController.forget()

  internal fun dismissAndroidDeviceConnectionNotice() = androidDeviceConnectionController.dismissNotice()

  internal fun refreshNodeCapabilityApproval() = launchGatewayRefresh { refreshNodeCapabilityApprovalFromGateway() }

  /** Approves only the pending capability request owned by this phone's stable device identity. */
  internal suspend fun approveCurrentPhoneNodeCapabilities() {
    check(mode == NodeRuntimeMode.Live) { "Node capability approval is unavailable in fixtures" }
    val gatewayScope = captureGatewayDataScope() ?: error("Local Gateway is offline")
    check(operatorConnected) { "Local Gateway operator connection is offline" }
    val selfNodeId = identityStore.loadOrCreate().deviceId
    val pairingJson = requestGatewayData(gatewayScope, GatewayMethod.NodePairList.rawValue, "{}")
    val requestId =
      currentPhoneNodePairingRequestId(
        root = json.parseToJsonElement(pairingJson).asObjectOrNull(),
        selfNodeId = selfNodeId,
      ) ?: error("No pending Android Use authorization request for this phone")
    requestGatewayData(
      gatewayScope,
      GatewayMethod.NodePairApprove.rawValue,
      JsonObject(mapOf("requestId" to JsonPrimitive(requestId))).toString(),
    )
    refreshNodeCapabilityApprovalFromGateway()
  }

  /** Clears setup credentials plus paired device tokens for both Android gateway roles. */
  suspend fun resetGatewaySetupAuth(stableId: String): Boolean =
    gatewayLifecycleIntentSeq.incrementAndGet().let { intent ->
      localGatewayLifecycleMutex.withLock {
        if (intent != gatewayLifecycleIntentSeq.get()) false else resetGatewaySetupAuthLocked(stableId)
      }
    }

  private suspend fun resetGatewaySetupAuthLocked(stableId: String): Boolean {
    val connectOperationsDrained =
      synchronized(gatewayAuthLifecycleLock) {
        if (gatewayAuthResetInProgress) {
          null
        } else {
          gatewayAuthResetInProgress = true
          gatewayConnectOperationsDrained
        }
      }
        ?: return false
    return try {
      connectOperationsDrained.await()
      if (activeLocalGatewayTarget?.endpoint?.stableId == stableId) {
        disconnectAndJoin()
      }
      drainIdleGatewaySessionTails()
      // A deliberate disconnect retains reconnect ownership. Authentication replacement does not.
      chat.onGatewayScopeChanging(retireRunState = true)
      // Replacing Supervisor authentication retires the old local pairing identity and cache.
      val cacheCleared =
        runCatching {
          chat.clearGatewayCache(stableId) {
            clientDatabases.commitGatewayRemoval(stableId, requireCacheRemoval = true)
            externalTranscriptCache?.clearGateway(stableId)
          }
        }.onFailure { err ->
          Log.e("OpenClawRuntime", "Failed to purge gateway chat data before auth reset", err)
          setStandaloneGatewayStatus("Failed: couldn't clear offline chat data. Retry sign out.")
        }.isSuccess
      if (!cacheCleared) return false
      prefs.clearGatewayCredentials(stableId)
      val deviceId = identityStore.loadOrCreate().deviceId
      deviceAuthStore.clearToken(stableId, deviceId, "node")
      deviceAuthStore.clearToken(stableId, deviceId, "operator")
      true
    } finally {
      synchronized(gatewayAuthLifecycleLock) { gatewayAuthResetInProgress = false }
    }
  }

  val activeGatewayStableId: StateFlow<String?> = prefs.localGatewayPairing.stableId

  private var didAttemptColdStartConnection = false

  @Volatile private var localGatewayReconnectSuppressed = initialReconnectSuppressed

  internal val chatHistory = chat.historyFeature

  init {
    scope.launch {
      chatHistory.selection.key.collect(modelCatalogRepository::selectSession)
    }
    scope.launch {
      modelCatalogRepository.state.collect { state ->
        val catalog = (state.status as? ModelCatalogStatus.Ready)?.snapshot?.models ?: return@collect
        chat.applyModelCatalog(catalog)
      }
    }
  }

  internal val projects = projectController.feature
  private val chatNavigation =
    run {
      val preferences = prefs
      ChatNavigation(
        selectSession = chat::switchSession,
        newDraft = chat::startNewDraft,
        captureSelection = chat::captureNavigationSelection,
        saveSelection = { selected ->
          preferences.setChatConversationSelection(selected.gatewayId, selected.sessionKey, selected.ownerAgentId)
        },
      ).feature
    }
  internal val chatCurrentWork =
    ChatCurrentWorkFeature(
      pendingRunCount = chat.pendingRunCount,
      activeRun = chat.selectedActiveRunPresentation,
      answerDraft = chat.answerDraft,
      runActivity = chat.runActivity,
      pendingToolCalls = chat.pendingToolCalls,
      questions = chat.questions,
      progressCard = chat.progressCard,
      taskNotices = chat.taskNotices,
      updateQuestionDraft = chat::updateQuestionDraft,
      resolveQuestion = chat::resolveQuestion,
      skipQuestion = chat::skipQuestion,
    )
  internal val chatExecution =
    ChatExecutionFeature(
      localDrafts =
        ChatLocalDraftFeature(
          states = chat.drafts.states,
          chooseAction = chat.drafts::choose,
          confirmAction = chat.drafts::confirm,
        ),
      stops =
        ChatStopFeature(
          states = chat.stops.states,
          abortCurrentAction = chat::abort,
          reconcileAction = chat.stops::reconcile,
        ),
    )
  internal val chatGateway =
    ChatGatewayFeature(
      status =
        ChatGatewayStatusFeature(
          connection = gatewayConnectionDisplay,
          remoteAddress = remoteAddress,
        ),
      scope =
        ChatGatewayScopeFeature(
          activeStableId = activeGatewayStableId,
          catalogRevision = gatewayCatalogRevision,
          operatorScopes = operatorScopes,
        ),
      routing =
        ChatGatewayRoutingFeature(
          mainSessionKey = mainSessionKey,
          defaultAgentId = gatewayDefaultAgentId,
        ),
    )
  internal val chatDirectory =
    ChatDirectoryFeature(
      catalog = chat.sessionCatalogFeature,
      navigation = chatNavigation,
      management =
        ChatConversationManagementFeature(
          canSetLabel = chat::canSetConversationLabel,
          setLabel = chat::setConversationLabel,
          delete = chat::deleteConversation,
        ),
      titlePreparation = chat.sessionTitlePreparationFeature,
    )
  internal val chatSessionOptions =
    ChatSessionOptionsFeature(
      thinkingLevel = chat.thinkingLevel,
      thinkingSelection = chat.thinkingLevelSelection,
      selectedModelRef = chat.selectedModelRef,
      modelCatalog = modelCatalogRepository.state,
      commands = chat.commands,
      favorites = prefs.modelFavorites,
      recents = prefs.modelRecents,
      refresh = {
        chat.refreshCommands()
        modelCatalogRepository.refresh(force = false)
      },
      selectThinkingLevel = chat::setThinkingLevel,
      selectModelRoute = { modelRef ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          val sessionKey = chatHistory.selection.key.value
          if (chat.setSessionModelAwait(sessionKey, modelRef)) {
            modelCatalogRepository.selectSession(sessionKey)
            modelCatalogRepository.refresh(force = false)
          }
        }
      },
      toggleFavorite = prefs::toggleModelFavorite,
    )
  internal val chatOutbox =
    ChatOutboxFeature(
      items = chat.outboxItems,
      presentationRestored = chat.outboxPresentationRestored,
      retry = chat::retryOutboxCommand,
      delete = chat::deleteOutboxCommand,
    )

  private fun applyScreenshotFixture() {
    val fixture = requireNotNull(screenshotFixture)
    _serverName.value = "OpenClaw Gateway"
    _remoteAddress.value = "Mac Studio on local network"
    _gatewayVersion.value = BuildConfig.VERSION_NAME
    replaceGatewayMethods(setOf(GatewayMethod.DesktopObserve.rawValue))
    replaceGatewayCapabilities(setOf(SESSION_UNREAD_ACK_CAPABILITY))
    terminalController.replacePage(
      GatewayControlPage(
        baseUrl = fixture.controlUiBaseUrl,
        token = null,
        password = null,
        tlsFingerprintSha256 = null,
      ),
    )
    updateGatewayDefaultAgentId("main")
    modelCatalogRepository.applyFixture(
      ModelCatalogSnapshot(
        models = fixture.models,
        refreshFailed = false,
        providerOutcomes = emptyList(),
      ),
    )
    _operatorScopes.value = listOf(OperatorAdminScope)
    _nodeCapabilityApproval.value = GatewayNodeCapabilityApproval.Approved
    _mainSessionKey.value = fixture.mainSessionKey
    chat.applyMainSessionKey(fixture.mainSessionKey)
    updateStatus {
      operatorConnected = !fixture.offline
      operatorStatusText = if (fixture.offline) "Gateway offline" else "Connected"
      operatorConnectionProblem = null
    }
    chat.refreshSessions(limit = 20)
  }

  init {
    if (mode == NodeRuntimeMode.Live) {
      scope.launch {
        combine(chat.sessionKey, chat.sessionOwnerAgentId, chat.sessions, chat.drafts.states) { key, agentId, sessions, _ ->
          ApprovalSession(key, agentId).takeUnless { chat.drafts.find(chatCacheGatewayId(), key)?.isLocal == true } to
            sessions
              .filter { it.hasActiveRun == true || !it.activeRunIds.isNullOrEmpty() }
              .map { ApprovalSession(it.key, it.ownerAgentId) }
              .toSet()
        }.collect { (selected, active) -> approvalInbox.setSessions(selected, active) }
      }

      autoConnectIfNeeded()
    } else {
      applyScreenshotFixture()
    }

    if (mode == NodeRuntimeMode.Live) {
      scope.launch {
        androidUseServiceAvailable.collect { available ->
          refreshNodeSurfaceAfterSettingsChange(available)
        }
      }
      (appContext as? NodeApp)?.let { nodeApp ->
        scope.launch {
          nodeApp.supervisorControl.state.collect {
            gatewayRestartCoordinator.onRuntimeIdentityChanged()
          }
        }
      }
    }
  }

  /** Native accessibility Stop owns Android Use only; VScreen remains independent. */
  internal fun stopAndroidUseFromSafetySurface() {
    androidUseLeaseController.revoke(AndroidUseRevocation.UserStop)
  }

  /** Updates foreground state and triggers reconnect/presence behavior on app visibility changes. */
  fun setForeground(value: Boolean) {
    val visibilityChanged = _isForeground.value != value
    _isForeground.value = value
    androidVScreenController.setAppForeground(value)
    if (mode == NodeRuntimeMode.ScreenshotFixture) return
    if (visibilityChanged) {
      scope.launch {
        syncSessionObserverVisibility()
      }
    }
    if (value) {
      reconnectLocalGatewayOnForeground()
      refreshNodeSurfaceAfterSettingsChange(androidUseServiceAvailable.value)
      scope.launch {
        approvalInbox.refresh()
      }
    } else {
      nodePresence.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
    }
  }

  private fun resolveLocalGatewayTarget(explicitAuth: GatewayConnectAuth? = null): LocalGatewayTarget? {
    val pairingSnapshot = prefs.localGatewayPairing.snapshot()
    val pairing = pairingSnapshot.pairing ?: return null
    val endpoint = pairing.endpoint()
    val auth =
      explicitAuth
        ?: pairing.credentials.let { credentials ->
          GatewayConnectAuth(
            token = credentials.token,
            bootstrapToken = credentials.bootstrapToken,
            password = credentials.password,
          )
        }
    return LocalGatewayTarget(
      pairingRevision = pairingSnapshot.revision,
      endpoint = endpoint,
      auth = auth,
      tls = connectionManager.resolveTlsParams(endpoint),
    )
  }

  /** Connects only the Supervisor-installed local pairing. */
  internal fun connectLocalGateway(explicitAuth: GatewayConnectAuth? = null) {
    localGatewayReconnectSuppressed = false
    gatewayLifecycleIntentSeq.incrementAndGet()
    launchLocalGatewayConnect(explicitAuth)
  }

  private fun autoConnectIfNeeded() {
    if (localGatewayReconnectSuppressed) return
    if (didAttemptColdStartConnection) return
    if (gatewayConnectionDisplay.value.isConnected) return
    if (prefs.localGatewayPairing.pairing.value == null) return
    // Only attempt the paired local Gateway once per runtime lifetime; users can
    // still reconnect explicitly from Local environment after a failed attempt.
    didAttemptColdStartConnection = true
    // Cold-start fallback only: atomically claim the first lifecycle intent. If any explicit
    // connect/disconnect intent already exists, do not override the user's decision.
    if (!gatewayLifecycleIntentSeq.compareAndSet(0L, 1L)) return
    launchLocalGatewayConnect(explicitAuth = null)
  }

  private fun reconnectLocalGatewayOnForeground() {
    if (localGatewayReconnectSuppressed) return
    if (gatewayConnectionDisplay.value.isConnected) return
    if (activeLocalGatewayTarget != null) {
      refreshGatewayConnection()
      return
    }
    if (prefs.localGatewayPairing.pairing.value != null) connectLocalGateway()
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  fun refreshGatewayConnection() {
    localGatewayReconnectSuppressed = false
    gatewayLifecycleIntentSeq.incrementAndGet()
    launchGatewayLifecycle {
      val target =
        runCatching { resolveLocalGatewayTarget() }
          .getOrElse {
            setStandaloneGatewayStatus("Failed: paired local Gateway identity is invalid. Run setup again.")
            return@launchGatewayLifecycle
          }
      if (target == null) {
        setStandaloneGatewayStatus("Failed: no paired local Gateway")
        return@launchGatewayLifecycle
      }
      updateStatus {
        operatorStatusText = "Connecting…"
        operatorConnectionProblem = null
      }
      if (activeLocalGatewayTarget?.pairingRevision == target.pairingRevision) {
        connectLocalGatewayTarget(target)
      } else {
        beginConnect(target)
      }
    }
  }

  private fun refreshNodeSurfaceAfterSettingsChange(available: Boolean) {
    launchGatewayLifecycle {
      if (localGatewayReconnectSuppressed) return@launchGatewayLifecycle
      if (!androidUseCapabilityPublication.needsRefresh(available)) return@launchGatewayLifecycle
      val target = activeLocalGatewayTarget ?: return@launchGatewayLifecycle
      connectNode(target)
    }
  }

  private fun launchGatewayLifecycle(block: () -> Unit) {
    val intent = gatewayLifecycleIntentSeq.get()
    val guardedBlock = {
      synchronized(gatewayLifecycleIntentLock) {
        if (intent == gatewayLifecycleIntentSeq.get()) block()
      }
    }
    if (localGatewayLifecycleMutex.tryLock()) {
      try {
        guardedBlock()
      } finally {
        localGatewayLifecycleMutex.unlock()
      }
    } else {
      scope.launch { localGatewayLifecycleMutex.withLock { guardedBlock() } }
    }
  }

  // One operation publishes the same immutable target to Terminal, Operator, and Node.
  private fun connectLocalGatewayTarget(
    target: LocalGatewayTarget,
    beforeConnect: () -> Unit = {},
  ): Boolean =
    runGatewayConnectOperation {
      beforeConnect()
      activeLocalGatewayTarget = target
      val storedOperatorEntry = loadStoredRoleDeviceAuthEntry(target.endpoint, "operator")
      refreshGatewayControlPage(target, storedOperatorEntry?.token)
      val usesStoredOperatorDeviceToken =
        operatorSessionUsesStoredDeviceToken(target.auth, storedOperatorEntry?.token)
      val operatorAuth =
        resolveOperatorSessionConnectAuth(
          auth = target.auth,
          storedOperatorToken = storedOperatorEntry?.token,
        )
      if (operatorAuth == null) {
        updateStatus {
          operatorConnected = false
          operatorStatusText = "Offline"
          operatorConnectionProblem = null
        }
        operatorSession.disconnect()
      } else {
        operatorSession.connect(
          target.endpoint,
          operatorAuth.token,
          operatorAuth.bootstrapToken,
          operatorAuth.password,
          connectionManager.buildOperatorConnectOptions(
            scopes =
              operatorConnectScopesForAuth(
                usesStoredDeviceToken = usesStoredOperatorDeviceToken,
                storedOperatorScopes = storedOperatorEntry?.scopes,
              ),
          ),
          target.tls,
        )
      }
      connectNode(target)
    }

  /** Refreshes only the advertised Node authority; Operator-owned UI work keeps its lease. */
  private fun connectNode(target: LocalGatewayTarget) {
    val options = connectionManager.buildNodeConnectOptions()
    nodeConnection.connect(
      target.endpoint,
      target.auth.token,
      target.auth.bootstrapToken,
      target.auth.password,
      options,
      target.tls,
    )
    androidUseCapabilityPublication.record(options.commands.isNotEmpty())
  }

  // Auth reset waits for claimed connection starts before disconnecting. Session calls stay outside
  // this monitor because GatewaySession invokes callbacks while holding its own lifecycle monitor.
  private fun runGatewayConnectOperation(block: () -> Unit): Boolean {
    val claimed =
      synchronized(gatewayAuthLifecycleLock) {
        if (gatewayAuthResetInProgress) {
          false
        } else {
          if (gatewayConnectOperationsInFlight == 0) {
            gatewayConnectOperationsDrained = CompletableDeferred()
          }
          gatewayConnectOperationsInFlight += 1
          true
        }
      }
    if (!claimed) return false
    try {
      block()
      return true
    } finally {
      val drained =
        synchronized(gatewayAuthLifecycleLock) {
          gatewayConnectOperationsInFlight -= 1
          gatewayConnectOperationsDrained.takeIf { gatewayConnectOperationsInFlight == 0 }
        }
      drained?.complete(Unit)
    }
  }

  private fun beginConnect(target: LocalGatewayTarget) {
    synchronized(gatewayAuthLifecycleLock) {
      if (gatewayAuthResetInProgress) return
    }
    if (gatewayDefaultAgentStableId?.let { it != target.endpoint.stableId } == true) {
      updateGatewayDefaultAgentId(null)
    }
    invalidateNodeCapabilityApprovalState()
    val connectAttemptId = connectAttemptSeq.incrementAndGet()
    chat.onGatewayScopeChanging()
    restoreChatConversationSelection(target.endpoint.stableId)
    publishLocalGatewayTarget(target, connectAttemptId)
  }

  private fun isCurrentConnectAttempt(connectAttemptId: Long): Boolean = connectAttemptSeq.get() == connectAttemptId

  private fun refreshGatewayControlPage(
    target: LocalGatewayTarget? = activeLocalGatewayTarget,
    storedOperatorToken: String? = target?.let { loadStoredRoleDeviceAuthEntry(it.endpoint, "operator")?.token },
  ) {
    if (target == null) {
      terminalController.replacePage(null)
      return
    }
    val pageAuth = resolveGatewayControlPageAuth(target.auth, storedOperatorToken)
    terminalController.replacePage(
      GatewayControlPage(
        baseUrl = gatewayControlPageBaseUrl(target.endpoint),
        token = pageAuth.token,
        password = pageAuth.password,
        tlsFingerprintSha256 = gatewayControlPageTlsFingerprint(target.endpoint),
      ),
    )
  }

  private fun publishLocalGatewayTarget(
    target: LocalGatewayTarget,
    connectAttemptId: Long,
  ) {
    if (!isCurrentConnectAttempt(connectAttemptId)) return
    connectLocalGatewayTarget(target) {
      updateStatus {
        operatorConnectionProblem = null
        operatorStatusText = "Connecting…"
        nodeConnection.beginConnecting()
      }
    }
  }

  private fun launchLocalGatewayConnect(explicitAuth: GatewayConnectAuth?) {
    launchGatewayLifecycle {
      val target =
        runCatching { resolveLocalGatewayTarget(explicitAuth) }
          .getOrElse {
            setStandaloneGatewayStatus("Failed: paired local Gateway identity is invalid. Run setup again.")
            return@launchGatewayLifecycle
          }
      if (target == null) {
        setStandaloneGatewayStatus("Failed: no paired local Gateway")
        return@launchGatewayLifecycle
      }
      beginConnect(target)
    }
  }

  private fun loadStoredRoleDeviceAuthEntry(
    endpoint: GatewayEndpoint,
    role: String,
  ): DeviceAuthEntry? {
    val deviceId = identityStore.loadOrCreate().deviceId
    return deviceAuthStore.loadEntry(endpoint.stableId, deviceId, role)
  }

  private fun maybeStartOperatorSessionAfterNodeConnect(target: LocalGatewayTarget) {
    if (activeLocalGatewayTarget !== target) return
    runGatewayConnectOperation {
      if (operatorConnected) return@runGatewayConnectOperation
      val storedOperatorEntry = loadStoredRoleDeviceAuthEntry(target.endpoint, "operator")
      val usesStoredOperatorDeviceToken =
        operatorSessionUsesStoredDeviceToken(target.auth, storedOperatorEntry?.token)
      val operatorAuth =
        resolveOperatorSessionConnectAuth(
          auth = target.auth,
          storedOperatorToken = storedOperatorEntry?.token,
        ) ?: return@runGatewayConnectOperation
      updateStatus {
        operatorStatusText = "Connecting…"
        operatorConnectionProblem = null
      }
      operatorSession.connect(
        target.endpoint,
        operatorAuth.token,
        operatorAuth.bootstrapToken,
        operatorAuth.password,
        connectionManager.buildOperatorConnectOptions(
          scopes =
            operatorConnectScopesForAuth(
              usesStoredDeviceToken = usesStoredOperatorDeviceToken,
              storedOperatorScopes = storedOperatorEntry?.scopes,
            ),
        ),
        target.tls,
      )
    }
  }

  fun disconnect() = disconnectGatewayLifecycle(retireRunState = false)

  private fun disconnectGatewayLifecycle(retireRunState: Boolean) {
    synchronized(gatewayLifecycleIntentLock) {
      localGatewayReconnectSuppressed = true
      gatewayLifecycleIntentSeq.incrementAndGet()
      disconnect(retireRunState)
    }
  }

  private fun disconnect(retireRunState: Boolean) {
    prepareDisconnect(retireRunState)
    operatorSession.disconnect()
    nodeConnection.session.disconnect()
  }

  private suspend fun disconnectAndJoin() {
    prepareDisconnect(retireRunState = true)
    // Both sockets close before either reconnect loop is joined, so no authenticated role stays
    // live while reset waits for the other role's terminal callback.
    coroutineScope {
      launch { operatorSession.disconnectAndJoin() }
      launch { nodeConnection.session.disconnectAndJoin() }
    }
  }

  private suspend fun drainIdleGatewaySessionTails() {
    if (activeLocalGatewayTarget != null) return
    coroutineScope {
      launch { operatorSession.disconnectAndJoin() }
      launch { nodeConnection.session.disconnectAndJoin() }
    }
  }

  private fun prepareDisconnect(retireRunState: Boolean) {
    connectAttemptSeq.incrementAndGet()
    synchronized(gatewayDataScopeLock) {
      gatewayDataGeneration += 1
      clearOperatorGatewayState(retirePendingWrites = true)
    }
    if (retireRunState) updateGatewayDefaultAgentId(null)
    chat.onGatewayScopeChanging(retireRunState)
    if (retireRunState) {
      val defaultMainSessionKey = resolveNodeMainSessionKey()
      _mainSessionKey.value = defaultMainSessionKey
    }
    activeLocalGatewayTarget = null
    terminalController.replacePage(null)
    updateStatus {
      operatorConnected = false
      operatorStatusText = "Offline"
      operatorConnectionProblem = null
      nodeConnection.prepareDisconnect()
    }
  }

  internal fun canSendForOwner(owner: ChatComposerOwner): Boolean = chat.isCurrentComposerOwner(owner)

  private suspend fun awaitConnectedGateway(stableId: String): Boolean {
    _isConnected.first { connected ->
      connected && activeLocalGatewayTarget?.endpoint?.stableId == stableId
    }
    return true
  }

  internal suspend fun sendChatForOwnerAwaitAcceptance(
    owner: ChatComposerOwner,
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
    idempotencyKey: String,
  ): Boolean =
    chat.sendMessageForOwnerAwaitAcceptance(
      message = message,
      thinkingLevel = thinking,
      attachments = attachments,
      expectedOwner = owner,
      idempotencyKey = idempotencyKey,
    )

  internal suspend fun openConversationNotificationTarget(
    target: ConversationNotificationTarget,
  ): Boolean =
    routeConversationNotificationTarget(
      target = target,
      activeGatewayStableId = { prefs.localGatewayPairing.stableId.value },
      switchSession = chatNavigation.select,
    )

  internal suspend fun sendConversationNotificationReply(
    target: ConversationNotificationTarget,
    reply: String,
    idempotencyKey: String,
  ): Boolean =
    routeConversationNotificationReply(
      target = target,
      reply = reply,
      idempotencyKey = idempotencyKey,
      activeGatewayStableId = { prefs.localGatewayPairing.stableId.value },
      awaitGatewayReady = ::awaitConnectedGateway,
      switchSession = chatNavigation.select,
      send = { owner, message, commandId ->
        sendChatForOwnerAwaitAcceptance(
          owner = owner,
          message = message,
          thinking = chat.thinkingLevel.value,
          attachments = emptyList(),
          idempotencyKey = commandId,
        )
      },
    )

  internal suspend fun wasChatOutboxCommandAdmitted(id: String): Boolean = chat.wasOutboxCommandAdmitted(id)

  private fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    if (event == "update.available") {
      _gatewayUpdateAvailable.value = parseGatewayUpdateAvailable(payloadJson)
    }
    if (event == GatewayEvent.UsersPrefsChanged.rawValue) {
      // The gateway targets this event at connections bound to the caller's own
      // profile; receipt means our profile appearance changed on another device.
      scope.launch { refreshBrandingFromGateway() }
    }
    if (event == GatewayEvent.SessionsChanged.rawValue) {
      projectController.refreshCatalog()
    }
    approvalInbox.onEvent(event, payloadJson)
    sessionPermissions.onEvent(event, payloadJson)
    parseAndroidAppInstallGatewayEvent(event, payloadJson, json)?.let { install ->
      captureGatewayDataScope()?.stableId?.let { gatewayId ->
        androidAppResultsController.record(gatewayId, install, androidAppResultStorage::displayName)
      }
    }
    parseWebProjectGatewayEvent(event, payloadJson, json)?.let { webEvent ->
      captureGatewayDataScope()?.stableId?.let { gatewayId ->
        webProjectResultsController.record(gatewayId, webEvent)
      }
    }
    androidVScreenController.onGatewayEvent(event, payloadJson)
    chat.handleGatewayEvent(event, payloadJson)
  }

  private fun parseGatewayUpdateAvailable(payloadJson: String?): GatewayUpdateAvailableSummary? {
    return try {
      val root = payloadJson?.let { json.parseToJsonElement(it).asObjectOrNull() }
      val update = root?.get("updateAvailable").asObjectOrNull() ?: return null
      GatewayUpdateAvailableSummary(
        currentVersion = update["currentVersion"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        latestVersion = update["latestVersion"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        channel = update["channel"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      )
    } catch (_: Throwable) {
      null
    }
  }

  private fun captureGatewayDataScope(): GatewayDataScope? =
    synchronized(gatewayDataScopeLock) {
      activeLocalGatewayTarget?.endpoint?.stableId?.let { GatewayDataScope(it, gatewayDataGeneration) }
    }

  private suspend fun requestGatewayData(
    gatewayScope: GatewayDataScope,
    method: String,
    paramsJson: String?,
    timeoutMs: Long = 15_000,
  ): String {
    gatewayDataRequestTimeoutObserverForTests?.invoke(method, timeoutMs)
    val response =
      gatewayDataRequestOverrideForTests?.invoke(gatewayScope.stableId, method, paramsJson)
        ?: operatorSession.requestForEndpoint(gatewayScope.stableId, method, paramsJson, timeoutMs)
    if (!isGatewayDataScopeCurrent(gatewayScope)) throw CancellationException("gateway scope changed")
    return response
  }

  private fun isGatewayDataScopeCurrent(gatewayScope: GatewayDataScope): Boolean =
    synchronized(gatewayDataScopeLock) {
      gatewayScope.generation == gatewayDataGeneration && activeLocalGatewayTarget?.endpoint?.stableId == gatewayScope.stableId
    }

  private inline fun publishGatewayData(
    gatewayScope: GatewayDataScope,
    publish: () -> Unit,
  ): Boolean =
    synchronized(gatewayDataScopeLock) {
      if (gatewayScope.generation != gatewayDataGeneration || activeLocalGatewayTarget?.endpoint?.stableId != gatewayScope.stableId) {
        false
      } else {
        publish()
        true
      }
    }

  private suspend fun <T> refreshGatewaySummary(
    summary: MutableStateFlow<T>,
    refreshing: MutableStateFlow<Boolean>,
    errorText: MutableStateFlow<NativeText?>,
    disconnectedSummary: T,
    failureText: NativeText,
    fetch: suspend (GatewayDataScope) -> T,
  ): Boolean {
    val gatewayScope = captureGatewayDataScope() ?: return false
    publishGatewayData(gatewayScope) {
      refreshing.value = true
      errorText.value = null
    }
    if (!operatorConnected) {
      summary.value = disconnectedSummary
      refreshing.value = false
      return false
    }
    return try {
      val nextSummary = fetch(gatewayScope)
      publishGatewayData(gatewayScope) { summary.value = nextSummary }
      true
    } catch (_: Throwable) {
      publishGatewayData(gatewayScope) { errorText.value = failureText }
      false
    } finally {
      publishGatewayData(gatewayScope) { refreshing.value = false }
    }
  }

  private suspend fun refreshBrandingFromGateway() {
    val gatewayScope = captureGatewayDataScope() ?: return
    if (!gatewayConnectionDisplay.value.isConnected) return
    try {
      val res = requestGatewayData(gatewayScope, "config.get", "{}")
      val root = json.parseToJsonElement(res).asObjectOrNull()
      val config = root?.get("config").asObjectOrNull()
      val parsed = fetchProfileAccentArgb(gatewayScope) ?: resolveGatewayAccentArgb(config)
      publishGatewayData(gatewayScope) {
        _gatewayAccentArgb.value = parsed
      }
    } catch (_: Throwable) {
      // ignore
    }
  }

  /**
   * Caller's per-profile accent (users.prefs.get). Null covers profile-less
   * connections (no_durable_identity), older gateways without the method, and
   * malformed stored values, so the gateway accent stays the fallback. Inner
   * try: a failed profile fetch must not discard the config accent.
   */
  private suspend fun fetchProfileAccentArgb(gatewayScope: GatewayDataScope): Long? =
    try {
      val res =
        requestGatewayData(gatewayScope, GatewayMethod.UsersPrefsGet.rawValue, """{"keys":["ui.accent"]}""")
      val root = json.parseToJsonElement(res).asObjectOrNull()
      if ((root?.get("status") as? JsonPrimitive)?.contentOrNull == "ok") {
        resolveProfileAccentArgb(root.get("entries").asObjectOrNull())
      } else {
        null
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      null
    }

  private suspend fun refreshNodeCapabilityApprovalFromGateway() {
    val gatewayScope = captureGatewayDataScope() ?: return
    val refreshGeneration = nodeApprovalRefreshGuard.begin()
    var refreshStarted = false
    val currentScope =
      publishGatewayData(gatewayScope) {
        refreshStarted =
          nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
            val pendingFallback = _nodeCapabilityApproval.value.withoutExactRequestId()
            if (pendingFallback != null) {
              _nodeCapabilityApproval.value = pendingFallback
            } else if (
              _nodeCapabilityApproval.value !is GatewayNodeCapabilityApproval.PendingApproval &&
              _nodeCapabilityApproval.value !is GatewayNodeCapabilityApproval.PendingReapproval
            ) {
              _nodeCapabilityApproval.value = GatewayNodeCapabilityApproval.Loading
            }
          }
      }
    if (!currentScope || !refreshStarted) return
    if (!operatorConnected) {
      publishGatewayData(gatewayScope) {
        nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
          _nodeCapabilityApproval.value = GatewayNodeCapabilityApproval.Loading
        }
      }
      return
    }
    try {
      val nodesRes = requestGatewayData(gatewayScope, "node.list", "{}")
      val nodesRoot = json.parseToJsonElement(nodesRes).asObjectOrNull()
      val nodes = parseGatewayNodeApprovalRecords(nodesRoot)
      val selfNodeId = identityStore.loadOrCreate().deviceId
      val approval =
        currentNodeCapabilityApproval(
          nodes = nodes,
          selfNodeId = selfNodeId,
        )
      var approvalPublished = false
      val scopePublished =
        publishGatewayData(gatewayScope) {
          approvalPublished =
            nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
              _nodeCapabilityApproval.value = approval
            }
        }
      if (!scopePublished || !approvalPublished) {
        return
      }
      scheduleNodeApprovalCommandRefresh(gatewayScope, refreshGeneration, approval)
    } catch (_: Throwable) {
      publishGatewayData(gatewayScope) {
        nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
          _nodeCapabilityApproval.value = GatewayNodeCapabilityApproval.Loading
        }
      }
    }
  }

  private fun scheduleNodeApprovalCommandRefresh(
    gatewayScope: GatewayDataScope,
    refreshGeneration: Long,
    approval: GatewayNodeCapabilityApproval,
  ) {
    val fallback = approval.withoutExactRequestId() ?: return
    scope.launch {
      delay(NODE_APPROVAL_COMMAND_FRESH_MS)
      // Pairing request IDs expire on the Gateway. Age out cached commands before rechecking so
      // recovery never leaves an old exact ID visible when a refresh fails or races disconnect.
      var approvalPublished = false
      val scopePublished =
        publishGatewayData(gatewayScope) {
          approvalPublished =
            nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
              _nodeCapabilityApproval.value = fallback
            }
        }
      if (scopePublished && approvalPublished && operatorConnected) {
        refreshNodeCapabilityApprovalFromGateway()
      }
    }
  }

  private fun replaceGatewayMethods(
    methods: Set<String>?,
    present: Boolean = true,
  ) {
    synchronized(gatewayMethodsLock) {
      // A hello may omit methods, so null alone does not mean disconnected. Retire
      // each live catalog once; repeated failed reconnects must not dismiss offline UI.
      if (!present && !gatewayMethodCatalogPresent) return
      gatewayMethodCatalogPresent = present
      val advertisedMethods = methods.orEmpty()
      gatewayAdvertisedMethods = methods
      gatewayMethodsEpoch.update { it + 1 }
    }
    pluginController.onConnectionChanged()
    skillController.onConnectionChanged()
    mcpConfigController.onConnectionChanged()
    modelCatalogRepository.onConnectionChanged()
    aiSetupController.onConnectionChanged()
  }

  private fun gatewayAdvertisesMethod(method: String): Boolean? = synchronized(gatewayMethodsLock) { gatewayAdvertisedMethods?.let { method in it } }

  private fun captureGatewayRuntimeIdentity(): GatewayRuntimeIdentity? {
    val connection = aiGatewayTransport.capture() ?: return null
    val supervisor = (appContext as? NodeApp)?.supervisorControl?.state?.value ?: return null
    val status =
      when (supervisor) {
        is SupervisorControlState.Status -> supervisor.value
        is SupervisorControlState.Waiting -> supervisor.latestStatus
        SupervisorControlState.Stopped,
        SupervisorControlState.Missing,
        is SupervisorControlState.Failed,
        -> null
      } ?: return null
    val gatewayGeneration = status.gatewayGeneration ?: return null
    return GatewayRuntimeIdentity(
      supervisorBootId = status.supervisorBootId,
      supervisorGatewayGeneration = gatewayGeneration,
      connection = connection,
    )
  }

  private fun replaceGatewayCapabilities(capabilities: Set<String>?) {
    synchronized(gatewayMethodsLock) {
      gatewayAdvertisedCapabilities = capabilities
    }
  }

  private fun gatewayAdvertisesCapability(capability: String): Boolean? = synchronized(gatewayMethodsLock) { gatewayAdvertisedCapabilities?.let { capability in it } }

  private fun invalidateNodeCapabilityApprovalState() {
    val refreshGeneration = nodeApprovalRefreshGuard.begin()
    nodeApprovalRefreshGuard.publishIfCurrent(refreshGeneration) {
      _nodeCapabilityApproval.value = GatewayNodeCapabilityApproval.Loading
    }
  }

  private fun skillMissingCount(missing: JsonObject?): Int = listOf("bins", "env", "config", "os").sumOf { key -> (missing?.get(key) as? JsonArray)?.size ?: 0 }

  private fun resolveActiveAgentId(): String {
    val mainKey = _mainSessionKey.value.trim()
    if (mainKey.startsWith("agent:")) {
      val agentId = mainKey.removePrefix("agent:").substringBefore(':').trim()
      if (agentId.isNotEmpty()) return agentId
    }
    return gatewayDefaultAgentId.value?.trim().orEmpty()
  }

  private fun normalized(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    return trimmed.ifEmpty { null }
  }
}

internal fun resolveOperatorSessionConnectAuth(
  auth: NodeRuntime.GatewayConnectAuth,
  storedOperatorToken: String?,
): NodeRuntime.GatewayConnectAuth? {
  val explicitToken = auth.token?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = explicitToken,
      bootstrapToken = null,
      password = null,
    )
  }

  val explicitPassword = auth.password?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitPassword != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = null,
      password = explicitPassword,
    )
  }

  val storedToken = storedOperatorToken?.trim()?.takeIf { it.isNotEmpty() }
  if (storedToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = null,
      password = null,
    )
  }

  val explicitBootstrapToken = auth.bootstrapToken?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitBootstrapToken != null) {
    return null
  }

  return NodeRuntime.GatewayConnectAuth(
    token = null,
    bootstrapToken = null,
    password = null,
  )
}

@Suppress("UNUSED_PARAMETER")
internal fun resolveGatewayControlPageAuth(
  auth: NodeRuntime.GatewayConnectAuth,
  storedOperatorToken: String?,
): NodeRuntime.GatewayConnectAuth {
  val explicitToken = auth.token?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitToken != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = explicitToken,
      bootstrapToken = null,
      password = null,
    )
  }

  val explicitPassword = auth.password?.trim()?.takeIf { it.isNotEmpty() }
  if (explicitPassword != null) {
    return NodeRuntime.GatewayConnectAuth(
      token = null,
      bootstrapToken = null,
      password = explicitPassword,
    )
  }

  return NodeRuntime.GatewayConnectAuth(
    token = null,
    bootstrapToken = null,
    password = null,
  )
}

internal fun operatorSessionUsesStoredDeviceToken(
  auth: NodeRuntime.GatewayConnectAuth,
  storedOperatorToken: String?,
): Boolean {
  val storedToken = storedOperatorToken?.trim()?.takeIf { it.isNotEmpty() }
  if (storedToken == null) return false
  val explicitToken = auth.token?.trim()?.takeIf { it.isNotEmpty() }
  val explicitPassword = auth.password?.trim()?.takeIf { it.isNotEmpty() }
  return explicitToken == null && explicitPassword == null
}

internal fun operatorConnectScopesForAuth(
  usesStoredDeviceToken: Boolean,
  storedOperatorScopes: List<String>?,
): List<String> {
  if (usesStoredDeviceToken && storedOperatorScopes != null) {
    return ConnectionManager.operatorScopesForStoredDeviceToken(storedOperatorScopes)
  }
  return ConnectionManager.nativeClientOperatorScopes
}

internal fun normalizeOperatorScopes(scopes: List<String>): List<String> =
  scopes
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .sorted()

/** HTTP(S) base URL serving the connected gateway's Control UI pages. */
internal fun gatewayControlPageBaseUrl(endpoint: GatewayEndpoint): String {
  val scheme = if (endpoint.tlsEnabled) "https" else "http"
  return "$scheme://${formatGatewayAuthority(endpoint.host, endpoint.port)}${endpoint.contextPath}"
}

data class GatewaySkillsSummary(
  val managedSkillsDirAvailable: Boolean = false,
  val skills: List<GatewaySkillSummary>,
)

data class GatewaySkillSummary(
  val skillKey: String,
  val name: String,
  val description: String?,
  val source: String,
  val emoji: String?,
  val disabled: Boolean,
  val eligible: Boolean,
  val blockedByAllowlist: Boolean,
  val blockedByAgentFilter: Boolean,
  val bundled: Boolean,
  val missingCount: Int,
  val installCount: Int,
  val clawHubSlug: String? = null,
  val clawHubValid: Boolean = false,
  /** Exact reference this skill was installed from; an install-only source keeps its identity. */
  val clawHubRequestedReference: String? = null,
  val clawHubOwnerHandle: String? = null,
  val clawHubInstalledVersion: String? = null,
)

enum class GatewayNodeApprovalState {
  Loading,
  Unsupported,
  Approved,
  PendingApproval,
  PendingReapproval,
  Unapproved,
}

/** Current phone approval state; only pending variants can carry an approval target. */
sealed interface GatewayNodeCapabilityApproval {
  data object Loading : GatewayNodeCapabilityApproval

  data object Unsupported : GatewayNodeCapabilityApproval

  data object Approved : GatewayNodeCapabilityApproval

  data class PendingApproval(
    val requestId: String?,
  ) : GatewayNodeCapabilityApproval

  data class PendingReapproval(
    val requestId: String?,
  ) : GatewayNodeCapabilityApproval

  data object Unapproved : GatewayNodeCapabilityApproval
}

internal fun GatewayNodeCapabilityApproval.withoutExactRequestId(): GatewayNodeCapabilityApproval? =
  when (this) {
    is GatewayNodeCapabilityApproval.PendingApproval ->
      requestId?.let { GatewayNodeCapabilityApproval.PendingApproval(requestId = null) }
    is GatewayNodeCapabilityApproval.PendingReapproval ->
      requestId?.let { GatewayNodeCapabilityApproval.PendingReapproval(requestId = null) }
    else -> null
  }

/** Prevents an older gateway response from publishing after a newer refresh begins. */
internal class LatestGatewayRefreshGuard {
  private val lock = Any()
  private var generation = 0L

  fun begin(): Long =
    synchronized(lock) {
      generation += 1
      generation
    }

  fun invalidate() {
    begin()
  }

  fun publishIfCurrent(
    refreshGeneration: Long,
    publish: () -> Unit,
  ): Boolean =
    synchronized(lock) {
      if (refreshGeneration != generation) return@synchronized false
      publish()
      true
    }
}

internal fun parseGatewayNodeApprovalState(raw: String?): GatewayNodeApprovalState =
  when (raw?.trim()?.lowercase()) {
    null, "" -> GatewayNodeApprovalState.Loading
    "approved" -> GatewayNodeApprovalState.Approved
    "pending-approval" -> GatewayNodeApprovalState.PendingApproval
    "pending-reapproval" -> GatewayNodeApprovalState.PendingReapproval
    "unapproved" -> GatewayNodeApprovalState.Unapproved
    else -> GatewayNodeApprovalState.Loading
  }

internal fun currentNodeCapabilityApproval(
  nodes: List<GatewayNodeApprovalRecord>,
  selfNodeId: String,
): GatewayNodeCapabilityApproval {
  val node = nodes.firstOrNull { it.id == selfNodeId } ?: return GatewayNodeCapabilityApproval.Loading
  return when (node.approvalState) {
    GatewayNodeApprovalState.Loading -> GatewayNodeCapabilityApproval.Loading
    GatewayNodeApprovalState.Unsupported -> GatewayNodeCapabilityApproval.Unsupported
    GatewayNodeApprovalState.Approved -> GatewayNodeCapabilityApproval.Approved
    GatewayNodeApprovalState.PendingApproval ->
      GatewayNodeCapabilityApproval.PendingApproval(
        normalizeGatewayApprovalRequestId(node.pendingRequestId),
      )
    GatewayNodeApprovalState.PendingReapproval ->
      GatewayNodeCapabilityApproval.PendingReapproval(
        normalizeGatewayApprovalRequestId(node.pendingRequestId),
      )
    GatewayNodeApprovalState.Unapproved -> GatewayNodeCapabilityApproval.Unapproved
  }
}

internal fun parseGatewayNodeApprovalRecord(item: JsonElement): GatewayNodeApprovalRecord? {
  val obj = item.asObjectOrNull() ?: return null
  val id = obj["nodeId"].asStringOrNull()?.trim().orEmpty()
  if (id.isEmpty()) return null
  return GatewayNodeApprovalRecord(
    id = id,
    // Only an omitted field identifies a legacy gateway; malformed and future values stay fail-closed.
    approvalState =
      if (obj.containsKey("approvalState")) {
        parseGatewayNodeApprovalState(obj["approvalState"].asStringOrNull())
      } else {
        GatewayNodeApprovalState.Unsupported
      },
    pendingRequestId = normalizeGatewayApprovalRequestId(obj["pendingRequestId"].asStringOrNull()),
  )
}

internal fun parseGatewayNodeApprovalRecords(root: JsonObject?): List<GatewayNodeApprovalRecord> {
  if (root == null) return emptyList()
  val seen = mutableSetOf<String>()
  val result = mutableListOf<GatewayNodeApprovalRecord>()

  fun append(nodes: JsonArray?) {
    for (node in nodes?.mapNotNull(::parseGatewayNodeApprovalRecord).orEmpty()) {
      if (seen.add(node.id)) {
        result.add(node)
      }
    }
  }

  append(root["nodes"] as? JsonArray)
  append(root["pending"] as? JsonArray)
  append(root["paired"] as? JsonArray)
  return result
}

/** Resolves one fresh pairing request without exposing or mutating any other Gateway node. */
internal fun currentPhoneNodePairingRequestId(
  root: JsonObject?,
  selfNodeId: String,
): String? {
  val expectedNodeId = selfNodeId.trim()
  if (root == null || expectedNodeId.isEmpty()) return null
  return (root["pending"] as? JsonArray)
    ?.asSequence()
    ?.mapNotNull { it.asObjectOrNull() }
    ?.firstOrNull { it["nodeId"].asStringOrNull()?.trim() == expectedNodeId }
    ?.get("requestId")
    .asStringOrNull()
    ?.let(::normalizeGatewayApprovalRequestId)
}

internal data class GatewayNodeApprovalRecord(
  val id: String,
  val approvalState: GatewayNodeApprovalState,
  val pendingRequestId: String?,
)

private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.content?.trim()?.toLongOrNull()

private fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()

private fun JsonObject?.boolean(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.content?.trim() == "true"

private fun gatewayControlPageTlsFingerprint(endpoint: GatewayEndpoint): String? = endpoint.tlsFingerprintSha256?.let(::normalizeGatewayTlsFingerprintInput)
