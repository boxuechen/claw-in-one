package ai.openclaw.app.project

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

private const val PROJECT_NAME_LIMIT = 80

internal class ProjectController(
  private val scope: CoroutineScope,
  private val transport: ProjectTransport,
  private val createLocalDraft: (projectId: String?, projectRoot: String?) -> String?,
  private val bindLocalDraft: (sessionKey: String, projectId: String, projectRoot: String) -> Boolean,
  private val openSession: (sessionKey: String, agentId: String?) -> Unit,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val codec = ProjectCodec()
  private val lock = Any()
  private val mutableCatalog = MutableStateFlow<ProjectCatalogState>(ProjectCatalogState.Loading)
  private val mutableCapabilities =
    MutableStateFlow<DevelopmentCapabilitiesState>(DevelopmentCapabilitiesState.Loading)
  private val mutableDestination = MutableStateFlow(ProjectDestinationState())
  private var submissionInFlight = false
  private var catalogRefreshInFlight = false
  private var catalogRefreshPending = false

  val feature =
    ProjectFeature(
      catalog = mutableCatalog.asStateFlow(),
      capabilities = mutableCapabilities.asStateFlow(),
      destination = mutableDestination.asStateFlow(),
      actions =
        ProjectActions(
          newProject = ::newProject,
          newChat = ::newChat,
          selectProject = ::selectProject,
          requestComposer = ::requestComposer,
          updateName = ::updateName,
          confirmName = ::confirmName,
          dismissName = ::dismissName,
          finishSetup = ::finishSetup,
          consumeActivation = ::consumeActivation,
          returnToRunOwner = ::returnToRunOwner,
          refreshCatalog = ::refreshCatalog,
        ),
    )

  fun clear() {
    synchronized(lock) {
      submissionInFlight = false
      catalogRefreshInFlight = false
      catalogRefreshPending = false
      mutableCatalog.value = ProjectCatalogState.Loading
      mutableCapabilities.value = DevelopmentCapabilitiesState.Loading
      mutableDestination.value = ProjectDestinationState()
    }
  }

  fun connectionUnavailable() {
    val ready = mutableCatalog.value as? ProjectCatalogState.Ready
    mutableCatalog.value = ProjectCatalogState.Unavailable(ready?.projects.orEmpty(), ready?.sessionBindings.orEmpty())
    val capabilities = mutableCapabilities.value as? DevelopmentCapabilitiesState.Ready
    mutableCapabilities.value =
      DevelopmentCapabilitiesState.Unavailable(capabilities?.capabilities.orEmpty())
  }

  fun newProject(): Boolean =
    synchronized(lock) {
      if (submissionInFlight) return false
      val key = createLocalDraft(null, null) ?: return false
      mutableDestination.value = ProjectDestinationState(draft = ProjectDraft(newId(), key))
      true
    }

  fun newChat(projectId: String): Boolean =
    synchronized(lock) {
      if (submissionInFlight || projectId.isBlank()) return false
      val project = project(projectId) ?: return false
      val key = createLocalDraft(project.id, project.repoRoot) ?: return false
      mutableDestination.value = ProjectDestinationState(activeProjectId = projectId)
      openSession(key, null)
      true
    }

  fun selectProject(projectId: String) {
    mutableDestination.value =
      mutableDestination.value.copy(
        activeProjectId = projectId,
        draft = null,
        dialog = ProjectNameDialogState.Hidden,
        runConflict = null,
      )
  }

  suspend fun admitRunForSession(sessionKey: String): Boolean {
    val projectId = projectIdForSession(sessionKey) ?: return true
    val connection = transport.capture() ?: return false
    if (PROJECT_RUN_LEASE_STATUS_METHOD !in connection.methods) return false
    val lease =
      try {
        codec.runLeaseStatus(
          transport.request(connection, PROJECT_RUN_LEASE_STATUS_METHOD, codec.runLeaseStatusParams(projectId)),
        )
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        return false
      }
    if (lease == null || lease.ownerSessionKey == sessionKey) {
      mutableDestination.value = mutableDestination.value.copy(runConflict = null)
      return true
    }
    mutableDestination.value = mutableDestination.value.copy(runConflict = lease)
    return false
  }

  private fun projectIdForSession(sessionKey: String): String? {
    val catalog = mutableCatalog.value as? ProjectCatalogState.Ready
    return catalog?.sessionBindings?.get(sessionKey)
      ?: projectIdFromSessionKey(sessionKey)
      ?: mutableDestination.value.activeProjectId
  }

  fun returnToRunOwner() {
    val conflict = mutableDestination.value.runConflict ?: return
    mutableDestination.value =
      mutableDestination.value.copy(
        activeProjectId = conflict.projectId,
        runConflict = null,
      )
    openSession(conflict.ownerSessionKey, null)
  }

  fun requestComposer(): Boolean =
    synchronized(lock) {
      val state = mutableDestination.value
      val draft = state.draft ?: return false
      if (state.dialog !is ProjectNameDialogState.Hidden || submissionInFlight) return false
      val candidate = allocateDefaultName(projectNames())
      mutableDestination.value =
        state.copy(
          dialog = ProjectNameDialogState.Editing(draft, candidate, ProjectNameOrigin.Default, newId()),
        )
      true
    }

  fun updateName(value: String) {
    synchronized(lock) {
      val current = mutableDestination.value
      val dialog = current.dialog as? ProjectNameDialogState.Editing ?: return
      mutableDestination.value =
        current.copy(
          dialog =
            dialog.copy(
              value = value,
              origin = ProjectNameOrigin.Custom,
              nameRevision = newId(),
              inlineError = null,
            ),
        )
    }
  }

  fun dismissName() {
    synchronized(lock) {
      if (submissionInFlight) return
      val state = mutableDestination.value
      mutableDestination.value = state.copy(dialog = ProjectNameDialogState.Hidden)
    }
  }

  fun confirmName() {
    val editing =
      synchronized(lock) {
        if (submissionInFlight) return
        val state = mutableDestination.value
        val value = state.dialog as? ProjectNameDialogState.Editing ?: return
        validateProjectName(value.value)?.let { error ->
          mutableDestination.value = state.copy(dialog = value.copy(inlineError = error, errorRevision = value.errorRevision + 1))
          return
        }
        submissionInFlight = true
        mutableDestination.value =
          state.copy(dialog = ProjectNameDialogState.Submitting(value.draft, value.value.trim(), value.origin, value.nameRevision))
        value
      }
    scope.launch { create(editing) }
  }

  fun finishSetup() {
    val dialog = mutableDestination.value.dialog as? ProjectNameDialogState.FinishSetup ?: return
    val project = dialog.result.project ?: return
    if (bindLocalDraft(dialog.draft.sessionKey, project.id, project.repoRoot)) {
      complete(dialog.draft, dialog.result)
    } else {
      mutableDestination.value =
        mutableDestination.value.copy(
          dialog = dialog.copy(message = "Workspace Chat setup is still unavailable"),
        )
    }
  }

  private suspend fun create(editing: ProjectNameDialogState.Editing) {
    val connection = transport.capture()
    if (
      connection == null ||
      !connection.admin ||
      PROJECT_CREATE_METHOD !in connection.methods ||
      PROJECT_READ_METHOD !in connection.methods
    ) {
      failBeforeProject(editing, "Project creation is unavailable")
      return
    }
    val intent =
      ProjectCreateIntent(
        intentId = editing.nameRevision,
        nameRevision = editing.nameRevision,
        origin = editing.origin,
        requestedName = editing.value,
      )
    val result =
      try {
        codec.creation(transport.request(connection, PROJECT_CREATE_METHOD, codec.createParams(intent)))
      } catch (error: CancellationException) {
        finishSubmission()
        throw error
      } catch (error: GatewayRequestRejected) {
        if (error.gatewayError.code == "name_conflict") {
          conflict(editing)
          return
        }
        failBeforeProject(editing, error.gatewayError.message)
        return
      } catch (_: GatewayRequestOutcomeUnknown) {
        try {
          codec.readback(transport.request(connection, PROJECT_READ_METHOD, codec.readParams(intent.intentId)))
        } catch (error: CancellationException) {
          finishSubmission()
          throw error
        } catch (_: Throwable) {
          null
        }
      } catch (_: Throwable) {
        null
      }
    if (
      result?.project == null ||
      result.phase != "registered" ||
      result.intentId != intent.intentId ||
      result.nameRevision != intent.nameRevision ||
      result.naming != intent.origin ||
      result.project.repoRoot != result.repoRoot ||
      result.project.displayName != result.displayName ||
      (intent.origin == ProjectNameOrigin.Custom && normalizeName(result.displayName) != normalizeName(intent.requestedName))
    ) {
      failBeforeProject(editing, "Project creation was not confirmed")
      return
    }
    if (!bindLocalDraft(editing.draft.sessionKey, result.project.id, result.project.repoRoot)) {
      synchronized(lock) {
        submissionInFlight = false
        mutableDestination.value =
          mutableDestination.value.copy(
            activeProjectId = result.project.id,
            dialog = ProjectNameDialogState.FinishSetup(editing.draft, result, "Finish creating the first Workspace Chat"),
          )
      }
      refreshCatalog()
      return
    }
    complete(editing.draft, result)
  }

  private fun complete(
    draft: ProjectDraft,
    result: ProjectCreationResult,
  ) {
    val project = result.project ?: return
    synchronized(lock) {
      submissionInFlight = false
      mutableDestination.value =
        ProjectDestinationState(
          activeProjectId = project.id,
          activation = ProjectSendActivation(newId(), draft.sessionKey),
        )
    }
    openSession(draft.sessionKey, project.agentId)
    refreshCatalog()
  }

  private fun conflict(editing: ProjectNameDialogState.Editing) {
    synchronized(lock) {
      submissionInFlight = false
      val next = editing.copy(inlineError = "Name already exists. Choose another.", errorRevision = editing.errorRevision + 1)
      mutableDestination.value = mutableDestination.value.copy(dialog = next)
    }
  }

  private fun failBeforeProject(
    editing: ProjectNameDialogState.Editing,
    message: String,
  ) {
    synchronized(lock) {
      submissionInFlight = false
      mutableDestination.value =
        mutableDestination.value.copy(
          dialog = editing.copy(inlineError = message, errorRevision = editing.errorRevision + 1),
        )
    }
  }

  private fun finishSubmission() {
    synchronized(lock) { submissionInFlight = false }
  }

  fun consumeActivation(id: String) {
    synchronized(lock) {
      val current = mutableDestination.value
      if (current.activation?.id == id) mutableDestination.value = current.copy(activation = null)
    }
  }

  fun refreshCatalog() {
    val shouldLaunch =
      synchronized(lock) {
        if (catalogRefreshInFlight) {
          catalogRefreshPending = true
          false
        } else {
          catalogRefreshInFlight = true
          true
        }
      }
    if (!shouldLaunch) return
    scope.launch {
      try {
        while (true) {
          refreshCatalogOnce()
          val repeat =
            synchronized(lock) {
              if (catalogRefreshPending) {
                catalogRefreshPending = false
                true
              } else {
                catalogRefreshInFlight = false
                false
              }
            }
          if (!repeat) break
        }
      } finally {
        synchronized(lock) {
          catalogRefreshInFlight = false
          catalogRefreshPending = false
        }
      }
    }
  }

  private suspend fun refreshCatalogOnce() {
    val connection = transport.capture()
    val ready = mutableCatalog.value as? ProjectCatalogState.Ready
    val retained = ready?.projects.orEmpty()
    if (
      connection == null ||
      PROJECT_CATALOG_METHOD !in connection.methods ||
      PROJECT_CAPABILITIES_METHOD !in connection.methods
    ) {
      mutableCatalog.value = ProjectCatalogState.Unavailable(retained, ready?.sessionBindings.orEmpty())
      val capabilities = mutableCapabilities.value as? DevelopmentCapabilitiesState.Ready
      mutableCapabilities.value =
        DevelopmentCapabilitiesState.Unavailable(capabilities?.capabilities.orEmpty())
      return
    }
    if (mutableCatalog.value !is ProjectCatalogState.Ready) mutableCatalog.value = ProjectCatalogState.Loading
    try {
      val catalog = codec.catalog(transport.request(connection, PROJECT_CATALOG_METHOD, "{}"))
      val capabilities =
        codec.capabilities(transport.request(connection, PROJECT_CAPABILITIES_METHOD, "{}"))
      mutableCatalog.value = catalog
      mutableCapabilities.value = capabilities
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      mutableCatalog.value = ProjectCatalogState.Unavailable(retained, ready?.sessionBindings.orEmpty())
      val capabilities = mutableCapabilities.value as? DevelopmentCapabilitiesState.Ready
      mutableCapabilities.value =
        DevelopmentCapabilitiesState.Unavailable(capabilities?.capabilities.orEmpty())
    }
  }

  private fun projectNames(): List<String> =
    when (val state = mutableCatalog.value) {
      is ProjectCatalogState.Ready -> state.projects.map(ProjectRecord::displayName)
      is ProjectCatalogState.Unavailable -> state.retainedProjects.map(ProjectRecord::displayName)
      ProjectCatalogState.Loading -> emptyList()
    }

  private fun project(projectId: String): ProjectRecord? =
    when (val state = mutableCatalog.value) {
      is ProjectCatalogState.Ready -> state.projects.firstOrNull { it.id == projectId }
      is ProjectCatalogState.Unavailable -> state.retainedProjects.firstOrNull { it.id == projectId }
      ProjectCatalogState.Loading -> null
    }
}

internal fun validateProjectName(value: String): String? {
  val normalized = normalizeName(value)
  return when {
    normalized.isEmpty() -> "Project name is required."
    normalized.codePointCount(0, normalized.length) > PROJECT_NAME_LIMIT -> "Project name is too long."
    normalized.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f } ->
      "Project name cannot contain path separators or control characters."
    else -> null
  }
}

internal fun allocateDefaultName(names: List<String>): String {
  val keys = names.map(::projectNameKey).toSet()
  var suffix = 1
  while (true) {
    val candidate = if (suffix == 1) "New Project" else "New Project $suffix"
    if (projectNameKey(candidate) !in keys) return candidate
    suffix += 1
  }
}

private fun normalizeName(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

private fun projectNameKey(value: String): String = normalizeName(value).lowercase(Locale.ROOT)
