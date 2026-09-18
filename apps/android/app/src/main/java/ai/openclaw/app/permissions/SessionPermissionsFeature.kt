package ai.openclaw.app.permissions

import kotlinx.coroutines.flow.StateFlow

/** User-facing reads/choices only. Creation and send admission remain private to composition. */
internal class SessionPermissionsFeature(
  val states: StateFlow<Map<SessionPermissionTarget, SessionPermissionsState>>,
  val refresh: suspend (SessionPermissionTarget) -> SessionPermissionsState,
  val choose: suspend (SessionPermissionTarget, SessionPermissionMode) -> Boolean,
  val reconcileCreation: suspend (SessionPermissionTarget) -> SessionPermissionRef?,
)
