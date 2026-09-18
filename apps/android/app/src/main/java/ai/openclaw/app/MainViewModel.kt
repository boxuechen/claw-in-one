package ai.openclaw.app

import ai.openclaw.app.ai.AiSetupFeature
import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiduse.setAndroidUseComponentsEnabled
import ai.openclaw.app.approval.ApprovalFeature
import ai.openclaw.app.bootstrap.BootstrapHandoffService
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatDirectoryFeature
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatGatewayFeature
import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.chat.resolveChatComposerOwner
import ai.openclaw.app.devkit.AndroidDeviceCapabilityActions
import ai.openclaw.app.devkit.AndroidDeviceCapabilityFeature
import ai.openclaw.app.devkit.AndroidUseAuthorizationNotice
import ai.openclaw.app.devkit.AndroidUseAuthorizationStatus
import ai.openclaw.app.devkit.AndroidUseCapabilityActions
import ai.openclaw.app.devkit.AndroidUseCapabilityFeature
import ai.openclaw.app.devkit.AndroidUseCapabilityState
import ai.openclaw.app.devkit.DevKitFeature
import ai.openclaw.app.devkit.DevelopmentCapabilityActions
import ai.openclaw.app.devkit.androidUseSkillRefreshSignal
import ai.openclaw.app.devkit.developerCapabilityCatalog
import ai.openclaw.app.devkit.developerCapabilityCatalogSnapshot
import ai.openclaw.app.eligibility.DeviceEligibilityFeature
import ai.openclaw.app.entry.AppEntryFeature
import ai.openclaw.app.entry.AppEntryState
import ai.openclaw.app.entry.resolveAppEntryState
import ai.openclaw.app.gateway.GatewayUpdateAvailableSummary
import ai.openclaw.app.gateway.LocalGatewayPairing
import ai.openclaw.app.mcp.ConnectionFeature
import ai.openclaw.app.onboarding.FirstRunCoordinator
import ai.openclaw.app.onboarding.FirstRunGatewayConnectResult
import ai.openclaw.app.onboarding.FirstRunGatewayPreparation
import ai.openclaw.app.onboarding.OnboardingReceipt
import ai.openclaw.app.onboarding.prepareFirstRunGatewayConnection
import ai.openclaw.app.permissions.SessionPermissionsFeature
import ai.openclaw.app.plugin.PluginFeature
import ai.openclaw.app.runtime.RuntimeAcknowledgementRepository
import ai.openclaw.app.runtime.RuntimeCoordinator
import ai.openclaw.app.settings.AboutSettingsFeature
import ai.openclaw.app.settings.AppearanceSettingsActions
import ai.openclaw.app.settings.AppearanceSettingsFeature
import ai.openclaw.app.settings.SettingsFeatureSet
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.skill.SkillState
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.activeCapabilityConsumers
import ai.openclaw.app.terminal.TerminalFeature
import ai.openclaw.app.ui.chat.ChatComposerAttachmentFeature
import ai.openclaw.app.ui.chat.ChatComposerAutoSendFeature
import ai.openclaw.app.ui.chat.ChatComposerDeliveryFeature
import ai.openclaw.app.ui.chat.ChatComposerDraftFeature
import ai.openclaw.app.ui.chat.ChatComposerFeature
import ai.openclaw.app.ui.chat.ChatComposerOwnerFeature
import ai.openclaw.app.ui.chat.ChatComposerSendStartResult
import ai.openclaw.app.ui.chat.ChatComposerShareFeature
import ai.openclaw.app.ui.chat.ChatComposerStateStore
import ai.openclaw.app.ui.chat.PendingAttachment
import ai.openclaw.app.ui.chat.chatComposerTextDraftsFromSnapshot
import ai.openclaw.app.ui.chat.matchesSession
import ai.openclaw.app.ui.chat.shouldMigrateComposerDraft
import ai.openclaw.app.ui.chat.toOutgoingAttachment
import ai.openclaw.app.ui.settings.SettingsDestination
import ai.openclaw.app.vscreen.PreviewSurfaceProbeState
import ai.openclaw.app.vscreen.VScreenFeature
import ai.openclaw.app.vscreen.VScreenState
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ChatDraftPlacement {
  Replace,
  BeforeExisting,
}

internal data class ChatDraft(
  val text: String,
  val placement: ChatDraftPlacement,
  val owner: ChatComposerOwner? = null,
  // Attachment payloads stay in ViewModel heap state; saved drafts persist text only.
  val attachments: List<PendingAttachment>? = null,
)

internal fun claimChatDraftForOwner(
  draft: ChatDraft,
  owner: ChatComposerOwner,
  mainSessionKey: String,
): ChatDraft? {
  val capturedOwner = draft.owner ?: return draft
  if (capturedOwner == owner) return draft
  if (!shouldMigrateComposerDraft(capturedOwner, owner, mainSessionKey)) return null
  return draft.copy(owner = owner)
}

internal data class PendingAssistantAutoSend(
  val prompt: String,
  val owner: ChatComposerOwner,
  val id: String = UUID.randomUUID().toString(),
)

private data class AssistantAutoSendOperation(
  var owner: ChatComposerOwner,
  val pendingId: String,
  val composerSendId: String,
)

internal fun clearCompletedAssistantAutoSend(
  current: PendingAssistantAutoSend?,
  completedId: String,
): PendingAssistantAutoSend? = current?.takeUnless { it.id == completedId }

internal fun retainRefusedAssistantPrompt(
  prompt: String,
  existing: String,
): String =
  when {
    existing.isBlank() -> prompt
    prompt.isBlank() || existing == prompt -> existing
    else -> "$prompt\n\n$existing"
  }

data class ChatShareDraft(
  val id: Long,
  val text: String?,
  val attachments: List<SharedAttachment>,
  val droppedAttachmentCount: Int,
)

internal const val MAX_PENDING_CHAT_SHARES = 16
private const val CHAT_COMPOSER_DRAFTS_STATE_KEY = "chat-composer-text-drafts"

/** Bounded process-local queue whose stable head survives Activity recreation with the ViewModel. */
internal class ChatShareDraftQueue(
  private val capacity: Int = MAX_PENDING_CHAT_SHARES,
) {
  private val lock = Any()
  private val drafts = ArrayDeque<ChatShareDraft>()
  private val ownersById = mutableMapOf<Long, ChatComposerOwner>()
  private val headLease = Mutex()
  private val _head = MutableStateFlow<ChatShareDraft?>(null)
  val head: StateFlow<ChatShareDraft?> = _head.asStateFlow()
  private val _queued = MutableStateFlow<List<ChatShareDraft>>(emptyList())
  val queued: StateFlow<List<ChatShareDraft>> = _queued.asStateFlow()
  private val _ownerRevision = MutableStateFlow(0L)
  val ownerRevision: StateFlow<Long> = _ownerRevision.asStateFlow()

  init {
    require(capacity > 0)
  }

  fun enqueue(
    draft: ChatShareDraft,
    owner: ChatComposerOwner,
  ): Boolean =
    synchronized(lock) {
      if (drafts.size >= capacity) return@synchronized false
      drafts.addLast(draft)
      ownersById[draft.id] = owner
      publishQueueLocked()
      true
    }

  /** Only the active loader may advance the queue; stale effects cannot acknowledge a newer head. */
  fun acknowledgeHead(
    id: Long,
    owner: ChatComposerOwner,
  ): Boolean =
    synchronized(lock) {
      val ownedHead = firstForOwnerLocked(owner)
      if (ownedHead?.id != id) return@synchronized false
      drafts.remove(ownedHead)
      ownersById.remove(id)
      publishQueueLocked()
      true
    }

  /** Serializes loaders across overlapping Activity instances while rechecking the stable head. */
  suspend fun withHeadLease(
    id: Long,
    owner: ChatComposerOwner,
    block: suspend () -> Unit,
  ): Boolean =
    headLease.withLock {
      val claimed =
        synchronized(lock) {
          firstForOwnerLocked(owner)?.id == id
        }
      if (!claimed) return@withLock false
      block()
      true
    }

  fun migrateOwner(
    from: ChatComposerOwner,
    to: ChatComposerOwner,
  ) {
    if (from == to) return
    synchronized(lock) {
      var changed = false
      for ((id, owner) in ownersById.toMap()) {
        if (owner == from) {
          ownersById[id] = to
          changed = true
        }
      }
      if (changed) _ownerRevision.value += 1
    }
  }

  fun clear() {
    synchronized(lock) {
      drafts.clear()
      ownersById.clear()
      publishQueueLocked()
    }
  }

  suspend fun removeOwners(matches: (ChatComposerOwner) -> Boolean) {
    headLease.withLock {
      synchronized(lock) {
        val removedIds = ownersById.filterValues(matches).keys
        if (removedIds.isEmpty()) return@synchronized
        drafts.removeAll { it.id in removedIds }
        removedIds.forEach(ownersById::remove)
        publishQueueLocked()
      }
    }
  }

  fun ownerOf(id: Long): ChatComposerOwner? = synchronized(lock) { ownersById[id] }

  fun hasOwner(owner: ChatComposerOwner): Boolean = synchronized(lock) { owner in ownersById.values }

  internal fun size(): Int = synchronized(lock) { drafts.size }

  private fun firstForOwnerLocked(owner: ChatComposerOwner): ChatShareDraft? = drafts.firstOrNull { draft -> ownersById[draft.id] == owner }

  private fun publishQueueLocked() {
    _head.value = drafts.firstOrNull()
    _queued.value = drafts.toList()
  }
}

internal fun GatewayNodeCapabilityApproval.toAndroidUseAuthorizationStatus(): AndroidUseAuthorizationStatus =
  when (this) {
    GatewayNodeCapabilityApproval.Loading -> AndroidUseAuthorizationStatus.Checking
    GatewayNodeCapabilityApproval.Unsupported -> AndroidUseAuthorizationStatus.Unsupported
    GatewayNodeCapabilityApproval.Approved -> AndroidUseAuthorizationStatus.Approved
    is GatewayNodeCapabilityApproval.PendingApproval -> AndroidUseAuthorizationStatus.ApprovalRequired
    is GatewayNodeCapabilityApproval.PendingReapproval -> AndroidUseAuthorizationStatus.ReapprovalRequired
    GatewayNodeCapabilityApproval.Unapproved -> AndroidUseAuthorizationStatus.Unapproved
  }

internal fun shouldStartRuntimeOnForeground(
  foreground: Boolean,
  onboardingCompleted: Boolean,
): Boolean = foreground && onboardingCompleted

internal class NodeServiceResumeGate {
  private var pending = false

  @Synchronized
  fun completeOnboarding(foreground: Boolean): Boolean {
    pending = !foreground
    return foreground
  }

  @Synchronized
  fun enterForeground(onboardingCompleted: Boolean): Boolean {
    if (!pending || !onboardingCompleted) return false
    pending = false
    return true
  }

  @Synchronized
  fun clear() {
    pending = false
  }
}

/**
 * UI-facing bridge that exposes NodeRuntime and preference state as Compose-friendly StateFlows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel private constructor(
  app: Application,
  private val prefs: SecurePrefs,
  savedStateHandle: SavedStateHandle,
  private val resolveShareMimeType: (Uri) -> String?,
  shareLaunchCapacity: Int,
) : AndroidViewModel(app) {
  constructor(
    app: Application,
    savedStateHandle: SavedStateHandle,
  ) : this(
    app = app,
    prefs = (app as NodeApp).prefs,
    savedStateHandle = savedStateHandle,
    resolveShareMimeType = app.contentResolver::getType,
    shareLaunchCapacity = MAX_PENDING_CHAT_SHARES,
  )

  internal constructor(
    app: NodeApp,
    prefs: SecurePrefs,
    savedStateHandle: SavedStateHandle,
    resolveShareMimeType: (Uri) -> String? = app.contentResolver::getType,
    shareLaunchCapacity: Int = MAX_PENDING_CHAT_SHARES,
  ) : this(
    app = app as Application,
    prefs = prefs,
    savedStateHandle = savedStateHandle,
    resolveShareMimeType = resolveShareMimeType,
    shareLaunchCapacity = shareLaunchCapacity,
  )

  private val nodeApp = app as NodeApp
  private val runtimeRef = MutableStateFlow<NodeRuntime?>(null)
  private val gatewayConfigOperationSeq = AtomicLong()
  private val gatewayConfigOperationMutex = Mutex()

  // Multiple MainActivity instances can overlap across sender tasks; the process owns one queue.
  private val chatShareDraftSeq = nodeApp.chatShareDraftSeq
  private val chatShareDraftQueue = nodeApp.chatShareDraftQueue
  private val shareLaunchMutex = Mutex()
  private val shareLaunchSlots = Semaphore(shareLaunchCapacity)
  private val shareLaunchOverflowLock = Any()
  private var pendingShareLaunchOverflowCount = 0

  internal val chatLaunchState = (app as NodeApp).chatLaunchState

  @Volatile private var foreground = false

  @Volatile private var runtimeStartupQueued = false

  private val nodeServiceResumeGate = NodeServiceResumeGate()
  private val initialIntentGate = MainActivityInitialIntentGate()

  private val _requestedHomeDestination = MutableStateFlow<HomeDestination?>(null)
  val requestedHomeDestination: StateFlow<HomeDestination?> = _requestedHomeDestination
  private val screenshotShellStateMutable = MutableStateFlow<AndroidScreenshotShellState?>(null)
  internal val screenshotShellState: StateFlow<AndroidScreenshotShellState?> = screenshotShellStateMutable
  private val requestedSettingsRouteState = MutableStateFlow<SettingsDestination?>(null)
  internal val requestedSettingsRoute: StateFlow<SettingsDestination?> get() = requestedSettingsRouteState
  private val chatDraftState = MutableStateFlow<ChatDraft?>(null)
  private val chatDraftLock = Any()
  private var attachedComposerRuntime: NodeRuntime? = null
  private var removeChatSessionDeletionListener: (() -> Unit)? = null

  // SavedStateHandle preserves a bounded set of complete owner-scoped drafts through process
  // recreation. Durable admission clears only the accepted snapshot, so later edits survive.
  private val chatComposerState =
    ChatComposerStateStore(
      initialDrafts = chatComposerTextDraftsFromSnapshot(savedStateHandle[CHAT_COMPOSER_DRAFTS_STATE_KEY]),
      onDraftSnapshotChanged = { snapshot -> savedStateHandle[CHAT_COMPOSER_DRAFTS_STATE_KEY] = snapshot },
    )
  private val assistantAutoSendLock = Any()

  init {
    // A replacement Activity/ViewModel adopts an already-live process Runtime explicitly.
    // Route reads must not be responsible for starting or attaching it as a side effect.
    nodeApp.peekRuntime()?.let(::attachComposerRuntime)
    val recoveredChatComposerSends = chatComposerState.recoveredSends()
    if (recoveredChatComposerSends.isNotEmpty()) {
      // A pending checkpoint is hidden until the durable outbox gives a definitive answer.
      // Database errors leave it parked instead of exposing text that may already be sending.
      viewModelScope.launch {
        val runtime = runCatching { ensureRuntime() }.getOrNull() ?: return@launch
        recoveredChatComposerSends.forEach { pending ->
          val admitted =
            runCatching { runtime.wasChatOutboxCommandAdmitted(pending.commandId) }.getOrNull() ?: return@forEach
          chatComposerState.resolveRecoveredSend(
            commandId = pending.commandId,
            fallbackOwner = pending.owner,
            admitted = admitted,
          )
        }
      }
    }
  }

  private val shareLaunchOverflowRevisionMutable = MutableStateFlow(0L)
  internal val shareLaunchOverflowRevision: StateFlow<Long> = shareLaunchOverflowRevisionMutable.asStateFlow()
  private val pendingAssistantAutoSendMutable = MutableStateFlow<PendingAssistantAutoSend?>(null)
  private val assistantAutoSendInFlightMutable = MutableStateFlow(false)
  private var assistantAutoSendOperation: AssistantAutoSendOperation? = null
  internal val chatComposer =
    ChatComposerFeature(
      state = chatComposerState,
      drafts =
        ChatComposerDraftFeature(
          pending = chatDraftState,
          consumeAction = ::consumeChatDraft,
          setAction = ::setChatDraft,
          setReplyAction = ::setChatReplyDraft,
        ),
      shares =
        ChatComposerShareFeature(
          queued = chatShareDraftQueue.queued,
          ownerRevision = chatShareDraftQueue.ownerRevision,
          targetsOwnerAction = ::chatShareDraftTargetsOwner,
          forOwnerAction = ::chatShareDraftForOwner,
          resolveOwnerAction = ::resolveChatShareDraftOwner,
          acknowledgeAction = ::acknowledgeChatShareDraft,
          withLeaseAction = ::withChatShareDraftLease,
        ),
      autoSend =
        ChatComposerAutoSendFeature(
          pending = pendingAssistantAutoSendMutable,
          inFlight = assistantAutoSendInFlightMutable,
          dispatchAction = ::dispatchPendingAssistantAutoSend,
        ),
      owners =
        ChatComposerOwnerFeature(
          isCurrentAction = ::isCurrentChatComposerOwner,
          resolveAliasesAction = ::resolveChatComposerOwnerAliases,
        ),
      delivery =
        ChatComposerDeliveryFeature(
          beginAction = ::beginChatComposerSend,
          acknowledgeAdmissionAction = ::acknowledgeChatComposerSendAdmission,
        ),
      attachments = ChatComposerAttachmentFeature(importAttachmentsAction = ::importChatComposerAttachments),
    )

  /**
   * Lazily starts NodeRuntime and preserves the current foreground bit across startup.
   */
  private fun ensureRuntime(): NodeRuntime {
    runtimeRef.value?.let { return it }
    val runtime = nodeApp.ensureRuntime()
    runtime.setForeground(foreground)
    attachComposerRuntime(runtime)
    return runtime
  }

  private fun attachComposerRuntime(runtime: NodeRuntime) {
    if (attachedComposerRuntime === runtime) {
      runtimeRef.value = runtime
      return
    }
    removeChatSessionDeletionListener?.invoke()
    attachedComposerRuntime = runtime
    removeChatSessionDeletionListener =
      runtime.addChatSessionDeletionListener { deletion ->
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
          deletion.gatewayId?.let { gatewayId ->
            clearChatComposerSession(
              gatewayStableId = gatewayId,
              agentId = deletion.agentId,
              sessionKey = deletion.sessionKey,
              mainSessionKey = deletion.mainSessionKey,
            )
          }
        }
      }
    runtimeRef.value = runtime
  }

  override fun onCleared() {
    removeChatSessionDeletionListener?.invoke()
    removeChatSessionDeletionListener = null
    attachedComposerRuntime = null
  }

  internal fun claimInitialIntentRouting(): Boolean = initialIntentGate.claim()

  internal fun enterScreenshotFixture(launch: AndroidScreenshotLaunch) {
    screenshotShellStateMutable.value = launch.shellState
    runtimeRef.value?.let { runtime ->
      // The ViewModel survives locale recreation; keep the fixture runtime instead of
      // treating the restored Activity as a second fixture startup.
      check(runtime.mode == NodeRuntimeMode.ScreenshotFixture) {
        "Screenshot fixture mode must be selected before live runtime startup"
      }
      runtime.setForeground(foreground)
      _requestedHomeDestination.value = launch.homeDestination
      requestedSettingsRouteState.value = launch.settingsRoute
      return
    }
    prefs.completeOnboarding(OnboardingReceipt.screenshotFixture())
    prefs.setAppearanceThemeMode(launch.themeMode)
    val runtime = nodeApp.ensureScreenshotFixtureRuntime(launch.fixture)
    runtime.setForeground(foreground)
    attachComposerRuntime(runtime)
    _requestedHomeDestination.value = launch.homeDestination
    requestedSettingsRouteState.value = launch.settingsRoute
  }

  /** Acknowledges the one-shot settings-route request that accompanies a home destination. */
  fun clearRequestedSettingsRoute() {
    requestedSettingsRouteState.value = null
  }

  /**
   * Starts the node runtime off the main thread so fresh installs can render
   * the shell before encrypted prefs, device identity, and gateway setup warm up.
   */
  private fun queueRuntimeStartup() {
    if (runtimeRef.value != null || runtimeStartupQueued) return
    runtimeStartupQueued = true
    viewModelScope.launch(Dispatchers.Default) {
      runCatching { ensureRuntime() }
      runtimeStartupQueued = false
    }
  }

  internal fun resumeNodeServiceForConnection() {
    if (prefs.onboardingReceipt.value == null) return
    NodeForegroundService.resume(context = nodeApp, startNow = true)
  }

  /**
   * Adapts a runtime StateFlow to a stable ViewModel StateFlow before runtime startup.
   */
  private fun <T> runtimeState(
    initial: T,
    selector: (NodeRuntime) -> StateFlow<T>,
  ): StateFlow<T> =
    runtimeRef
      .flatMapLatest { runtime -> runtime?.let(selector) ?: flowOf(initial) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

  val runtimeInitialized: StateFlow<Boolean> =
    runtimeRef
      .flatMapLatest { runtime -> flowOf(runtime != null) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val isConnected: StateFlow<Boolean> = runtimeState(initial = false) { it.isConnected }
  internal val gatewayConnectionDisplay: StateFlow<GatewayConnectionDisplay> =
    runtimeState(initial = GatewayConnectionDisplay(false, GATEWAY_STATUS_OFFLINE, null)) { it.gatewayConnectionDisplay }
  internal val androidDeviceConnectionState: StateFlow<AndroidDeviceConnectionState> =
    runtimeState(initial = AndroidDeviceConnectionState()) { it.androidDeviceConnectionState }
  internal val previewSurfaceProbeState: StateFlow<PreviewSurfaceProbeState> =
    runtimeState(initial = PreviewSurfaceProbeState.Idle) { it.previewSurfaceProbeState }
  internal val androidUseAvailable: StateFlow<Boolean> =
    runtimeState(initial = false) { it.androidUseAvailable }
  private val androidUseNodeCapabilityApproval: StateFlow<GatewayNodeCapabilityApproval> =
    runtimeState(initial = GatewayNodeCapabilityApproval.Loading) { it.nodeCapabilityApproval }
  private val androidUseAuthorizationBusy = MutableStateFlow(false)
  private val androidUseAuthorizationNotice = MutableStateFlow<AndroidUseAuthorizationNotice?>(null)
  internal val androidUseCapabilityState: StateFlow<AndroidUseCapabilityState> =
    combine(
      prefs.preventSleep,
      combine(androidUseAvailable, prefs.androidUseConsent, runtimeState(initial = false) { it.androidUseServiceAvailable }) { available, enabled, service -> Triple(available, enabled, service) },
      androidUseNodeCapabilityApproval,
      androidUseAuthorizationBusy,
      androidUseAuthorizationNotice,
    ) { keepAwake, available, approval, busy, notice ->
      AndroidUseCapabilityState(
        preventSleep = keepAwake,
        available = available.first,
        enabled = available.second,
        serviceAvailable = available.third,
        authorization = approval.toAndroidUseAuthorizationStatus(),
        authorizing = busy,
        notice = notice,
      )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AndroidUseCapabilityState())
  internal val vscreenPresentation = (app as NodeApp).vscreenPresentation

  internal val vscreen: Flow<VScreenFeature?> = runtimeRef.map { it?.vscreen }
  private val vscreenState: StateFlow<VScreenState> =
    runtimeState(initial = VScreenState()) { it.vscreen.state }
  val gatewayVersion: StateFlow<String?> = runtimeState(initial = null) { it.gatewayVersion }
  val gatewayUpdateAvailable: StateFlow<GatewayUpdateAvailableSummary?> = runtimeState(initial = null) { it.gatewayUpdateAvailable }
  internal val aiSetupFeature: Flow<AiSetupFeature?> = runtimeRef.map { it?.aiSetupFeature }
  internal val pluginFeature: Flow<PluginFeature?> = runtimeRef.map { it?.pluginFeature }
  internal val pluginDirectoryFeature = nodeApp.pluginDirectoryFeature
  internal val skillFeature: Flow<SkillFeature?> = runtimeRef.map { it?.skillFeature }
  private val devKitSkillState: StateFlow<SkillState> =
    runtimeState(initial = SkillState()) { it.skillFeature.state }

  init {
    viewModelScope.launch {
      combine(androidUseCapabilityState, devKitSkillState, ::androidUseSkillRefreshSignal)
        .distinctUntilChanged()
        .collect { signal ->
          if (signal.shouldRefresh) {
            runtimeRef.value
              ?.skillFeature
              ?.actions
              ?.refresh
              ?.invoke()
          }
        }
    }
  }

  internal val skillDirectoryFeature = nodeApp.skillDirectoryFeature
  internal val connectionFeature: Flow<ConnectionFeature?> = runtimeRef.map { it?.connectionFeature }
  internal val terminalFeature: Flow<TerminalFeature?> = runtimeRef.map { it?.terminalFeature }
  private val aiSetupStateForFirstRun: StateFlow<AiSetupState> =
    runtimeState(initial = AiSetupState.Disconnected) { it.aiSetupFeature.state }
  val gatewayAccentArgb: StateFlow<Long?> = runtimeState(initial = null) { it.gatewayAccentArgb }
  val preventSleep: StateFlow<Boolean> = prefs.preventSleep
  val activeGatewayStableId: StateFlow<String?> = prefs.localGatewayPairing.stableId
  internal val onboardingReceipt: StateFlow<OnboardingReceipt?> = prefs.onboardingReceipt
  internal val deviceEligibilityFeature by lazy {
    DeviceEligibilityFeature(
      state = nodeApp.deviceEligibility.state,
      refresh = nodeApp.deviceEligibility::refresh,
    )
  }
  private val appEntryState: StateFlow<AppEntryState> by lazy {
    combine(onboardingReceipt, deviceEligibilityFeature.state) { receipt, eligibility ->
      resolveAppEntryState(
        onboardingCompleted = receipt != null,
        snapshot = eligibility,
      )
    }.stateIn(
      viewModelScope,
      SharingStarted.Eagerly,
      resolveAppEntryState(
        onboardingCompleted = onboardingReceipt.value != null,
        snapshot = deviceEligibilityFeature.state.value,
      ),
    )
  }
  internal val appEntryFeature by lazy {
    AppEntryFeature(
      state = appEntryState,
      refreshEligibility = deviceEligibilityFeature.refresh,
    )
  }
  private val aiGatewayRestart =
    runtimeState<ai.openclaw.app.ai.GatewayRestartState>(ai.openclaw.app.ai.GatewayRestartState.Idle) {
      it.aiGatewayRestart
    }
  internal val runtimeCoordinator: RuntimeCoordinator by lazy {
    RuntimeCoordinator(
      scope = viewModelScope,
      supervisor = nodeApp.supervisorControl,
      connection = gatewayConnectionDisplay,
      version = gatewayVersion,
      deviceEligibility = deviceEligibilityFeature,
      reconnect = ::refreshGatewayConnection,
      plannedRestart = aiGatewayRestart,
      acknowledgement = RuntimeAcknowledgementRepository(nodeApp),
    )
  }
  internal val runtimeFeature by lazy { runtimeCoordinator.feature }

  /** Publishes the same signed one-paste bundle as onboarding for explicit Supervisor repair. */
  internal suspend fun prepareSupervisorRepairCommand(): Boolean {
    nodeApp.bootstrapDelivery.resetAndPrepare()
    BootstrapHandoffService.start(nodeApp)
    return nodeApp.bootstrapDelivery.copyReadyCommand()
  }

  internal val firstRunCoordinator: FirstRunCoordinator by lazy {
    FirstRunCoordinator(
      context = nodeApp,
      scope = viewModelScope,
      deviceEligibility = nodeApp.deviceEligibility,
      bootstrapDelivery = nodeApp.bootstrapDelivery,
      bootstrapHandoff = nodeApp.bootstrapHandoff,
      supervisorRepository = nodeApp.supervisorControlRepository,
      supervisorControl = nodeApp.supervisorControl,
      gatewayConnected = isConnected,
      aiSetupState = aiSetupStateForFirstRun,
      onboardingReceipt = onboardingReceipt,
      connectGateway = ::connectFirstRunGateway,
      completeOnboarding = ::completeOnboarding,
    )
  }
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = prefs.appearanceThemeMode
  internal val devKitFeature: DevKitFeature by lazy {
    fun currentSupervisorStatus() =
      when (val current = nodeApp.supervisorControl.state.value) {
        is SupervisorControlState.Status -> current.value
        is SupervisorControlState.Waiting -> current.latestStatus
        else -> null
      }

    fun selectedDevelopmentCapabilities(): Set<DevelopmentCapability> =
      currentSupervisorStatus()
        ?.selectedCapabilities
        .orEmpty()
        .toSet()

    fun installDevelopmentCapability(capability: DevelopmentCapability) {
      nodeApp.supervisorControl.applyCapabilities(selectedDevelopmentCapabilities() + capability)
    }

    fun retryDevelopmentCapability(capability: DevelopmentCapability) {
      val status = currentSupervisorStatus() ?: return
      val planId = status.planId ?: return
      if (status.currentComponent?.let { capability in activeCapabilityConsumers(it, status.selectedCapabilities) } == true) {
        nodeApp.supervisorControl.retryCapabilities(planId)
      }
    }

    fun repairDevelopmentCapability(capability: DevelopmentCapability) {
      val status = currentSupervisorStatus()
      if (
        status?.currentComponent?.let { capability in activeCapabilityConsumers(it, status.selectedCapabilities) } == true &&
        status.planId != null
      ) {
        nodeApp.supervisorControl.retryCapabilities(status.planId)
      } else {
        installDevelopmentCapability(capability)
      }
    }
    val deviceFeature =
      AndroidDeviceCapabilityFeature(
        state = androidDeviceConnectionState,
        actions =
          AndroidDeviceCapabilityActions(
            refresh = ::refreshAndroidDeviceConnection,
            reconnect = ::reconnectAndroidDeviceConnection,
            forget = ::forgetAndroidDeviceConnection,
            dismissNotice = ::dismissAndroidDeviceConnectionNotice,
          ),
      )
    val androidUseFeature =
      AndroidUseCapabilityFeature(
        state = androidUseCapabilityState,
        actions =
          AndroidUseCapabilityActions(
            setPreventSleep = ::setPreventSleep,
            setEnabled = ::setAndroidUseEnabled,
            refreshAuthorization = ::refreshAndroidUseAuthorization,
            authorizeCurrentPhone = ::authorizeCurrentPhoneForAndroidUse,
            dismissNotice = { androidUseAuthorizationNotice.value = null },
          ),
      )
    DevKitFeature(
      catalog =
        developerCapabilityCatalog(
          environment = runtimeFeature.state,
          device = androidDeviceConnectionState,
          vscreen = vscreenState,
          androidUse = androidUseCapabilityState,
          skills = devKitSkillState,
        ),
      initialCatalog = {
        developerCapabilityCatalogSnapshot(
          environment = runtimeFeature.state,
          device = androidDeviceConnectionState,
          vscreen = vscreenState,
          androidUse = androidUseCapabilityState,
          skills = devKitSkillState,
        )
      },
      environment = runtimeFeature,
      deviceConnection = deviceFeature,
      androidUse = androidUseFeature,
      development =
        DevelopmentCapabilityActions(
          install = ::installDevelopmentCapability,
          retry = ::retryDevelopmentCapability,
          repair = ::repairDevelopmentCapability,
        ),
      refresh = {
        nodeApp.supervisorControl.observe()
        refreshAndroidDeviceConnection()
        runtimeRef.value
          ?.skillFeature
          ?.actions
          ?.refresh
          ?.invoke()
      },
      refreshSkills = {
        runtimeRef.value
          ?.skillFeature
          ?.actions
          ?.refresh
          ?.invoke()
      },
    )
  }
  internal val settingsFeatures: SettingsFeatureSet by lazy {
    SettingsFeatureSet(
      appearance =
        AppearanceSettingsFeature(
          themeMode = appearanceThemeMode,
          actions = AppearanceSettingsActions(selectTheme = ::setAppearanceThemeMode),
        ),
      about =
        AboutSettingsFeature(
          updateAvailable = gatewayUpdateAvailable,
          gatewayVersion = gatewayVersion,
        ),
    )
  }

  internal val androidAppResults: Flow<ai.openclaw.app.appdelivery.AndroidAppResultsFeature?> = runtimeRef.map { it?.androidAppResults }
  internal val webProjectResults: Flow<ai.openclaw.app.webdelivery.WebProjectResultsFeature?> = runtimeRef.map { it?.webProjectResults }
  internal val chatHistory: Flow<ChatHistoryFeature?> = runtimeRef.map { it?.chatHistory }
  internal val chatSessionOptions: Flow<ChatSessionOptionsFeature?> = runtimeRef.map { it?.chatSessionOptions }
  internal val chatCurrentWork: Flow<ChatCurrentWorkFeature?> = runtimeRef.map { it?.chatCurrentWork }
  internal val chatExecution: Flow<ChatExecutionFeature?> = runtimeRef.map { it?.chatExecution }
  internal val chatGateway: Flow<ChatGatewayFeature?> = runtimeRef.map { it?.chatGateway }
  internal val chatOutbox: Flow<ChatOutboxFeature?> = runtimeRef.map { it?.chatOutbox }
  internal val chatDirectory: Flow<ChatDirectoryFeature?> = runtimeRef.map { it?.chatDirectory }
  internal val projects: Flow<ai.openclaw.app.project.ProjectFeature?> = runtimeRef.map { it?.projects }
  internal val approvalFeature: Flow<ApprovalFeature?> = runtimeRef.map { it?.approvalFeature }
  internal val permissionFeature: Flow<SessionPermissionsFeature?> = runtimeRef.map { it?.permissionFeature }

  /**
   * Starts runtime on foreground entry only after onboarding has completed.
   */
  fun setForeground(value: Boolean) {
    // The ViewModel survives configuration recreation. Ignore the replacement
    // Activity's duplicate true edge so it cannot restart gateway work.
    if (foreground == value) return
    foreground = value
    if (value && nodeServiceResumeGate.enterForeground(prefs.onboardingReceipt.value != null)) {
      NodeForegroundService.resume(nodeApp, startNow = true)
    }
    if (
      shouldStartRuntimeOnForeground(
        foreground = value,
        onboardingCompleted = prefs.onboardingReceipt.value != null,
      )
    ) {
      queueRuntimeStartup()
    }
    runtimeRef.value?.setForeground(value)
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  /** Clears setup credentials without starting the runtime just to discard first-run pairing auth. */
  private suspend fun resetGatewaySetupAuth(stableId: String): Boolean {
    val reset = nodeApp.resetGatewaySetupAuth(stableId)
    nodeApp.peekRuntime()?.let(::attachComposerRuntime)
    if (reset) clearChatComposerGateway(stableId)
    return reset
  }

  /** Auth replacement retires the old gateway identity, including every retained composer owner. */
  internal suspend fun clearChatComposerGateway(stableId: String) {
    val gateway = stableId.trim()
    if (gateway.isEmpty()) return
    clearChatComposerOwners { it.gatewayStableId == gateway }
  }

  internal suspend fun clearChatComposerSession(
    gatewayStableId: String,
    agentId: String,
    sessionKey: String,
    mainSessionKey: String,
  ) {
    val gateway = gatewayStableId.trim()
    val agent = agentId.trim()
    val key = sessionKey.trim()
    if (gateway.isEmpty() || agent.isEmpty() || key.isEmpty()) return
    clearChatComposerOwners { owner ->
      owner.matchesSession(
        gatewayStableId = gateway,
        agentId = agent,
        sessionKey = key,
        mainSessionKey = mainSessionKey,
      )
    }
  }

  private suspend fun clearChatComposerOwners(matches: (ChatComposerOwner) -> Boolean) {
    chatComposerState.removeAttachmentOwners(matches)
    chatShareDraftQueue.removeOwners(matches)
    synchronized(assistantAutoSendLock) {
      // Read the live operation id while its start/finally paths are excluded so cleanup retains
      // exactly that gate after removing other state owned by the retired identity.
      chatComposerState.removeOwners(matches, assistantAutoSendOperation?.composerSendId)
    }
    pendingAssistantAutoSendMutable.update { pending ->
      pending?.takeIf { !matches(it.owner) }
    }
    synchronized(chatDraftLock) {
      chatDraftState.value = chatDraftState.value?.takeIf { draft -> draft.owner?.let(matches) != true }
    }
    // Repeat after suspending share cleanup. Any callback that raced the first tombstone is
    // serialized with this final token-and-attachment purge before cleanup returns.
    chatComposerState.removeAttachmentOwners(matches)
  }

  private fun installSupervisorGatewayPairing(pairing: LocalGatewayPairing) {
    resumeNodeServiceForConnection()
    val operation = gatewayConfigOperationSeq.incrementAndGet()
    // Pairing touches encrypted prefs, identity files, caches, and sockets; keep the complete
    // authenticated replacement off the Compose thread.
    viewModelScope.launch(Dispatchers.Default) {
      gatewayConfigOperationMutex.withLock {
        if (operation != gatewayConfigOperationSeq.get()) return@withLock
        val existingPairing = prefs.localGatewayPairing.pairing.value
        if (existingPairing != null && !resetGatewaySetupAuth(existingPairing.stableId)) return@launch
        if (operation != gatewayConfigOperationSeq.get()) return@launch
        if (!prefs.localGatewayPairing.replace(pairing)) return@launch
        val runtime = ensureRuntime()
        runtime.connectLocalGateway(
          NodeRuntime.GatewayConnectAuth(
            token = pairing.credentials.token,
            bootstrapToken = pairing.credentials.bootstrapToken,
            password = pairing.credentials.password,
          ),
        )
      }
    }
  }

  private suspend fun connectFirstRunGateway(setupCode: String): FirstRunGatewayConnectResult =
    when (val preparation = prepareFirstRunGatewayConnection(setupCode)) {
      FirstRunGatewayPreparation.InvalidSetup -> FirstRunGatewayConnectResult.InvalidSetup
      FirstRunGatewayPreparation.PortForwardingRequired -> FirstRunGatewayConnectResult.PortForwardingRequired
      is FirstRunGatewayPreparation.Ready -> {
        installSupervisorGatewayPairing(preparation.pairing)
        FirstRunGatewayConnectResult.Started
      }
    }

  /** Records the first-run milestone and starts the normal runtime ownership path. */
  internal fun completeOnboarding(receipt: OnboardingReceipt) {
    ensureRuntime()
    prefs.completeOnboarding(receipt)
    NodeForegroundService.resume(
      nodeApp,
      startNow = nodeServiceResumeGate.completeOnboarding(foreground),
    )
  }

  internal fun clearOnboarding() {
    prefs.clearOnboarding()
    nodeServiceResumeGate.clear()
  }

  private fun setAndroidUseEnabled(value: Boolean) {
    prefs.setAndroidUseConsent(value)
    if (value) {
      setAndroidUseComponentsEnabled(nodeApp, true)
    } else {
      nodeApp.peekRuntime()?.stopAndroidUseFromSafetySurface()
      nodeApp.androidUseLeaseController.revoke(ai.openclaw.app.androiduse.AndroidUseRevocation.ConsentDisabled)
    }
  }

  private fun refreshAndroidUseAuthorization() {
    ensureRuntime().refreshNodeCapabilityApproval()
  }

  private fun authorizeCurrentPhoneForAndroidUse() {
    if (androidUseAuthorizationBusy.value) return
    androidUseAuthorizationBusy.value = true
    androidUseAuthorizationNotice.value = null
    viewModelScope.launch {
      try {
        ensureRuntime().approveCurrentPhoneNodeCapabilities()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        androidUseAuthorizationNotice.value = AndroidUseAuthorizationNotice.ApprovalFailed
      } finally {
        androidUseAuthorizationBusy.value = false
      }
    }
  }

  /** Routes assistant intents into chat, either as a draft or queued auto-send prompt. */
  fun handleAssistantLaunch(request: AssistantLaunchRequest) {
    _requestedHomeDestination.value = HomeDestination.Chat
    chatShareDraftQueue.clear()
    val owner = currentOrProvisionalChatComposerOwner()
    if (request.autoSend) {
      pendingAssistantAutoSendMutable.value = request.prompt?.let { PendingAssistantAutoSend(prompt = it, owner = owner) }
      setChatDraft(null)
      return
    }
    pendingAssistantAutoSendMutable.value = null
    setChatDraft(request.prompt?.let { ChatDraft(text = it, placement = ChatDraftPlacement.Replace, owner = owner) })
  }

  /**
   * Owns share admission through queue insertion so Activity recreation cannot cancel accepted work.
   */
  internal fun handleShareLaunchIntent(intent: Intent): Boolean {
    if (!shareLaunchSlots.tryAcquire()) {
      reportShareLaunchOverflow()
      return false
    }
    val retainedIntent = Intent(intent)
    val owner = captureChatShareOwner()
    viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        shareLaunchMutex.withLock {
          val request =
            withContext(Dispatchers.IO) {
              parseShareLaunchIntent(retainedIntent, resolveShareMimeType)
            } ?: return@withLock
          if (!enqueueShareLaunch(request, owner)) reportShareLaunchOverflow()
        }
      } finally {
        shareLaunchSlots.release()
      }
    }
    return true
  }

  internal fun reportShareLaunchOverflow(count: Int = 1) {
    if (count <= 0) return
    synchronized(shareLaunchOverflowLock) {
      pendingShareLaunchOverflowCount += count
      shareLaunchOverflowRevisionMutable.value += 1
    }
  }

  internal fun takeShareLaunchOverflowCount(): Int =
    synchronized(shareLaunchOverflowLock) {
      pendingShareLaunchOverflowCount.also {
        pendingShareLaunchOverflowCount = 0
      }
    }

  /** Opens shared content as a fresh composer draft; sending still requires an explicit tap. */
  private fun enqueueShareLaunch(
    request: ShareLaunchRequest,
    owner: ChatComposerOwner,
  ): Boolean {
    val accepted =
      chatShareDraftQueue.enqueue(
        ChatShareDraft(
          id = chatShareDraftSeq.incrementAndGet(),
          text = request.text,
          attachments = request.attachments,
          droppedAttachmentCount = request.droppedAttachmentCount,
        ),
        owner,
      )
    if (!accepted) return false
    _requestedHomeDestination.value = HomeDestination.Chat
    pendingAssistantAutoSendMutable.value = null
    setChatDraft(null)
    return true
  }

  fun clearRequestedHomeDestination() {
    _requestedHomeDestination.value = null
  }

  fun requestHomeDestination(destination: HomeDestination) {
    _requestedHomeDestination.value = destination
  }

  internal fun openConversationNotification(target: ConversationNotificationTarget) {
    resumeNodeServiceForConnection()
    viewModelScope.launch(Dispatchers.Default) {
      if (ensureRuntime().openConversationNotificationTarget(target)) {
        _requestedHomeDestination.value = HomeDestination.Chat
      }
    }
  }

  private fun consumeChatDraft(
    expected: ChatDraft,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ): ChatDraft? =
    synchronized(chatDraftLock) {
      val current = chatDraftState.value
      if (current !== expected) return@synchronized null
      val claimed = claimChatDraftForOwner(current, owner, mainSessionKey) ?: return@synchronized null
      chatDraftState.value = null
      claimed
    }

  private fun setChatDraft(value: ChatDraft?) {
    synchronized(chatDraftLock) {
      chatDraftState.value = value
    }
  }

  private fun acknowledgeChatShareDraft(
    id: Long,
    owner: ChatComposerOwner,
  ): Boolean = chatShareDraftQueue.acknowledgeHead(id, owner)

  private suspend fun withChatShareDraftLease(
    id: Long,
    owner: ChatComposerOwner,
    block: suspend () -> Unit,
  ): Boolean = chatShareDraftQueue.withHeadLease(id, owner, block)

  private fun chatShareDraftTargetsOwner(
    id: Long,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ): Boolean {
    val captured = chatShareDraftQueue.ownerOf(id) ?: return false
    return captured == owner || shouldMigrateComposerDraft(captured, owner, mainSessionKey)
  }

  private fun chatShareDraftForOwner(
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ): ChatShareDraft? =
    chatShareDraftQueue.queued.value.firstOrNull { draft ->
      chatShareDraftTargetsOwner(draft.id, owner, mainSessionKey)
    }

  private fun resolveChatShareDraftOwner(
    id: Long?,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) {
    if (id == null) return
    val captured = chatShareDraftQueue.ownerOf(id) ?: return
    if (shouldMigrateComposerDraft(captured, owner, mainSessionKey)) {
      chatShareDraftQueue.migrateOwner(captured, owner)
    }
  }

  private fun setChatReplyDraft(
    value: String,
    owner: ChatComposerOwner,
  ) {
    if (!isCurrentChatComposerOwner(owner)) return
    pendingAssistantAutoSendMutable.value = null
    setChatDraft(ChatDraft(text = value, placement = ChatDraftPlacement.BeforeExisting, owner = owner))
  }

  /** Claims an assistant prompt before sending so Compose effect restarts cannot dispatch it twice. */
  private fun dispatchPendingAssistantAutoSend(
    pending: PendingAssistantAutoSend,
    thinking: String,
  ) {
    val prompt = pending.prompt.trim().ifEmpty { return }
    if (runtimeRef.value
        ?.chatHistory
        ?.healthy
        ?.value != true ||
      runtimeRef.value
        ?.chatCurrentWork
        ?.pendingRunCount
        ?.value
        ?.let { it > 0 } != false
    ) {
      return
    }
    if (!isCurrentChatComposerOwner(pending.owner)) return
    if (runtimeRef.value?.canSendForOwner(pending.owner) != true) return
    val operation =
      synchronized(assistantAutoSendLock) {
        if (!assistantAutoSendInFlightMutable.compareAndSet(false, true)) return
        if (pendingAssistantAutoSendMutable.value != pending) {
          assistantAutoSendInFlightMutable.value = false
          return
        }
        val composerSendId = chatComposerState.tryBeginTrackedSend(pending.owner)
        if (composerSendId == null) {
          assistantAutoSendInFlightMutable.value = false
          return
        }
        val started =
          AssistantAutoSendOperation(
            owner = pending.owner,
            pendingId = pending.id,
            composerSendId = composerSendId,
          )
        assistantAutoSendOperation = started
        started
      }
    viewModelScope.launch {
      try {
        val accepted =
          sendChatForOwnerAwaitAcceptance(
            owner = pending.owner,
            message = prompt,
            thinking = thinking,
            attachments = emptyList(),
            idempotencyKey = UUID.randomUUID().toString(),
          )
        if (accepted) {
          pendingAssistantAutoSendMutable.update { current ->
            clearCompletedAssistantAutoSend(current, operation.pendingId)
          }
        } else {
          val current = pendingAssistantAutoSendMutable.value
          if (current?.id == operation.pendingId && pendingAssistantAutoSendMutable.compareAndSet(current, null)) {
            // Refusal can mean owner validation changed before admission. Preserve the one-shot
            // prompt as editable text, using the operation owner that alias migration updates.
            val currentDraft = chatComposerState.textDrafts[operation.owner]
            chatComposerState.textDrafts[operation.owner] = retainRefusedAssistantPrompt(current.prompt, currentDraft)
          }
        }
      } finally {
        synchronized(assistantAutoSendLock) {
          if (assistantAutoSendOperation === operation) {
            chatComposerState.finishTrackedSend(operation.composerSendId)
            assistantAutoSendOperation = null
            // Observable releases wake a prompt blocked by this or a manual send admission.
            assistantAutoSendInFlightMutable.value = false
          }
        }
      }
    }
  }

  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    prefs.setAppearanceThemeMode(mode)
  }

  fun refreshGatewayConnection() {
    resumeNodeServiceForConnection()
    viewModelScope.launch(Dispatchers.Default) {
      ensureRuntime().refreshGatewayConnection()
    }
  }

  internal fun refreshAndroidDeviceConnection() {
    ensureRuntime().refreshAndroidDeviceConnection()
  }

  internal fun reconnectAndroidDeviceConnection(endpoint: String?) {
    ensureRuntime().reconnectAndroidDeviceConnection(endpoint)
  }

  internal fun verifyAndroidDeviceReconnect(endpoint: String?) {
    ensureRuntime().verifyAndroidDeviceReconnect(endpoint)
  }

  internal fun checkPreviewSurface(
    expectedTargetId: String,
    phoneVerificationId: String,
  ) {
    ensureRuntime().checkPreviewSurface(expectedTargetId, phoneVerificationId)
  }

  internal fun forgetAndroidDeviceConnection() {
    ensureRuntime().forgetAndroidDeviceConnection()
  }

  internal fun dismissAndroidDeviceConnectionNotice() {
    ensureRuntime().dismissAndroidDeviceConnectionNotice()
  }

  /** Reads the authoritative flows at commit time so stale Compose callbacks cannot cross chats. */
  private fun currentChatComposerOwner(): ChatComposerOwner? {
    val runtime = runtimeRef.value ?: return null
    return resolveChatComposerOwner(
      gatewayStableId = prefs.localGatewayPairing.stableId.value,
      gatewayDefaultAgentId = runtime.chatHistory.selection.ownerAgentId.value ?: runtime.gatewayDefaultAgentId.value,
      lastVerifiedOwner = runtime.chatHistory.selection.defaultOwner.value,
      sessionKey = runtime.chatHistory.selection.key.value,
      mainSessionKey = runtime.mainSessionKey.value,
    )
  }

  /** Captures a share before async runtime startup; later hello/alias resolution may migrate it. */
  private fun currentOrProvisionalChatComposerOwner(): ChatComposerOwner {
    val runtime = runtimeRef.value
    return currentChatComposerOwner()
      ?: resolveChatComposerOwner(
        gatewayStableId = prefs.localGatewayPairing.stableId.value,
        gatewayDefaultAgentId = runtime?.gatewayDefaultAgentId?.value,
        lastVerifiedOwner = null,
        sessionKey = "main",
        mainSessionKey = runtime?.mainSessionKey?.value ?: "main",
      )
  }

  internal fun captureChatShareOwner(): ChatComposerOwner = currentOrProvisionalChatComposerOwner()

  private fun isCurrentChatComposerOwner(expected: ChatComposerOwner): Boolean =
    (
      currentChatComposerOwner() ?: currentOrProvisionalChatComposerOwner()
    ) == expected

  private fun resolveChatComposerOwnerAliases(
    to: ChatComposerOwner,
    mainSessionKey: String,
  ) {
    val (composerSources, operationSource) =
      synchronized(assistantAutoSendLock) {
        // The gate and operation owner must move together so finally releases the migrated key.
        val sources = chatComposerState.resolveAliases(to = to, mainSessionKey = mainSessionKey)
        val operationSource =
          assistantAutoSendOperation?.let { operation ->
            operation.owner.takeIf { source -> shouldMigrateComposerDraft(source, to, mainSessionKey) }?.also {
              operation.owner = to
            }
          }
        sources to operationSource
      }
    val pendingAutoSend = pendingAssistantAutoSendMutable.value
    val pendingAutoSendSource =
      pendingAutoSend
        ?.owner
        ?.takeIf { source -> shouldMigrateComposerDraft(source, to, mainSessionKey) }
    if (pendingAutoSendSource != null) {
      pendingAssistantAutoSendMutable.compareAndSet(pendingAutoSend, pendingAutoSend.copy(owner = to))
    }
    val sources = composerSources + listOfNotNull(pendingAutoSendSource, operationSource)
    sources.forEach { source -> chatShareDraftQueue.migrateOwner(from = source, to = to) }
  }

  /** The ViewModel owns image decoding so Activity recreation cannot cancel an accepted picker result. */
  private fun importChatComposerAttachments(
    owner: ChatComposerOwner,
    attachmentAuthorizationId: String,
    mainSessionKey: String,
    expectedCount: Int,
    load: suspend () -> List<PendingAttachment>,
  ) {
    val importId =
      chatComposerState.beginAttachmentImport(owner, attachmentAuthorizationId, mainSessionKey) ?: return
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val loaded =
          try {
            load()
          } catch (err: CancellationException) {
            throw err
          } catch (_: Throwable) {
            emptyList()
          }
        chatComposerState.completeAttachmentImport(
          importId = importId,
          candidates = loaded,
          failedCount = expectedCount - loaded.size,
        )
      } catch (err: CancellationException) {
        chatComposerState.cancelAttachmentImport(importId)
        throw err
      }
    }
  }

  internal suspend fun sendChatForOwnerAwaitAcceptance(
    owner: ChatComposerOwner,
    message: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
    idempotencyKey: String,
  ): Boolean =
    ensureRuntime().sendChatForOwnerAwaitAcceptance(
      owner = owner,
      message = message,
      thinking = thinking,
      attachments = attachments,
      idempotencyKey = idempotencyKey,
    )

  /** Admission outlives the composing Activity; accepted payloads clear by owner and snapshot. */
  private fun beginChatComposerSend(
    owner: ChatComposerOwner,
    thinking: String,
  ): ChatComposerSendStartResult {
    if (!isCurrentChatComposerOwner(owner)) return ChatComposerSendStartResult.Unavailable
    val start = chatComposerState.beginSend(owner)
    val request = start.request ?: return start.result
    val outgoing = request.attachments.map(PendingAttachment::toOutgoingAttachment)
    viewModelScope.launch {
      var accepted: Boolean? = null
      try {
        accepted =
          sendChatForOwnerAwaitAcceptance(
            owner = request.owner,
            message = request.message,
            thinking = thinking,
            attachments = outgoing,
            idempotencyKey = request.commandId,
          )
      } catch (err: CancellationException) {
        throw err
      } catch (_: Throwable) {
        accepted = false
      } finally {
        chatComposerState.completeSend(request, accepted)
      }
    }
    return ChatComposerSendStartResult.Started
  }

  private fun acknowledgeChatComposerSendAdmission(
    owner: ChatComposerOwner,
    id: String,
  ) {
    chatComposerState.acknowledgeSendAdmission(owner, id)
  }
}
