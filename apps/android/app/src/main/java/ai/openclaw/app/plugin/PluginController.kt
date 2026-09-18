package ai.openclaw.app.plugin

import ai.openclaw.app.ai.GatewayRestartCoordinator
import ai.openclaw.app.ai.GatewayRestartState
import ai.openclaw.app.extensions.ExtensionGatewayConnection
import ai.openclaw.app.extensions.ExtensionGatewayTransport
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.node.asObjectOrNull
import ai.openclaw.app.node.asStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

private const val PLUGIN_RESTART_OWNER_ID = "plugin-lifecycle"

/** Owns Gateway Plugin inventory, inspection, and mutation state. */
internal class PluginController(
  private val scope: CoroutineScope,
  private val transport: ExtensionGatewayTransport,
  private val json: Json,
  private val iconLoader: suspend (ExtensionGatewayConnection, String) -> GatewayPluginIconPayload?,
  private val restart: GatewayRestartCoordinator,
  private val incompatibleApiMessage: String =
    "This Plugin requires a newer OpenClaw Runtime. Update OpenClaw, then try again.",
  private val genericChangeFailureMessage: String = "Could not change this Plugin.",
  actionsEnabled: Boolean = true,
) {
  private data class PendingMutation(
    val connection: ExtensionGatewayConnection,
    val intent: GatewayPluginMutationIntent,
    val acknowledgeInstallPolicyWarning: Boolean = false,
    val capabilityReviewToken: String? = null,
  )

  private data class MutationReceipt(
    val restartRequired: Boolean,
    val warnings: List<String>,
    val removed: List<String> = emptyList(),
  )

  private data class PendingRestart(
    val intent: GatewayPluginMutationIntent,
    val warnings: List<String>,
    val removed: List<String>,
  )

  private val mutableState = MutableStateFlow(PluginState())
  val state = mutableState.asStateFlow()
  private val searchSequence = AtomicLong()
  private val inspectionSequence = AtomicLong()
  private val mutationMutex = Mutex()
  private val stateLock = Any()
  private var activeConnection: ExtensionGatewayConnection? = null
  private var pendingMutation: PendingMutation? = null
  private var pendingRestart: PendingRestart? = null
  private var restartVerificationInFlight = false

  init {
    scope.launch { restart.state.collect(::onRestartState) }
  }

  val feature =
    PluginFeature(
      state = state,
      actions =
        PluginActions(
          refresh = { launch(actionsEnabled, ::refresh) },
          search = { query -> launch(actionsEnabled) { search(query) } },
          inspect = { pluginId -> launch(actionsEnabled) { inspect(pluginId) } },
          requestInstall = { intent -> if (actionsEnabled) requestInstall(intent) },
          requestSetEnabled = { pluginId, displayName, enabled ->
            if (actionsEnabled) requestSetEnabled(pluginId, displayName, enabled)
          },
          requestUninstall = { pluginId, displayName ->
            if (actionsEnabled) requestUninstall(pluginId, displayName)
          },
          confirmMutation = { if (actionsEnabled) confirmMutation() },
          confirmInstallPolicy = { if (actionsEnabled) confirmInstallPolicy() },
          confirmCapabilities = { if (actionsEnabled) confirmCapabilities() },
          dismissMutation = ::dismissMutation,
          reconcileMutation = { launch(actionsEnabled, ::reconcileMutation) },
        ),
      loadIcon = { pluginId -> loadIcon(pluginId) },
    )

  fun onConnectionChanged() {
    val connection = transport.capture()
    val changed =
      synchronized(stateLock) {
        if (activeConnection == connection) {
          false
        } else {
          activeConnection = connection
          if (pendingRestart == null) {
            pendingMutation = null
          } else {
            restartVerificationInFlight = false
          }
          true
        }
      }
    if (changed && pendingRestart != null) {
      retireRequests()
      mutableState.value =
        mutableState.value.copy(
          connected = connection != null,
          adminScope = connection?.adminScope == true,
          capabilities = pluginGatewayCapabilities(connection?.methods.orEmpty()),
        )
      resumeRestartVerification()
    } else if (changed) {
      retireRequests()
      mutableState.value =
        PluginState(
          connected = connection != null,
          adminScope = connection?.adminScope == true,
          capabilities = pluginGatewayCapabilities(connection?.methods.orEmpty()),
        )
    } else if (connection != null) {
      mutableState.value =
        mutableState.value.copy(
          connected = true,
          adminScope = connection.adminScope,
          capabilities = pluginGatewayCapabilities(connection.methods),
        )
    }
  }

  fun clear() {
    val preserveRestart =
      synchronized(stateLock) {
        activeConnection = null
        pendingMutation = null
        if (pendingRestart != null) restartVerificationInFlight = false
        pendingRestart != null
      }
    retireRequests()
    if (preserveRestart) {
      mutableState.value =
        mutableState.value.copy(
          connected = false,
          adminScope = false,
          capabilities = PluginGatewayCapabilities(),
        )
    } else {
      mutableState.value = PluginState()
    }
  }

  internal fun applyFixture(state: PluginState) {
    synchronized(stateLock) {
      activeConnection = null
      pendingMutation = null
      pendingRestart = null
      restartVerificationInFlight = false
    }
    retireRequests()
    mutableState.value = state
  }

  private fun launch(
    enabled: Boolean,
    operation: suspend () -> Unit,
  ) {
    if (!enabled) return
    scope.launch(start = CoroutineStart.UNDISPATCHED) { operation() }
  }

  private fun retireRequests() {
    searchSequence.incrementAndGet()
    inspectionSequence.incrementAndGet()
  }

  private fun captureActive(): ExtensionGatewayConnection? {
    val captured = transport.capture() ?: return null
    return synchronized(stateLock) { captured.takeIf { it == activeConnection } }
  }

  private fun publish(
    connection: ExtensionGatewayConnection,
    update: (PluginState) -> PluginState,
  ): Boolean = transport.publish(connection) { mutableState.value = update(mutableState.value) }

  private suspend fun refresh(): Boolean {
    val connection = captureActive()
    if (connection == null) {
      mutableState.value = PluginState(errorText = nativeString("Connect the gateway to load Plugins."))
      return false
    }
    if (!state.value.capabilities.inventory) {
      publish(connection) { it.copy(errorText = nativeString("Update OpenClaw to load Plugins.")) }
      return false
    }
    publish(connection) { it.copy(refreshing = true, errorText = null) }
    return try {
      val summary = readCatalog(connection)
      publish(connection) { it.copy(summary = summary, refreshing = false, errorText = null) }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      publish(connection) { it.copy(refreshing = false, errorText = nativeString("Could not load Plugins.")) }
      false
    }
  }

  private suspend fun search(query: String) {
    val normalized = query.trim()
    val sequence = searchSequence.incrementAndGet()
    if (normalized.isEmpty()) {
      mutableState.value = mutableState.value.copy(search = GatewayPluginSearchState())
      return
    }
    val connection = captureActive()
    if (connection == null) {
      mutableState.value =
        mutableState.value.copy(
          search = GatewayPluginSearchState(query = normalized, errorText = nativeString("Connect the gateway to search ClawHub Plugins.")),
        )
      return
    }
    if (!state.value.capabilities.search) {
      publish(connection) {
        it.copy(search = GatewayPluginSearchState(query = normalized, errorText = nativeString("Update OpenClaw to search ClawHub Plugins.")))
      }
      return
    }
    publish(connection) { it.copy(search = GatewayPluginSearchState(query = normalized, searching = true)) }
    try {
      val results = parsePluginSearchResults(transport.request(connection, "plugins.search", pluginSearchParams(normalized)), json)
      if (searchSequence.get() == sequence) {
        publish(connection) { it.copy(search = GatewayPluginSearchState(query = normalized, results = results)) }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      if (searchSequence.get() == sequence) {
        publish(connection) {
          it.copy(search = GatewayPluginSearchState(query = normalized, errorText = nativeString("Could not search ClawHub Plugins.")))
        }
      }
    }
  }

  private suspend fun inspect(pluginId: String) {
    val normalized = pluginId.trim()
    if (normalized.isEmpty()) return
    val sequence = inspectionSequence.incrementAndGet()
    val connection = captureActive()
    if (connection == null) {
      mutableState.value =
        mutableState.value.copy(
          inspection = GatewayPluginInspectionState.Error(normalized, nativeString("Connect the gateway to inspect this Plugin.")),
        )
      return
    }
    if (!state.value.capabilities.inspect) {
      publish(connection) {
        it.copy(
          inspection = GatewayPluginInspectionState.Error(normalized, nativeString("Update OpenClaw to inspect Plugin capabilities.")),
        )
      }
      return
    }
    publish(connection) { it.copy(inspection = GatewayPluginInspectionState.Loading(normalized)) }
    try {
      val inspection =
        parsePluginInspection(
          transport.request(connection, "plugins.inspect", pluginInspectParams(normalized)),
          json,
        ) ?: error("Malformed plugins.inspect response")
      if (inspectionSequence.get() == sequence) {
        publish(connection) {
          it.copy(inspection = GatewayPluginInspectionState.Ready(normalized, inspection.toDetails()))
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      if (inspectionSequence.get() == sequence) {
        publish(connection) {
          it.copy(inspection = GatewayPluginInspectionState.Error(normalized, nativeString("Could not inspect this Plugin.")))
        }
      }
    }
  }

  private fun requestInstall(intent: GatewayPluginMutationIntent.Install) {
    if (intent.displayName.isBlank()) return
    requestConfirmation(intent)
  }

  private fun requestSetEnabled(
    pluginId: String,
    displayName: String,
    enabled: Boolean,
  ) {
    val normalized = pluginId.trim()
    if (normalized.isEmpty() || displayName.isBlank()) return
    startMutation(GatewayPluginMutationIntent.SetEnabled(normalized, displayName, enabled))
  }

  private fun requestUninstall(
    pluginId: String,
    displayName: String,
  ) {
    val normalized = pluginId.trim()
    if (normalized.isEmpty() || displayName.isBlank()) return
    requestConfirmation(GatewayPluginMutationIntent.Uninstall(normalized, displayName))
  }

  private fun requestConfirmation(intent: GatewayPluginMutationIntent) {
    synchronized(stateLock) {
      if (state.value.mutation.blocksAnotherMutation()) return
      pendingMutation = null
      mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Confirmation(intent))
    }
  }

  private fun startMutation(intent: GatewayPluginMutationIntent) {
    val connection = captureActive()
    val pending =
      synchronized(stateLock) {
        if (state.value.mutation.blocksAnotherMutation()) return
        if (connection == null) {
          mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Failed(intent, nativeString("Reconnect before changing this Plugin.")))
          return
        }
        pendingMutation = null
        mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Working(intent, intent.initialStage()))
        PendingMutation(connection, intent)
      }
    scope.launch { runMutation(pending) }
  }

  private fun confirmMutation() {
    val connection = captureActive()
    val pending =
      synchronized(stateLock) {
        val current = state.value.mutation as? GatewayPluginMutationState.Confirmation ?: return
        if (connection == null) {
          mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Failed(current.intent, nativeString("Reconnect before changing this Plugin.")))
          return
        }
        mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Working(current.intent, current.intent.initialStage()))
        PendingMutation(connection, current.intent)
      }
    scope.launch { runMutation(pending) }
  }

  private fun confirmInstallPolicy() {
    val pending =
      synchronized(stateLock) {
        val current = state.value.mutation as? GatewayPluginMutationState.PolicyReview ?: return
        val continuation = pendingMutation?.takeIf { it.intent == current.intent } ?: return
        mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Working(current.intent, GatewayPluginMutationStage.Installing))
        continuation.copy(acknowledgeInstallPolicyWarning = true)
      }
    scope.launch { runMutation(pending) }
  }

  private fun confirmCapabilities() {
    val pending =
      synchronized(stateLock) {
        val current = state.value.mutation as? GatewayPluginMutationState.CapabilityReview ?: return
        val continuation = pendingMutation?.takeIf { it.intent == current.intent } ?: return
        mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Working(current.intent, current.intent.initialStage()))
        continuation
      }
    scope.launch { runMutation(pending) }
  }

  private fun dismissMutation() {
    synchronized(stateLock) {
      if (state.value.mutation is GatewayPluginMutationState.Working) return
      pendingMutation = null
      mutableState.value = state.value.copy(mutation = GatewayPluginMutationState.Idle)
    }
  }

  private suspend fun reconcileMutation() {
    val intent = (state.value.mutation as? GatewayPluginMutationState.UnknownOutcome)?.intent ?: return
    val connection = captureActive()
    if (connection == null) {
      failMutation(intent, nativeString("Reconnect before refreshing the Plugin catalog."))
      return
    }
    publish(connection) { it.copy(mutation = GatewayPluginMutationState.Working(intent, GatewayPluginMutationStage.Reconciling)) }
    try {
      val catalog = readCatalog(connection)
      val restarting = synchronized(stateLock) { pendingRestart?.takeIf { it.intent == intent } }
      val published =
        publish(connection) { current ->
          current.copy(
            summary = catalog,
            errorText = null,
            mutation =
              if (catalog.confirms(intent)) {
                GatewayPluginMutationState.Succeeded(
                  intent,
                  restartRequired = if (restarting == null) null else false,
                  warnings = restarting?.warnings.orEmpty(),
                  removed = restarting?.removed.orEmpty(),
                )
              } else {
                GatewayPluginMutationState.Failed(intent, nativeString("The refreshed catalog did not confirm the Plugin change. You can try again."))
              },
          )
        }
      if (published && catalog.confirms(intent) && restarting != null) completeRestart()
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      publish(connection) {
        it.copy(mutation = GatewayPluginMutationState.UnknownOutcome(intent, nativeString("Could not refresh the Plugin catalog. Reconnect and try again.")))
      }
    }
  }

  private suspend fun runMutation(pending: PendingMutation) {
    mutationMutex.withLock {
      val intent = pending.intent
      if (captureActive() != pending.connection) {
        failMutation(intent, nativeString("Reconnect before changing this Plugin."))
        return@withLock
      }
      val current = state.value
      if (!pending.connection.adminScope) {
        failMutation(intent, nativeString("This gateway connection needs operator.admin to manage Plugins."), pending.connection)
        return@withLock
      }
      if (!current.capabilities.supports(intent) || !current.summary.mutationAllowed) {
        failMutation(intent, nativeString("This Gateway does not allow that Plugin change."), pending.connection)
        return@withLock
      }
      synchronized(stateLock) { pendingMutation = null }
      publish(pending.connection) { it.copy(mutation = GatewayPluginMutationState.Working(intent, intent.initialStage())) }
      try {
        val receipt = performMutation(pending)
        publish(pending.connection) { it.copy(mutation = GatewayPluginMutationState.Working(intent, GatewayPluginMutationStage.Reconciling)) }
        val catalog = readCatalog(pending.connection)
        if (!catalog.confirms(intent)) {
          publish(pending.connection) { state ->
            state.copy(
              summary = catalog,
              errorText = null,
              mutation = GatewayPluginMutationState.UnknownOutcome(intent, nativeString("The change returned, but the refreshed Plugin catalog did not confirm it. Refresh again before retrying.")),
            )
          }
        } else if (receipt.restartRequired) {
          synchronized(stateLock) {
            pendingRestart = PendingRestart(intent, receipt.warnings, receipt.removed)
            restartVerificationInFlight = false
          }
          publish(pending.connection) { state ->
            state.copy(
              summary = catalog,
              errorText = null,
              mutation = GatewayPluginMutationState.Working(intent, GatewayPluginMutationStage.Restarting),
            )
          }
          restart.request(PLUGIN_RESTART_OWNER_ID, "Apply ${intent.displayName} Plugin change")
          val restartState = restart.state.value
          if (restartState.ownedByPluginLifecycle()) {
            onRestartState(restartState)
          } else {
            synchronized(stateLock) { pendingRestart = null }
            publish(pending.connection) {
              it.copy(mutation = GatewayPluginMutationState.Failed(intent, nativeString("Another OpenClaw restart is already in progress.")))
            }
          }
        } else {
          publish(pending.connection) { state ->
            state.copy(
              summary = catalog,
              errorText = null,
              mutation = GatewayPluginMutationState.Succeeded(intent, false, receipt.warnings, receipt.removed),
            )
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: GatewayRequestRejected) {
        handleMutationRejection(pending, error)
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(pending.connection) {
          it.copy(mutation = GatewayPluginMutationState.UnknownOutcome(intent, nativeString("The Plugin change result is unknown. Reconnect and refresh before retrying.")))
        }
      } catch (_: Throwable) {
        if (captureActive() == pending.connection) {
          failMutation(intent, nativeString("Could not change this Plugin."), pending.connection)
        }
      }
    }
  }

  private suspend fun performMutation(pending: PendingMutation): MutationReceipt =
    when (val intent = pending.intent) {
      is GatewayPluginMutationIntent.Install -> {
        val result =
          parsePluginMutationResult(
            transport.request(
              pending.connection,
              "plugins.install",
              pluginInstallParams(intent.action, intent.version, pending.acknowledgeInstallPolicyWarning, pending.capabilityReviewToken),
              PLUGIN_MUTATION_REQUEST_TIMEOUT_MS,
            ),
            json,
          ) ?: error("Malformed plugins.install response")
        MutationReceipt(result.restartRequired, result.warnings)
      }
      is GatewayPluginMutationIntent.SetEnabled -> {
        val result =
          parsePluginMutationResult(
            transport.request(
              pending.connection,
              "plugins.setEnabled",
              pluginSetEnabledParams(intent.pluginId, intent.enabled, pending.capabilityReviewToken),
              PLUGIN_MUTATION_REQUEST_TIMEOUT_MS,
            ),
            json,
          ) ?: error("Malformed plugins.setEnabled response")
        MutationReceipt(result.restartRequired, result.warnings)
      }
      is GatewayPluginMutationIntent.Uninstall -> {
        val result =
          parsePluginUninstallResult(
            transport.request(
              pending.connection,
              "plugins.uninstall",
              pluginUninstallParams(intent.pluginId),
              PLUGIN_MUTATION_REQUEST_TIMEOUT_MS,
            ),
            json,
          ) ?: error("Malformed plugins.uninstall response")
        if (result.pluginId != intent.pluginId) error("Plugin uninstall identity changed")
        MutationReceipt(result.restartRequired, result.warnings, result.removed)
      }
    }

  private suspend fun handleMutationRejection(
    pending: PendingMutation,
    error: GatewayRequestRejected,
  ) {
    when (val challenge = parsePluginMutationChallenge(error.gatewayError.rawDetailsJson, json)) {
      is PluginMutationChallenge.InstallPolicy -> {
        val installIntent = pending.intent as? GatewayPluginMutationIntent.Install
        if (installIntent == null) {
          failMutation(pending.intent, error.gatewayError.message, pending.connection)
        } else {
          synchronized(stateLock) { pendingMutation = pending }
          publish(pending.connection) { it.copy(mutation = GatewayPluginMutationState.PolicyReview(installIntent, challenge.value)) }
        }
      }
      is PluginMutationChallenge.CapabilityConsent -> {
        publish(pending.connection) { it.copy(mutation = GatewayPluginMutationState.Working(pending.intent, GatewayPluginMutationStage.Inspecting)) }
        try {
          val inspection =
            parsePluginInspection(
              transport.request(pending.connection, "plugins.inspect", pluginInspectParams(challenge.value.pluginId)),
              json,
            ) ?: error("Malformed plugins.inspect response")
          if (inspection.plugin.id != challenge.value.pluginId) error("Plugin inspection identity changed")
          val continuation = pending.copy(capabilityReviewToken = inspection.reviewToken)
          synchronized(stateLock) { pendingMutation = continuation }
          publish(pending.connection) {
            it.copy(
              mutation =
                GatewayPluginMutationState.CapabilityReview(
                  pending.intent,
                  challenge.value.pluginId,
                  inspection.toDetails(),
                  challenge.value.widened,
                  challenge.value.acceptedAt,
                ),
            )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          failMutation(pending.intent, nativeString("Could not load the Plugin capability review."), pending.connection)
        }
      }
      null -> failMutation(pending.intent, mutationFailureMessage(error.gatewayError), pending.connection)
    }
  }

  private fun mutationFailureMessage(error: GatewaySession.ErrorShape): String {
    val detailsCode =
      error.details?.code
        ?: error.rawDetailsJson
          ?.let { raw ->
            runCatching {
              json
                .parseToJsonElement(raw)
                .asObjectOrNull()
                ?.get("code")
                .asStringOrNull()
            }.getOrNull()
          }
    val incompatible =
      sequenceOf(error.code, detailsCode)
        .filterNotNull()
        .any { it.equals("incompatible_plugin_api", ignoreCase = true) } ||
        error.message.contains("\"code\":\"incompatible_plugin_api\"", ignoreCase = true)
    if (incompatible) return incompatibleApiMessage
    return error.message
      .substringBefore(" | {")
      .trim()
      .ifEmpty { genericChangeFailureMessage }
  }

  private suspend fun readCatalog(connection: ExtensionGatewayConnection): GatewayPluginCatalogSummary = parsePluginCatalog(transport.request(connection, "plugins.list", "{}"), json)

  private suspend fun loadIcon(pluginId: String): GatewayPluginIconPayload? {
    val normalized = pluginId.trim()
    if (normalized.isEmpty()) return null
    val connection = captureActive() ?: return null
    return iconLoader(connection, normalized).takeIf { captureActive() == connection }
  }

  private fun failMutation(
    intent: GatewayPluginMutationIntent?,
    message: String,
    connection: ExtensionGatewayConnection? = null,
  ) {
    synchronized(stateLock) { pendingMutation = null }
    val update: (PluginState) -> PluginState = {
      it.copy(mutation = GatewayPluginMutationState.Failed(intent, message))
    }
    if (connection == null) {
      mutableState.value = update(mutableState.value)
    } else {
      publish(connection, update)
    }
  }

  private fun onRestartState(restartState: GatewayRestartState) {
    if (!restartState.ownedByPluginLifecycle()) return
    val pending = synchronized(stateLock) { pendingRestart } ?: return
    when (restartState) {
      is GatewayRestartState.Checking -> updateRestartStage(pending, GatewayPluginMutationStage.Restarting)
      is GatewayRestartState.WaitingForSafeRestart -> updateRestartStage(pending, GatewayPluginMutationStage.WaitingForRestart)
      is GatewayRestartState.Restarting -> updateRestartStage(pending, GatewayPluginMutationStage.Restarting)
      is GatewayRestartState.Reconnecting,
      is GatewayRestartState.UnknownOutcome,
      -> updateRestartStage(pending, GatewayPluginMutationStage.Reconnecting)
      is GatewayRestartState.Ready -> resumeRestartVerification()
      is GatewayRestartState.Failed -> {
        synchronized(stateLock) {
          pendingRestart = null
          restartVerificationInFlight = false
        }
        mutableState.value = mutableState.value.copy(mutation = GatewayPluginMutationState.Failed(pending.intent, restartState.message))
        restart.consume(PLUGIN_RESTART_OWNER_ID)
      }
      GatewayRestartState.Idle -> Unit
    }
  }

  private fun updateRestartStage(
    pending: PendingRestart,
    stage: GatewayPluginMutationStage,
  ) {
    mutableState.value = mutableState.value.copy(mutation = GatewayPluginMutationState.Working(pending.intent, stage))
  }

  private fun resumeRestartVerification() {
    if (restart.state.value !is GatewayRestartState.Ready) return
    val connection = captureActive() ?: return
    val pending =
      synchronized(stateLock) {
        val current = pendingRestart ?: return
        if (restartVerificationInFlight) return
        restartVerificationInFlight = true
        current
      }
    mutableState.value = mutableState.value.copy(mutation = GatewayPluginMutationState.Working(pending.intent, GatewayPluginMutationStage.Reconciling))
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val catalog = readCatalog(connection)
        if (!catalog.confirms(pending.intent)) error("Restarted catalog did not confirm mutation")
        val published =
          publish(connection) {
            it.copy(
              summary = catalog,
              errorText = null,
              mutation = GatewayPluginMutationState.Succeeded(pending.intent, false, pending.warnings, pending.removed),
            )
          }
        if (published) {
          completeRestart()
        } else {
          synchronized(stateLock) { restartVerificationInFlight = false }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        synchronized(stateLock) { restartVerificationInFlight = false }
        if (captureActive() == connection) {
          publish(connection) {
            it.copy(mutation = GatewayPluginMutationState.UnknownOutcome(pending.intent, nativeString("OpenClaw restarted, but the Plugin status could not be verified. Refresh to check again.")))
          }
        }
      }
    }
  }

  private fun completeRestart() {
    synchronized(stateLock) {
      pendingRestart = null
      restartVerificationInFlight = false
    }
    restart.consume(PLUGIN_RESTART_OWNER_ID)
  }
}

private fun GatewayRestartState.ownedByPluginLifecycle(): Boolean =
  when (this) {
    GatewayRestartState.Idle -> false
    is GatewayRestartState.Checking -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.WaitingForSafeRestart -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.Restarting -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.Reconnecting -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.UnknownOutcome -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.Ready -> ownerId == PLUGIN_RESTART_OWNER_ID
    is GatewayRestartState.Failed -> ownerId == PLUGIN_RESTART_OWNER_ID
  }

private fun GatewayPluginMutationState.blocksAnotherMutation(): Boolean =
  when (this) {
    is GatewayPluginMutationState.Confirmation,
    is GatewayPluginMutationState.Working,
    is GatewayPluginMutationState.PolicyReview,
    is GatewayPluginMutationState.CapabilityReview,
    is GatewayPluginMutationState.UnknownOutcome,
    -> true
    GatewayPluginMutationState.Idle,
    is GatewayPluginMutationState.Succeeded,
    is GatewayPluginMutationState.Failed,
    -> false
  }

private fun GatewayPluginMutationIntent.initialStage(): GatewayPluginMutationStage =
  when (this) {
    is GatewayPluginMutationIntent.Install -> GatewayPluginMutationStage.Installing
    is GatewayPluginMutationIntent.SetEnabled -> if (enabled) GatewayPluginMutationStage.Enabling else GatewayPluginMutationStage.Disabling
    is GatewayPluginMutationIntent.Uninstall -> GatewayPluginMutationStage.Removing
  }

private fun PluginGatewayCapabilities.supports(intent: GatewayPluginMutationIntent): Boolean =
  when (intent) {
    is GatewayPluginMutationIntent.Install -> install
    is GatewayPluginMutationIntent.SetEnabled -> setEnabled
    is GatewayPluginMutationIntent.Uninstall -> uninstall
  }
