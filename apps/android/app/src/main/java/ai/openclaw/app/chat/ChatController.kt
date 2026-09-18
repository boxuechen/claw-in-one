package ai.openclaw.app.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.gateway.GatewayLoadedImage
import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.QuestionAnswers
import ai.openclaw.app.gateway.QuestionGetResult
import ai.openclaw.app.gateway.QuestionListResult
import ai.openclaw.app.gateway.QuestionRecord
import ai.openclaw.app.gateway.SessionObserverDigest
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveOptionalNativeText
import ai.openclaw.app.i18n.verbatimText
import ai.openclaw.app.permissions.SessionPermissionDraft
import ai.openclaw.app.permissions.SessionPermissionRef
import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val QUESTION_REFRESH_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

private fun normalizedChatCacheScope(scope: ChatCacheScope?): ChatCacheScope? {
  val current = scope ?: return null
  val gatewayId = current.gatewayId.trim().takeIf { it.isNotEmpty() } ?: return null
  return if (gatewayId == current.gatewayId) current else current.copy(gatewayId = gatewayId)
}

internal data class MainSessionBinding(
  val key: String,
  val label: String,
)

internal data class ChatSessionDeletion(
  val gatewayId: String?,
  val agentId: String,
  val sessionKey: String,
  val mainSessionKey: String,
)

private class MainSessionReadiness(
  val gatewayScope: ChatCacheScope,
  val binding: MainSessionBinding,
  val ready: CompletableDeferred<Unit>,
) {
  var job: Job? = null
}

class ChatController internal constructor(
  private val scope: CoroutineScope,
  private val json: Json,
  private val requestGateway: suspend (method: String, paramsJson: String?) -> String,
  private val requestGatewayForGateway: suspend (gatewayId: String, method: String, paramsJson: String?) -> String =
    { _, method, paramsJson -> requestGateway(method, paramsJson) },
  private val gatewayAdvertisesMethod: (method: String) -> Boolean? = { null },
  private val gatewayAdvertisesCapability: (capability: String) -> Boolean? = { null },
  private val currentGatewayCatalogRevision: () -> Long = { 0L },
  private val prepareSessionSend: suspend (gatewayId: String?, sessionKey: String, agentId: String) -> Boolean = { _, _, _ -> true },
  private val createDraftSession: suspend (SessionPermissionDraft) -> SessionPermissionRef? = { null },
  private val onSessionStopped: suspend (ChatStopTarget) -> Boolean = { false },
  private val stopNativeControl: (ChatStopTarget) -> Boolean = { false },
  private val captureRequestLease: (gatewayScope: ChatCacheScope?) -> GatewaySession.RequestLease? =
    { gatewayScope ->
      GatewaySession.RequestLease(endpointStableId = gatewayScope?.gatewayId.orEmpty()) { method, paramsJson, _, withEnqueue ->
        withEnqueue {}
        if (gatewayScope == null) {
          requestGateway(method, paramsJson)
        } else {
          requestGatewayForGateway(gatewayScope.gatewayId, method, paramsJson)
        }
      }
    },
  private val transcriptCache: ChatTranscriptCache? = null,
  private val cacheScope: () -> ChatCacheScope? = { null },
  private val currentDefaultAgentId: () -> String? = { "main" },
  private val currentDefaultAgentRevision: () -> Long = { 0L },
  private val loadGatewayImage: suspend (
    gatewayId: String?,
    sessionKey: String,
    agentId: String?,
    artifactId: String,
  ) -> GatewayLoadedImage? = { _, _, _, _ -> null },
  private val commandOutbox: ChatCommandOutbox? = null,
  private val currentModelCatalog: () -> List<AiModel> = { emptyList() },
  private val recordModelRecent: (String) -> Unit = {},
  private val onSessionDeleted: (ChatSessionDeletion) -> Unit = {},
  private val onOfflineDefaultAgentRestored: (String) -> Unit = {},
  private val onAssistantReplyFinalized: (owner: ChatComposerOwner, runId: String, text: String) -> Unit = { _, _, _ -> },
) {
  internal constructor(
    scope: CoroutineScope,
    session: GatewaySession,
    json: Json,
    transcriptCache: ChatTranscriptCache? = null,
    cacheScope: () -> ChatCacheScope? = { null },
    currentDefaultAgentId: () -> String? = { "main" },
    currentDefaultAgentRevision: () -> Long = { 0L },
    gatewayAdvertisesMethod: (method: String) -> Boolean? = { null },
    gatewayAdvertisesCapability: (capability: String) -> Boolean? = { null },
    currentGatewayCatalogRevision: () -> Long = { 0L },
    prepareSessionSend: suspend (gatewayId: String?, sessionKey: String, agentId: String) -> Boolean = { _, _, _ -> true },
    createDraftSession: suspend (SessionPermissionDraft) -> SessionPermissionRef? = { null },
    onSessionStopped: suspend (ChatStopTarget) -> Boolean = { false },
    stopNativeControl: (ChatStopTarget) -> Boolean = { false },
    commandOutbox: ChatCommandOutbox? = null,
    currentModelCatalog: () -> List<AiModel> = { emptyList() },
    recordModelRecent: (String) -> Unit = {},
    onSessionDeleted: (ChatSessionDeletion) -> Unit = {},
    onOfflineDefaultAgentRestored: (String) -> Unit = {},
    onAssistantReplyFinalized: (owner: ChatComposerOwner, runId: String, text: String) -> Unit = { _, _, _ -> },
  ) : this(
    scope = scope,
    json = json,
    requestGateway = { method, paramsJson -> session.request(method, paramsJson) },
    requestGatewayForGateway = { gatewayId, method, paramsJson ->
      session.requestForEndpoint(gatewayId, method, paramsJson)
    },
    gatewayAdvertisesMethod = gatewayAdvertisesMethod,
    gatewayAdvertisesCapability = gatewayAdvertisesCapability,
    currentGatewayCatalogRevision = currentGatewayCatalogRevision,
    prepareSessionSend = prepareSessionSend,
    createDraftSession = createDraftSession,
    onSessionStopped = onSessionStopped,
    stopNativeControl = stopNativeControl,
    captureRequestLease = { gatewayScope ->
      session.captureRequestLease(gatewayScope?.gatewayId)
    },
    transcriptCache = transcriptCache,
    cacheScope = cacheScope,
    currentDefaultAgentId = currentDefaultAgentId,
    currentDefaultAgentRevision = currentDefaultAgentRevision,
    loadGatewayImage = { gatewayId, sessionKey, agentId, artifactId ->
      session.loadImageArtifact(gatewayId, sessionKey, agentId, artifactId)
    },
    commandOutbox = commandOutbox,
    currentModelCatalog = currentModelCatalog,
    recordModelRecent = recordModelRecent,
    onSessionDeleted = onSessionDeleted,
    onOfflineDefaultAgentRestored = onOfflineDefaultAgentRestored,
    onAssistantReplyFinalized = onAssistantReplyFinalized,
  )

  private val sessionTitlePreparation =
    ChatSessionTitlePreparationController(
      scope = scope,
      canPrepare = { target ->
        currentCacheScope()?.gatewayId == target.gatewayId &&
          gatewayAdvertisesMethod("sessions.title.prepare") == true
      },
      request = { target, params ->
        requestGatewayForGateway(target.gatewayId, "sessions.title.prepare", params)
      },
    )
  internal val sessionTitlePreparationFeature = sessionTitlePreparation.feature

  internal fun canSetConversationLabel(): Boolean = gatewayAdvertisesMethod("sessions.patch") == true

  suspend fun loadImage(artifactId: String): GatewayLoadedImage? {
    val normalizedArtifactId = artifactId.trim().takeIf(String::isNotEmpty) ?: return null
    val sessionKey = sessionSelection.normalizeKey(sessionSelection.key.value)
    return loadGatewayImage(
      currentCacheScope()?.gatewayId,
      sessionKey,
      sessionSelection.resolveOwner(sessionKey),
      normalizedArtifactId,
    )
  }

  // Serialize canonical history publication with durable delivery reconciliation.
  private val historyPublicationMutex = Mutex()
  private val cacheMutationMutex = Mutex()
  private val defaultAgentPersistenceMutex = Mutex()
  private val defaultAgentPersistenceRevisions = mutableMapOf<String, Long>()

  private val gatewayScopeApplyLock = Any()
  internal val drafts = ChatDraftController()
  private val sessionSelection =
    ChatSessionSelection(
      gatewayScopeApplyLock,
      ::currentCacheScope,
      currentDefaultAgentId,
      currentDefaultAgentRevision,
    )
  val sessionKey: StateFlow<String> = sessionSelection.key
  val sessionOwnerAgentId: StateFlow<String?> = sessionSelection.ownerAgentId
  internal val selectionGeneration: StateFlow<Long> = sessionSelection.generation
  internal val composerDefaultAgentOwner: StateFlow<GatewayDefaultAgentOwner?> = sessionSelection.defaultOwner

  private val sessionCodec = ChatSessionCatalogCodec(json)
  private val historyCodec = ChatHistoryCodec(json, sessionCodec)
  private val transcript =
    ChatTranscript(
      scope = scope,
      publicationLock = gatewayScopeApplyLock,
      selection = sessionSelection,
      currentGatewayScope = ::currentCacheScope,
      currentDefaultAgentRevision = currentDefaultAgentRevision,
      codec = historyCodec,
      awaitSessionReadiness = ::awaitMainSessionReadiness,
      requestHistory = { request ->
        requestGatewayBound(
          request.gatewayScope?.gatewayId,
          "chat.history",
          buildJsonObject {
            put("sessionKey", JsonPrimitive(request.sessionKey))
            put("agentId", JsonPrimitive(request.agentId))
          }.toString(),
        )
      },
      cache = transcriptCache,
      cacheMutationMutex = cacheMutationMutex,
    )
  val sessionId: StateFlow<String?> = transcript.sessionId
  val messages: StateFlow<List<ChatMessage>> = transcript.messages
  val transcriptAnchor: StateFlow<ChatTranscriptAnchorState?> = transcript.anchor
  val messagesFromCache: StateFlow<Boolean> = transcript.fromCache
  val historyLoading: StateFlow<Boolean> = transcript.loading

  private val _errorText = MutableStateFlow<NativeText?>(null)
  val errorText: StateFlow<String?> = _errorText.resolveOptionalNativeText()

  private val mutableTaskNotices = MutableStateFlow<Map<String, ChatTaskNotice>>(emptyMap())
  internal val taskNotices: StateFlow<Map<String, ChatTaskNotice>> = mutableTaskNotices.asStateFlow()

  internal fun clearTaskNotice(sessionKey: String) {
    mutableTaskNotices.update { notices -> notices - sessionKey }
  }

  internal fun reportTaskNotice(
    sessionKey: String,
    message: String,
  ) {
    val notice = runCatching { ChatTaskNotice(sessionKey, message) }.getOrNull() ?: return
    mutableTaskNotices.update { notices -> notices + (sessionKey to notice) }
  }

  private val _healthOk = MutableStateFlow(false)
  val healthOk: StateFlow<Boolean> = _healthOk.asStateFlow()

  private val fullMessageReader =
    ChatFullMessageReader(
      publicationLock = gatewayScopeApplyLock,
      sessionSelection = sessionSelection,
      messages = transcript.messages,
      currentGatewayCatalogRevision = currentGatewayCatalogRevision,
      captureRequestLease = captureRequestLease,
      gatewayAdvertisesMethod = gatewayAdvertisesMethod,
      json = json,
      historyCodec = historyCodec,
    )
  internal val historyFeature =
    ChatHistoryFeature(
      selection = sessionSelection.feature,
      messages = transcript.messages,
      anchor = transcript.anchor,
      loading = transcript.loading,
      error = errorText,
      healthy = healthOk,
      load = ::load,
      refresh = ::refresh,
      fullMessages = fullMessageReader.feature,
      loadImage = ::loadImage,
    )

  private val sessionSettings: ChatSessionSettings =
    ChatSessionSettings(
      scope = scope,
      json = json,
      publicationLock = gatewayScopeApplyLock,
      drafts = drafts,
      selectedSessionKey = { sessionSelection.key.value },
      currentKey = { sessionSettingsKey(it) },
      readMetadata = { key -> sessionCatalog.entry(key)?.settingsMetadata() },
      publishMetadata = { key, metadata -> sessionCatalog.applySettings(key, metadata) },
      captureRequestLease = captureRequestLease,
      publishError = ::updateLocalizedErrorText,
      recordModelRecent = recordModelRecent,
      onLaneDrained = { if (_healthOk.value) delivery.requestFlush() },
    )
  val thinkingLevel: StateFlow<String> = sessionSettings.thinkingLevel
  val thinkingLevelSelection: StateFlow<ChatThinkingLevelSelection> = sessionSettings.thinkingLevelSelection
  val selectedModelRef: StateFlow<String?> = sessionSettings.selectedModelRef

  internal fun applyModelCatalog(catalog: List<AiModel>) = sessionSettings.applyModelCatalog(catalog)

  private val metadata =
    ChatMetadata(
      scope = scope,
      json = json,
      publicationLock = gatewayScopeApplyLock,
      selection = sessionSelection,
      currentGatewayScope = ::currentCacheScope,
      currentCatalogRevision = currentGatewayCatalogRevision,
      gatewayAdvertisesCapability = gatewayAdvertisesCapability,
      isLocalDraft = { gateway, key -> drafts.find(gateway?.gatewayId, key)?.isLocal == true },
      captureRequestLease = captureRequestLease,
    )
  val commands: StateFlow<List<ChatCommandEntry>> = metadata.commands

  private val runState = ChatRunState()
  val pendingRunCount: StateFlow<Int> = runState.pendingCount
  internal val selectedActiveRunPresentation: StateFlow<ChatActiveRunPresentation> = runState.presentation

  private val _answerDraft = MutableStateFlow<ChatAssistantAnswerDraft?>(null)
  val answerDraft: StateFlow<ChatAssistantAnswerDraft?> = _answerDraft.asStateFlow()

  private val runActivityStore = ChatRunActivityStore()
  internal val runActivity: StateFlow<ChatRunActivity?> = runActivityStore.activity

  private val pendingToolCallsById = ConcurrentHashMap<String, ChatPendingToolCall>()
  private val _pendingToolCalls = MutableStateFlow<List<ChatPendingToolCall>>(emptyList())
  val pendingToolCalls: StateFlow<List<ChatPendingToolCall>> = _pendingToolCalls.asStateFlow()

  private val _questions = MutableStateFlow<List<ChatQuestionPrompt>>(emptyList())
  val questions: StateFlow<List<ChatQuestionPrompt>> = _questions.asStateFlow()
  private val questionStateLock = Any()
  private var questionStateRevision = 0L
  private var questionRefreshGeneration = 0L

  private data class QuestionEvictionJob(
    val job: Job,
    val observedAtMs: Long?,
  )

  private val questionEvictionJobs = mutableMapOf<String, QuestionEvictionJob>()

  private val _progressCard = MutableStateFlow<ChatProgressCard?>(null)
  val progressCard: StateFlow<ChatProgressCard?> = _progressCard.asStateFlow()

  @Volatile private var progressCardScopeKey: String? = null

  private val sessionCatalog: ChatSessionCatalog =
    ChatSessionCatalog(
      publicationLock = gatewayScopeApplyLock,
      selection = sessionSelection,
      settings = sessionSettings,
      codec = sessionCodec,
      requestGateway = requestGateway,
      persist = ::persistSessions,
      onSnapshotPublished = { _ ->
        pruneRunTelemetryToAuthoritativeOwnership()
        publishRunPresentation()
        synchronized(gatewayScopeApplyLock) {
          sessionReads.observe(sessionCatalog.entry(sessionSelection.key.value))
        }
      },
    )
  val sessions: StateFlow<List<ChatSessionEntry>> = sessionCatalog.entries
  internal val sessionCatalogFeature = ChatSessionCatalogFeature(sessions, ::refreshSessions, sessionCatalog::search)

  private val sessionReads: ChatSessionReads =
    ChatSessionReads(
      scope = scope,
      publicationLock = gatewayScopeApplyLock,
      selection = sessionSelection,
      currentGatewayScope = ::currentCacheScope,
      gatewayAdvertisesCapability = gatewayAdvertisesCapability,
      captureRequestLease = captureRequestLease,
      onAcknowledged = ::refreshSessionsForCurrentWindow,
      onFailure = { updateErrorText(it) },
    )

  private val pendingSends =
    ChatPendingSends(
      scope = scope,
      lock = gatewayScopeApplyLock,
      transcript = transcript,
      runState = runState,
      context = {
        ChatPendingSendContext(
          connection = currentCacheScope(),
          owner = currentChatComposerRoutingOwner(),
          sessionKey = sessionSelection.key.value,
          healthy = _healthOk.value,
          advertisedRunIds = advertisedRunIds().toSet(),
        )
      },
      refreshHistory = { key, generation, runIds ->
        when (historySync.refreshSnapshot(key, generation, runIds)) {
          is ChatHistoryRefreshResult.Applied -> ChatPendingHistoryResult.Applied
          ChatHistoryRefreshResult.Superseded -> ChatPendingHistoryResult.Superseded
          ChatHistoryRefreshResult.OwnerUnavailable, ChatHistoryRefreshResult.Failed -> ChatPendingHistoryResult.Unavailable
        }
      },
      parkUnconfirmed = { deliveryJournal.parkUnconfirmed(it) },
      publish = { event ->
        when (event) {
          ChatPendingSendEvent.Changed -> publishRunPresentation()
          ChatPendingSendEvent.Projected -> {
            updateErrorText(null)
            clearLiveRunUi()
          }
          ChatPendingSendEvent.Idle -> clearLiveRunUi()
          is ChatPendingSendEvent.Streaming -> _answerDraft.value = ChatAssistantAnswerDraft(event.runId, event.text)
          ChatPendingSendEvent.ReplyTimedOut -> updateLocalizedErrorText(nativeText("Timed out waiting for a reply; try again or refresh."))
          ChatPendingSendEvent.ConfirmationTimedOut -> updateLocalizedErrorText(nativeText("Timed out confirming the sent message; refresh to check delivery."))
        }
      },
    )

  private val progressCardFetchGeneration = AtomicLong(0)
  private val legacyProgressCardRevision = AtomicInteger(0)

  // Ownerless delete proofs must finish in event order. Parallel list refreshes can supersede
  // each other and strand an earlier session's durable cache/outbox state.
  private val ambiguousDeleteReconciliationMutex = Mutex()

  // Every live history path awaits this gateway/session readiness. Per-gateway locking keeps
  // rapid agent switches from letting an older lookup refresh before the new session is ready.
  private val mainSessionAdoptionLocks = ConcurrentHashMap<String, Mutex>()
  private val desiredMainSessions = ConcurrentHashMap<String, MainSessionBinding>()
  private val mainSessionReadinessLock = Any()
  private var mainSessionReadiness: MainSessionReadiness? = null
  private var lastHandledTerminalRunId: String? = null
  private var historyLoadErrorGeneration: Long? = null
  internal val stops =
    ChatStopController(
      scope,
      ::currentCacheScope,
      { captureRequestLease(it) },
      { deliveryJournal.parkStopped(it) },
      onSessionStopped,
      stopNativeControl,
    )

  private var lastHealthPollAtMs: Long? = null

  private fun updateErrorText(
    message: String?,
    historyGeneration: Long? = null,
  ) {
    _errorText.value = message?.let(::verbatimText)
    historyLoadErrorGeneration = historyGeneration
  }

  private fun updateLocalizedErrorText(
    message: NativeText?,
    historyGeneration: Long? = null,
  ) {
    _errorText.value = message
    historyLoadErrorGeneration = historyGeneration
  }

  private val outboxState =
    ChatOutboxState(scope, commandOutbox, gatewayScopeApplyLock, ::currentCacheScope)
  val outboxItems: StateFlow<List<ChatOutboxItem>> = outboxState.items
  val outboxPresentationRestored: StateFlow<Boolean> = outboxState.restored

  private val deliveryJournal: ChatDeliveryJournal =
    ChatDeliveryJournal(
      commandOutbox,
      outboxState,
      historyPublicationMutex,
      sessionSelection::normalizeKey,
      pendingSends::isLocallyOwned,
      onStorageFailure = { _healthOk.value = false },
      resumeDelivery = { if (_healthOk.value) delivery.requestFlush() },
    )

  private val delivery: ChatDelivery =
    ChatDelivery(
      scope = scope,
      commandOutbox = commandOutbox,
      journal = deliveryJournal,
      outboxState = outboxState,
      pendingSends = pendingSends,
      runState = runState,
      sendDispatcher = ChatSendDispatcher(json, stops::enqueue),
      stops = stops,
      context = {
        ChatDeliveryContext(
          connection = currentCacheScope(),
          owner = currentChatComposerRoutingOwner(),
          selectionGeneration = sessionSelection.generation.value,
          defaultAgentRevision = currentDefaultAgentRevision(),
          sessionKey = sessionSelection.key.value,
          healthy = _healthOk.value,
          supportsThinking = sessionSettings.supportsThinking(currentModelCatalog()),
        )
      },
      normalizeSessionKey = sessionSelection::normalizeKey,
      awaitSettings = { gateway, key, agent ->
        sessionSettings.awaitPending(sessionSettingsKey(key, gateway, agent))
      },
      prepareSessionSend = prepareSessionSend,
      captureRequestLease = captureRequestLease,
      readHistory = { gateway, owner -> readDetachedHistory(gateway, owner.sessionKey, owner.agentId) },
      refreshHistory = { historySync.refreshCurrent(runIdsToReconcile = it) },
      onUnavailable = { _healthOk.value = false },
      publish = { event ->
        when (event) {
          ChatDeliveryEvent.Idle -> clearLiveRunUi()
          is ChatDeliveryEvent.Error -> updateLocalizedErrorText(event.failure.errorText())
        }
      },
    )

  private val historySync: ChatHistorySync =
    ChatHistorySync(
      scope = scope,
      gatewayScopeApplyLock = gatewayScopeApplyLock,
      historyPublicationMutex = historyPublicationMutex,
      sessionSelection = sessionSelection,
      transcript = transcript,
      sessionSettings = sessionSettings,
      sessionCatalog = sessionCatalog,
      pendingSends = pendingSends,
      outboxState = outboxState,
      drafts = drafts,
      transcriptCache = transcriptCache,
      context = { ChatHistoryContext(currentCacheScope(), currentDefaultAgentId(), currentDefaultAgentRevision(), _healthOk.value) },
      pollHealthIfNeeded = { pollHealthIfNeeded(force = it) },
      publishSessionInfo = { updateSessionFromHistory(it, publishRunState = false) },
      onHistoryPublished = { generation, history ->
        if (historyLoadErrorGeneration == generation) updateErrorText(null)
        val inFlightRunId = history.inFlightRun?.runId
        when {
          inFlightRunId != null && inFlightRunId in pendingSends.visibleRunIds ->
            runActivityStore.restore(inFlightRunId, history.commentary)
          pendingSends.visibleRunIds.isEmpty() -> runActivityStore.clear()
        }
      },
      onHistoryError = { generation, message -> updateErrorText(message, historyGeneration = generation) },
      onOfflineDefaultAgentRestored = onOfflineDefaultAgentRestored,
      confirmDelivery = { gateway, history, agent -> delivery.confirmHistory(gateway, history, agent) },
    )

  /** Clears transient chat state when the operator gateway session disconnects. */
  fun onDisconnected(message: String) {
    metadata.clear()
    retireMainSessionReadiness()
    transcript.disconnect()
    historySync.disconnected()
    _healthOk.value = false
    updateErrorText(null)
    pendingSends.rememberDisconnected()
    // History can lag the accepted send. Keep the optimistic echo available for the
    // reconnect snapshot to reconcile instead of dropping the user's message.
    pendingSends.clearAll(
      clearOptimisticMessages = false,
      preserveDisconnectedOwnership = true,
    )
    clearLiveRunUi()
    // Failed connect attempts pass through onGatewayScopeChanging, which empties the published
    // outbox rows; repopulate for the still-selected gateway so queued sends stay visible offline.
    scope.launch { outboxState.refresh() }
  }

  /** Refreshes the connected gateway while preserving recovery ownership after a disconnect. */
  fun onGatewayConnected() {
    refreshConnectedGateway()
  }

  /** Creates/adopts the app-owned main session before connected history can load. */
  internal fun onGatewayConnected(mainSession: MainSessionBinding) {
    val requestScope = currentCacheScope()
    if (requestScope == null) {
      refreshConnectedGateway()
      return
    }
    desiredMainSessions[requestScope.gatewayId] = mainSession
    val readiness =
      MainSessionReadiness(
        gatewayScope = requestScope,
        binding = mainSession,
        ready = CompletableDeferred(),
      )
    val adoptionJob =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          val adoptionLock = mainSessionAdoptionLocks.computeIfAbsent(requestScope.gatewayId) { Mutex() }
          adoptionLock.withLock {
            if (desiredMainSessions[requestScope.gatewayId] != mainSession) return@withLock
            try {
              val describeParams = buildJsonObject { put("key", JsonPrimitive(mainSession.key)) }
              val describeResponse =
                requestGatewayBound(requestScope.gatewayId, "sessions.describe", describeParams.toString())
              val describeRoot = json.parseToJsonElement(describeResponse).asObjectOrNull() ?: error("invalid sessions.describe response")
              if (!describeRoot.containsKey("session")) error("sessions.describe returned no session field")
              if (desiredMainSessions[requestScope.gatewayId] != mainSession) return@withLock
              val existingSession = describeRoot["session"].asObjectOrNull()
              val existingLabel =
                existingSession
                  ?.get("label")
                  .asStringOrNull()
                  ?.trim()
                  ?.takeIf { it.isNotEmpty() }
              if (existingLabel == null) {
                // Label-only sessions.patch is operator.write-scoped and atomically upserts the row,
                // avoiding the concurrent-session identity race in sessions.create.
                val patchParams =
                  buildJsonObject {
                    put("key", JsonPrimitive(mainSession.key))
                    put("label", JsonPrimitive(mainSession.label))
                  }
                requestGatewayBound(requestScope.gatewayId, "sessions.patch", patchParams.toString())
              }
            } catch (err: CancellationException) {
              throw err
            } catch (_: Throwable) {
              // History remains usable under the already-bound key when adoption cannot be verified.
            }
          }
        } finally {
          readiness.ready.complete(Unit)
        }
        // A superseded connect owns the next refresh and must not inherit this response.
        if (
          synchronized(mainSessionReadinessLock) { mainSessionReadiness === readiness } &&
          requestScope == currentCacheScope() &&
          desiredMainSessions[requestScope.gatewayId] == mainSession
        ) {
          refreshConnectedGateway()
        }
      }
    readiness.job = adoptionJob
    val supersededReadiness =
      synchronized(mainSessionReadinessLock) {
        val current = mainSessionReadiness
        mainSessionReadiness = readiness
        current
      }
    supersededReadiness?.job?.cancel()
    supersededReadiness?.ready?.complete(Unit)
    adoptionJob.start()
  }

  private fun refreshConnectedGateway() {
    refreshQuestions()
    if (!historySync.recovering) {
      refresh()
      return
    }
    updateErrorText(null)
    historySync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
  }

  /** Invalidates pairing-bound UI state before replacement can race old responses. */
  fun onGatewayScopeChanging(retireRunState: Boolean = false) {
    metadata.clear()
    retireMainSessionReadiness()
    synchronized(gatewayScopeApplyLock) {
      if (retireRunState) {
        historySync.retireRecovery()
        pendingSends.clearAll()
        clearLiveRunUi()
      }
      sessionSelection.resetMainKey()
      beginHistoryLoad(
        key = "main",
        ownerAgentId = null,
        clearMessages = true,
        markLoading = false,
      )
      clearProgressCard()
      transcript.invalidateLive()
      sessionCatalog.clear()
      publishRunPresentation()
      clearQuestions()
      sessionSettings.applyMetadata(null)
      sessionReads.clear()
      metadata.clear()
      lastHealthPollAtMs = null
      // Outbox rows are gateway-scoped too; the next publish repopulates them for the new scope.
      outboxState.clear()
    }
  }

  private fun retireMainSessionReadiness() {
    val staleReadiness =
      synchronized(mainSessionReadinessLock) {
        val current = mainSessionReadiness
        mainSessionReadiness = null
        current
      }
    staleReadiness?.job?.cancel()
    staleReadiness?.ready?.complete(Unit)
  }

  /** Restores the selected gateway's local state without waiting for transport availability. */
  fun restoreSelectedGatewayOfflineState() {
    refresh()
    scope.launch { outboxState.refresh() }
  }

  /** Purges cached transcripts and queued sends for one retired authentication scope. */
  internal suspend fun clearGatewayCache(gatewayId: String) {
    clearGatewayCache(gatewayId) { gateway ->
      transcriptCache?.clearGateway(gateway)
      commandOutbox?.clearGateway(gateway)
    }
  }

  /** Serializes an owner-provided cross-store purge with every chat cache/outbox mutation. */
  internal suspend fun clearGatewayCache(
    gatewayId: String,
    clearStores: suspend (String) -> Unit,
  ) {
    val gateway = gatewayId.trim().takeIf { it.isNotEmpty() } ?: return
    synchronized(defaultAgentPersistenceRevisions) {
      defaultAgentPersistenceRevisions[gateway] = (defaultAgentPersistenceRevisions[gateway] ?: 0L) + 1L
    }
    sessionSelection.forgetDefaultAgent(gateway)
    // Serialize after invalidating the revision. An already-running save finishes first and is
    // deleted here; queued old-owner saves then fail their revision check after this unlocks.
    defaultAgentPersistenceMutex.withLock {
      cacheMutationMutex.withLock {
        clearStores(gateway)
      }
    }
  }

  /** Loads a chat session, normalizing "main" to the current gateway-provided main session key. */
  fun load(
    sessionKey: String,
    ownerAgentId: String? = null,
  ) {
    val key = sessionSelection.normalizeKey(sessionKey)
    val owner = sessionSelection.normalizeOwner(key, ownerAgentId)
    if (key == sessionSelection.key.value && owner == sessionSelection.ownerAgentId.value) {
      if (hasCurrentLiveHistory(key)) return
      refresh()
      return
    }
    val generation = beginHistoryLoad(key, ownerAgentId = owner, clearMessages = true)
    scope.launch {
      historySync.bootstrap(sessionKey = key, generation = generation, forceHealth = true, refreshSessions = true)
    }
  }

  /** Rebinds chat to a new canonical main session key after gateway hello/agent changes. */
  fun applyMainSessionKey(mainSessionKey: String) {
    bindMainSessionKey(mainSessionKey, loadHistory = true)
  }

  /** Rebinds without loading; the connected lifecycle creates/adopts the session first. */
  internal fun prepareMainSessionKey(mainSessionKey: String) {
    bindMainSessionKey(mainSessionKey, loadHistory = false)
  }

  /** Selects a newly chosen agent's main session without racing history ahead of adoption. */
  internal fun prepareAndSelectMainSessionKey(mainSessionKey: String) {
    val selectedKey = mainSessionKey.trim()
    if (selectedKey.isEmpty()) return
    prepareSessionSelection(selectedKey)
    bindMainSessionKey(mainSessionKey, loadHistory = false)
    val key = sessionSelection.normalizeKey(mainSessionKey)
    if (sessionSelection.key.value != key || sessionSelection.ownerAgentId.value != resolveAgentIdFromMainSessionKey(key)) {
      beginHistoryLoad(
        key,
        ownerAgentId = resolveAgentIdFromMainSessionKey(key),
        clearMessages = true,
      )
    }
  }

  /** Clears and reloads an unscoped chat when the gateway's routing owner changes. */
  internal fun onDefaultAgentChanged(agentId: String?) {
    // A disconnect makes routing temporarily unknown; retain the last verified owner's
    // offline projection until hello proves that ownership actually changed.
    val verifiedAgentId = agentId ?: return
    val verifiedGatewayId = currentCacheScope()?.gatewayId
    val previousAgentId = sessionSelection.recordDefaultAgent(verifiedGatewayId, verifiedAgentId)
    if (verifiedGatewayId != null) {
      val persistenceRevision =
        synchronized(defaultAgentPersistenceRevisions) {
          val next = (defaultAgentPersistenceRevisions[verifiedGatewayId] ?: 0L) + 1L
          defaultAgentPersistenceRevisions[verifiedGatewayId] = next
          next
        }
      // The live default is the only authoritative owner for unscoped keys. Persist it so an
      // offline process restart can reopen the same owner's cache without guessing.
      scope.launch {
        defaultAgentPersistenceMutex.withLock {
          val isLatest =
            synchronized(defaultAgentPersistenceRevisions) {
              defaultAgentPersistenceRevisions[verifiedGatewayId] == persistenceRevision
            }
          if (isLatest) {
            runCatching { transcriptCache?.saveLastDefaultAgentId(verifiedGatewayId, verifiedAgentId) }
          }
        }
      }
    }
    val key = sessionSelection.normalizeKey(sessionSelection.key.value)
    if (resolveAgentIdFromMainSessionKey(key) != null) return
    if (sessionSelection.ownerAgentId.value != null) return
    if (previousAgentId == verifiedAgentId) return
    // Session titles and model metadata are scoped to the default agent even when the visible
    // session alias stays unchanged. Empty first so offline bootstrap cannot reuse the old owner.
    sessionCatalog.clear()
    val generation = beginHistoryLoad(key, ownerAgentId = null, clearMessages = true, markLoading = true)
    scope.launch {
      historySync.bootstrap(sessionKey = key, generation = generation, forceHealth = true, refreshSessions = true)
    }
  }

  private fun bindMainSessionKey(
    mainSessionKey: String,
    loadHistory: Boolean,
  ) {
    val nextKey = sessionSelection.bindMainKey(mainSessionKey) ?: return
    val generation =
      beginHistoryLoad(
        nextKey,
        ownerAgentId = resolveAgentIdFromMainSessionKey(nextKey),
        clearMessages = true,
      )
    if (!loadHistory) return
    scope.launch {
      historySync.bootstrap(
        sessionKey = nextKey,
        generation = generation,
        forceHealth = true,
        refreshSessions = true,
      )
    }
  }

  /** Refreshes current chat history and session list without clearing optimistic messages first. */
  fun refresh() {
    updateErrorText(null)
    historySync.refreshForRecovery(forceHealth = true)
  }

  fun refreshSessions(
    limit: Int? = null,
    archived: Boolean = false,
  ) {
    scope.launch { sessionCatalog.refresh(limit = limit, archived = archived) }
  }

  suspend fun patchSession(
    key: String,
    ownerAgentId: String? = null,
    expectedSessionId: String? = null,
    label: String? = null,
    clearLabel: Boolean = false,
    color: String? = null,
    clearColor: Boolean = false,
    pinned: Boolean? = null,
    archived: Boolean? = null,
    unread: Boolean? = null,
  ): Boolean {
    val sessionKey = key.trim().takeIf { it.isNotEmpty() } ?: return false
    val requestCacheScope = currentCacheScope()
    val capturedOwnerAgentId =
      resolveAgentIdFromMainSessionKey(sessionKey)
        ?: ownerAgentId?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (sessionKey == sessionSelection.key.value) sessionSelection.resolveOwner(sessionKey) else null
    val hasPatch =
      clearLabel ||
        label != null ||
        clearColor ||
        color != null ||
        pinned != null ||
        archived != null ||
        unread != null
    if (!hasPatch) return false
    val lifecycleSessionId = expectedSessionId?.trim()?.takeIf { it.isNotEmpty() }
    if (archived != null && lifecycleSessionId == null) {
      updateErrorText("Session lifecycle action requires a durable session identity.")
      return false
    }
    try {
      val params =
        buildJsonObject {
          put("key", JsonPrimitive(sessionKey))
          capturedOwnerAgentId?.let { put("agentId", JsonPrimitive(it)) }
          lifecycleSessionId?.let { put("expectedSessionId", JsonPrimitive(it)) }
          if (clearLabel) {
            put("label", JsonNull)
          } else if (label != null) {
            put("label", JsonPrimitive(label))
          }
          if (clearColor) {
            put("color", JsonNull)
          } else if (color != null) {
            put("color", JsonPrimitive(color))
          }
          if (pinned != null) put("pinned", JsonPrimitive(pinned))
          if (archived != null) put("archived", JsonPrimitive(archived))
          if (unread != null) put("unread", JsonPrimitive(unread))
        }
      if (archived == true) {
        val defaultAgentRevision = currentDefaultAgentRevision().takeIf { sessionSelection.tracksDefaultAgent(sessionKey) }
        val selection =
          synchronized(gatewayScopeApplyLock) {
            sessionSelection.captureAction(sessionKey)?.takeIf {
              it.gatewayScope == requestCacheScope &&
                it.ownerAgentId == capturedOwnerAgentId?.lowercase() &&
                transcript.sessionId.value == lifecycleSessionId
            }
          }
        val lease = captureRequestLease(requestCacheScope) ?: throw GatewayRequestNotEnqueued("not connected")
        lease.request("sessions.patch", params.toString(), 10 * 60_000L)
        lease.commitIfCurrent {
          synchronized(gatewayScopeApplyLock) {
            // Same-key history and default-owner changes need not move selection generation.
            // Only the selection captured at entry may navigate after the archive completes.
            if (
              selection != null &&
              sessionSelection.isCurrent(selection) &&
              transcript.sessionId.value == lifecycleSessionId &&
              (defaultAgentRevision == null || defaultAgentRevision == currentDefaultAgentRevision())
            ) {
              fallBackFromRetiredActiveSession(sessionKey)
            }
          }
        }
      } else {
        requestGateway("sessions.patch", params.toString())
      }
      fetchSessionsForCurrentWindow()
      return true
    } catch (err: Throwable) {
      updateErrorText(err.message)
      return false
    }
  }

  internal suspend fun setConversationLabel(
    session: ChatSessionEntry,
    label: String?,
  ): Boolean {
    if (!canSetConversationLabel()) return false
    val expectedSessionId = session.sessionId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val normalizedLabel = label?.trim()?.takeIf(String::isNotEmpty)
    if (label != null && normalizedLabel == null) return false
    if (normalizedLabel != null && normalizedLabel.length > CHAT_SESSION_LABEL_MAX_CHARS) return false
    return patchSession(
      key = session.key,
      ownerAgentId = session.ownerAgentId,
      expectedSessionId = expectedSessionId,
      label = normalizedLabel,
      clearLabel = label == null,
    )
  }

  internal suspend fun deleteConversation(session: ChatSessionEntry): Boolean {
    val observedSessionId = session.sessionId?.trim()?.takeIf { it.isNotEmpty() }
    if (observedSessionId == null) {
      updateErrorText("Conversation deletion requires a durable session identity.")
      return false
    }
    val archived =
      patchSession(
        key = session.key,
        ownerAgentId = session.ownerAgentId,
        expectedSessionId = observedSessionId,
        archived = true,
      )
    if (!archived) return false
    return deleteSession(session.key, session.ownerAgentId) != null
  }

  internal suspend fun deleteSession(
    key: String,
    ownerAgentId: String? = null,
  ): ChatSessionDeletion? {
    val sessionKey = key.trim().takeIf { it.isNotEmpty() } ?: return null
    val capturedOwnerAgentId =
      resolveAgentIdFromMainSessionKey(sessionKey)
        ?: ownerAgentId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val requestCacheScope = currentCacheScope()
    val requestMainSessionKey = sessionSelection.mainKey
    val deleted =
      try {
        val params =
          buildJsonObject {
            put("key", JsonPrimitive(sessionKey))
            put("agentId", JsonPrimitive(capturedOwnerAgentId))
            put("deleteTranscript", JsonPrimitive(true))
            // archive-then-delete: the bounded operator session lacks admin, and
            // the gateway grants write-scope deletes only for archived sessions.
            put("archivedOnly", JsonPrimitive(true))
          }
        val response = requestGatewayBound(requestCacheScope?.gatewayId, "sessions.delete", params.toString())
        json
          .parseToJsonElement(response)
          .asObjectOrNull()
          ?.get("deleted")
          .asBooleanOrNull() == true
      } catch (err: Throwable) {
        updateErrorText(err.message)
        return null
      }
    try {
      if (deleted) {
        if (removeSessionEntry(sessionKey, ownerAgentId = capturedOwnerAgentId, cacheScope = requestCacheScope)) {
          fallBackFromRetiredActiveSession(sessionKey)
        }
      }
      fetchSessionsForCurrentWindow()
    } catch (err: Throwable) {
      updateErrorText(err.message)
    }
    return if (deleted) {
      ChatSessionDeletion(
        gatewayId = requestCacheScope?.gatewayId,
        agentId = capturedOwnerAgentId,
        sessionKey = sessionKey,
        mainSessionKey = requestMainSessionKey,
      )
    } else {
      null
    }
  }

  // Archiving or deleting the open chat must not leave the app focused on a
  // retired session; fall back to the gateway main session like web and iOS do.
  private fun fallBackFromRetiredActiveSession(retiredKey: String) {
    if (retiredKey != sessionSelection.key.value) return
    switchSession("main")
  }

  /** Selects a local draft. This operation never needs a connected Gateway or creates a Session. */
  fun startNewDraft(): Boolean = startNewProjectDraft(null, null) != null

  /** Selects a Project-owned local draft and returns its stable Session key. */
  fun startNewProjectDraft(
    projectId: String?,
    projectRoot: String?,
  ): String? {
    val gatewayId = currentCacheScope()?.gatewayId ?: return null
    val agentId = sessionSelection.resolveOwner(sessionSelection.key.value) ?: return null
    val draft =
      projectId
        ?.let { drafts.pristineProjectDraft(gatewayId, it) }
        ?: drafts.newDraft(gatewayId, agentId, projectId, projectRoot)
    val wasHealthy = _healthOk.value
    beginHistoryLoad(draft.intent.target.key, agentId, clearMessages = true, markLoading = false)
    _healthOk.value = wasHealthy
    return draft.intent.target.key
  }

  fun bindProjectDraft(
    sessionKey: String,
    projectId: String,
    projectRoot: String,
  ): Boolean {
    val gatewayId = currentCacheScope()?.gatewayId ?: return false
    val draft = drafts.find(gatewayId, sessionSelection.normalizeKey(sessionKey)) ?: return false
    return drafts.bindProject(draft.intent.target, projectId, projectRoot)
  }

  /** Local drafts are deliberately not persisted as a resumable conversation. */
  internal fun captureNavigationSelection(): ChatNavigationSelection? =
    synchronized(gatewayScopeApplyLock) {
      val gatewayId = currentCacheScope()?.gatewayId ?: return null
      val key = sessionSelection.key.value
      if (drafts.find(gatewayId, key)?.isLocal == true) return null
      ChatNavigationSelection(gatewayId, key, sessionSelection.ownerAgentId.value)
    }

  /** Refreshes the available text slash commands for the current gateway. */
  fun refreshCommands() = metadata.refresh()

  fun setThinkingLevel(thinkingLevel: String) = sessionSettings.setThinkingLevel(thinkingLevel)

  fun setSessionModel(
    sessionKey: String,
    modelRef: String?,
  ) = sessionSettings.setSessionModel(sessionSelection.normalizeKey(sessionKey), modelRef)

  internal suspend fun setSessionModelAwait(
    sessionKey: String,
    modelRef: String?,
  ): Boolean = sessionSettings.setSessionModelAwait(sessionSelection.normalizeKey(sessionKey), modelRef)

  /** Selects another Chat session and starts a fresh history load. */
  fun switchSession(
    sessionKey: String,
    ownerAgentId: String? = null,
  ) {
    val key = sessionSelection.normalizeKey(sessionKey)
    if (key.isEmpty()) return
    val owner = sessionSelection.normalizeOwner(key, ownerAgentId)
    prepareSessionSelection(key, owner)
    if (key == sessionSelection.key.value && owner == sessionSelection.ownerAgentId.value) return
    val generation = beginHistoryLoad(key, ownerAgentId = owner, clearMessages = true)
    scope.launch {
      historySync.bootstrap(sessionKey = key, generation = generation, forceHealth = true, refreshSessions = false)
    }
  }

  private fun prepareSessionSelection(
    key: String,
    ownerAgentId: String? = null,
  ) = sessionReads.activate(key, ownerAgentId) { sessionCatalog.entry(key) }

  private fun beginHistoryLoad(
    key: String,
    ownerAgentId: String?,
    clearMessages: Boolean,
    markLoading: Boolean = true,
  ): Long {
    val owner = sessionSelection.normalizeOwner(key, ownerAgentId)
    // Commit selection and its reset together: a newer IO refresh must not finish
    // between publishing this generation and clearing its readiness or run state.
    val (generation, selectionChanged) =
      synchronized(gatewayScopeApplyLock) {
        val generation = transcript.beginSelection(clearMessages, markLoading)
        val changed = sessionSelection.select(key, owner)
        sessionReads.selectionChanged()
        if (changed) clearProgressCard()
        sessionCatalog.reconcileSelectedOwner()
        sessionSettings.selectSession(
          sessionCatalog.entries.value
            .firstOrNull { it.key == key }
            ?.settingsMetadata(),
        )
        lastHandledTerminalRunId = null
        metadata.selectionChanged()
        updateErrorText(null)
        _healthOk.value = false
        pendingSends.clearAll()
        clearLiveRunUi()
        pendingSends.restoreForCurrentOwner()
        generation to changed
      }
    if (selectionChanged) refreshProgressCard()
    return generation
  }

  private fun hasCurrentLiveHistory(sessionKey: String): Boolean = transcript.hasLiveHistory(sessionKey) && _errorText.value == null && _healthOk.value

  /** Queues a chat send without waiting for gateway acceptance. */
  fun sendMessage(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
  ) {
    scope.launch {
      sendMessageAwaitAcceptance(
        message = message,
        thinkingLevel = thinkingLevel,
        attachments = attachments,
      )
    }
  }

  /** Sends a chat message and returns once it is durably admitted or the gateway rejects it. */
  suspend fun sendMessageAwaitAcceptance(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
  ): Boolean = sendMessageAwaitAcceptance(message, thinkingLevel, attachments, expectedOwner = null)

  internal suspend fun sendMessageForOwnerAwaitAcceptance(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
    expectedOwner: ChatComposerOwner,
    idempotencyKey: String? = null,
  ): Boolean = sendMessageAwaitAcceptance(message, thinkingLevel, attachments, expectedOwner, idempotencyKey)

  internal suspend fun wasOutboxCommandAdmitted(id: String): Boolean = commandOutbox?.wasAdmitted(id) == true

  internal fun isCurrentComposerOwner(expectedOwner: ChatComposerOwner): Boolean = sessionSelection.isCurrentComposerOwner(expectedOwner)

  private suspend fun sendMessageAwaitAcceptance(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
    expectedOwner: ChatComposerOwner?,
    idempotencyKey: String? = null,
  ): Boolean {
    val sendAttachments = attachments.toList()
    val sendCacheScope = currentCacheScope()
    val sendGatewayId = sendCacheScope?.gatewayId
    val sendSelectionGeneration = sessionSelection.generation.value
    val trimmed = message.trim()
    if (trimmed.isEmpty() && sendAttachments.isEmpty()) return false
    val sessionKey = sessionSelection.key.value
    val effectiveSessionKey = sessionSelection.normalizeKey(sessionKey)
    // Owner-aware UI sends must wait for Android's device-scoped main key. The legacy `main`
    // alias is resolved by the gateway's mutable default agent and cannot be routed immutably.
    if (expectedOwner != null && !isCurrentComposerOwner(expectedOwner)) return false
    val routingOwner =
      resolveChatComposerRoutingOwner(
        gatewayStableId = sendCacheScope?.gatewayId,
        gatewayDefaultAgentId = sessionSelection.ownerAgentId.value ?: sessionSelection.effectiveDefaultAgentId(),
        sessionKey = effectiveSessionKey,
        mainSessionKey = sessionSelection.mainKey,
      )
        ?: return false
    if (expectedOwner != null && expectedOwner != routingOwner) return false
    val capturedOwner = expectedOwner ?: routingOwner
    if (stops.blocksSend(capturedOwner)) return false
    val stopRevision = stops.revision(capturedOwner)
    val sendLease = captureRequestLease(sendCacheScope)
    val tracksDefaultAgent = sessionSelection.tracksDefaultAgent(effectiveSessionKey)
    val sendDefaultAgentRevision = currentDefaultAgentRevision()

    fun isCapturedOwnerCurrent(): Boolean = capturedOwner.matches(currentCacheScope(), sessionSelection.key.value)

    fun ownsCapturedUi(): Boolean =
      sessionSelection.generation.value == sendSelectionGeneration &&
        (!tracksDefaultAgent || currentDefaultAgentRevision() == sendDefaultAgentRevision) &&
        isCapturedOwnerCurrent()

    // Session settings and sends share one ordering boundary; the first post-selection turn
    // must not leave with stale model or thinking state while sessions.patch is in flight.
    if (!sessionSettings.awaitPending(sessionKey)) return false
    if (!ownsCapturedUi()) return false
    drafts.find(sendGatewayId, effectiveSessionKey)?.takeIf { it.isLocal }?.let { draft ->
      // No Session or message is created by New Chat, model selection, or a reconnect.
      if (!_healthOk.value) return false
      val preparedDisplayName =
        sessionTitlePreparation.take(
          ChatSessionTitleCandidate(
            target = draft.intent.target,
            catalogRevision = currentGatewayCatalogRevision(),
            message = trimmed,
            modelRef = draft.intent.modelRef,
          ),
        )
      val intent = drafts.beginCreation(draft.intent.target, preparedDisplayName) ?: return false
      try {
        val ref = createDraftSession(intent) ?: return false
        if (!drafts.confirm(intent, ref)) return false
      } finally {
        drafts.unconfirmed(intent)
      }
      if (!ownsCapturedUi()) return false
    }
    if (drafts.find(sendGatewayId, effectiveSessionKey)?.createdRef != null && transcript.sessionId.value == null) {
      // Read-only creation recovery establishes the real Session before enqueue.
      historySync.bootstrap(effectiveSessionKey, transcript.generation, forceHealth = true, refreshSessions = true)
      if (!_healthOk.value || !ownsCapturedUi()) return false
    }
    if (_healthOk.value && !prepareSessionSend(sendGatewayId, effectiveSessionKey, capturedOwner.agentId)) return false
    if (!ownsCapturedUi() || stops.blocksSend(capturedOwner) || stops.revision(capturedOwner) != stopRevision) return false
    // agent-command.ts throws for explicit unsupported levels, so hidden controls must send off.
    // Applied at enqueue time too so durable rows never persist a level the selected model
    // rejects; reconnect flushes with a cleared catalog fail open, matching pre-gating behavior.
    val thinking =
      if (sessionSettings.supportsThinking(currentModelCatalog())) {
        normalizeChatThinking(thinkingLevel)
      } else {
        "off"
      }
    val text = if (trimmed.isEmpty() && sendAttachments.isNotEmpty()) "See attached." else trimmed

    return delivery.send(
      ChatDeliveryRequest(
        origin =
          ChatDeliveryOrigin(
            connection = sendCacheScope,
            owner = capturedOwner,
            selectionGeneration = sendSelectionGeneration,
            defaultAgentRevision = sendDefaultAgentRevision.takeIf { tracksDefaultAgent },
          ),
        sessionKey = sessionKey,
        text = text,
        thinking = thinking,
        attachments = sendAttachments,
        idempotencyKey = idempotencyKey,
        lease = sendLease,
        stopRevision = stopRevision,
      ),
    )
  }

  private fun ChatComposerOwner.matches(
    cacheScope: ChatCacheScope?,
    sessionKey: String,
  ): Boolean =
    this ==
      resolveChatComposerRoutingOwner(
        gatewayStableId = cacheScope?.gatewayId,
        gatewayDefaultAgentId = sessionSelection.ownerAgentId.value ?: sessionSelection.effectiveDefaultAgentId(),
        sessionKey = sessionKey,
        mainSessionKey = sessionSelection.mainKey,
      )

  private fun currentChatComposerRoutingOwner(): ChatComposerOwner? =
    resolveChatComposerRoutingOwner(
      gatewayStableId = currentCacheScope()?.gatewayId,
      gatewayDefaultAgentId = sessionSelection.ownerAgentId.value ?: sessionSelection.effectiveDefaultAgentId(),
      sessionKey = sessionSelection.key.value,
      mainSessionKey = sessionSelection.mainKey,
    )

  private fun currentSelectedSession(): ChatSessionEntry? = sessionCatalog.entries.value.firstOrNull { it.key == sessionSelection.key.value }

  private fun advertisedRunIds(session: ChatSessionEntry? = currentSelectedSession()): List<String> =
    session
      ?.activeRunIds
      .orEmpty()
      .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
      .distinct()

  private fun publishRunPresentation() {
    val localRunIds = pendingSends.visibleRunIds
    val session = currentSelectedSession()
    runState.publish(
      ChatRunSnapshot(
        sessionKey = sessionSelection.key.value,
        localRunIds = localRunIds,
        advertisedRunIds = advertisedRunIds(session),
        hasAdvertisedRun = session?.hasActiveRun == true,
        startedAt = session?.startedAt,
        localClockKeys = localRunIds.associateWith(pendingSends::clockKey),
      ),
    )
  }

  private fun pruneRunTelemetryToAuthoritativeOwnership() {
    val session = currentSelectedSession() ?: return
    if (!session.hasActiveRunMetadata) return
    val localRunIds = pendingSends.visibleRunIds
    runState.prune(localRunIds + advertisedRunIds(session))
  }

  private fun sameOutboxSession(
    left: String,
    right: String,
  ): Boolean = sessionSelection.normalizeKey(left) == sessionSelection.normalizeKey(right)

  /** Captures identity synchronously; no suspended Stop reads the newly selected Chat. */
  fun abort() {
    val connection = currentCacheScope() ?: return
    val owner = currentChatComposerRoutingOwner() ?: return
    val sessionId = transcript.sessionId.value ?: currentSelectedSession()?.sessionId ?: return
    val runs = pendingSends.visibleRunIds + advertisedRunIds()
    stops.stop(ChatStopTarget(owner, sessionId, connection, runs))
  }

  /** Resolves a trusted Gateway run without consulting or changing foreground selection. */
  internal fun captureRunStopTarget(
    gatewayStableId: String,
    sessionKey: String,
    runId: String,
  ): ChatStopTarget? {
    val normalizedSessionKey = sessionKey.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedRunId = runId.trim().takeIf(String::isNotEmpty) ?: return null
    val connection = currentCacheScope()?.takeIf { it.gatewayId == gatewayStableId } ?: return null
    val owner =
      resolveChatComposerRoutingOwner(
        gatewayStableId = connection.gatewayId,
        gatewayDefaultAgentId = currentDefaultAgentId(),
        sessionKey = normalizedSessionKey,
        mainSessionKey = sessionSelection.mainKey,
      ) ?: return null
    val row = sessionCatalog.entry(normalizedSessionKey)
    row?.ownerAgentId?.let { if (it != owner.agentId) return null }
    val canonicalSessionId =
      row?.sessionId
        ?: transcript.sessionId.value.takeIf { sessionSelection.key.value == normalizedSessionKey }
        ?: return null
    return ChatStopTarget(
      owner = owner,
      sessionId = canonicalSessionId,
      connection = connection,
      runIds = setOf(normalizedRunId),
    )
  }

  internal fun stopCapturedRun(target: ChatStopTarget): Boolean = stops.stop(target)

  fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    when (event) {
      "tick" -> {
        if (historySync.recovering) {
          historySync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
        } else {
          scope.launch { pollHealthIfNeeded(force = false) }
        }
      }
      "health" -> {
        refreshQuestions()
        refreshProgressCard()
        if (historySync.recovering) {
          historySync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
        } else {
          markHealthOk()
          refreshCommandsAfterReconnect()
        }
      }
      "seqGap" -> {
        // Metadata notifications can be dropped too, even when history and health remain current.
        refreshCommands()
        // Missed events can hide terminal state or usage for any active run.
        // Keep ownership, discard incomplete telemetry, and recover from snapshots.
        runState.invalidateIncomplete()
        publishRunPresentation()
        clearLiveRunUi()
        refreshQuestions()
        refreshProgressCard()
        historySync.refreshForRecovery()
      }
      "progressCard.changed" -> {
        if (payloadJson.isNullOrBlank()) return
        handleProgressCardChanged(payloadJson)
      }
      "chat" -> {
        if (payloadJson.isNullOrBlank()) return
        handleChatEvent(payloadJson)
      }
      "chat.metadata.changed" -> refreshCommands()
      "sessions.changed" -> {
        if (payloadJson.isNullOrBlank()) {
          refreshSessionsForCurrentWindow()
        } else {
          handleSessionsChangedEvent(payloadJson)
        }
      }
      "session.observer" -> {
        if (payloadJson.isNullOrBlank()) return
        handleSessionObserverEvent(payloadJson)
      }
      "session.message" -> {
        if (payloadJson.isNullOrBlank()) return
        handleSessionMessageEvent(payloadJson)
      }
      "agent" -> {
        if (payloadJson.isNullOrBlank()) return
        handleAgentEvent(payloadJson)
      }
      "question.requested" -> {
        if (payloadJson.isNullOrBlank()) return
        handleQuestionRequested(payloadJson)
      }
      "question.resolved" -> {
        if (payloadJson.isNullOrBlank()) return
        handleQuestionResolved(payloadJson)
      }
    }
  }

  fun updateQuestionDraft(
    expected: ChatQuestionPrompt,
    update: (ChatQuestionDraft) -> ChatQuestionDraft,
  ) {
    synchronized(questionStateLock) {
      // Draft edits use the current value, but only the same live question may receive them.
      // They do not invalidate an in-flight question.list reconciliation.
      _questions.value =
        _questions.value.map { prompt ->
          if (prompt.promptOwner === expected.promptOwner && prompt.record == expected.record && prompt.status() == ChatQuestionStatus.Pending) {
            prompt.copy(draft = update(prompt.draft))
          } else {
            prompt
          }
        }
    }
  }

  fun resolveQuestion(
    expected: ChatQuestionPrompt,
    answers: Map<String, List<String>>,
  ) = resolveQuestion(expected = expected, answers = answers, cancel = false)

  fun skipQuestion(expected: ChatQuestionPrompt) = resolveQuestion(expected = expected, answers = null, cancel = true)

  private fun resolveQuestion(
    expected: ChatQuestionPrompt,
    answers: Map<String, List<String>>?,
    cancel: Boolean,
  ) {
    val gatewayId = currentCacheScope()?.gatewayId
    var claimedOwner: Any? = null
    var allowedHosts: List<String>? = null
    updateQuestions { prompts ->
      prompts.map { prompt ->
        if (prompt.promptOwner === expected.promptOwner && prompt.status() == ChatQuestionStatus.Pending) {
          claimedOwner = prompt.promptOwner
          allowedHosts = prompt.draft.secretStoreAllowedHosts(prompt.record.questions)
          prompt.copy(submitting = true, skipping = cancel, errorText = null)
        } else {
          prompt
        }
      }
    }
    // updateQuestions owns the question-state lock, so competing answer/skip callbacks
    // observe the first claim as Submitting and cannot launch a second mutation.
    // Terminal fanout preserves this owner; replacement or gateway retirement does not.
    val owner = claimedOwner ?: return
    scope.launch {
      try {
        val params =
          buildJsonObject {
            put("id", JsonPrimitive(expected.record.id))
            if (cancel) {
              put("cancel", JsonPrimitive(true))
            } else {
              allowedHosts?.let { put("secretStoreAllowedHosts", JsonArray(it.map(::JsonPrimitive))) }
              put(
                "answers",
                buildJsonObject {
                  put(
                    "answers",
                    buildJsonObject {
                      answers.orEmpty().forEach { (questionId, values) ->
                        put(questionId, JsonArray(values.map(::JsonPrimitive)))
                      }
                    },
                  )
                },
              )
            }
          }
        val result = json.parseToJsonElement(requestGatewayBound(gatewayId, "question.resolve", params.toString())).jsonObject
        val status = if (cancel) "cancelled" else "answered"
        check(result["status"].asStringOrNull() == status) { "Invalid question.resolve response" }
        // The Gateway owns normalized answers, including secret-store markers; never retain submitted values as the outcome.
        val resolvedAnswers = if (cancel) null else json.decodeFromJsonElement<QuestionAnswers>(result.getValue("answers"))
        updateQuestions { prompts ->
          prompts.map { prompt ->
            if (prompt.promptOwner === owner) {
              prompt.copy(
                record =
                  prompt.record.copy(
                    status = status,
                    answers = resolvedAnswers,
                  ),
                submitting = false,
                skipping = false,
                answeredLocally = !cancel,
                recoveryUnavailable = false,
                terminalObservedAtMs = prompt.terminalObservedAtMs ?: System.currentTimeMillis(),
              )
            } else {
              prompt
            }
          }
        }
      } catch (error: Throwable) {
        updateQuestions { prompts ->
          prompts.map { prompt ->
            if (prompt.promptOwner === owner) {
              prompt.copy(submitting = false, skipping = false, errorText = error.message ?: "Question failed")
            } else {
              prompt
            }
          }
        }
      }
    }
  }

  private fun refreshQuestions() {
    val gatewayScope = currentCacheScope()
    val refreshGeneration =
      synchronized(questionStateLock) {
        questionRefreshGeneration += 1
        questionRefreshGeneration
      }
    scope.launch {
      var retryIndex = 0
      var retryStateRevision: Long? = null
      while (true) {
        val expectedStateRevision = questionRefreshCurrentRevision(refreshGeneration, gatewayScope) ?: return@launch
        if (retryStateRevision != expectedStateRevision) {
          retryStateRevision = expectedStateRevision
          retryIndex = 0
        }
        val complete =
          runCatching {
            refreshQuestions(refreshGeneration, expectedStateRevision, gatewayScope)
          }.getOrDefault(false)
        if (complete) return@launch
        val currentStateRevision = questionRefreshCurrentRevision(refreshGeneration, gatewayScope) ?: return@launch
        if (currentStateRevision != expectedStateRevision) {
          // A local mutation invalidates the whole lookup snapshot, not one transport attempt.
          // Restart the bounded budget so the last attempt cannot strand another question.
          retryStateRevision = currentStateRevision
          retryIndex = 0
        }
        val retryDelayMs = QUESTION_REFRESH_RETRY_DELAYS_MS.getOrNull(retryIndex) ?: return@launch
        retryIndex += 1
        delay(retryDelayMs)
      }
    }
  }

  private suspend fun refreshQuestions(
    refreshGeneration: Long,
    stateRevision: Long,
    gatewayScope: ChatCacheScope?,
  ): Boolean {
    val response =
      if (gatewayAdvertisesMethod("question.list") == false) {
        null
      } else {
        try {
          requestGatewayBound(gatewayScope?.gatewayId, "question.list", "{}")
        } catch (err: GatewayRequestRejected) {
          val unavailable =
            err.gatewayError.missingScope() == "operator.questions" ||
              (
                err.gatewayError.code == "INVALID_REQUEST" &&
                  err.gatewayError.message == "unknown method: question.list"
              )
          if (!unavailable) throw err
          null
        }
      }
    if (response == null) {
      if (!questionRefreshIsCurrent(refreshGeneration, stateRevision, gatewayScope)) return false
      return synchronized(questionStateLock) {
        if (!questionRefreshIsCurrentLocked(refreshGeneration, stateRevision)) return@synchronized false
        publishQuestionsLocked(emptyList())
        true
      }
    }
    if (!questionRefreshIsCurrent(refreshGeneration, stateRevision, gatewayScope)) return false
    val listedRecords = json.decodeFromString<QuestionListResult>(response).questions
    val listedIds = listedRecords.mapTo(mutableSetOf()) { it.id }
    val missingPendingRecords =
      synchronized(questionStateLock) {
        if (!questionRefreshIsCurrentLocked(refreshGeneration, stateRevision)) return false
        _questions.value
          .filter { prompt ->
            prompt.record.id !in listedIds &&
              prompt.record.status == "pending" &&
              !prompt.recoveryUnavailable
          }.map { it.record }
      }
    val fallbackRecords = mutableListOf<QuestionRecord>()
    val unresolvedIds = mutableSetOf<String>()
    val unavailableIds = mutableSetOf<String>()
    for (record in missingPendingRecords) {
      val params = buildJsonObject { put("id", JsonPrimitive(record.id)) }
      try {
        val fallback = requestGatewayBound(gatewayScope?.gatewayId, "question.get", params.toString())
        fallbackRecords += json.decodeFromString<QuestionGetResult>(fallback).question
      } catch (err: CancellationException) {
        throw err
      } catch (err: GatewayRequestRejected) {
        if (err.gatewayError.details?.reason == "QUESTION_NOT_FOUND") {
          // The terminal tombstone has aged out, so the question is no longer actionable,
          // but its answered/cancelled/expired outcome cannot be reconstructed.
          unavailableIds += record.id
        } else {
          unresolvedIds += record.id
        }
      } catch (_: Throwable) {
        unresolvedIds += record.id
      }
    }
    if (!questionRefreshIsCurrent(refreshGeneration, stateRevision, gatewayScope)) return false
    val records = listedRecords + fallbackRecords.filter { it.id !in listedIds }
    return synchronized(questionStateLock) {
      if (!questionRefreshIsCurrentLocked(refreshGeneration, stateRevision)) return@synchronized false
      val current = _questions.value
      val existing = current.associateBy { it.record.id }
      val nowMs = System.currentTimeMillis()
      val refreshedIds = records.mapTo(mutableSetOf()) { it.id }
      val retainedCandidates =
        current
          .filter { prompt ->
            val status = prompt.status(nowMs)
            prompt.record.id !in refreshedIds &&
              (
                prompt.record.id in unresolvedIds ||
                  prompt.record.id in unavailableIds ||
                  (
                    status != ChatQuestionStatus.Pending &&
                      status != ChatQuestionStatus.Submitting
                  )
              )
          }
      val retainedPrompts =
        retainedCandidates.map { prompt ->
          if (prompt.record.id in unavailableIds) {
            prompt.copy(
              submitting = false,
              skipping = false,
              terminalObservedAtMs = prompt.terminalObservedAtMs ?: nowMs,
              recoveryUnavailable = true,
            )
          } else {
            prompt
          }
        }
      val next =
        records.map { record ->
          existing[record.id]?.let { prompt ->
            mergeQuestionPrompt(prompt, record, nowMs)
          } ?: ChatQuestionPrompt(
            record = record,
            terminalObservedAtMs = nowMs.takeIf { record.status != "pending" || nowMs >= record.expiresAtMs },
          )
        } + retainedPrompts
      publishQuestionsLocked(next)
      unresolvedIds.isEmpty()
    }
  }

  private fun questionRefreshCurrentRevision(
    refreshGeneration: Long,
    gatewayScope: ChatCacheScope?,
  ): Long? {
    if (gatewayScope != currentCacheScope()) return null
    return synchronized(questionStateLock) {
      questionStateRevision.takeIf { refreshGeneration == questionRefreshGeneration }
    }
  }

  private fun questionRefreshIsCurrent(
    refreshGeneration: Long,
    stateRevision: Long,
    gatewayScope: ChatCacheScope?,
  ): Boolean =
    gatewayScope == currentCacheScope() &&
      synchronized(questionStateLock) { questionRefreshIsCurrentLocked(refreshGeneration, stateRevision) }

  private fun questionRefreshIsCurrentLocked(
    refreshGeneration: Long,
    stateRevision: Long,
  ): Boolean = refreshGeneration == questionRefreshGeneration && stateRevision == questionStateRevision

  private fun handleQuestionRequested(payloadJson: String) {
    val record = runCatching { json.decodeFromString<QuestionRecord>(payloadJson) }.getOrNull() ?: return
    updateQuestions { prompts ->
      if (prompts.any { it.record.id == record.id }) {
        prompts.map { prompt ->
          if (prompt.record.id == record.id) {
            mergeQuestionPrompt(prompt, record, System.currentTimeMillis())
          } else {
            prompt
          }
        }
      } else {
        prompts + ChatQuestionPrompt(record)
      }
    }
    refreshQuestions()
  }

  private fun mergeQuestionPrompt(
    prompt: ChatQuestionPrompt,
    record: QuestionRecord,
    nowMs: Long,
  ): ChatQuestionPrompt {
    // Gateway terminal state is monotonic. A delayed requested/list replay must not
    // make an already resolved question actionable again.
    if ((prompt.record.status != "pending" || prompt.recoveryUnavailable) && record.status == "pending") return prompt
    // Outcome fields can change within one request; a replaced definition or lifetime owns new input.
    val sameQuestion = record.copy(status = prompt.record.status, answers = prompt.record.answers, resolvedBy = prompt.record.resolvedBy) == prompt.record
    if (!sameQuestion) {
      return ChatQuestionPrompt(record = record, terminalObservedAtMs = nowMs.takeIf { record.status != "pending" || nowMs >= record.expiresAtMs })
    }
    return prompt.copy(
      record = record.copy(answers = record.answers ?: prompt.record.answers),
      submitting = prompt.submitting && record.status == "pending",
      skipping = prompt.skipping && record.status == "pending",
      answeredLocally = prompt.answeredLocally && record.status == "answered",
      recoveryUnavailable = false,
      terminalObservedAtMs =
        if (record.status == "pending" && nowMs < record.expiresAtMs) {
          null
        } else {
          prompt.terminalObservedAtMs ?: nowMs
        },
    )
  }

  private fun handleQuestionResolved(payloadJson: String) {
    val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
    val id = payload["id"].asStringOrNull() ?: return
    val status = payload["status"].asStringOrNull() ?: return
    val answers = payload["answers"]?.let { runCatching { json.decodeFromJsonElement<QuestionAnswers>(it) }.getOrNull() }
    val nowMs = System.currentTimeMillis()
    updateQuestions { prompts ->
      prompts.map { prompt ->
        if (prompt.record.id == id) {
          prompt.copy(
            record = prompt.record.copy(status = status, answers = answers ?: prompt.record.answers),
            submitting = false,
            skipping = false,
            recoveryUnavailable = false,
            terminalObservedAtMs = prompt.terminalObservedAtMs ?: nowMs,
          )
        } else {
          prompt
        }
      }
    }
    refreshQuestions()
  }

  private fun updateQuestions(transform: (List<ChatQuestionPrompt>) -> List<ChatQuestionPrompt>) {
    synchronized(questionStateLock) {
      publishQuestionsLocked(transform(_questions.value))
    }
  }

  private fun publishQuestionsLocked(prompts: List<ChatQuestionPrompt>): Boolean {
    // Terminal cards remain in history, but must not retain unsent (possibly secret) input.
    val next =
      prompts.map { prompt ->
        when (prompt.status()) {
          ChatQuestionStatus.Pending, ChatQuestionStatus.Submitting -> prompt
          else -> prompt.copy(draft = ChatQuestionDraft())
        }
      }
    val changed = next != _questions.value
    if (changed) {
      _questions.value = next
      questionStateRevision += 1
    }
    syncQuestionEvictionsLocked()
    return changed
  }

  private fun syncQuestionEvictionsLocked(nowMs: Long = System.currentTimeMillis()) {
    val currentById = _questions.value.associateBy { it.record.id }
    questionEvictionJobs.entries.removeAll { (id, scheduled) ->
      val prompt = currentById[id]
      if (prompt == null || scheduled.observedAtMs != prompt.terminalObservedAtMs) {
        scheduled.job.cancel()
        true
      } else {
        false
      }
    }
    for (prompt in _questions.value) {
      if (questionEvictionJobs.containsKey(prompt.record.id)) continue
      val id = prompt.record.id
      val observedAt = prompt.terminalObservedAtMs
      if (observedAt != null || prompt.record.status != "pending" || prompt.record.expiresAtMs == Long.MAX_VALUE) continue
      val remainingMs = (prompt.record.expiresAtMs - nowMs).coerceAtLeast(0)
      val job =
        scope.launch(start = CoroutineStart.LAZY) {
          delay(remainingMs)
          var shouldRefresh = false
          synchronized(questionStateLock) {
            questionEvictionJobs.remove(id)
            val current = _questions.value
            val next =
              current.map {
                if (it.record.id == id && it.record.status == "pending" && it.terminalObservedAtMs == null) {
                  it.copy(terminalObservedAtMs = System.currentTimeMillis())
                } else {
                  it
                }
              }
            shouldRefresh = publishQuestionsLocked(next)
          }
          // The local deadline is only a presentation fallback. Reconcile outside
          // the state lock in case another surface supplied the terminal outcome.
          if (shouldRefresh) refreshQuestions()
        }
      questionEvictionJobs[id] = QuestionEvictionJob(job, observedAt)
      job.start()
    }
  }

  private fun clearQuestions() {
    synchronized(questionStateLock) {
      questionRefreshGeneration += 1
      publishQuestionsLocked(emptyList())
    }
  }

  private suspend fun awaitMainSessionReadiness(
    sessionKey: String,
    requestScope: ChatCacheScope?,
  ) {
    val readiness =
      synchronized(mainSessionReadinessLock) {
        mainSessionReadiness
          ?.takeIf { state ->
            state.gatewayScope == requestScope && state.binding.key == sessionKey
          }?.ready
      }
    readiness?.await()
  }

  private suspend fun persistSessions(
    requestCacheScope: ChatCacheScope?,
    agentId: String,
    sessions: List<ChatSessionEntry>,
    retainedSessionKey: String?,
  ) {
    val cache = transcriptCache ?: return
    val capturedScope = requestCacheScope ?: return
    cacheMutationMutex.withLock {
      if (capturedScope != currentCacheScope()) return@withLock
      runCatching { cache.saveSessions(capturedScope.gatewayId, agentId, sessions, retainedSessionKey) }
    }
  }

  private fun currentSessionWindowLimit(): Int =
    sessionCatalog.entries.value.size
      .takeIf { it > 0 } ?: 100

  private suspend fun fetchSessionsForCurrentWindow(): Boolean = sessionCatalog.refresh(limit = currentSessionWindowLimit(), archived = sessionCatalog.isArchived)

  private fun refreshSessionsForCurrentWindow() {
    scope.launch { fetchSessionsForCurrentWindow() }
  }

  private fun refreshSessionsAfterAmbiguousDelete(sessionKey: String) {
    val retiredKey = sessionSelection.normalizeKey(sessionKey)
    val requestCacheScope = currentCacheScope()
    val retiredOwner = sessionSelection.resolveOwner(retiredKey)
    val wasVisible = sessionCatalog.entries.value.any { it.key == retiredKey }
    val requestArchived = sessionCatalog.isArchived
    val requestMainSessionKey = sessionSelection.mainKey
    scope.launch {
      ambiguousDeleteReconciliationMutex.withLock {
        if (requestCacheScope == null || retiredOwner == null || requestCacheScope != currentCacheScope()) return@withLock
        val result = fetchSessionsSnapshotForOwner(requestCacheScope, retiredOwner, requestArchived) ?: return@withLock
        if (requestCacheScope != currentCacheScope()) return@withLock
        // A truncated result cannot prove absence. Preserve all local state until a complete
        // owner-scoped snapshot confirms that the formerly visible key is gone.
        val removalConfirmed = wasVisible && !result.isTruncated && result.sessions.none { it.key == retiredKey }
        if (!removalConfirmed) return@withLock
        purgeSessionOwnedState(
          retiredKey,
          retiredOwner,
          requestCacheScope,
          mainSessionKey = requestMainSessionKey,
        )
        if (sessionCatalog.remove(retiredKey, requestCacheScope, retiredOwner)) {
          fallBackFromRetiredActiveSession(retiredKey)
        }
      }
    }
  }

  private suspend fun fetchSessionsSnapshotForOwner(
    requestCacheScope: ChatCacheScope,
    ownerAgentId: String,
    archived: Boolean,
  ): ChatSessionListResult? =
    try {
      val params =
        buildJsonObject {
          put("includeGlobal", JsonPrimitive(true))
          put("includeUnknown", JsonPrimitive(false))
          put("agentId", JsonPrimitive(ownerAgentId))
          put("limit", JsonPrimitive(SESSION_RECONCILIATION_FETCH_LIMIT))
          if (archived) put("archived", JsonPrimitive(true))
        }
      sessionCodec.parseList(requestGatewayBound(requestCacheScope.gatewayId, "sessions.list", params.toString()))
    } catch (err: CancellationException) {
      throw err
    } catch (_: Throwable) {
      null
    }

  private suspend fun pollHealthIfNeeded(force: Boolean) {
    val requestCacheScope = currentCacheScope()
    val now = System.currentTimeMillis()
    val last = lastHealthPollAtMs
    if (!force && last != null && now - last < 10_000) return
    lastHealthPollAtMs = now
    try {
      requestGatewayBound(requestCacheScope?.gatewayId, "health", null)
      if (requestCacheScope != currentCacheScope()) return
      markHealthOk()
      if (!metadata.isLoaded()) {
        metadata.refreshAwait()
      }
    } catch (_: Throwable) {
      if (requestCacheScope == currentCacheScope()) {
        _healthOk.value = false
      }
    }
  }

  // Gateway-health transition is the single reconnect trigger for the outbox flush; it avoids a
  // second reachability source (ConnectivityManager) that could disagree with gateway state.
  private fun markHealthOk() {
    val wasOk = _healthOk.value
    _healthOk.value = true
    if (!wasOk && commandOutbox != null) {
      delivery.requestFlush()
    }
  }

  /** Readiness wakes the queue; each row still passes its own permission and ownership gate. */
  internal fun onSessionPermissionsReady() {
    if (_healthOk.value) delivery.requestFlush()
  }

  private fun refreshCommandsAfterReconnect() {
    if (metadata.isLoaded()) return
    refreshCommands()
  }

  /** Re-queues a failed outbox item and flushes immediately when the gateway is healthy. */
  fun retryOutboxCommand(id: String) {
    val outboxScope = currentCacheScope() ?: return
    val row = outboxState.items.value.firstOrNull { it.id == id } ?: return
    val retryOwnerAgentId = row.ownerAgentId ?: resolveAgentIdFromMainSessionKey(row.sessionKey) ?: return
    val owner = ChatComposerOwner(outboxScope.gatewayId, retryOwnerAgentId, row.sessionKey)
    if (stops.blocksSend(owner)) return
    val stopRevision = stops.revision(owner)
    scope.launch {
      deliveryJournal.retry(row, outboxScope, retryOwnerAgentId) {
        currentCacheScope() == outboxScope && !stops.blocksSend(owner) && stops.revision(owner) == stopRevision
      }
    }
  }

  fun deleteOutboxCommand(id: String) {
    scope.launch { deliveryJournal.delete(id) }
  }

  private fun handleChatEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    val runId = payload["runId"].asStringOrNull()
    val state = payload["state"].asStringOrNull()
    val projection = runId?.let(pendingSends::projection)
    if (!sessionKey.isNullOrEmpty() && sessionKey != sessionSelection.key.value) {
      if (state == "final" || state == "aborted" || state == "error") {
        handleInactiveChatTerminal(
          payload = payload,
          runId = runId,
          owner = resolveChatEventRoutingOwner(sessionKey, projection),
        )
      }
      return
    }

    if (projection != null && projection.owner != currentChatComposerRoutingOwner()) {
      if (state == "final" || state == "aborted" || state == "error") {
        handleInactiveChatTerminal(
          payload = payload,
          runId = runId,
          owner = resolveChatEventRoutingOwner(sessionKey, projection),
        )
      }
      return
    }
    val isPending =
      if (runId != null) pendingSends.isPending(runId) else true
    val isOwned = isPending || (runId != null && pendingSends.hasReply(runId))

    when (state) {
      "delta" -> {
        // Only show streaming text for runs we initiated in this controller.
        val answerRunId = runId?.trim()?.takeIf(String::isNotEmpty) ?: return
        if (!isPending) return
        val text = parseAssistantDeltaText(payload)
        if (!text.isNullOrEmpty()) {
          _answerDraft.value = ChatAssistantAnswerDraft(answerRunId, text)
        }
      }
      "final", "aborted", "error" -> {
        val terminalHasAssistantMessage =
          state == "final" && payload["message"].asObjectOrNull()?.get("role").asStringOrNull() == "assistant"
        val resolvesWithoutReply = state != "final" || !terminalHasAssistantMessage
        val wasTimedOut = runId != null && pendingSends.consumeTimeout(runId)
        if (runId != null && runId == lastHandledTerminalRunId) return
        if (runId != null && !isOwned && !wasTimedOut) {
          val hasLocalRun =
            pendingSends.visibleRunIds.isNotEmpty() || pendingSends.replyRunIds.isNotEmpty()
          if (!hasLocalRun) {
            // Another client or chat.inject can finish the open session. Refresh
            // idle history without allowing its terminal state to own local UI.
            lastHandledTerminalRunId = runId
            publishAssistantReplyFinalized(
              payload = payload,
              runId = runId,
              owner = currentChatComposerRoutingOwner(),
            )
            historySync.refreshCurrent(updateSessionInfo = true)
          }
          return
        }
        if (runId != null) {
          lastHandledTerminalRunId = runId
          runState.retire(runId)
        }
        publishAssistantReplyFinalized(
          payload = payload,
          runId = runId,
          owner = currentChatComposerRoutingOwner(),
        )
        if (wasTimedOut) {
          val hasNewerRun =
            pendingSends.visibleRunIds.isNotEmpty() || pendingSends.replyRunIds.isNotEmpty()
          if (!hasNewerRun) {
            clearLiveRunUi()
            updateLocalizedErrorText(
              if (state == "error") {
                payload["errorMessage"].asStringOrNull()?.let(::verbatimText) ?: nativeText("Chat failed")
              } else {
                null
              },
            )
          }
          publishRunPresentation()
          historySync.refreshCurrent(updateSessionInfo = true)
          return
        }
        if (runId != null && !isPending) {
          if (resolvesWithoutReply) pendingSends.markTerminalWithoutReply(runId)
          publishRunPresentation()
          historySync.refreshCurrent(
            runIdsToReconcile = setOf(runId),
            updateSessionInfo = true,
          )
          return
        }
        if (state == "error") {
          updateLocalizedErrorText(payload["errorMessage"].asStringOrNull()?.let(::verbatimText) ?: nativeText("Chat failed"))
        }
        val terminalRunIds =
          runId?.let(::setOf)
            ?: (pendingSends.visibleRunIds + pendingSends.replyRunIds)
        if (runId != null) {
          pendingSends.clear(runId)
          if (resolvesWithoutReply) {
            pendingSends.markTerminalWithoutReply(runId)
          }
        } else {
          terminalRunIds.forEach(runState::retire)
          pendingSends.clearAll(clearOptimisticMessages = false, clearRunTelemetry = false)
        }
        clearLiveRunUi()
        historySync.refreshCurrent(
          runIdsToReconcile = terminalRunIds,
          updateSessionInfo = true,
        )
      }
    }
  }

  private fun handleSessionsChangedEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val reason = payload["reason"].asStringOrNull()
    val phase = payload["phase"].asStringOrNull()
    if (reason == "patch" || reason == "command-metadata" || reason == "reset" || payload["phase"].asStringOrNull() == "reset") {
      val session = eventSessionObject(payload)
      val key = payload["sessionKey"].asStringOrNull() ?: session?.get("key").asStringOrNull()
      val agentId =
        key?.let(::resolveAgentIdFromMainSessionKey)
          ?: payload["agentId"].asStringOrNull()
          ?: session?.get("ownerAgentId").asStringOrNull()
      // Profile-only mutations do not change global credentials or the visible session key.
      if (metadata.matchesSession(key, agentId)) refreshCommands()
    }
    if (reason == "delete") {
      val sessionKey = payload["sessionKey"].asStringOrNull() ?: payload["key"].asStringOrNull()
      val ownerAgentId = payload["agentId"].asStringOrNull()
      if (removeSessionEntry(sessionKey, ownerAgentId = ownerAgentId)) {
        sessionKey?.let(::fallBackFromRetiredActiveSession)
      } else if (sessionKey != null && resolveAgentIdFromMainSessionKey(sessionKey) == null && ownerAgentId == null) {
        // Older gateways omitted the owner for ambiguous keys. Refresh visible state, but do
        // not guess which agent's durable cache/outbox should be destroyed.
        refreshSessionsAfterAmbiguousDelete(sessionKey)
      }
      return
    }
    applySessionEvent(payload, refreshWhenMissing = true)
    // Terminal lifecycle events are broadcast only after the Gateway commits the
    // transcript. Their lightweight Session snapshot intentionally omits transcript-
    // derived fields, so refresh the enriched catalog at this authoritative boundary.
    if (phase == "end" || phase == "error") refreshSessionsForCurrentWindow()
  }

  private fun handleSessionObserverEvent(payloadJson: String) {
    val digest = runCatching { json.decodeFromString<SessionObserverDigest>(payloadJson) }.getOrNull() ?: return
    sessionCatalog.applyObserver(digest)
  }

  private fun handleSessionMessageEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    applySessionEvent(payload, refreshWhenMissing = false)
  }

  private fun applySessionEvent(
    payload: JsonObject,
    refreshWhenMissing: Boolean,
  ) {
    val eventObject = eventSessionObject(payload)
    val entry = eventObject?.let(sessionCodec::parseEntry)
    if (entry == null) {
      if (refreshWhenMissing) refreshSessionsForCurrentWindow()
      return
    }
    val eventOwner =
      resolveAgentIdFromMainSessionKey(entry.key)
        ?: entry.ownerAgentId
        ?: payload["agentId"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val visibleOwner = sessionSelection.resolveOwner(sessionSelection.key.value)
    // Session keys can collide across agents. Never merge an ownerless or foreign event into
    // the visible agent-scoped snapshot; an authoritative refresh resolves ambiguous payloads.
    if (eventOwner == null || visibleOwner == null) {
      refreshSessionsForCurrentWindow()
      return
    }
    if (eventOwner != visibleOwner) return
    val ownedEntry = reconcileSessionObserverProjectionOwner(entry, eventOwner)
    val phase = payload["phase"].asStringOrNull()
    val terminalRunId =
      payload["runId"]
        .asStringOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeIf { phase == "end" || phase == "error" }
    val terminalWasLocal = terminalRunId?.let(pendingSends::isVisibleOwned) == true
    val terminalWasAdvertised = terminalRunId?.let { it in advertisedRunIds() } == true
    val settlesSelectedRun = terminalRunId != null && (terminalWasLocal || terminalWasAdvertised)
    upsertSessionEntry(
      entry = if (ownedEntry.ownerAgentId == eventOwner) ownedEntry else ownedEntry.copy(ownerAgentId = eventOwner),
      clearedFields = parseExplicitSessionClears(eventObject),
      publishRunState = !settlesSelectedRun,
    )
    if (!settlesSelectedRun) return
    val settledRunId = terminalRunId
    if (!entry.hasActiveRunMetadata) runState.retire(settledRunId)
    if (terminalWasLocal) {
      pendingSends.clear(settledRunId)
      pendingSends.idleIfSettled()
    } else {
      publishRunPresentation()
    }
  }

  private fun eventSessionObject(payload: JsonObject): JsonObject? = payload["session"].asObjectOrNull() ?: payload.takeIf { it["key"].asStringOrNull() != null }

  // The gateway sends explicit JSON null for a cleared label on session
  // events; the merge must apply those clears instead of preserving stale values.
  private fun parseExplicitSessionClears(obj: JsonObject): Set<String> =
    buildSet {
      if (obj["label"] is JsonNull) add("label")
    }

  private fun ownsLiveRunTelemetry(runId: String): Boolean = pendingSends.isVisibleOwned(runId) || runId in advertisedRunIds()

  private fun parseAgentEventSequence(payload: JsonObject): Long? {
    val raw = payload["seq"] as? JsonPrimitive ?: return null
    if (raw.isString) return null
    return raw.asLongOrNull()?.takeIf { it > 0L }
  }

  private fun parsePositiveOutputTokens(data: JsonObject?): Long? {
    val raw = data?.get("outputTokens") as? JsonPrimitive ?: return null
    if (raw.isString) return null
    return raw.asLongOrNull()?.takeIf { it > 0L }
  }

  private fun handleAgentEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    if (!sessionKey.isNullOrEmpty() && sessionKey != sessionSelection.key.value) return
    val runId = payload["runId"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty)
    val projection = runId?.let(pendingSends::projection)
    if (projection != null && projection.owner != currentChatComposerRoutingOwner()) return

    val stream = payload["stream"].asStringOrNull()
    val data = payload["data"].asObjectOrNull()
    if (stream == "usage") {
      val usageRunId = runId?.takeIf(::ownsLiveRunTelemetry) ?: return
      val sequence = parseAgentEventSequence(payload) ?: return
      val outputTokens = parsePositiveOutputTokens(data) ?: return
      if (runState.recordUsage(usageRunId, sequence, outputTokens)) publishRunPresentation()
      return
    }
    if (stream == "lifecycle") {
      val phase = data?.get("phase").asStringOrNull()
      val isTerminal = phase == "end" || phase == "error"
      if (runId != null && !ownsLiveRunTelemetry(runId)) return
      if (runId == null && !isTerminal) return
      val lifecycleRunId = runId ?: pendingSends.locallyOwnedRunIds.singleOrNull() ?: return
      val sequence = parseAgentEventSequence(payload)
      when (phase) {
        "start" -> {
          val orderedSequence = sequence ?: return
          if (runState.applyLifecycle(lifecycleRunId, orderedSequence, terminal = false)) publishRunPresentation()
        }
        "end", "error" -> {
          val accepted =
            if (sequence == null) {
              runState.retire(lifecycleRunId)
              true
            } else {
              runState.applyLifecycle(lifecycleRunId, sequence, terminal = true)
            }
          if (!accepted) return
          if (pendingSends.isVisibleOwned(lifecycleRunId)) {
            pendingSends.clear(lifecycleRunId)
            pendingSends.idleIfSettled()
          } else {
            publishRunPresentation()
          }
        }
      }
      return
    }

    if (runId != null && !pendingSends.isVisibleOwned(runId)) return
    when (stream) {
      "item" -> {
        if (runId == null || !pendingSends.isVisibleOwned(runId)) return
        val itemData = data ?: return
        if (itemData["kind"].asStringOrNull() != "preamble") return
        // Gateway 9.4 permits commentary without a provider item identity. Such snapshots
        // are one replace-in-place lane for this exact run, never distinct transcript rows.
        val itemId = itemData["itemId"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: "idless-preamble"
        val text = itemData["progressText"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
        val phase =
          when (itemData["phase"].asStringOrNull()) {
            "update" -> ChatCommentaryPhase.Update
            "end" -> ChatCommentaryPhase.End
            else -> return
          }
        runActivityStore.accept(
          ChatCommentarySegment(
            runId = runId,
            itemId = itemId,
            text = text,
            timestampMs = payload["ts"].asLongOrNull(),
            sequence = parseAgentEventSequence(payload),
            phase = phase,
          ),
        )
      }
      "assistant" -> {
        val answerRunId = runId ?: return
        if (!pendingSends.isVisibleOwned(answerRunId)) return
        val text = data?.get("text")?.asStringOrNull()
        if (!text.isNullOrEmpty()) {
          _answerDraft.value = ChatAssistantAnswerDraft(answerRunId, text)
        }
      }
      "tool" -> {
        val phase = data?.get("phase")?.asStringOrNull()
        val name = data?.get("name")?.asStringOrNull()
        val toolCallId = data?.get("toolCallId")?.asStringOrNull()
        if (phase.isNullOrEmpty() || name.isNullOrEmpty() || toolCallId.isNullOrEmpty()) return

        val ts = payload["ts"].asLongOrNull() ?: System.currentTimeMillis()
        when (phase) {
          "start" -> {
            val existing = pendingToolCallsById[toolCallId]
            pendingToolCallsById[toolCallId] =
              ChatPendingToolCall(
                toolCallId = toolCallId,
                name = name,
                args = data.get("args").asObjectOrNull(),
                startedAtMs = existing?.startedAtMs ?: ts,
                isError = null,
                liveDiff = existing?.liveDiff,
              )
            publishPendingToolCalls()
          }
          "input_delta" -> {
            val diff = parseChatDiffStat(data["diff"]) ?: return
            val existing = pendingToolCallsById[toolCallId]
            pendingToolCallsById[toolCallId] =
              existing?.copy(name = name, liveDiff = diff)
                ?: ChatPendingToolCall(
                  toolCallId = toolCallId,
                  name = name,
                  startedAtMs = ts,
                  liveDiff = diff,
                )
            publishPendingToolCalls()
          }
          "result" -> {
            pendingToolCallsById.remove(toolCallId)
            publishPendingToolCalls()
          }
        }
      }
      "plan" -> {
        // Released Gateways through v2026.8.x only emit stream:"plan" and lack progressCard.get.
        // SUNSET 2026-10-18: this fallback is a fixed cutover window, not a permanent contract.
        // On that date delete it together with the Gateway's legacy stream:"plan" dual-emit and
        // the Apple twin in ChatViewModel+TransportEvents.swift. Tracked: #125639.
        if (gatewayAdvertisesMethod("progressCard.get") != false) return
        val planData = data ?: return
        if (planData["phase"].asStringOrNull() != "update") return
        val steps = parseChatPlanSteps(planData["steps"])
        if (steps.isEmpty()) {
          clearProgressCard(clearScopeKey = false)
          return
        }
        _progressCard.value =
          ChatProgressCard(
            revision = legacyProgressCardRevision.incrementAndGet(),
            updatedAt = payload["ts"].asLongOrNull() ?: 0L,
            markdown = planData["explanation"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            steps = steps,
          )
      }
      "error" -> {
        updateLocalizedErrorText(nativeText("Event stream interrupted; try refreshing."))
        if (runId == null) {
          pendingSends.clearAll()
        } else {
          pendingSends.clear(runId)
          pendingSends.idleIfSettled()
        }
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        _answerDraft.value = null
      }
    }
  }

  private fun handleInactiveChatTerminal(
    payload: JsonObject,
    runId: String?,
    owner: ChatComposerOwner?,
  ) {
    val normalizedRunId = runId?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedRunId != null && normalizedRunId != lastHandledTerminalRunId) {
      lastHandledTerminalRunId = normalizedRunId
      runState.retire(normalizedRunId)
      publishAssistantReplyFinalized(
        payload = payload,
        runId = normalizedRunId,
        owner = owner,
      )
    }
    normalizedRunId?.let(pendingSends::clear)
  }

  private fun resolveChatEventRoutingOwner(
    sessionKey: String?,
    projection: ChatPendingSend?,
  ): ChatComposerOwner? {
    val gatewayStableId = currentCacheScope()?.gatewayId
    val normalizedSessionKey = sessionKey?.trim()?.takeIf(String::isNotEmpty)
    if (projection != null) {
      return projection.owner.takeIf { owner ->
        owner.gatewayStableId == gatewayStableId &&
          (normalizedSessionKey == null || owner.sessionKey == normalizedSessionKey)
      }
    }

    val eventSessionKey =
      when (normalizedSessionKey) {
        null -> return null
        "main" -> sessionSelection.mainKey.trim().takeIf(String::isNotEmpty) ?: return null
        else -> normalizedSessionKey
      }
    return resolveChatComposerRoutingOwner(
      gatewayStableId = gatewayStableId,
      gatewayDefaultAgentId = sessionSelection.effectiveDefaultAgentId(),
      sessionKey = eventSessionKey,
      mainSessionKey = sessionSelection.mainKey,
    )
  }

  private fun publishAssistantReplyFinalized(
    payload: JsonObject,
    runId: String?,
    owner: ChatComposerOwner?,
  ) {
    if (payload["state"].asStringOrNull() != "final") return
    val normalizedRunId = runId?.trim()?.takeIf(String::isNotEmpty) ?: return
    val verifiedOwner = owner?.takeIf { it.routingVerified } ?: return
    val text = parseAssistantDeltaText(payload)?.trim()?.takeIf(String::isNotEmpty) ?: return
    runCatching { onAssistantReplyFinalized(verifiedOwner, normalizedRunId, text) }
  }

  private fun parseAssistantDeltaText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"].asArrayOrNull() ?: return null
    for (item in content) {
      val obj = item.asObjectOrNull() ?: continue
      if (obj["type"].asStringOrNull() != "text") continue
      val text = obj["text"].asStringOrNull()
      if (!text.isNullOrEmpty()) {
        return text
      }
    }
    return null
  }

  private fun publishPendingToolCalls() {
    _pendingToolCalls.value =
      pendingToolCallsById.values.sortedBy { it.startedAtMs }
  }

  private fun clearLiveRunUi() {
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    _answerDraft.value = null
    runActivityStore.clear()
  }

  private fun clearProgressCard(clearScopeKey: Boolean = true) {
    progressCardFetchGeneration.incrementAndGet()
    if (clearScopeKey) progressCardScopeKey = null
    _progressCard.value = null
  }

  private fun refreshProgressCard() {
    if (gatewayAdvertisesMethod("progressCard.get") == false) return
    val sessionKey = sessionSelection.normalizeKey(sessionSelection.key.value)
    val gatewayScope = currentCacheScope()
    val generation = progressCardFetchGeneration.incrementAndGet()
    scope.launch {
      if (generation != progressCardFetchGeneration.get()) return@launch
      try {
        val params = buildJsonObject { put("sessionKey", JsonPrimitive(sessionKey)) }
        val response = requestGatewayBound(gatewayScope?.gatewayId, "progressCard.get", params.toString())
        val parsed = parseChatProgressCardGetResult(json.parseToJsonElement(response))
        if (
          generation != progressCardFetchGeneration.get() ||
          !sameOutboxSession(sessionKey, sessionSelection.key.value) ||
          gatewayScope != currentCacheScope()
        ) {
          return@launch
        }
        parsed.sessionKey?.let { progressCardScopeKey = it }
        _progressCard.value = parsed.card
      } catch (err: CancellationException) {
        throw err
      } catch (_: Throwable) {
        // Older gateways and malformed responses quietly leave the last durable card intact.
      }
    }
  }

  private fun handleProgressCardChanged(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val eventSessionKey = payload["sessionKey"].asJsonStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (
      !sameOutboxSession(eventSessionKey, sessionSelection.key.value) &&
      eventSessionKey != progressCardScopeKey
    ) {
      // Pokes carry the server-derived observer scope key (e.g. agent:<id>:global), which the
      // client only learns from a get response carrying a card. Until then attribution is
      // unknown, so refetch — the get is authoritative and self-corrects — instead of
      // silently dropping the session's first poke.
      if (progressCardScopeKey == null) refreshProgressCard()
      return
    }
    val revisionElement = payload["revision"]
    if (revisionElement is JsonNull) {
      clearProgressCard(clearScopeKey = false)
      return
    }
    val revision =
      (revisionElement as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.content
        ?.toLongOrNull()
        ?.takeIf { it in 1..Int.MAX_VALUE }
        ?.toInt()
        ?: return
    if (_progressCard.value?.revision == revision) return
    refreshProgressCard()
  }

  private fun updateSessionFromHistory(
    history: ChatHistory,
    publishRunState: Boolean = true,
  ) {
    val info = history.sessionInfo ?: return
    upsertSessionEntry(
      info,
      preserveExistingContextUsageWithoutTotal = true,
      replaceActiveRunIds = true,
      publishRunState = publishRunState,
    )
  }

  private fun upsertSessionEntry(
    entry: ChatSessionEntry,
    preserveExistingContextUsageWithoutTotal: Boolean = false,
    replaceActiveRunIds: Boolean = false,
    clearedFields: Set<String> = emptySet(),
    publishRunState: Boolean = true,
  ) {
    val applied = sessionCatalog.upsert(entry, preserveExistingContextUsageWithoutTotal, replaceActiveRunIds, clearedFields)
    if (applied.key == sessionSelection.key.value) {
      sessionSettings.applyMetadata(applied.settingsMetadata())
      pruneRunTelemetryToAuthoritativeOwnership()
      if (publishRunState) publishRunPresentation()
    }
    synchronized(gatewayScopeApplyLock) {
      sessionReads.observe(sessionCatalog.entry(applied.key))
    }
  }

  private fun removeSessionEntry(
    sessionKey: String?,
    ownerAgentId: String? = null,
    cacheScope: ChatCacheScope? = currentCacheScope(),
  ): Boolean {
    val key = sessionKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val owner = resolveAgentIdFromMainSessionKey(key) ?: ownerAgentId?.trim()?.takeIf { it.isNotEmpty() }
    val removesVisibleEntry = sessionCatalog.remove(key, cacheScope, owner)
    // Gateway-side deletes must also purge the offline copy, or the deleted transcript would
    // reappear on the next offline cold open. Queued commands for the session die with it too.
    val requestCacheScope = cacheScope
    if (requestCacheScope != null && owner != null) {
      purgeSessionOwnedState(key, owner, requestCacheScope)
    }
    return removesVisibleEntry
  }

  private fun purgeSessionOwnedState(
    sessionKey: String,
    ownerAgentId: String,
    cacheScope: ChatCacheScope,
    mainSessionKey: String = sessionSelection.mainKey,
  ) {
    onSessionDeleted(
      ChatSessionDeletion(
        gatewayId = cacheScope.gatewayId,
        agentId = ownerAgentId,
        sessionKey = sessionKey,
        mainSessionKey = mainSessionKey,
      ),
    )
    scope.launch {
      cacheMutationMutex.withLock {
        transcriptCache?.let { runCatching { it.deleteSession(cacheScope.gatewayId, ownerAgentId, sessionKey) } }
        commandOutbox?.let { runCatching { it.deleteForSession(cacheScope.gatewayId, sessionKey, ownerAgentId) } }
      }
      outboxState.refresh()
    }
  }

  private suspend fun readDetachedHistory(
    gateway: ChatCacheScope,
    sessionKey: String,
    agentId: String,
  ): ChatHistory {
    val response =
      requestGatewayBound(
        gateway.gatewayId,
        "chat.history",
        buildJsonObject {
          put("sessionKey", JsonPrimitive(sessionKey))
          put("agentId", JsonPrimitive(agentId))
        }.toString(),
      )
    return historyCodec.parseHistory(response, sessionKey = sessionKey, previousMessages = emptyList())
  }

  private suspend fun requestGatewayBound(
    gatewayId: String?,
    method: String,
    paramsJson: String?,
  ): String =
    if (gatewayId == null) {
      requestGateway(method, paramsJson)
    } else {
      requestGatewayForGateway(gatewayId, method, paramsJson)
    }

  private fun currentCacheScope(): ChatCacheScope? = normalizedChatCacheScope(cacheScope())

  private fun sessionSettingsKey(
    sessionKey: String,
    gatewayScope: ChatCacheScope? = currentCacheScope(),
    ownerAgentId: String? = sessionSelection.resolveOwner(sessionKey),
  ): ChatSessionSettingsKey =
    ChatSessionSettingsKey(
      gatewayScope = gatewayScope,
      sessionKey = sessionKey,
      ownerAgentId = ownerAgentId,
    )
}

// Group mutations enumerate whole stores; far past any realistic session count.
private const val SESSION_RECONCILIATION_FETCH_LIMIT = 10_000

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
  }

private fun JsonElement?.asJsonStringOrNull(): String? =
  (this as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.content

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }

private fun JsonElement?.asBooleanOrNull(): Boolean? =
  when (this) {
    is JsonPrimitive -> content.toBooleanStrictOrNull()
    else -> null
  }

private fun parseChatDiffStat(element: JsonElement?): ChatDiffStat? {
  val value = element.asObjectOrNull() ?: return null

  fun count(key: String): Int? =
    value[key]
      .asLongOrNull()
      ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
      ?.toInt()

  return ChatDiffStat(
    added = count("added") ?: return null,
    removed = count("removed") ?: return null,
  )
}
