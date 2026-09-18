package ai.openclaw.app.skill

import ai.openclaw.app.CLAWHUB_INSTALL_REQUEST_TIMEOUT_MS
import ai.openclaw.app.CLAWHUB_SKILL_GATEWAY_UNAVAILABLE
import ai.openclaw.app.GatewayClawHubSkillSearchState
import ai.openclaw.app.GatewayClawHubSkillSummary
import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.GatewaySkillsSummary
import ai.openclaw.app.clawHubDetailParams
import ai.openclaw.app.clawHubInstallOutcomeUnknownMessage
import ai.openclaw.app.clawHubInstallParams
import ai.openclaw.app.clawHubInstallRejection
import ai.openclaw.app.clawHubSearchParams
import ai.openclaw.app.extensions.ExtensionGatewayConnection
import ai.openclaw.app.extensions.ExtensionGatewayTransport
import ai.openclaw.app.formatClawHubInstallMessage
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.isClawHubSkillInstalled
import ai.openclaw.app.isClawHubSkillInstalledByReference
import ai.openclaw.app.node.asObjectOrNull
import ai.openclaw.app.node.asStringOrNull
import ai.openclaw.app.parseClawHubInstallReview
import ai.openclaw.app.parseClawHubSearchResults
import ai.openclaw.app.skillEnabledParams
import ai.openclaw.app.supportsClawHubSkillManagement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.atomic.AtomicLong

/** Owns Gateway Skill inventory, enablement, and ClawHub install lifecycle. */
internal class SkillController(
  private val scope: CoroutineScope,
  private val transport: ExtensionGatewayTransport,
  private val json: Json,
  private val beforeInstallClaim: (() -> Unit)? = null,
  actionsEnabled: Boolean = true,
) {
  private val mutableState = MutableStateFlow(SkillState())
  val state = mutableState.asStateFlow()
  private val searchSequence = AtomicLong()
  private val reviewSequence = AtomicLong()
  private val installMutex = Mutex()
  private val stateLock = Any()
  private var activeConnection: ExtensionGatewayConnection? = null

  val feature =
    SkillFeature(
      state = state,
      actions =
        SkillActions(
          refresh = { launch(actionsEnabled, ::refresh) },
          setEnabled = { key, enabled -> launch(actionsEnabled) { setEnabled(key, enabled) } },
          searchClawHub = { query -> launch(actionsEnabled) { searchClawHub(query) } },
          reviewClawHubInstall = { skill -> if (actionsEnabled) reviewClawHubInstall(skill) },
          dismissInstallReview = ::dismissInstallReview,
          installClawHub = { slug, version -> launch(actionsEnabled) { installClawHub(slug, version) } },
          clearClawHubNotice = ::clearClawHubNotice,
        ),
    )

  fun onConnectionChanged() {
    val connection = transport.capture()
    val changed =
      synchronized(stateLock) {
        if (activeConnection == connection) {
          false
        } else {
          activeConnection = connection
          true
        }
      }
    if (changed) {
      retireRequests()
      mutableState.value =
        SkillState(
          connected = connection != null,
          adminScope = connection?.adminScope == true,
          installMethodsAvailable = supportsClawHubSkillManagement(connection?.methods.orEmpty()),
        )
    } else if (connection != null) {
      mutableState.value =
        mutableState.value.copy(
          connected = true,
          adminScope = connection.adminScope,
          installMethodsAvailable = supportsClawHubSkillManagement(connection.methods),
        )
    }
  }

  fun clear() {
    synchronized(stateLock) { activeConnection = null }
    retireRequests()
    mutableState.value = SkillState()
  }

  internal fun applyFixture(state: SkillState) {
    synchronized(stateLock) { activeConnection = null }
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
    reviewSequence.incrementAndGet()
  }

  private fun captureActive(): ExtensionGatewayConnection? {
    val captured = transport.capture() ?: return null
    return synchronized(stateLock) { captured.takeIf { it == activeConnection } }
  }

  private fun publish(
    connection: ExtensionGatewayConnection,
    update: (SkillState) -> SkillState,
  ): Boolean = transport.publish(connection) { mutableState.value = update(mutableState.value) }

  private suspend fun refresh(): Boolean {
    val connection = captureActive()
    if (connection == null) {
      mutableState.value = SkillState(errorText = nativeString("Connect the gateway to load skills."))
      return false
    }
    return refresh(connection)
  }

  private suspend fun refresh(connection: ExtensionGatewayConnection): Boolean {
    if (captureActive() != connection) return false
    publish(connection) { it.copy(refreshing = true, errorText = null) }
    return try {
      val summary = readSummary(connection)
      publish(connection) { it.copy(summary = summary, loaded = true, refreshing = false, errorText = null) }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      publish(connection) { it.copy(refreshing = false, errorText = nativeString("Could not load skills.")) }
      false
    }
  }

  private suspend fun setEnabled(
    skillKey: String,
    enabled: Boolean,
  ) {
    val normalized = skillKey.trim()
    if (normalized.isEmpty()) return
    val connection = captureActive()
    if (connection == null) {
      mutableState.value = mutableState.value.copy(errorText = nativeString("Connect the gateway to update skills."))
      return
    }
    if (!connection.adminScope) {
      publish(connection) { it.copy(errorText = nativeString("This gateway connection needs operator.admin to update skills.")) }
      return
    }
    publish(connection) { it.copy(mutationKeys = it.mutationKeys + normalized, errorText = null) }
    try {
      transport.request(connection, "skills.update", skillEnabledParams(normalized, enabled))
      refresh(connection)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      publish(connection) { it.copy(errorText = nativeString(if (enabled) "Could not enable skill." else "Could not disable skill.")) }
    } finally {
      publish(connection) { it.copy(mutationKeys = it.mutationKeys - normalized) }
    }
  }

  private suspend fun searchClawHub(query: String) {
    val normalized = query.trim()
    val sequence = searchSequence.incrementAndGet()
    reviewSequence.incrementAndGet()
    val connection = captureActive()
    if (connection == null) {
      mutableState.value =
        mutableState.value.copy(
          clawHub = GatewayClawHubSkillSearchState(query = normalized, errorText = nativeString("Connect the gateway to search ClawHub skills.")),
        )
      return
    }
    if (!state.value.installMethodsAvailable) {
      publish(connection) { it.copy(clawHub = it.clawHub.copy(errorText = CLAWHUB_SKILL_GATEWAY_UNAVAILABLE)) }
      return
    }
    publish(connection) {
      it.copy(
        clawHub =
          it.clawHub.copy(
            query = normalized,
            searching = true,
            results = emptyList(),
            reviewingSlug = null,
            installReview = null,
            errorText = null,
            messageText = null,
          ),
      )
    }
    try {
      val results = parseClawHubSearchResults(transport.request(connection, "skills.search", clawHubSearchParams(normalized)), json)
      if (searchSequence.get() == sequence) {
        publish(connection) {
          it.copy(clawHub = it.clawHub.copy(searching = false, results = results, messageText = if (results.isEmpty()) "No ClawHub skills matched." else null))
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      if (searchSequence.get() == sequence) {
        publish(connection) { it.copy(clawHub = it.clawHub.copy(searching = false, errorText = nativeString("Could not search ClawHub skills."))) }
      }
    }
  }

  private fun reviewClawHubInstall(skill: GatewayClawHubSkillSummary) {
    if (skill.slug.isBlank()) return
    val normalized = skill.copy(slug = skill.slug.trim())
    if (!normalized.canReadDetails) {
      launch(true) { installClawHub(normalized.reference, null) }
      return
    }
    launch(true) { reviewClawHubInstallFromGateway(normalized) }
  }

  private suspend fun reviewClawHubInstallFromGateway(skill: GatewayClawHubSkillSummary) {
    val sequence = reviewSequence.incrementAndGet()
    val connection = captureActive()
    if (connection == null) {
      mutableState.value = mutableState.value.copy(clawHub = state.value.clawHub.copy(errorText = nativeString("Connect the gateway to inspect ClawHub skills.")))
      return
    }
    if (!state.value.installMethodsAvailable) {
      publish(connection) { it.copy(clawHub = it.clawHub.copy(errorText = CLAWHUB_SKILL_GATEWAY_UNAVAILABLE)) }
      return
    }
    publish(connection) {
      it.copy(clawHub = it.clawHub.copy(reviewingSlug = skill.reference, installReview = null, errorText = null, messageText = null))
    }
    try {
      val review = parseClawHubInstallReview(transport.request(connection, "skills.detail", clawHubDetailParams(skill.reference)), skill, json)
      if (reviewSequence.get() == sequence) {
        publish(connection) {
          it.copy(
            clawHub =
              it.clawHub.copy(
                reviewingSlug = null,
                installReview = review,
                errorText = if (review == null) "ClawHub did not return an installable version for ${skill.reference}." else null,
              ),
          )
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      if (reviewSequence.get() == sequence) {
        publish(connection) {
          it.copy(clawHub = it.clawHub.copy(reviewingSlug = null, errorText = nativeString("Could not load ClawHub details for \${skill.reference}.", skill.reference)))
        }
      }
    }
  }

  private fun dismissInstallReview() {
    reviewSequence.incrementAndGet()
    mutableState.value = mutableState.value.copy(clawHub = state.value.clawHub.copy(reviewingSlug = null, installReview = null))
  }

  private fun clearClawHubNotice() {
    reviewSequence.incrementAndGet()
    mutableState.value =
      mutableState.value.copy(
        clawHub = state.value.clawHub.copy(reviewingSlug = null, installReview = null, errorText = null, messageText = null),
      )
  }

  private suspend fun installClawHub(
    slug: String,
    version: String?,
  ) {
    val normalized = slug.trim()
    if (normalized.isEmpty()) return
    val connection = captureActive()
    if (connection == null) {
      mutableState.value = mutableState.value.copy(clawHub = state.value.clawHub.copy(errorText = nativeString("Connect the gateway to install ClawHub skills.")))
      return
    }
    if (!state.value.installMethodsAvailable) {
      publish(connection) { it.copy(clawHub = it.clawHub.copy(errorText = CLAWHUB_SKILL_GATEWAY_UNAVAILABLE)) }
      return
    }
    if (!connection.adminScope) {
      publish(connection) {
        it.copy(clawHub = it.clawHub.copy(errorText = nativeString("This gateway connection needs operator.admin to install ClawHub skills.")))
      }
      return
    }
    beforeInstallClaim?.invoke()
    val claimed =
      installMutex.withLock {
        var published = false
        publish(connection) { current ->
          if (normalized in current.clawHub.installingSlugs) {
            current
          } else {
            published = true
            current.copy(clawHub = current.clawHub.copy(installingSlugs = current.clawHub.installingSlugs + normalized))
          }
        }
        published
      }
    if (!claimed) return
    val attemptedVersion = version?.trim()?.takeIf(String::isNotEmpty)
    publish(connection) { it.copy(clawHub = it.clawHub.copy(installReview = null, errorText = null, messageText = null)) }
    try {
      val response = transport.request(connection, "skills.install", clawHubInstallParams(normalized, attemptedVersion), CLAWHUB_INSTALL_REQUEST_TIMEOUT_MS)
      val root = json.parseToJsonElement(response).asObjectOrNull()
      val message =
        root
          ?.get("message")
          .asStringOrNull()
          ?.trim()
          ?.takeIf(String::isNotEmpty)
      val warning =
        root
          ?.get("warning")
          .asStringOrNull()
          ?.trim()
          ?.takeIf(String::isNotEmpty)
      val refreshed = refresh(connection)
      publish(connection) {
        it.copy(
          clawHub =
            it.clawHub.copy(
              messageText =
                formatClawHubInstallMessage(
                  message ?: "Installed $normalized.",
                  listOfNotNull(warning, if (refreshed) null else "Installed, but the skills list could not be refreshed.")
                    .joinToString("\n")
                    .ifBlank { null },
                ),
            ),
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: GatewayRequestOutcomeUnknown) {
      val confirmed = refreshAndConfirmInstall(connection, normalized, attemptedVersion)
      publish(connection) {
        it.copy(clawHub = it.clawHub.copy(errorText = if (confirmed) null else clawHubInstallOutcomeUnknownMessage(normalized), messageText = if (confirmed) "Installed $normalized." else null))
      }
    } catch (error: GatewayRequestRejected) {
      val confirmed = refreshAndConfirmInstall(connection, normalized, attemptedVersion)
      val rejection = if (confirmed) null else clawHubInstallRejection(error.gatewayError)
      publish(connection) {
        it.copy(
          clawHub =
            it.clawHub.copy(
              errorText = rejection?.let { value -> formatClawHubInstallMessage(value.message, value.warning) },
              messageText = if (confirmed) "Installed $normalized." else null,
            ),
        )
      }
    } catch (_: Throwable) {
      publish(connection) { it.copy(clawHub = it.clawHub.copy(errorText = nativeString("Could not install \${slug} from ClawHub.", normalized))) }
    } finally {
      releaseInstallClaim(normalized, connection)
    }
  }

  private suspend fun refreshAndConfirmInstall(
    connection: ExtensionGatewayConnection,
    slug: String,
    version: String?,
  ): Boolean {
    if (!refresh(connection) || captureActive() != connection) return false
    val skills = state.value.summary.skills
    return version?.let { isClawHubSkillInstalled(skills, slug, it) }
      ?: isClawHubSkillInstalledByReference(skills, slug)
  }

  private suspend fun releaseInstallClaim(
    slug: String,
    connection: ExtensionGatewayConnection,
  ) {
    installMutex.withLock {
      publish(connection) { it.copy(clawHub = it.clawHub.copy(installingSlugs = it.clawHub.installingSlugs - slug)) }
    }
  }

  private suspend fun readSummary(connection: ExtensionGatewayConnection): GatewaySkillsSummary {
    val root = json.parseToJsonElement(transport.request(connection, "skills.status", "{}")).asObjectOrNull()
    return GatewaySkillsSummary(
      managedSkillsDirAvailable =
        root
          ?.get("managedSkillsDir")
          .asStringOrNull()
          ?.trim()
          ?.isNotEmpty() == true,
      skills = parseSkillSummaries(root?.get("skills") as? JsonArray),
    )
  }
}

internal fun parseSkillSummaries(skills: JsonArray?): List<GatewaySkillSummary> =
  skills
    ?.mapNotNull { item ->
      val obj = item.asObjectOrNull() ?: return@mapNotNull null
      val name = obj["name"].asStringOrNull()?.trim().orEmpty()
      if (name.isEmpty()) return@mapNotNull null
      val missing = obj["missing"].asObjectOrNull()
      val clawHub = obj["clawhub"].asObjectOrNull()
      GatewaySkillSummary(
        skillKey = obj["skillKey"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: name,
        name = name,
        description = obj["description"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty),
        source = obj["source"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: "unknown",
        emoji = obj["emoji"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty),
        disabled = obj.boolean("disabled"),
        eligible = obj.boolean("eligible"),
        blockedByAllowlist = obj.boolean("blockedByAllowlist"),
        blockedByAgentFilter = obj.boolean("blockedByAgentFilter"),
        bundled = obj.boolean("bundled"),
        missingCount = listOf("bins", "env", "config", "os").sumOf { key -> (missing?.get(key) as? JsonArray)?.size ?: 0 },
        installCount = (obj["install"] as? JsonArray)?.size ?: 0,
        clawHubSlug =
          clawHub
            ?.get("slug")
            .asStringOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty),
        clawHubValid = clawHub?.boolean("valid") == true,
        clawHubRequestedReference =
          clawHub
            ?.get("requestedReference")
            .asStringOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty),
        clawHubOwnerHandle =
          clawHub
            ?.get("ownerHandle")
            .asStringOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty),
        clawHubInstalledVersion =
          clawHub
            ?.get("installedVersion")
            .asStringOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty),
      )
    }.orEmpty()

private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
