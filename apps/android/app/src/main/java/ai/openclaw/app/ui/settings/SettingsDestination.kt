package ai.openclaw.app.ui.settings

/** Detail destinations reachable from the settings overview. */
internal enum class SettingsDestination {
  Home,
  AiModels,
  LocalEnvironment,
  Appearance,
  About,
  Licenses,
}

/** Permanent entries shown by the Settings directory. */
internal val topLevelSettingsDestinations =
  setOf(
    SettingsDestination.AiModels,
    SettingsDestination.Appearance,
    SettingsDestination.LocalEnvironment,
    SettingsDestination.About,
  )
