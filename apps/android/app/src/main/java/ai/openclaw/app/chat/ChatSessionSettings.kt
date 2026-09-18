package ai.openclaw.app.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.verbatimText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Owns picker state, captured-connection mutation lanes, and the catalog publication fence.
 * Drafts own local intent; the catalog owns rows; delivery waits for this owner's lane.
 * The shared lock makes settings admission and catalog publication one atomic boundary.
 */
internal class ChatSessionSettings(
  private val scope: CoroutineScope,
  private val json: Json,
  private val publicationLock: Any,
  private val drafts: ChatDraftController,
  private val selectedSessionKey: () -> String,
  private val currentKey: (String) -> ChatSessionSettingsKey,
  private val readMetadata: (String) -> ChatSessionSettingsMetadata?,
  private val publishMetadata: (ChatSessionSettingsKey, ChatSessionSettingsMetadata) -> Unit,
  private val captureRequestLease: (ChatCacheScope?) -> GatewaySession.RequestLease?,
  private val publishError: (NativeText?) -> Unit,
  private val recordModelRecent: (String) -> Unit,
  private val onLaneDrained: () -> Unit,
) {
  private val _thinkingLevel = MutableStateFlow("off")
  val thinkingLevel = _thinkingLevel.asStateFlow()
  private val _thinkingLevelSelection = MutableStateFlow(defaultChatThinkingLevelSelection)
  val thinkingLevelSelection = _thinkingLevelSelection.asStateFlow()
  private val _selectedModelRef = MutableStateFlow<String?>(null)
  val selectedModelRef = _selectedModelRef.asStateFlow()
  private val modelSelectionGeneration = AtomicLong(0)
  val modelRevision: Long get() = modelSelectionGeneration.get()

  fun selectSession(metadata: ChatSessionSettingsMetadata?): Unit =
    synchronized(publicationLock) {
      applyMetadata(metadata)
      _selectedModelRef.value =
        drafts
          .find(currentKey(selectedSessionKey()).gatewayScope?.gatewayId, selectedSessionKey())
          ?.takeIf { it.isLocal }
          ?.intent
          ?.modelRef
    }

  fun applyHistoryModel(
    requestRevision: Long,
    modelRef: String?,
  ): Unit =
    synchronized(publicationLock) {
      if (requestRevision == modelRevision) _selectedModelRef.value = modelRef
    }

  fun applyHistoryThinking(level: String?): Unit =
    synchronized(publicationLock) {
      level?.trim()?.takeIf(String::isNotEmpty)?.let { _thinkingLevel.value = it }
    }

  private data class SessionSettingsPatchResolution(
    val modelProvider: String?,
    val model: String?,
    val thinkingLevel: String?,
    val thinkingLevels: List<ChatThinkingLevelOption>?,
  )

  private fun parseSessionSettingsPatchResolution(value: String): SessionSettingsPatchResolution? {
    val root = json.parseToJsonElement(value) as? JsonObject ?: return null
    val resolved = root["resolved"] as? JsonObject ?: return null
    return SessionSettingsPatchResolution(
      resolved["modelProvider"].settingsString(),
      resolved["model"].settingsString(),
      resolved["thinkingLevel"].settingsString(),
      parseChatThinkingLevels(resolved["thinkingLevels"]),
    )
  }

  private data class QueuedSessionSettingsMutation(
    val settingsKey: ChatSessionSettingsKey,
    val requestLease: GatewaySession.RequestLease?,
    val pending: CompletableDeferred<Boolean>,
    val previous: CompletableDeferred<Boolean>?,
  )

  private val pendingSettingsMutations = ConcurrentHashMap<ChatSessionSettingsKey, CompletableDeferred<Boolean>>()
  private val settingsMutationRevisions = mutableMapOf<ChatCacheScope?, Long>()
  private val activeSessionRefreshesByScope = mutableMapOf<ChatCacheScope?, Int>()

  private data class ThinkingIntent(
    val requestId: Long,
    val level: String,
  )

  private data class AcceptedThinkingState(
    val level: String,
    val thinkingLevels: List<ChatThinkingLevelOption>?,
  )

  private val thinkingRequestSequence = AtomicLong(0)
  private val latestThinkingIntents = ConcurrentHashMap<ChatSessionSettingsKey, ThinkingIntent>()
  private val latestAcceptedThinkingStates = ConcurrentHashMap<ChatSessionSettingsKey, AcceptedThinkingState>()

  /** Persists the normalized thinking level used for subsequent chat sends. */
  fun setThinkingLevel(thinkingLevel: String): Unit =
    synchronized(publicationLock) {
      val normalized = normalizeChatThinking(thinkingLevel)
      val selection = _thinkingLevelSelection.value
      if (selection.isGatewayProvided && selection.options.none { it.id == normalized }) {
        return
      }
      if (normalized == _thinkingLevel.value) return
      val key = selectedSessionKey()
      val settingsKey = currentKey(key)
      drafts.find(settingsKey.gatewayScope?.gatewayId, key)?.takeIf { it.isLocal }?.let { draft ->
        if (drafts.selectThinking(draft.intent.target, normalized)) _thinkingLevel.value = normalized
        return
      }
      val rollbackEntry = readMetadata(key)
      val rollbackLevel =
        rollbackEntry
          ?.thinkingLevel
          ?.let(::normalizeChatThinking)
          ?: _thinkingLevel.value
      val queuedMutation = enqueueSessionSettingsMutation(settingsKey)
      latestAcceptedThinkingStates.putIfAbsent(
        settingsKey,
        AcceptedThinkingState(
          level = rollbackLevel,
          thinkingLevels =
            rollbackEntry?.thinkingLevels
              ?: selection.options.takeIf { selection.isGatewayProvided },
        ),
      )
      val intent = ThinkingIntent(requestId = thinkingRequestSequence.incrementAndGet(), level = normalized)
      latestThinkingIntents[settingsKey] = intent
      _thinkingLevel.value = normalized
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        setSessionThinkingLevelAwait(
          sessionKey = key,
          thinkingLevel = normalized,
          fallbackRollbackLevel = rollbackLevel,
          settingsKey = settingsKey,
          intent = intent,
          queuedMutation = queuedMutation,
        )
      }
    }

  /** Patches the active session model without blocking the Compose caller. */
  fun setSessionModel(
    sessionKey: String,
    modelRef: String?,
  ) {
    // Enter the model-selection queue before returning so an immediate send cannot overtake it.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      setSessionModelAwait(sessionKey = sessionKey, modelRef = modelRef)
    }
  }

  /** Patches a session model and updates picker state only after gateway acceptance. */
  internal suspend fun setSessionModelAwait(
    sessionKey: String,
    modelRef: String?,
  ): Boolean {
    val key = sessionKey
    val normalizedModelRef = modelRef?.trim()?.takeIf { it.isNotEmpty() }
    val settingsKey =
      synchronized(publicationLock) {
        val captured = currentKey(key)
        drafts.find(captured.gatewayScope?.gatewayId, key)?.takeIf { it.isLocal }?.let { draft ->
          val changed = drafts.selectModel(draft.intent.target, normalizedModelRef)
          if (changed && selectedSessionKey() == key) {
            modelSelectionGeneration.incrementAndGet()
            _selectedModelRef.value = normalizedModelRef
          }
          return changed
        }
        captured
      }
    return runSessionSettingsMutation(settingsKey) { requestLease ->
      clearErrorIfCurrent(settingsKey)
      try {
        val lease = requestLease ?: throw GatewayRequestNotEnqueued("not connected")
        val params =
          buildJsonObject {
            put("key", JsonPrimitive(key))
            settingsKey.ownerAgentId?.let { put("agentId", JsonPrimitive(it)) }
            put("model", normalizedModelRef?.let(::JsonPrimitive) ?: JsonNull)
          }
        val response = lease.request("sessions.patch", params.toString())
        val resolution = parseSessionSettingsPatchResolution(response)
        normalizedModelRef?.let(recordModelRecent)
        synchronized(publicationLock) {
          applyAcceptedModelPatch(
            key = key,
            settingsKey = settingsKey,
            modelRef = normalizedModelRef,
            resolution = resolution,
          )
          if (selectedSessionKey() == key && settingsKey == currentKey(key)) {
            modelSelectionGeneration.incrementAndGet()
            _selectedModelRef.value = normalizedModelRef
          }
        }
        true
      } catch (err: CancellationException) {
        throw err
      } catch (err: Throwable) {
        synchronized(publicationLock) {
          if (settingsKey == currentKey(key)) {
            publishError(err.message?.let(::verbatimText) ?: nativeText("Could not update model."))
          }
        }
        false
      }
    }
  }

  private suspend fun setSessionThinkingLevelAwait(
    sessionKey: String,
    thinkingLevel: String,
    fallbackRollbackLevel: String,
    settingsKey: ChatSessionSettingsKey,
    intent: ThinkingIntent,
    queuedMutation: QueuedSessionSettingsMutation,
  ): Boolean =
    runSessionSettingsMutation(queuedMutation) { requestLease ->
      val rollbackEntry = readMetadata(sessionKey)
      val rollbackState =
        latestAcceptedThinkingStates[settingsKey]
          ?: AcceptedThinkingState(
            level = rollbackEntry?.thinkingLevel?.let(::normalizeChatThinking) ?: fallbackRollbackLevel,
            thinkingLevels =
              rollbackEntry?.thinkingLevels
                ?: _thinkingLevelSelection.value.options.takeIf {
                  _thinkingLevelSelection.value.isGatewayProvided
                },
          )
      clearErrorIfCurrent(settingsKey)
      try {
        val lease = requestLease ?: throw GatewayRequestNotEnqueued("not connected")
        val params =
          buildJsonObject {
            put("key", JsonPrimitive(sessionKey))
            settingsKey.ownerAgentId?.let { put("agentId", JsonPrimitive(it)) }
            put("thinkingLevel", JsonPrimitive(thinkingLevel))
          }
        val response = lease.request("sessions.patch", params.toString())
        val resolution = parseSessionSettingsPatchResolution(response)
        synchronized(publicationLock) {
          applyAcceptedThinkingPatch(sessionKey, settingsKey, thinkingLevel, intent, resolution)
        }
        true
      } catch (err: CancellationException) {
        latestThinkingIntents.remove(settingsKey, intent)
        throw err
      } catch (err: Throwable) {
        synchronized(publicationLock) {
          if (
            selectedSessionKey() == sessionKey &&
            settingsKey == currentKey(sessionKey) &&
            latestThinkingIntents[settingsKey]?.requestId == intent.requestId
          ) {
            val existing = readMetadata(sessionKey)
            val applied =
              (existing ?: ChatSessionSettingsMetadata()).copy(
                thinkingLevel = rollbackState.level,
                thinkingLevels = rollbackState.thinkingLevels,
              )
            publishMetadata(settingsKey, applied)
            _thinkingLevel.value = rollbackState.level
            applyMetadata(applied)
          }
          latestThinkingIntents.remove(settingsKey, intent)
          if (settingsKey == currentKey(sessionKey)) {
            publishError(
              err.message?.let(::verbatimText) ?: nativeText("Could not update thinking level."),
            )
          }
        }
        false
      }
    }

  private fun clearErrorIfCurrent(settingsKey: ChatSessionSettingsKey) {
    synchronized(publicationLock) {
      if (settingsKey == currentKey(settingsKey.sessionKey)) publishError(null)
    }
  }

  private suspend fun runSessionSettingsMutation(
    settingsKey: ChatSessionSettingsKey,
    operation: suspend (GatewaySession.RequestLease?) -> Boolean,
  ): Boolean = runSessionSettingsMutation(enqueueSessionSettingsMutation(settingsKey), operation)

  private fun enqueueSessionSettingsMutation(settingsKey: ChatSessionSettingsKey): QueuedSessionSettingsMutation {
    // Capture the physical socket before waiting. A reconnect may retire this
    // lease, but queued work can never resolve the replacement connection.
    val requestLease = captureRequestLease(settingsKey.gatewayScope)
    val pending = CompletableDeferred<Boolean>()
    return synchronized(publicationLock) {
      val previous = pendingSettingsMutations.put(settingsKey, pending)
      incrementSettingsMutationRevision(settingsKey.gatewayScope)
      QueuedSessionSettingsMutation(
        settingsKey = settingsKey,
        requestLease = requestLease,
        pending = pending,
        previous = previous,
      )
    }
  }

  private suspend fun runSessionSettingsMutation(
    queuedMutation: QueuedSessionSettingsMutation,
    operation: suspend (GatewaySession.RequestLease?) -> Boolean,
  ): Boolean {
    val settingsKey = queuedMutation.settingsKey
    val pending = queuedMutation.pending
    var succeeded = false
    var drainedLane = false
    return try {
      queuedMutation.previous?.await()
      // A queued mutation captured a concrete gateway generation. Never let it
      // fall through to the replacement connection after waiting its turn.
      succeeded =
        if (settingsKey == currentKey(settingsKey.sessionKey)) {
          operation(queuedMutation.requestLease)
        } else {
          false
        }
      succeeded
    } finally {
      // Cancelling queued work must not release a still-running predecessor's
      // admission fence or let a successor dispatch ahead of it. Join only the
      // already captured completion; never retry or acquire a replacement lease.
      queuedMutation.previous?.let { previous ->
        if (!previous.isCompleted) withContext(NonCancellable) { previous.await() }
      }
      synchronized(publicationLock) {
        incrementSettingsMutationRevision(settingsKey.gatewayScope)
        drainedLane = pendingSettingsMutations.remove(settingsKey, pending)
        if (drainedLane) {
          // These baselines bridge adjacent operations in one lane only. After
          // drain, refreshed session metadata is authoritative rollback state.
          latestAcceptedThinkingStates.remove(settingsKey)
          latestThinkingIntents.remove(settingsKey)
        }
        pruneSettingsMutationRevision(settingsKey.gatewayScope)
      }
      // Publish only after registry cleanup so a resumed scope waiter cannot
      // repeatedly observe this completed mutation before finally removes it.
      pending.complete(succeeded)
      if (drainedLane && succeeded) {
        // A failed predecessor can stop a reconnect flush while its successor is queued.
        // The successful lane tail must hand durable rows back to the flush owner.
        onLaneDrained()
      }
    }
  }

  private fun incrementSettingsMutationRevision(gatewayScope: ChatCacheScope?) {
    settingsMutationRevisions[gatewayScope] = (settingsMutationRevisions[gatewayScope] ?: 0L) + 1L
  }

  fun revision(gatewayScope: ChatCacheScope?): Long =
    synchronized(publicationLock) {
      settingsMutationRevisions[gatewayScope] ?: 0L
    }

  // Call inside the same publication lock as the catalog commit.
  fun matchesSnapshot(
    gatewayScope: ChatCacheScope?,
    revision: Long,
  ): Boolean =
    synchronized(publicationLock) {
      revision == revision(gatewayScope) && !hasPendingSessionSettings(gatewayScope)
    }

  fun beginRefresh(gatewayScope: ChatCacheScope?) =
    synchronized(publicationLock) {
      activeSessionRefreshesByScope[gatewayScope] = (activeSessionRefreshesByScope[gatewayScope] ?: 0) + 1
    }

  fun endRefresh(gatewayScope: ChatCacheScope?) =
    synchronized(publicationLock) {
      val remaining = (activeSessionRefreshesByScope[gatewayScope] ?: 1) - 1
      if (remaining > 0) {
        activeSessionRefreshesByScope[gatewayScope] = remaining
      } else {
        activeSessionRefreshesByScope.remove(gatewayScope)
        pruneSettingsMutationRevision(gatewayScope)
      }
    }

  private fun hasPendingSessionSettings(gatewayScope: ChatCacheScope?): Boolean = pendingSettingsMutations.keys.any { it.gatewayScope == gatewayScope }

  private fun pruneSettingsMutationRevision(gatewayScope: ChatCacheScope?) {
    // A drained revision only matters while an in-flight list request can
    // compare it. Retired connection generations must not accumulate forever.
    if (
      !hasPendingSessionSettings(gatewayScope) &&
      activeSessionRefreshesByScope[gatewayScope] == null
    ) {
      settingsMutationRevisions.remove(gatewayScope)
    }
  }

  suspend fun awaitPending(sessionKey: String): Boolean = awaitPending(currentKey(sessionKey))

  suspend fun awaitPending(settingsKey: ChatSessionSettingsKey): Boolean {
    var pending = pendingSettingsMutations[settingsKey] ?: return true
    while (true) {
      if (!pending.await()) return false
      val next = pendingSettingsMutations[settingsKey]
      if (next == null || next === pending) return true
      pending = next
    }
  }

  suspend fun awaitPending(gatewayScope: ChatCacheScope?) {
    while (true) {
      val pending =
        synchronized(publicationLock) {
          pendingSettingsMutations
            .filterKeys { it.gatewayScope == gatewayScope }
            .values
            .toList()
        }
      if (pending.isEmpty()) return
      pending.forEach { it.await() }
    }
  }

  private fun applyAcceptedModelPatch(
    key: String,
    settingsKey: ChatSessionSettingsKey,
    modelRef: String?,
    resolution: SessionSettingsPatchResolution?,
  ) {
    val existing = readMetadata(key)
    val previousThinkingState =
      latestAcceptedThinkingStates[settingsKey]
        ?: AcceptedThinkingState(
          level =
            existing?.thinkingLevel?.let(::normalizeChatThinking)
              ?: _thinkingLevel.value.takeIf { selectedSessionKey() == key }
              ?: "off",
          thinkingLevels =
            existing?.thinkingLevels
              ?: _thinkingLevelSelection.value.options.takeIf {
                selectedSessionKey() == key && _thinkingLevelSelection.value.isGatewayProvided
              },
        )
    val acceptedThinkingState =
      if (resolution?.thinkingLevel != null || resolution?.thinkingLevels != null) {
        AcceptedThinkingState(
          level = resolution.thinkingLevel?.let(::normalizeChatThinking) ?: previousThinkingState.level,
          thinkingLevels = resolution.thinkingLevels ?: previousThinkingState.thinkingLevels,
        )
      } else {
        previousThinkingState
      }
    latestAcceptedThinkingStates[settingsKey] = acceptedThinkingState
    if (settingsKey != currentKey(key)) return
    val fallbackProvider = modelRef?.substringBefore('/', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
    val fallbackModel =
      modelRef?.let { ref -> ref.substringAfter('/', missingDelimiterValue = ref) }?.takeIf { it.isNotEmpty() }
    val applied =
      (existing ?: ChatSessionSettingsMetadata()).copy(
        modelProvider = resolution?.modelProvider ?: fallbackProvider ?: existing?.modelProvider,
        model = resolution?.model ?: fallbackModel ?: existing?.model,
        thinkingLevel = acceptedThinkingState.level,
        thinkingLevels = resolution?.thinkingLevels ?: acceptedThinkingState.thinkingLevels,
        thinkingDefault = null,
      )
    publishMetadata(settingsKey, applied)
    if (selectedSessionKey() == key) {
      val pendingThinkingLevel = latestThinkingIntents[settingsKey]?.level
      applyMetadata(applied)
      // A queued thinking patch owns the visible intent until it succeeds or
      // rolls back; the preceding model response must not replace that intent.
      pendingThinkingLevel?.let { _thinkingLevel.value = it }
    }
  }

  private fun applyAcceptedThinkingPatch(
    key: String,
    settingsKey: ChatSessionSettingsKey,
    requestedLevel: String,
    intent: ThinkingIntent,
    resolution: SessionSettingsPatchResolution?,
  ) {
    val acceptedLevel = resolution?.thinkingLevel?.let(::normalizeChatThinking) ?: requestedLevel
    latestAcceptedThinkingStates[settingsKey] =
      AcceptedThinkingState(
        level = acceptedLevel,
        thinkingLevels = resolution?.thinkingLevels ?: latestAcceptedThinkingStates[settingsKey]?.thinkingLevels,
      )
    if (settingsKey != currentKey(key)) {
      latestThinkingIntents.remove(settingsKey, intent)
      return
    }
    val existing = readMetadata(key)
    if (existing != null) {
      publishMetadata(
        settingsKey,
        existing.copy(
          modelProvider = resolution?.modelProvider ?: existing.modelProvider,
          model = resolution?.model ?: existing.model,
          thinkingLevel = acceptedLevel,
          thinkingLevels = resolution?.thinkingLevels ?: existing.thinkingLevels,
        ),
      )
    }
    if (selectedSessionKey() == key && latestThinkingIntents[settingsKey]?.requestId == intent.requestId) {
      _thinkingLevel.value = acceptedLevel
      resolution?.thinkingLevels?.let { levels ->
        applyMetadata(
          (readMetadata(key) ?: ChatSessionSettingsMetadata()).copy(
            thinkingLevel = acceptedLevel,
            thinkingLevels = levels,
          ),
        )
      }
    }
    latestThinkingIntents.remove(settingsKey, intent)
  }

  fun applyMetadata(entry: ChatSessionSettingsMetadata?): Unit =
    synchronized(publicationLock) {
      drafts.find(currentKey(selectedSessionKey()).gatewayScope?.gatewayId, selectedSessionKey())?.takeIf { it.isLocal }?.let { draft ->
        _thinkingLevelSelection.value = defaultChatThinkingLevelSelection
        _thinkingLevel.value = draft.intent.thinkingLevel ?: "off"
        return
      }
      val advertised = entry?.thinkingLevels
      if (advertised == null) {
        _thinkingLevelSelection.value = defaultChatThinkingLevelSelection
        val requestedLevel =
          entry
            ?.thinkingLevel
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeChatThinking)
            ?: normalizeChatThinking(_thinkingLevel.value)
        _thinkingLevel.value =
          requestedLevel.takeIf { candidate ->
            defaultChatThinkingLevelSelection.options.any { it.id == candidate }
          } ?: "off"
        return
      }
      val options =
        advertised
          .map { option ->
            val id = normalizeChatThinking(option.id)
            ChatThinkingLevelOption(
              id = id,
              label = option.label.trim().takeIf { it.isNotEmpty() } ?: id,
            )
          }.distinctBy { it.id }
          .ifEmpty { listOf(ChatThinkingLevelOption(id = "off", label = "Off")) }
      _thinkingLevelSelection.value =
        ChatThinkingLevelSelection(
          options = options,
          isGatewayProvided = true,
        )
      val selected = entry.thinkingLevel?.let(::normalizeChatThinking)
      val currentLevel = normalizeChatThinking(_thinkingLevel.value)
      val defaultLevel = entry.thinkingDefault?.let(::normalizeChatThinking)
      // Lightweight picker metadata can omit a Gateway-validated effective level.
      // Preserve that send state; only local/default fallbacks require picker membership.
      _thinkingLevel.value =
        selected
          ?: listOf(currentLevel, defaultLevel).firstOrNull { candidate -> options.any { it.id == candidate } }
          ?: options.first().id
    }

  /** Uses catalog metadata only when the selected session has not advertised richer settings. */
  fun applyModelCatalog(catalog: List<AiModel>): Unit =
    synchronized(publicationLock) {
      val sessionKey = selectedSessionKey()
      val localDraft =
        drafts
          .find(currentKey(sessionKey).gatewayScope?.gatewayId, sessionKey)
          ?.takeIf { it.isLocal }
      if (localDraft == null && readMetadata(sessionKey)?.thinkingLevels != null) return
      val selected = _selectedModelRef.value?.trim()?.takeIf(String::isNotEmpty)
      if (selected == null) {
        resetThinkingToFallback()
        return
      }
      val model = catalog.firstOrNull { it.providerQualifiedRef() == selected }
      if (model == null) {
        resetThinkingToFallback()
        return
      }
      if (model.thinkingLevels.isEmpty()) {
        resetThinkingToFallback()
        return
      }
      val options =
        model.thinkingLevels
          .map { option ->
            val id = normalizeChatThinking(option.id)
            ChatThinkingLevelOption(id = id, label = option.label.trim().ifEmpty { id })
          }.distinctBy(ChatThinkingLevelOption::id)
      if (options.isEmpty()) return
      _thinkingLevelSelection.value = ChatThinkingLevelSelection(options = options, isGatewayProvided = true)
      val current = normalizeChatThinking(_thinkingLevel.value)
      val fallback = model.thinkingDefault?.let(::normalizeChatThinking)
      val localDraftThinking = localDraft?.intent?.thinkingLevel
      _thinkingLevel.value =
        (if (localDraftThinking != null) listOf(current, fallback) else listOf(fallback, current))
          .firstOrNull { candidate -> options.any { it.id == candidate } }
          ?: options.first().id
    }

  private fun resetThinkingToFallback() {
    _thinkingLevelSelection.value = defaultChatThinkingLevelSelection
    val current = normalizeChatThinking(_thinkingLevel.value)
    _thinkingLevel.value = current.takeIf { candidate -> defaultChatThinkingLevelSelection.options.any { it.id == candidate } } ?: "off"
  }

  fun supportsThinking(catalog: List<AiModel>): Boolean {
    val selection = _thinkingLevelSelection.value
    return if (selection.isGatewayProvided) {
      selection.options.any { it.id != "off" }
    } else {
      thinkingSupportedForAiSelection(_selectedModelRef.value, catalog)
    }
  }
}
