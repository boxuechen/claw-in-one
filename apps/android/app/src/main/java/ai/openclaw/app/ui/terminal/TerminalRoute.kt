package ai.openclaw.app.ui.terminal

import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.terminal.TerminalControlAccess
import ai.openclaw.app.terminal.TerminalFeature
import ai.openclaw.app.terminal.TerminalState
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRoute
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRouteActions
import ai.openclaw.app.ui.terminal.web.TerminalViewRetention
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException

/** Adapts the authenticated upstream terminal surface; no terminal RPCs live in Compose. */
@Composable
internal fun TerminalRoute(
  feature: TerminalFeature?,
  environment: RuntimeFeature,
  environmentActions: RuntimeEnvironmentRouteActions,
  retention: TerminalViewRetention,
  onBack: () -> Unit,
) {
  val state = feature?.state?.collectAsState()?.value ?: TerminalState()
  val connected = state.connected
  val admin = state.adminScope
  val page = state.page
  var access by remember(page) { mutableStateOf<TerminalControlAccess?>(null) }
  var failed by remember(page) { mutableStateOf(false) }
  var authorizationAttempt by remember(page) { mutableIntStateOf(0) }
  var environmentVisible by remember { mutableStateOf(false) }
  LaunchedEffect(connected, admin, page, authorizationAttempt) {
    val current = page ?: return@LaunchedEffect
    if (!connected || !admin) return@LaunchedEffect
    retention.bind(current)
    failed = false
    if (retention.hasPage(current)) {
      access = TerminalControlAccess(current)
    } else {
      try {
        access = feature?.actions?.prepareAccess?.invoke()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        failed = true
      }
    }
  }
  val availability =
    when {
      !connected -> TerminalAvailability.Disconnected
      !admin -> TerminalAvailability.AdminRequired
      failed -> TerminalAvailability.AuthorizationFailed
      page == null || access == null -> TerminalAvailability.Preparing
      else -> TerminalAvailability.Ready
    }
  TerminalScreen(availability = availability, onBack = onBack, onReconnect = {
    if (failed && connected) authorizationAttempt++ else feature?.actions?.reconnect?.invoke()
  }, onOpenEnvironment = { environmentVisible = true }) {
    access?.let { prepared ->
      TerminalSurface(
        page = prepared.page,
        bootstrapToken = prepared.bootstrapToken,
        onBootstrapConsumed = { consumed ->
          if (access?.bootstrapToken == consumed) access = TerminalControlAccess(prepared.page)
        },
        retention = retention,
        renewAccess = { requireNotNull(feature).actions.prepareAccess() },
      )
    }
  }
  if (environmentVisible) {
    RuntimeEnvironmentRoute(
      feature = environment,
      actions = environmentActions,
      onClose = { environmentVisible = false },
    )
  }
}

internal fun terminalUrl(baseUrl: String): String =
  baseUrl
    .trimEnd('/')
    .toUri()
    .buildUpon()
    .clearQuery()
    .fragment(null)
    .appendPath("focus")
    .appendPath("terminal")
    .build()
    .toString()
