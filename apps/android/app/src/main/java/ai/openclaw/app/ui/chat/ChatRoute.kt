package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.ModelCatalogStatus
import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.approval.ApprovalFeature
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatDirectoryFeature
import ai.openclaw.app.chat.ChatDraftPhase
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatGatewayFeature
import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.chat.ChatSessionTitleCandidate
import ai.openclaw.app.chat.chatOutboxQueueFailureText
import ai.openclaw.app.chat.providerQualifiedRef
import ai.openclaw.app.chat.questionsForSession
import ai.openclaw.app.chat.resolveChatComposerOwner
import ai.openclaw.app.chat.resolveChatSessionTitle
import ai.openclaw.app.chat.resolveGatewayDefaultAgentId
import ai.openclaw.app.chat.thinkingSupportedForAiSelection
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.joinedNativeText
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.verbatimText
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionPhase
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsFeature
import ai.openclaw.app.permissions.SessionPermissionsState
import ai.openclaw.app.project.ProjectCatalogState
import ai.openclaw.app.project.ProjectFeature
import ai.openclaw.app.project.ProjectNameDialogState
import ai.openclaw.app.project.projectIdFromSessionKey
import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.ui.approval.ApprovalDetailSheet
import ai.openclaw.app.ui.approval.collectInbox
import ai.openclaw.app.ui.approval.review
import ai.openclaw.app.ui.approval.reviewsFor
import ai.openclaw.app.ui.project.ProjectNameDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Feature-only Chat composition; lifecycle and domain owners are supplied explicitly. */
@Composable
internal fun ChatRoute(
  composer: ChatComposerFeature,
  projectStarters: List<ProjectStarter> = emptyList(),
  history: ChatHistoryFeature?,
  currentWork: ChatCurrentWorkFeature?,
  execution: ChatExecutionFeature?,
  appResults: ai.openclaw.app.appdelivery.AndroidAppResultsFeature? = null,
  webResults: ai.openclaw.app.webdelivery.WebProjectResultsFeature? = null,
  gateway: ChatGatewayFeature?,
  outbox: ChatOutboxFeature?,
  directory: ChatDirectoryFeature?,
  sessionOptions: ChatSessionOptionsFeature?,
  approvals: ApprovalFeature?,
  permissions: SessionPermissionsFeature?,
  projects: ProjectFeature? = null,
  skills: SkillFeature? = null,
  devKitCatalog: DeveloperCapabilityCatalogState? = null,
  forceBlank: Boolean,
  showSidebarButton: Boolean,
  onOpenSidebar: () -> Unit,
  onOpenAiSettings: () -> Unit = {},
  skillSelectionRequest: ChatSkillSelectionRequest? = null,
  onSkillSelectionConsumed: (Long) -> Unit = {},
  focusApprovalId: String?,
  onApprovalFocusConsumed: () -> Unit,
) {
  val catalog = directory?.catalog
  val navigation = directory?.navigation
  val historyState = history.collectHistory()
  val messages = historyState.messages
  val transcriptAnchor = historyState.anchor
  val historyLoading = historyState.loading
  val errorText = historyState.error
  val currentWorkState = currentWork.collectCurrentWorkPresentation()
  val pendingRunCount = currentWorkState.pendingRunCount
  val selectedActiveRun = currentWorkState.activeRun
  val healthOk = historyState.healthy
  val gatewayState = gateway.collectGatewayPresentation()
  val gatewayConnectionDisplay = gatewayState.connection
  val activeGatewayStableId = gatewayState.activeStableId
  val appResultsState = appResults?.state?.collectAsState()?.value
  val webResultsState = webResults?.state?.collectAsState()?.value
  val sessionKey = historyState.sessionKey
  val selectionGeneration = historyState.selectionGeneration
  val gatewayCatalogRevision = gatewayState.catalogRevision
  val skillState = key(skills) { skills?.state?.collectAsState()?.value }
  val sessionOwnerAgentId = historyState.ownerAgentId
  val executionState = execution.collectExecutionPresentation()
  val stopStates = executionState.stops
  val draftStates = executionState.localDrafts
  val operatorScopes = gatewayState.operatorScopes
  val permissionStates =
    key(permissions) {
      permissions
        ?.states
        ?.collectAsState()
        ?.value
        .orEmpty()
    }
  val permissionTarget =
    remember(activeGatewayStableId, sessionKey, sessionOwnerAgentId) {
      activeGatewayStableId?.let { gatewayId ->
        val agentId = resolveAgentIdFromMainSessionKey(sessionKey) ?: sessionOwnerAgentId
        agentId?.let { runCatching { SessionPermissionTarget(gatewayId, sessionKey, it) }.getOrNull() }
      }
    }
  val permissionState = permissionTarget?.let { permissionStates[it] ?: SessionPermissionsState(it) }
  val localDraft = permissionTarget?.let { draftStates[it] }?.takeIf { it.isLocal }
  val projectDestination = projects?.destination?.collectAsState()?.value
  val projectCatalog = projects?.catalog?.collectAsState()?.value
  val sessionProjectId =
    localDraft?.intent?.projectId
      ?: activeSessionProjectId(
        sessionKey = sessionKey,
        sessions = catalog?.entries?.value.orEmpty(),
        catalog = projectCatalog,
      )
      ?: projectDestination?.activeProjectId
  val uncommittedProjectDraft =
    projectDestination?.draft?.takeIf { it.sessionKey == sessionKey && localDraft?.intent?.projectId == null }
  val projectTitle =
    if (uncommittedProjectDraft != null) {
      nativeString("New Project")
    } else {
      projectName(sessionProjectId, projectCatalog)
    }
  val permissionsBusy =
    if (localDraft != null) {
      localDraft.phase == ChatDraftPhase.Creating ||
        !gatewayConnectionDisplay.isConnected ||
        operatorScopes.none { it == "operator.write" || it == "operator.admin" }
    } else {
      permissionState?.phase in setOf(SessionPermissionPhase.Applying, SessionPermissionPhase.Unconfirmed) ||
        (gatewayConnectionDisplay.isConnected && permissionState?.readyToSend != true)
    }
  LaunchedEffect(permissions, permissionTarget, localDraft != null, gatewayConnectionDisplay.isConnected, gatewayCatalogRevision) {
    if (localDraft == null) permissionTarget?.let { permissions?.refresh?.invoke(it) }
  }
  val approvalInbox = approvals.collectInbox()
  val chatApprovals = if (forceBlank) emptyList() else approvalInbox.reviewsFor(ApprovalSession(sessionKey, sessionOwnerAgentId))
  val approvalActions = approvals?.actions ?: ApprovalActions({}, { _, _, _ -> }, {})
  var approvalDetailId by rememberSaveable(approvals, activeGatewayStableId, sessionKey) { mutableStateOf<String?>(null) }
  val mainSessionKey = gatewayState.mainSessionKey
  val gatewayDefaultAgentId = gatewayState.defaultAgentId
  val gatewayComposerDefaultAgentOwner = historyState.defaultOwner
  val options = sessionOptions.collectPresentation()
  val thinkingLevel = options.thinkingLevel
  val thinkingLevelSelection = options.thinkingSelection
  val answerDraft = currentWorkState.answerDraft
  val pendingToolCalls = currentWorkState.pendingToolCalls
  val questions = currentWorkState.questions
  val progressCard = currentWorkState.progressCard
  val taskNotice = currentWorkState.taskNotices[sessionKey]
  val sessions =
    key(catalog) {
      catalog
        ?.entries
        ?.collectAsState()
        ?.value
        .orEmpty()
    }
  val chatCommands = options.commands
  val chatDraft by composer.drafts.pending.collectAsState()
  val chatShareDrafts by composer.shares.queued.collectAsState()
  val pendingAssistantAutoSend by composer.autoSend.pending.collectAsState()
  val assistantAutoSendInFlight by composer.autoSend.inFlight.collectAsState()
  val outboxState = outbox.collectOutboxPresentation()
  val outboxItems = outboxState.items
  val outboxPresentationRestored = outboxState.restored
  val modelCatalogState = options.modelCatalog
  val modelCatalogSnapshot = (modelCatalogState.status as? ModelCatalogStatus.Ready)?.snapshot
  val modelCatalog = modelCatalogSnapshot?.models.orEmpty()
  val modelFavorites = options.favorites
  val modelRecents = options.recents
  val selectedModelRef = options.selectedModelRef
  val thinkingSupported =
    chatThinkingSupported(
      selection = thinkingLevelSelection,
      fallbackSupported = thinkingSupportedForAiSelection(selectedModelRef, modelCatalog),
    )
  val contextUsage = resolveChatContextUsage(sessionKey = sessionKey, mainSessionKey = mainSessionKey, sessions = sessions)
  val activeSession =
    sessions.firstOrNull {
      isActiveSessionChoice(
        choiceKey = it.key,
        sessionKey = sessionKey,
        mainSessionKey = mainSessionKey,
      )
    }
  val chatTitle =
    if (uncommittedProjectDraft != null) {
      nativeString("New Project")
    } else {
      activeSession
        ?.let { session -> resolveChatSessionTitle(session) { nativeString("New chat") }.text }
        ?: nativeString("New chat")
    }
  val renameEnabled =
    activeSession?.sessionId?.isNotBlank() == true &&
      directory != null &&
      directory.management.canSetLabel() &&
      gatewayConnectionDisplay.isConnected &&
      operatorScopes.any { it == "operator.write" || it == "operator.admin" }
  val gatewayOffline = !gatewayConnectionDisplay.isConnected
  val effectiveGatewayDefaultAgentId =
    resolveGatewayDefaultAgentId(activeGatewayStableId, gatewayDefaultAgentId, gatewayComposerDefaultAgentOwner)
  val sessionAgentId = resolveAgentIdFromMainSessionKey(sessionKey) ?: sessionOwnerAgentId ?: effectiveGatewayDefaultAgentId ?: "main"
  val composerOwner =
    resolveChatComposerOwner(
      gatewayStableId = activeGatewayStableId,
      gatewayDefaultAgentId = sessionOwnerAgentId ?: gatewayDefaultAgentId,
      lastVerifiedOwner = if (sessionOwnerAgentId == null) gatewayComposerDefaultAgentOwner else null,
      sessionKey = sessionKey,
      mainSessionKey = mainSessionKey,
    )
  val currentSessionOutboxItems =
    outboxItemsForSession(
      items = outboxItems,
      sessionKey = sessionKey,
      mainSessionKey = mainSessionKey,
      ownerAgentId = composerOwner.agentId,
      messages = messages,
    )
  val stopState = stopStates[composerOwner]
  val stopBusy = stopState?.phase in setOf(ai.openclaw.app.chat.ChatStopPhase.Stopping, ai.openclaw.app.chat.ChatStopPhase.Unconfirmed)
  val activeAgentId = sessionAgentId
  val selectedWebResult =
    if (forceBlank) {
      null
    } else {
      webResultsState
        ?.results
        ?.lastOrNull {
          it.gatewayId == activeGatewayStableId &&
            it.sessionKey == sessionKey &&
            (activeSession?.sessionId == null || it.sessionId == activeSession.sessionId)
        }
    }
  val selectedAppResult =
    if (forceBlank) {
      null
    } else {
      appResultsState
        ?.results
        ?.lastOrNull {
          it.gatewayId == activeGatewayStableId &&
            it.sessionKey == sessionKey &&
            (activeSession?.sessionId == null || it.sessionId == activeSession.sessionId)
        }
    }
  val timelineResults =
    buildList {
      selectedWebResult?.let { result ->
        add(
          ChatResultState(
            key = "web:${result.id}",
            kind = ChatResultKind.WebApp,
            label = result.appName,
            opening = webResultsState?.openingId == result.id,
            error = webResultsState?.error?.takeIf { webResultsState.errorId == result.id },
          ),
        )
      }
      selectedAppResult?.let { result ->
        add(
          ChatResultState(
            key = "android:${result.id}",
            kind = ChatResultKind.AndroidApp,
            label = result.displayName,
            opening = appResultsState?.openingId == result.id,
            error = appResultsState?.error?.takeIf { appResultsState.errorId == result.id },
            warning = taskNotice?.message,
          ),
        )
      }
    }
  val currentWorkUiState =
    if (forceBlank) {
      null
    } else {
      resolveChatCurrentWorkUiState(
        pendingRunCount = pendingRunCount,
        activeRunId = selectedActiveRun.runId,
        runActivity = currentWorkState.runActivity,
        progress = progressCard,
        tools = pendingToolCalls,
      )
    }
  val timelineAttentions =
    buildList {
      if (!forceBlank) {
        errorText?.takeIf(String::isNotBlank)?.let { add(chatHistoryAttention(userFacingChatError(it))) }
        projectDestination?.runConflict?.let { add(chatRunConflictAttention()) }
        val stopAttention = resolveChatStopAttention(stopState?.phase, gatewayConnectionDisplay.isConnected)
        stopAttention?.let(::add)
        if (stopAttention == null) {
          resolveChatPermissionAttention(
            localDraftPhase = localDraft?.phase,
            permissionState = permissionState,
            connected = gatewayConnectionDisplay.isConnected,
          )?.let(::add)
        }
        if (selectedAppResult == null) taskNotice?.let { add(chatTaskWarning(it.message)) }
      } else {
        resolveChatPermissionAttention(
          localDraftPhase = localDraft?.phase,
          permissionState = permissionState,
          connected = gatewayConnectionDisplay.isConnected,
        )?.let(::add)
      }
    }
  val timelineSupplement =
    ChatTimelineSupplement(
      attentions = timelineAttentions,
      currentWork = currentWorkUiState,
      results = timelineResults,
    )
  val presentationState =
    resolveChatPresentationState(
      hasContent =
        !forceBlank &&
          (
            messages.isNotEmpty() ||
              !answerDraft?.text.isNullOrBlank() ||
              pendingToolCalls.isNotEmpty() ||
              chatApprovals.isNotEmpty() ||
              questions.isNotEmpty() ||
              currentSessionOutboxItems.isNotEmpty() ||
              timelineAttentions.isNotEmpty() ||
              timelineResults.isNotEmpty()
          ),
      runActive = !forceBlank && pendingRunCount > 0,
    )
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
  val resolver = context.applicationContext.contentResolver
  val scope = rememberCoroutineScope()
  val composerState = remember(composer) { composer.state }
  val inputDrafts = composerState.textDrafts
  val input = inputDrafts[composerOwner]
  val selectedSkillReferences = inputDrafts.skillReferences(composerOwner)
  val skillMentionOptions =
    remember(skillState?.summary?.skills, devKitCatalog) {
      eligibleChatSkillMentions(
        skills = skillState?.summary?.skills.orEmpty(),
        capabilities = devKitCatalog,
      )
    }
  val staleSkillReferences =
    remember(skillState?.loaded, selectedSkillReferences, skillMentionOptions) {
      staleChatSkillReferences(skillState?.loaded == true, selectedSkillReferences, skillMentionOptions)
    }
  LaunchedEffect(skillSelectionRequest, composerOwner) {
    val request = skillSelectionRequest ?: return@LaunchedEffect
    inputDrafts.selectSkillReference(composerOwner, request.reference)
    onSkillSelectionConsumed(request.id)
  }
  val composerPlatform =
    rememberChatComposerPlatformBinding(
      composer = composer,
      owner = composerOwner,
      mainSessionKey = mainSessionKey,
    )
  val composerPristine =
    input.isBlank() &&
      selectedSkillReferences.isEmpty() &&
      composerPlatform.attachments.isEmpty() &&
      chatShareDrafts.none { composer.shares.targetsOwner(it.id, composerOwner, mainSessionKey) } &&
      pendingAssistantAutoSend == null &&
      !assistantAutoSendInFlight

  LaunchedEffect(projectDestination?.activation, composerOwner, healthOk, permissionsBusy) {
    val activation = projectDestination?.activation ?: return@LaunchedEffect
    if (activation.sessionKey != sessionKey || !healthOk || permissionsBusy) return@LaunchedEffect
    val accepted = composer.delivery.begin(composerOwner, thinkingLevel) == ChatComposerSendStartResult.Started
    if (accepted) projects.actions.consumeActivation(activation.id)
  }
  val destinationKind =
    when {
      forceBlank -> ChatDestinationKind.None
      uncommittedProjectDraft != null -> ChatDestinationKind.NewProjectDraft
      localDraft != null -> ChatDestinationKind.ProjectChatDraft
      activeSession != null -> ChatDestinationKind.MaterializedChat
      else -> ChatDestinationKind.None
    }
  val blankContent =
    resolveChatBlankContent(
      starters = projectStarters,
      destinationKind = destinationKind,
      presentationState = presentationState,
      historyLoading = historyLoading,
      composerPristine = composerPristine,
      startersEnabled =
        localDraft?.editable == true &&
          gatewayConnectionDisplay.isConnected &&
          "operator.admin" in operatorScopes &&
          !permissionsBusy,
    )
  val shareOwnerRevision by composer.shares.ownerRevision.collectAsState()
  val chatShareDraft =
    remember(chatShareDrafts, composerOwner, mainSessionKey, shareOwnerRevision) {
      chatShareDrafts.firstOrNull { draft ->
        composer.shares.targetsOwner(draft.id, composerOwner, mainSessionKey)
      }
    }
  val shareStaging =
    chatShareDraft?.let { composer.shares.targetsOwner(it.id, composerOwner, mainSessionKey) } == true
  val sendInFlight = composerPlatform.sendInFlight
  var showModelPicker by rememberSaveable { mutableStateOf(false) }
  var showPermissionPicker by rememberSaveable(sessionKey) { mutableStateOf(false) }
  var renameDialogState by remember(sessionKey) { mutableStateOf<ChatRenameDialogState?>(null) }
  var sendMessageTooLong by rememberSaveable(composerOwner) { mutableStateOf(false) }
  var sendCheckpointFull by rememberSaveable(composerOwner) { mutableStateOf(false) }

  LaunchedEffect(composerOwner, mainSessionKey, chatShareDraft?.id) {
    composer.owners.resolveAliases(composerOwner, mainSessionKey)
    composer.shares.resolveOwner(chatShareDraft?.id, composerOwner, mainSessionKey)
  }

  LaunchedEffect(skills, gatewayConnectionDisplay.isConnected, gatewayCatalogRevision) {
    if (gatewayConnectionDisplay.isConnected) skills?.actions?.refresh?.invoke()
  }

  val titleCandidate =
    remember(localDraft?.intent, input, selectedSkillReferences) {
      localDraft
        ?.takeIf { it.editable }
        ?.intent
        ?.let { intent ->
          ChatSessionTitleCandidate(
            target = intent.target,
            catalogRevision = gatewayCatalogRevision,
            message = serializeExplicitSkillPrompt(selectedSkillReferences, input),
            modelRef = intent.modelRef,
          )
        }
    }
  DisposableEffect(directory?.titlePreparation, titleCandidate?.target) {
    val preparation = directory?.titlePreparation
    val target = titleCandidate?.target
    onDispose { if (preparation != null && target != null) preparation.clear(target) }
  }
  LaunchedEffect(
    directory?.titlePreparation,
    titleCandidate,
    permissionTarget,
    gatewayConnectionDisplay.isConnected,
    operatorScopes,
    gatewayCatalogRevision,
  ) {
    val preparation = directory?.titlePreparation ?: return@LaunchedEffect
    val candidate = titleCandidate
    if (
      candidate != null &&
      gatewayConnectionDisplay.isConnected &&
      operatorScopes.any { it == "operator.write" || it == "operator.admin" }
    ) {
      preparation.stage(candidate)
    } else {
      permissionTarget?.let(preparation::clear)
    }
  }

  val modelSections =
    remember(modelCatalog, selectedModelRef, modelFavorites, modelRecents) {
      chatModelPickerSections(
        catalog = modelCatalog,
        selectedModelRef = selectedModelRef,
        favorites = modelFavorites,
        recents = modelRecents,
      )
    }
  val selectedModelLabel =
    selectedModelRef?.let { selected ->
      modelCatalog.firstOrNull { it.providerQualifiedRef() == selected }?.name?.takeIf { it.isNotBlank() }
        ?: selected.substringAfterLast('/')
    } ?: nativeString("Default")
  LaunchedEffect(history) {
    val feature = history ?: return@LaunchedEffect
    val loadSessionKey = resolveInitialChatLoadSessionKey(feature.selection.key.value, mainSessionKey)
    if (loadSessionKey != null) {
      feature.load(loadSessionKey, feature.selection.ownerAgentId.value)
    }
  }

  LaunchedEffect(catalog) { catalog?.refresh?.invoke(100, false) }
  LaunchedEffect(sessionOptions, sessionKey) { sessionOptions?.refresh?.invoke() }

  LaunchedEffect(
    pendingAssistantAutoSend,
    assistantAutoSendInFlight,
    composerPlatform.sendState,
    composerOwner,
    healthOk,
    pendingRunCount,
    thinkingLevel,
  ) {
    if (!healthOk) return@LaunchedEffect
    val pending =
      resolvePendingAssistantAutoSend(
        pending = pendingAssistantAutoSend,
        currentOwner = composerOwner,
        healthOk = healthOk,
        pendingRunCount = pendingRunCount,
      ) ?: return@LaunchedEffect
    composer.autoSend.dispatch(
      pending = pending,
      thinking = thinkingLevel,
    )
  }

  val shareImportNotice =
    when (composerPlatform.attachmentNotice) {
      ChatComposerAttachmentNotice.Attachment ->
        NativeText.Resource(source = "Could not stage an attachment for sending.", formatArgs = emptyList())
      ChatComposerAttachmentNotice.Image ->
        nativeText("Some shared images were omitted or could not be added.")
      null ->
        when {
          sendMessageTooLong ->
            joinedNativeText(
              separator = " ",
              parts =
                listOf(
                  chatOutboxQueueFailureText(),
                  verbatimText("${input.length}/$CHAT_COMPOSER_MAX_SEND_CHARS"),
                ),
            )
          sendCheckpointFull -> chatOutboxQueueFailureText()
          else -> null
        }
    }

  LaunchedEffect(chatDraft, composerOwner, mainSessionKey) {
    val pending = chatDraft ?: return@LaunchedEffect
    val claimed =
      composer.drafts.consume(
        expected = pending,
        owner = composerOwner,
        mainSessionKey = mainSessionKey,
      ) ?: return@LaunchedEffect
    val merged =
      mergeChatDraft(draft = claimed, currentInput = input, currentOwner = composerOwner) ?: return@LaunchedEffect
    inputDrafts[composerOwner] = merged
    claimed.attachments?.let { composerState.replaceAttachments(composerOwner, it) }
  }

  LaunchedEffect(composerOwner, composerPlatform.pendingSendAdmissionIds) {
    composerPlatform.pendingSendAdmissionIds.forEach { admissionId ->
      composer.delivery.acknowledgeAdmission(composerOwner, admissionId)
    }
  }

  // The process queue remembers the first owner; only an explicit alias/identity resolution
  // migrates that claim. Navigating elsewhere must never retarget a shared payload.
  LaunchedEffect(chatShareDraft?.id, lifecycleState, composerOwner, shareOwnerRevision) {
    if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
    val share = chatShareDraft ?: return@LaunchedEffect
    val ownerSnapshot = composerOwner
    composer.shares.withLease(share.id, ownerSnapshot) {
      val staged =
        withContext(Dispatchers.IO) {
          stageChatShareDraft(share) { attachment ->
            loadSharedAttachment(resolver, attachment)
          }
        }
      if (!composer.owners.isCurrent(ownerSnapshot)) return@withLease
      if (
        !canCommitStagedChatShare(
          stagedId = share.id,
          currentHead = composer.shares.forOwner(ownerSnapshot, mainSessionKey),
          ownerSnapshot = ownerSnapshot,
          currentOwner = ownerSnapshot,
        )
      ) {
        return@withLease
      }
      // A non-resumed Activity must not acknowledge into its hidden composer; the next visible
      // Activity keeps the process-owned head and retries the complete import instead.
      if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        return@withLease
      }
      // Keep the head pending through both mutations: Send stays gated until text and images
      // have been merged together, and disposal before this point leaves the head for retry.
      inputDrafts[ownerSnapshot] =
        mergeSharedChatText(sharedText = staged.text, currentInput = inputDrafts[ownerSnapshot])
      val admissionOmissions = composerState.addAttachments(ownerSnapshot, staged.attachments)
      composerState.reportAttachmentOmission(
        ownerSnapshot,
        staged.failedAttachmentCount + staged.droppedAttachmentCount + admissionOmissions,
      )
      composer.shares.acknowledge(share.id, ownerSnapshot)
    }
  }

  LaunchedEffect(gatewayConnectionDisplay.isConnected) {
    if (!gatewayConnectionDisplay.isConnected) {
      showModelPicker = false
    }
  }

  val permissionPickerState =
    resolveChatPermissionPickerState(
      localDraftMode = localDraft?.intent?.mode,
      permissionState = permissionState,
      connected = gatewayConnectionDisplay.isConnected,
    )
  val chatScreenState =
    ChatScreenState(
      header =
        ChatHeaderState(
          showSidebarButton = showSidebarButton,
          sessionIdentity = sessionKey,
          chatTitle = chatTitle,
          projectName = projectTitle,
          renameEnabled = renameEnabled,
          permission = permissionPickerState,
          modelLabel = selectedModelLabel,
          modelPickerEnabled = gatewayConnectionDisplay.isConnected && sessionOptions != null && modelCatalogState.status is ModelCatalogStatus.Ready,
          thinkingLevel = thinkingLevel,
          thinkingOptions = thinkingLevelSelection.options,
          thinkingSupported = thinkingSupported,
          contextPercent = contextMeterWidth(contextUsage)?.let { (it * 100).roundToInt() },
          refreshVisible = errorText?.isNotBlank() == true,
        ),
      timeline = timelineSupplement,
    )
  val refreshChat = {
    history?.refresh?.invoke()
    catalog?.refresh?.invoke(100, false)
    Unit
  }
  val handleAttention: (ChatAttentionAction) -> Unit = { action ->
    when (action) {
      ChatAttentionAction.RefreshChat -> refreshChat()
      ChatAttentionAction.ReturnToRunOwner -> projects?.actions?.returnToRunOwner?.invoke()
      ChatAttentionAction.CheckPermissions -> {
        permissionTarget?.let { target ->
          scope.launch {
            if (localDraft != null) {
              permissions
                ?.reconcileCreation
                ?.invoke(target)
                ?.let { execution?.localDrafts?.confirm(localDraft.intent, it) }
            } else {
              permissions?.refresh?.invoke(target)
            }
          }
        }
      }
      ChatAttentionAction.StopForPermissions,
      ChatAttentionAction.StopChat,
      -> execution?.stops?.abortCurrent()
      ChatAttentionAction.CheckStop -> execution?.stops?.reconcile(composerOwner)
    }
  }
  val openResult: (ChatResultState) -> Unit = { state ->
    when (state.kind) {
      ChatResultKind.AndroidApp -> {
        selectedAppResult
          ?.takeIf { state.key == "android:${it.id}" }
          ?.let { appResults?.open?.invoke(it) }
      }
      ChatResultKind.WebApp -> {
        selectedWebResult
          ?.takeIf { state.key == "web:${it.id}" }
          ?.let { webResults?.open?.invoke(it) }
      }
    }
  }

  ChatScreen(
    state = chatScreenState,
    actions =
      ChatScreenActions(
        openSidebar = onOpenSidebar,
        openModelPicker = { showModelPicker = true },
        openPermissionPicker = {
          if (permissionPickerState?.canOpen == true) showPermissionPicker = true
        },
        openRenameDialog = openRename@{
          val session = activeSession ?: return@openRename
          val sessionId = session.sessionId?.trim()?.takeIf(String::isNotEmpty)
          if (renameEnabled && sessionId != null) {
            val savedLabel = session.label?.trim()?.takeIf(String::isNotEmpty)
            renameDialogState =
              ChatRenameDialogState(
                sessionKey = session.key,
                sessionId = sessionId,
                savedLabel = savedLabel,
                initialValue = savedLabel ?: chatTitle,
                value = savedLabel ?: chatTitle,
              )
          }
        },
        selectThinkingLevel = { sessionOptions?.selectThinkingLevel?.invoke(it) },
        refreshChat = refreshChat,
        handleAttention = handleAttention,
        openResult = openResult,
      ),
    timeline = { modifier, supplement, actions ->
      key(history) {
        ChatMessageList(
          presentationState = presentationState,
          blankContent = blankContent,
          onProjectStarter = { starter ->
            if (uncommittedProjectDraft != null) {
              sendMessageTooLong = false
              sendCheckpointFull = false
              composer.state.stageProjectStarter(composerOwner, starter)
            }
          },
          sessionKey = sessionKey,
          fullMessageOwner = composerOwner,
          selectionGeneration = selectionGeneration,
          gatewayCatalogRevision = gatewayCatalogRevision,
          fullMessageSource = history?.fullMessages,
          session = activeSession,
          messages = messages,
          transcriptAnchor = transcriptAnchor,
          historyLoading = historyLoading,
          activeRunCount = selectedActiveRun.count,
          activeRunId = selectedActiveRun.runId,
          activeRunClockKey = selectedActiveRun.clockKey,
          activeRunOutputTokens = selectedActiveRun.outputTokens,
          pendingToolCalls = pendingToolCalls,
          questions = questionsForSession(questions, sessionKey, mainSessionKey, activeAgentId),
          approvals = chatApprovals,
          timelineSupplement = supplement,
          approvalActions = approvalActions,
          onApprovalDetails = { approvalDetailId = it },
          focusApprovalId = focusApprovalId,
          onApprovalFocusConsumed = onApprovalFocusConsumed,
          answerDraft = answerDraft,
          healthOk = healthOk,
          gatewayOffline = gatewayOffline,
          outboxItems = currentSessionOutboxItems,
          recoveryOutboxItems =
            outboxItemsForRecovery(
              items = outboxItems,
            ),
          onRetryOutbox = { id -> outbox?.retry?.invoke(id) },
          onDeleteOutbox = { id -> outbox?.delete?.invoke(id) },
          onResolveQuestion = { prompt, answers -> currentWork?.resolveQuestion?.invoke(prompt, answers) },
          onQuestionDraftChanged = { prompt, update -> currentWork?.updateQuestionDraft?.invoke(prompt, update) },
          onSkipQuestion = { prompt -> currentWork?.skipQuestion?.invoke(prompt) },
          onReplyMessage = { value -> composer.drafts.setReply(value, composerOwner) },
          onAttentionAction = actions.handleAttention,
          onOpenResult = actions.openResult,
          history = history,
          modifier = modifier,
        )
      }
    },
    composer = {
      ChatComposer(
        value = input,
        onValueChange = {
          sendMessageTooLong = false
          sendCheckpointFull = false
          inputDrafts[composerOwner] = it
        },
        skillOptions = skillMentionOptions,
        skillsLoaded = skillState?.loaded == true,
        selectedSkillReferences = selectedSkillReferences,
        staleSkillReferences = staleSkillReferences,
        onSelectSkill = { option ->
          sendMessageTooLong = false
          sendCheckpointFull = false
          inputDrafts.selectSkillReference(composerOwner, option.reference)
          inputDrafts[composerOwner] = removeChatSkillMentionQuery(inputDrafts[composerOwner])
        },
        onRemoveSkill = { reference -> inputDrafts.removeSkillReference(composerOwner, reference) },
        attachments = composerPlatform.attachments,
        pendingRunCount = pendingRunCount,
        shareStaging = shareStaging,
        sendInFlight = sendInFlight || forceBlank || permissionsBusy || stopBusy,
        shareImportNotice = shareImportNotice,
        onDismissShareImportNotice = {
          sendMessageTooLong = false
          sendCheckpointFull = false
          composerPlatform.clearAttachmentNotice()
        },
        commands = chatCommands,
        onPickImages = composerPlatform.pickImages,
        onPickDocument = composerPlatform.pickDocument,
        onRemoveAttachment = composerPlatform.removeAttachment,
        onAbort = { execution?.stops?.abortCurrent() },
        onSend = {
          // Re-read the ViewModel so a stale click callback cannot beat StateFlow recomposition.
          val currentShare = composer.shares.forOwner(composerOwner, mainSessionKey)
          if (currentShare != null || composerPlatform.sendInFlight) {
            return@ChatComposer
          }
          val ownerSnapshot = composerOwner
          if (!composer.owners.isCurrent(ownerSnapshot)) return@ChatComposer
          if (uncommittedProjectDraft != null) {
            projects.actions.requestComposer()
            return@ChatComposer
          }
          val result =
            composer.delivery.begin(
              owner = ownerSnapshot,
              thinking = thinkingLevel,
            )
          sendMessageTooLong = result == ChatComposerSendStartResult.MessageTooLong
          sendCheckpointFull = result == ChatComposerSendStartResult.CheckpointFull
        },
      )
    },
  )

  projectDestination?.dialog?.takeUnless { it is ProjectNameDialogState.Hidden }?.let { dialog ->
    ProjectNameDialog(
      state = dialog,
      onNameChange = projects.actions.updateName,
      onConfirm = projects.actions.confirmName,
      onDismiss = projects.actions.dismissName,
      onFinishSetup = projects.actions.finishSetup,
    )
  }

  renameDialogState?.let { dialog ->
    val target =
      activeSession?.takeIf {
        it.key == dialog.sessionKey && it.sessionId == dialog.sessionId
      }

    fun submitLabel(label: String?) {
      if (!renameEnabled || target == null || dialog.submitting) return
      renameDialogState = dialog.copy(submitting = true, errorMessage = null)
      scope.launch {
        val succeeded = directory.management.setLabel(target, label)
        val current = renameDialogState
        if (current?.sessionKey != dialog.sessionKey || current.sessionId != dialog.sessionId) return@launch
        renameDialogState =
          if (succeeded) {
            null
          } else {
            current.copy(
              submitting = false,
              errorMessage = nativeString("Chat name could not be changed. Try again."),
            )
          }
      }
    }
    ChatRenameDialog(
      state = dialog,
      available = renameEnabled && target != null,
      onValueChange = { value ->
        val current = renameDialogState
        if (
          current?.sessionKey == dialog.sessionKey &&
          current.sessionId == dialog.sessionId &&
          current.value != value
        ) {
          renameDialogState = current.copy(value = value, errorMessage = null)
        }
      },
      onSave = { submitLabel(dialog.value.trim()) },
      onUseAutomaticName = { submitLabel(null) },
      onDismiss = { renameDialogState = null },
    )
  }

  approvalDetailId?.let { id ->
    ApprovalDetailSheet(approvalInbox.review(id), !gatewayOffline, approvalActions, onClose = { approvalDetailId = null })
  }

  if (showModelPicker) {
    ChatModelPickerSheet(
      sections = modelSections,
      favorites = modelFavorites.toSet(),
      onDismiss = { showModelPicker = false },
      onSelect = { modelRef ->
        sessionOptions?.selectModelRoute?.invoke(modelRef)
        showModelPicker = false
      },
      onToggleFavorite = { sessionOptions?.toggleFavorite?.invoke(it) },
      onRecoverModelAccess = {
        showModelPicker = false
        onOpenAiSettings()
      },
    )
  }

  if (showPermissionPicker && permissionPickerState != null) {
    ChatPermissionPickerSheet(
      state = permissionPickerState,
      onDismiss = { showPermissionPicker = false },
      onSelect = { mode: SessionPermissionMode ->
        permissionTarget?.let { target ->
          scope.launch {
            if (permissions?.choose?.invoke(target, mode) == true) {
              showPermissionPicker = false
            }
          }
        }
      },
      onRecover = {
        if (permissionState?.phase == SessionPermissionPhase.Unconfirmed) {
          execution?.stops?.abortCurrent()
        } else {
          permissionTarget?.let { target -> scope.launch { permissions?.refresh?.invoke(target) } }
        }
      },
    )
  }
}

private fun activeSessionProjectId(
  sessionKey: String,
  sessions: List<ai.openclaw.app.chat.ChatSessionEntry>,
  catalog: ProjectCatalogState?,
): String? {
  sessions.firstOrNull { it.key == sessionKey }?.projectId?.let { return it }
  projectIdFromSessionKey(sessionKey)?.let { return it }
  return when (catalog) {
    is ProjectCatalogState.Ready -> catalog.sessionBindings[sessionKey]
    is ProjectCatalogState.Unavailable -> catalog.sessionBindings[sessionKey]
    ProjectCatalogState.Loading,
    null,
    -> null
  }
}

private fun projectName(
  projectId: String?,
  catalog: ProjectCatalogState?,
): String? {
  if (projectId == null) return null
  val projects =
    when (catalog) {
      is ProjectCatalogState.Ready -> catalog.projects
      is ProjectCatalogState.Unavailable -> catalog.retainedProjects
      ProjectCatalogState.Loading,
      null,
      -> emptyList()
    }
  return projects.firstOrNull { it.id == projectId }?.displayName
}
